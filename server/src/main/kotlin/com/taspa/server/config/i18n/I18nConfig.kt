package com.taspa.server.config.i18n

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.i18n.LocaleContext
import org.springframework.context.i18n.SimpleTimeZoneAwareLocaleContext
import org.springframework.context.i18n.TimeZoneAwareLocaleContext
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.web.servlet.LocaleContextResolver
import org.springframework.web.servlet.LocaleResolver
import org.springframework.web.servlet.i18n.CookieLocaleResolver
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor
import java.time.Duration
import java.util.Locale

/**
 * i18n 구성. base 번들(messages.properties)을 두지 않아 Spring Boot 의 MessageSourceAutoConfiguration
 * (ResourceBundleCondition 이 `messages.properties` 존재를 요구)이 자동 구성을 건너뛰므로, MessageSource
 * 를 이 클래스에서 **명시적으로** 정의한다(아래 messageSource 빈). 로케일 해석기와 ?lang= 변경
 * 인터셉터도 여기서 정의한다. (application.yml 의 spring.messages.* 는 사용하지 않는다.)
 *
 * 로케일 결정 순서(우선순위):
 *  1. `TASPA_LOCALE` 쿠키(사용자가 ?lang= 로 선택 — LocaleChangeInterceptor 가 이 쿠키에 저장)
 *  2. Accept-Language 헤더(지원 로케일 중 최적 매칭) — **헤더가 실제로 존재할 때만** 참조
 *  3. 기본 로케일(ko)
 *
 * 해석된 로케일은 항상 [SUPPORTED_LOCALES](ko/en)로 클램프된다 — 파싱은 되지만 미지원인 값
 * (예: `?lang=fr`, `ja`, `zh-CN`, 또는 과거에 저장된 레거시 쿠키)이 그대로 새어 나가면 messageSource
 * 가 모든 키에 NoSuchMessageException 을 던져(또는 Thymeleaf ??key?? 로) 렌더를 깨뜨리기 때문이다.
 * 클램프는 쿠키/헤더 어느 경로든 [ClampingLocaleContextResolver] 에서 일괄 적용된다.
 *
 * 표준 CookieLocaleResolver 는 쿠키가 없으면 `request.getLocale()`(Accept-Language 부재 시 JVM 기본
 * 로케일)로 폴백해 CI 의 JVM 로케일에 따라 렌더가 흔들린다. `setDefaultLocaleFunction` 으로 이를
 * 대체해, 헤더가 없으면 결정적으로 ko 로 떨어뜨린다(MockMvc 요청은 Accept-Language 헤더가 없으므로
 * 항상 ko → 기존 통합/e2e 텍스트 단언 무손상).
 */
@Configuration
class I18nConfig {
    /**
     * 애플리케이션 MessageSource(빈 이름 `messageSource` 필수 — Thymeleaf `#{}`·MailService 가 사용).
     *
     * base 번들(messages.properties)을 두지 않으므로 Boot 의 MessageSourceAutoConfiguration
     * (ResourceBundleCondition 이 `messages.properties` 존재를 요구)이 자동 구성을 건너뛴다.
     * 따라서 여기서 명시적으로 정의한다. `fallbackToSystemLocale=false` + `useCodeAsDefaultMessage=false`
     * 로, 지원 로케일(ko/en) 밖으로 새지 않고 누락 키는 조용히 삼켜지지 않게 한다(코드로 대체하지 않음 →
     * Thymeleaf 는 ??key?? 로, 직접 호출은 NoSuchMessageException 으로 드러난다).
     */
    @Bean
    fun messageSource(): MessageSource =
        ResourceBundleMessageSource().apply {
            setBasename("messages")
            setDefaultEncoding("UTF-8")
            setFallbackToSystemLocale(false)
            setUseCodeAsDefaultMessage(false)
        }

    /**
     * DispatcherServlet 은 정확히 `localeResolver` 라는 이름의 빈을 사용한다(LOCALE_RESOLVER_BEAN_NAME).
     * 메서드명이 곧 빈 이름이므로 반드시 이 이름을 유지한다.
     */
    @Bean
    fun localeResolver(): LocaleResolver {
        val delegate =
            CookieLocaleResolver(COOKIE_NAME).apply {
                setCookiePath("/")
                setCookieMaxAge(Duration.ofDays(365))
                setDefaultLocaleFunction(::resolveDefaultLocale)
            }
        // 표준 CookieLocaleResolver 의 resolveLocale 은 resolveLocaleContext 를 거치지 않고 쿠키를
        // 직접 파싱해 요청 속성에 담으므로, 서브클래싱만으로는 "읽기" 경로 클램프가 누락된다.
        // 모든 진입점(resolve/set × Locale/LocaleContext)을 감싸는 데코레이터로 클램프를 강제한다.
        return ClampingLocaleContextResolver(delegate)
    }

