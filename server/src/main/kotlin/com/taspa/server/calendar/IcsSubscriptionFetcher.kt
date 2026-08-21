package com.taspa.server.calendar

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import org.springframework.stereotype.Component
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * 구독 .ics 를 안전하게 가져온다(Phase 0-E ★필수 SSRF·DoS 방어).
 *  - [IcsUrlSecurity] 로 매 홉(초기 URL + 각 리다이렉트 Location) 재검증(https·사설/메타데이터 IP 거부).
 *  - 자동 리다이렉트를 끄고(NEVER) 수동으로 최대 maxRedirects 회 따라가며 매번 재검증한다.
 *  - 연결/요청 타임아웃(fetchTimeout)과 응답 크기 상한(maxFeedSizeBytes)을 강제한다.
 */
@Component
class IcsSubscriptionFetcher(
    private val urlSecurity: IcsUrlSecurity,
    private val properties: CalendarProperties,
) {
    fun fetch(rawUrl: String): String {
        var currentUrl = rawUrl
        var hops = 0
        while (true) {
            // ★ 매 홉 재검증 — 초기 URL 뿐 아니라 리다이렉트 대상도 SSRF 검사를 통과해야 한다.
            val uri = urlSecurity.validate(currentUrl)
            val client =
                HttpClient
                    .newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(properties.fetchTimeout)
                    .build()
            val request =
                HttpRequest
                    .newBuilder(uri)
                    .timeout(properties.fetchTimeout)
                    .header("Accept", "text/calendar, text/plain, */*")
                    .GET()
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val status = response.statusCode()

            if (status in 300..399) {
                if (hops >= properties.maxRedirects) {
                    response.body().close()
                    throw AuthException(ErrorCode.VALIDATION_ERROR, "리다이렉트가 너무 많습니다")
                }
                val location = response.headers().firstValue("location").orElse(null)
                response.body().close()
                if (location.isNullOrBlank()) {
                    throw AuthException(ErrorCode.VALIDATION_ERROR, "리다이렉트에 Location 이 없습니다")
                }
                // 상대 Location 을 절대 URL 로 해석 → 다음 루프에서 재검증된다.
                currentUrl = URI(currentUrl).resolve(location).toString()
                hops++
                continue
            }
            if (status != 200) {
                response.body().close()
                throw AuthException(ErrorCode.VALIDATION_ERROR, "구독 응답 상태가 비정상입니다: $status")
            }
            // Content-Length 가 상한을 넘으면 본문을 읽기 전에 조기 거부.
            val declaredLength = response.headers().firstValueAsLong("content-length").orElse(-1L)
            if (declaredLength > properties.maxFeedSizeBytes) {
                response.body().close()
                throw AuthException(ErrorCode.VALIDATION_ERROR, "구독 응답이 크기 상한을 초과합니다")
            }
            return response.body().use { readCapped(it) }
        }
    }

    /** 상한(maxFeedSizeBytes)까지만 읽고, 초과하면 즉시 실패한다(스트리밍 DoS 방어). */
    private fun readCapped(input: InputStream): String {
        val limit = properties.maxFeedSizeBytes
        val buffer = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > limit) {
                throw AuthException(ErrorCode.VALIDATION_ERROR, "구독 응답이 크기 상한을 초과합니다")
            }
            out.write(buffer, 0, read)
        }
        return out.toString(StandardCharsets.UTF_8)
    }

    companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
