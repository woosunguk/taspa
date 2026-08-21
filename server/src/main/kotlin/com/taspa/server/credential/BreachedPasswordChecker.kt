package com.taspa.server.credential

import org.slf4j.LoggerFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.security.MessageDigest
import java.util.concurrent.Future
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * HaveIBeenPwned(HIBP) k-익명 유출 비밀번호 검사.
 *
 * 원본 비밀번호를 절대 외부로 보내지 않는다: 비밀번호의 SHA-1(대문자 hex) 40자 중 **앞 5자(prefix)만**
 * range API(`GET {apiUrl}/{prefix}`)로 보내고, 응답으로 받은 "나머지 35자 suffix:count" 목록에서
 * 로컬로 계산한 나머지 35자를 대조한다(k-익명성). count>0 인 매칭이 있으면 유출된 비밀번호다.
 *
 * `Add-Padding: true` 로 요청해 HIBP 가 count 0 인 더미 suffix 로 응답을 패딩하도록 하고(응답 크기 기반
 * 사이드채널 완화), 대조 시 count>0 만 유효 매칭으로 인정해 패딩을 무시한다.
 *
 * 장애 처리는 [PasswordPolicyProperties.BreachCheck.failOpen] 을 따른다(기본 fail-open: 통과).
 * enabled=false 면 네트워크 호출 없이 항상 false(유출 아님)를 반환한다.
 *
 * ## 총 대기 시간 상한(DNS 포함)
 * SimpleClientHttpRequestFactory(내부 HttpURLConnection) 의 connect/read 타임아웃은 TCP 연결·소켓 읽기만
 * 상한하고, JVM 의 이름 해석(InetAddress.getByName) 단계는 상한하지 못한다. DNS 가 블랙홀이면 OS 리졸버
 * 기본 타임아웃(수 초)까지 호출 스레드가 블록되어 가입/비밀번호 변경이 [BreachCheck.timeout] 예산을
 * 넘겨 지연될 수 있다. 이를 막기 위해 range 호출을 별도 데몬 스레드에 위임하고 [isBreached] 에서
 * hard-deadline([Future.get] 타임아웃)을 걸어 **DNS 해석까지 포함한 총 대기 시간을 상한**한다.
 */
@Service
class BreachedPasswordChecker(
    private val properties: PasswordPolicyProperties,
) {
    private val log = LoggerFactory.getLogger(BreachedPasswordChecker::class.java)

    private val restClient: RestClient = buildRestClient()

    /**
     * range 호출 전용 데몬 스레드 풀.
     *
     * DNS 로 블록된 스레드는 인터럽트에 반응하지 않을 수 있으므로, 코어 0·상한 무제한·keepAlive 30s 의
     * 캐시형 풀에 **데몬** 스레드를 쓴다: hard-deadline 초과로 버려진(취소된) 스레드가 JVM 종료를 막지
     * 않고 리졸버가 풀리면 정상 종료되어 결국 회수된다. 정상 부하에서는 스레드가 재사용된다.
     */
    private val executor =
        ThreadPoolExecutor(
            0,
            Int.MAX_VALUE,
            30L,
            TimeUnit.SECONDS,
            SynchronousQueue(),
        ) { runnable ->
            Thread(runnable, "hibp-breach-check").apply { isDaemon = true }
        }

    private fun buildRestClient(): RestClient {
        val timeoutMillis =
            properties.breachCheck.timeout
                .toMillis()
                .toInt()
        val factory =
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(timeoutMillis)
                setReadTimeout(timeoutMillis)
            }
        return RestClient
            .builder()
            .requestFactory(factory)
            .baseUrl(properties.breachCheck.apiUrl)
            .build()
    }

    /**
     * true = 알려진 유출에 포함된 비밀번호(거부 대상).
     * enabled=false 면 항상 false. HIBP 장애(5xx·네트워크 오류·연결/읽기 타임아웃·DNS 무응답) 시
     * fail-open(기본)이면 false, fail-closed 면 true.
     */
    fun isBreached(password: String): Boolean {
        if (!properties.breachCheck.enabled) return false

        val sha1 = sha1UpperHex(password)
        val prefix = sha1.substring(0, 5)
        val suffix = sha1.substring(5)

        // 정상 경로에서는 connect/read 타임아웃(= timeout)이 먼저 만료된다. 이 deadline 은 그 타임아웃이
        // 상한하지 못하는 DNS 해석 구간을 위한 백스톱이며, DNS_BACKSTOP_MILLIS 만큼만 여유를 준다.
        val deadlineMillis = properties.breachCheck.timeout.toMillis() + DNS_BACKSTOP_MILLIS
        var future: Future<Boolean>? = null
        return try {
            future = executor.submit<Boolean> { queryBreached(prefix, suffix) }
            future.get(deadlineMillis, TimeUnit.MILLISECONDS)
        } catch (ex: Exception) {
            if (ex is InterruptedException) Thread.currentThread().interrupt()
            // 버려진 range 호출이 계속 블록되지 않도록 취소 시도(DNS 블록은 인터럽트에 반응하지 않을 수 있음).
            future?.cancel(true)
            if (properties.breachCheck.failOpen) {
                log.warn("HIBP breach check failed; failing open (treating password as not breached)", ex)
                false
            } else {
                log.warn("HIBP breach check failed; failing closed (treating password as breached)", ex)
                true
            }
        }
    }

    /** range API 를 호출해 유출 여부를 판정한다. 별도 스레드에서 실행되며, 장애 시 예외를 그대로 전파한다. */
    private fun queryBreached(
        prefix: String,
        suffix: String,
    ): Boolean {
        val body =
            restClient
                .get()
                .uri("/{prefix}", prefix)
                .header("Add-Padding", "true")
                .retrieve()
                .body(String::class.java)
                ?: return false
        return body.lineSequence().any { line ->
            val parts = line.trim().split(':', limit = 2)
            parts.size == 2 &&
                parts[0].equals(suffix, ignoreCase = true) &&
                (parts[1].trim().toLongOrNull() ?: 0L) > 0L
        }
    }

    private fun sha1UpperHex(value: String): String =
        MessageDigest
            .getInstance("SHA-1")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }

    private companion object {
        /**
         * connect/read 타임아웃이 상한하지 못하는 DNS 해석 구간에 허용하는 추가 여유(ms).
         * 총 대기 상한 ≈ timeout + 이 값. DNS 블랙홀 시에도 가입/변경이 이 상한 내에 반환된다.
         */
        const val DNS_BACKSTOP_MILLIS = 1_000L
    }
}
