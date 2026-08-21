package com.taspa.server.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.support.ResourceBundleMessageSource
import java.util.Locale
import java.util.Properties

/**
 * i18n 메시지 번들 계약 검증 — 빠른 순수 JUnit(테스트컨테이너/Spring 컨텍스트 불필요).
 *
 * 1) messages_ko 와 messages_en 의 키 집합이 정확히 일치한다(한쪽에만 있는 키 = 렌더 시 ??key?? 누락).
 * 2) 값은 빈 문자열이 아니다(의도적 빈 값은 명시적 allowlist 로만 허용).
 * 3) 모든 키가 두 로케일 모두에서 실제 MessageSource 로 해석된다(useCodeAsDefaultMessage=false →
 *    누락 키는 NoSuchMessageException 으로 즉시 실패한다 — 조용히 삼키지 않음).
 */
class I18nMessagesTest {
    private fun load(resource: String): Properties {
        val props = Properties()
        javaClass.classLoader.getResourceAsStream(resource).use { stream ->
            requireNotNull(stream) { "$resource not found on classpath" }
            // spring.messages.encoding=UTF-8 과 동일하게 UTF-8 로 읽는다.
            stream.reader(Charsets.UTF_8).use { props.load(it) }
        }
        return props
    }

    private val ko = load("messages_ko.properties")
    private val en = load("messages_en.properties")

    /** 값이 비어도 되는(의도적) 키. countSuffix 는 한국어 조수사 "개"에 대응하며 영어에선 접미사가 없다. */
    private val allowedEmpty = setOf("admin.users.js.countSuffix")

    /**
     * 발급 가능한 모든 scope ↔ 동의 화면 설명.
     *
     * ★표에 없는 scope 는 `ConsentController.withDescriptions` 가 **원문 식별자를 그대로** 보여준다.
     * 한동안 사용자는 "org.roles" 같은 문자열에 동의해야 했다 — 무엇에 동의하는지 모르는 동의는
     * 동의가 아니고, 이 화면의 존재 이유 자체가 사라진다. ko/en 패리티 검사로는 잡히지 않는다
     * (양쪽 다 없으면 패리티는 그대로다). application.yml 을 파싱해 **설정이 진실의 출처**임을 지킨다.
     */
    @Test
    fun `모든 발급 가능 scope 에 동의 화면 설명이 있다`() {
        val yml =
            javaClass.classLoader
                .getResourceAsStream("application.yml")!!
                .reader(Charsets.UTF_8)
                .readText()
        // `allowed-scopes:` 아래 `- scope` 목록만 추출(다음 같은 들여쓰기 키가 나오면 종료).
        val scopes =
            yml
                .lineSequence()
                .dropWhile { !it.trimEnd().endsWith("allowed-scopes:") }
                .drop(1)
                .map { it.trim() }
                .takeWhile { it.startsWith("- ") || it.startsWith("#") || it.isEmpty() }
                .filter { it.startsWith("- ") }
                .map { it.removePrefix("- ").trim() }
                .toList()
        assertThat(scopes).describedAs("application.yml 의 allowed-scopes 파싱").isNotEmpty()

        val descriptionKeys = com.taspa.server.oidc.ConsentController.SCOPE_DESCRIPTION_KEYS
        assertThat(scopes.filterNot { descriptionKeys.containsKey(it) })
            .describedAs("동의 화면 설명이 없는 scope(사용자가 원문 식별자에 동의하게 된다)")
            .isEmpty()

        val missingMessages = descriptionKeys.values.filterNot { ko.containsKey(it) }
        assertThat(missingMessages).describedAs("messages_ko 에 없는 동의 설명 키").isEmpty()
    }

    /**
     * ErrorCode ↔ `error.{NAME}` 키 대응.
     *
     * ko/en 패리티 검사만으로는 이 드리프트를 못 잡는다 — 새 ErrorCode 를 추가하면서 키를 **양쪽 다**
     * 빠뜨리면 패리티는 유지된 채 GlobalExceptionHandler 의 폴백이 동작해 **조용히 영어 문구가 노출된다**.
     * 사용자에게 영어가 새어나가는 건 눈에 잘 띄지 않으므로 테스트가 대신 지킨다.
     */
    @Test
    fun `모든 ErrorCode 에 대응하는 error 키가 있다`() {
        val missing =
            com.taspa.server.common.exception.ErrorCode.entries
                .map { "error.${it.name}" }
                .filterNot { ko.containsKey(it) }
        assertThat(missing)
            .describedAs("messages_ko 에 없는 ErrorCode 키(추가하지 않으면 영어 문구가 그대로 노출된다)")
            .isEmpty()
    }

    @Test
    fun `ko 와 en 의 키 집합이 정확히 일치한다`() {
        val koKeys = ko.stringPropertyNames()
        val enKeys = en.stringPropertyNames()
        assertThat(koKeys - enKeys).describedAs("en 에 없는 ko 키").isEmpty()
        assertThat(enKeys - koKeys).describedAs("ko 에 없는 en 키").isEmpty()
        assertThat(koKeys).isNotEmpty()
    }

    @Test
    fun `모든 값은 비어 있지 않다 (의도적 예외 제외)`() {
        for (key in ko.stringPropertyNames()) {
            if (key in allowedEmpty) continue
            assertThat(ko.getProperty(key)).describedAs("ko[$key]").isNotBlank()
            assertThat(en.getProperty(key)).describedAs("en[$key]").isNotBlank()
        }
    }

    @Test
    fun `모든 키가 ko en 두 로케일에서 해석된다`() {
        val ms =
            ResourceBundleMessageSource().apply {
                setBasename("messages")
                setDefaultEncoding("UTF-8")
                setFallbackToSystemLocale(false)
                // 누락 키는 코드로 대체하지 않고 예외로 드러낸다.
                setUseCodeAsDefaultMessage(false)
            }
        val args = arrayOf<Any>("A", "B", "C") // {0}~{2} 를 쓰는 메시지(메일 본문 등) 커버.
        for (key in ko.stringPropertyNames()) {
            for (locale in listOf(Locale.KOREAN, Locale.ENGLISH)) {
                // 파라미터 메시지에만 args 를 넘겨 런타임(#{key} vs #{key(..)}) 의미를 흉내낸다.
                val useArgs = ko.getProperty(key).contains("{0}")
                val resolved = ms.getMessage(key, if (useArgs) args else null, locale)
                assertThat(resolved).describedAs("$key ($locale)").doesNotStartWith("??")
            }
        }
    }
}