    @Bean
    fun localeChangeInterceptor(): LocaleChangeInterceptor =
        LocaleChangeInterceptor().apply {
            // ?lang=ko / ?lang=en 로 로케일을 전환한다(쿠키에 영속).
            paramName = LANG_PARAM
            // 파싱 불가한 값은 무시하고 현재 로케일을 유지한다(??key?? 렌더 방지).
            setIgnoreInvalidLocale(true)
        }

    /** 쿠키가 없을 때의 기본 로케일: Accept-Language 헤더가 있으면 지원 로케일 중 최적 매칭, 없으면 ko. */
    private fun resolveDefaultLocale(request: HttpServletRequest): Locale {
        val header = request.getHeader("Accept-Language")
        if (!header.isNullOrBlank()) {
            try {
                val ranges = Locale.LanguageRange.parse(header)
                Locale.lookup(ranges, SUPPORTED_LOCALES)?.let { return it }
            } catch (_: IllegalArgumentException) {
                // 형식이 잘못된 Accept-Language 헤더는 무시하고 기본 로케일로 폴백한다.
            }
        }
        return DEFAULT_LOCALE
    }

    companion object {
        val DEFAULT_LOCALE: Locale = Locale.KOREAN
        val SUPPORTED_LOCALES: List<Locale> = listOf(Locale.KOREAN, Locale.ENGLISH)
        const val LANG_PARAM = "lang"
        const val COOKIE_NAME = "TASPA_LOCALE"

        /** 임의 로케일을 지원 로케일(ko/en)로 클램프한다 — 언어 태그가 일치하는 지원 로케일, 없으면 ko. */
        fun clampToSupported(locale: Locale?): Locale {
            if (locale == null) return DEFAULT_LOCALE
            return SUPPORTED_LOCALES.firstOrNull { it.language == locale.language } ?: DEFAULT_LOCALE
        }
    }
}

/**
 * 위임한 [LocaleContextResolver] 의 해석·저장 로케일을 항상 지원 로케일(ko/en)로 클램프하는 데코레이터.
 *
 * 표준 [LocaleChangeInterceptor] 의 `ignoreInvalidLocale` 은 **파싱 불가한** 값만 무시하므로,
 * `?lang=fr`/`ja`/`zh-CN` 처럼 파싱은 되지만 미지원인 값은 그대로 쿠키에 저장되고 이후 렌더에서
 * messageSource 가 NoSuchMessageException(또는 Thymeleaf ??key??)을 던진다.
 *
 * CookieLocaleResolver 는 `resolveLocale` 을 `resolveLocaleContext` 로 위임하지 않고 쿠키를 직접
 * 파싱하므로 서브클래싱은 읽기 경로를 놓친다. 네 진입점(resolve/set × Locale/LocaleContext)을 모두
 * 감싸 어느 경로로 들어와도 지원 로케일만 흘러나가게(그리고 쿠키엔 ko/en 만 저장되게) 한다.
 */
private class ClampingLocaleContextResolver(
    private val delegate: LocaleContextResolver,
) : LocaleContextResolver {
    override fun resolveLocale(request: HttpServletRequest): Locale = I18nConfig.clampToSupported(delegate.resolveLocale(request))

    override fun setLocale(
        request: HttpServletRequest,
        response: HttpServletResponse?,
        locale: Locale?,
    ) {
        delegate.setLocale(request, response, locale?.let { I18nConfig.clampToSupported(it) })
    }

    override fun resolveLocaleContext(request: HttpServletRequest): LocaleContext {
        val ctx = delegate.resolveLocaleContext(request)
        val timeZone = (ctx as? TimeZoneAwareLocaleContext)?.timeZone
        return SimpleTimeZoneAwareLocaleContext(I18nConfig.clampToSupported(ctx.locale), timeZone)
    }

    override fun setLocaleContext(
        request: HttpServletRequest,
        response: HttpServletResponse?,
        localeContext: LocaleContext?,
    ) {
        val clamped =
            localeContext?.let {
                val timeZone = (it as? TimeZoneAwareLocaleContext)?.timeZone
                SimpleTimeZoneAwareLocaleContext(I18nConfig.clampToSupported(it.locale), timeZone)
            }
        delegate.setLocaleContext(request, response, clamped)
    }
}
