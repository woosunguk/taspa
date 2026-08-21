package com.taspa.server.config

import com.taspa.server.config.i18n.I18nConfig
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.DispatcherServlet
import java.util.Locale

/**
 * 런타임 로케일 전환 메커니즘 검증 — 순수 JUnit(테스트컨테이너/Spring 컨텍스트 불필요).
 *
 * I18nMessagesTest 가 번들 계약(키 parity/해석)만 보는 반면, 여기서는 실제
 * LocaleChangeInterceptor → SupportedLocaleCookieResolver → messageSource 사슬로
 *  1) 영어 렌더 경로(쿠키 en → 영문 번들 해석),
 *  2) ?lang= 전환/쿠키 저장(사용자 선택의 영속),
 *  3) 파싱은 되지만 미지원인 로케일(fr/ja/zh-CN)의 ko 클램프(NoSuchMessageException 방지)
 * 를 검증한다. (Playwright 는 ko-KR 고정, e2e 는 이 환경에서 미실행 — 그 공백을 메운다.)
 */
class I18nLocaleResolutionTest {
    private val config = I18nConfig()
    private val messageSource = config.messageSource()
    private val localeResolver = config.localeResolver()
    private val interceptor = config.localeChangeInterceptor()

    private fun request(cookie: String? = null): MockHttpServletRequest =
        MockHttpServletRequest("GET", "/login").apply {
            // 인터셉터가 RequestContextUtils 로 리졸버를 찾을 수 있게 DispatcherServlet 속성을 심는다.
            setAttribute(DispatcherServlet.LOCALE_RESOLVER_ATTRIBUTE, localeResolver)
            cookie?.let { setCookies(Cookie(I18nConfig.COOKIE_NAME, it)) }
        }

    @Test
    fun `en 쿠키를 가진 재방문자는 영어로 해석되고 영문 번들이 렌더된다`() {
        val locale = localeResolver.resolveLocale(request(cookie = "en"))
        assertThat(locale).isEqualTo(Locale.ENGLISH)
        assertThat(messageSource.getMessage("login.identifier.subtitle", null, locale))
            .isEqualTo("Continue with your taspa account")
        assertThat(messageSource.getMessage("login.error.userNotFound", null, locale))
            .isEqualTo("We couldn't find a taspa account")
    }

    @Test
    fun `쿠키도 헤더도 없으면 한국어 기준선으로 떨어진다`() {
        val locale = localeResolver.resolveLocale(request())
        assertThat(locale).isEqualTo(Locale.KOREAN)
        assertThat(messageSource.getMessage("login.error.userNotFound", null, locale))
            .isEqualTo("taspa 계정을 찾을 수 없습니다")
    }

    @Test
    fun `lang=en 은 로케일을 전환하고 쿠키에 en 을 영속한다`() {
        val req = request().apply { setParameter(I18nConfig.LANG_PARAM, "en") }
        val res = MockHttpServletResponse()
        assertThat(interceptor.preHandle(req, res, Any())).isTrue()
        assertThat(res.getCookie(I18nConfig.COOKIE_NAME)?.value).isEqualTo("en")
    }

    @Test
    fun `파싱되지만 미지원인 lang 은 쿠키에 ko 로 클램프되어 저장된다`() {
        for (unsupported in listOf("fr", "ja", "zh-CN")) {
            val req = request().apply { setParameter(I18nConfig.LANG_PARAM, unsupported) }
            val res = MockHttpServletResponse()
            interceptor.preHandle(req, res, Any())
            assertThat(res.getCookie(I18nConfig.COOKIE_NAME)?.value)
                .describedAs("lang=$unsupported 은 클램프된 ko 로 저장되어야 한다")
                .isEqualTo("ko")
        }
    }

    @Test
    fun `레거시 미지원 쿠키는 해석 시 ko 로 클램프되어 NoSuchMessage 를 던지지 않는다`() {
        for (unsupported in listOf("fr", "ja", "zh-CN")) {
            val locale = localeResolver.resolveLocale(request(cookie = unsupported))
            assertThat(locale).describedAs(unsupported).isEqualTo(Locale.KOREAN)
            // 클램프 덕분에 미지원 쿠키여도 모든 키가 정상 해석된다(예외/??key?? 없음).
            assertThat(messageSource.getMessage("login.identifier.heading", null, locale)).isEqualTo("로그인")
        }
    }
}
