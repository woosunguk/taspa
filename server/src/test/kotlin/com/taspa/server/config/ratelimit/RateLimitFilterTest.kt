package com.taspa.server.config.ratelimit

import com.taspa.server.config.i18n.I18nConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Duration

/**
 * RateLimitFilter 경로 정규화 단위 테스트 — raw requestURI 가 아니라 UrlPathHelper(디코드+세미콜론 제거)로
 * 매칭하므로, URL 인코딩(`%70`)이나 matrix 파라미터(`;x=1`)로 rate limit 을 우회할 수 없어야 한다.
 * 이 우회는 필터가 group 을 못 찾아(null) 그냥 통과시키던 결함이었다.
 */
class RateLimitFilterTest {
    // 429 본문을 로케일화하려면 실제 MessageSource/LocaleResolver 가 필요하므로 프로덕션 빈을 그대로 쓴다.
    private val i18n = I18nConfig()
    private val messageSource = i18n.messageSource()
    private val localeResolver = i18n.localeResolver()

    private fun filterWithCapacity(capacity: Long) =
        RateLimitFilter(
            RateLimiter(
                capacity = capacity,
                refillTokens = capacity,
                refillPeriod = Duration.ofSeconds(600),
            ),
            messageSource,
            localeResolver,
        )

    private fun request(
        method: String,
        uri: String,
    ) = MockHttpServletRequest(method, uri).apply { remoteAddr = "203.0.113.7" }

    @Test
    fun `url-encoded path is normalized and still rate limited`() {
        val filter = filterWithCapacity(1)
        // %70 == 'p' → 정규화하면 /login/password (login-password 룰)
        val first = MockHttpServletResponse()
        filter.doFilter(request("POST", "/login/%70assword"), first, MockFilterChain())
        assertThat(first.status).isEqualTo(200) // 첫 요청은 유일한 토큰 소비 → 통과

        val second = MockHttpServletResponse()
        filter.doFilter(request("POST", "/login/%70assword"), second, MockFilterChain())
        assertThat(second.status).isEqualTo(429) // 같은 그룹 → 토큰 소진 → 차단
    }

    @Test
    fun `matrix parameters are stripped before matching`() {
        val filter = filterWithCapacity(1)
        // /oauth2/token;jsessionid=abc → 세미콜론 콘텐츠 제거 후 /oauth2/token (token 룰)
        val first = MockHttpServletResponse()
        filter.doFilter(request("POST", "/oauth2/token;jsessionid=abc"), first, MockFilterChain())
        assertThat(first.status).isEqualTo(200)

        val second = MockHttpServletResponse()
        filter.doFilter(request("POST", "/oauth2/token;jsessionid=abc"), second, MockFilterChain())
        assertThat(second.status).isEqualTo(429)
    }

    @Test
    fun `device verification is rate limited to throttle user_code brute force`() {
        val filter = filterWithCapacity(1)
        // RFC 8628 §5.2: user_code 무차별 대입을 IP 단위로 제한한다. 첫 시도 통과, 두 번째 차단.
        val first = MockHttpServletResponse()
        filter.doFilter(request("POST", "/oauth2/device_verification"), first, MockFilterChain())
        assertThat(first.status).isEqualTo(200)

        val second = MockHttpServletResponse()
        filter.doFilter(request("POST", "/oauth2/device_verification"), second, MockFilterChain())
        assertThat(second.status).isEqualTo(429)
    }

    /**
     * ★가입은 **임의의 외부 주소로 우리 이름의 메일을 발송**하는 공개 엔드포인트다
     * (`AccountService.signup` 이 인증 코드를 보낸다). 다른 발송 경로는 전부 재발송 스로틀이나 이
     * 필터로 묶여 있는데 가입만 비어 있었다 — 주소 목록만 있으면 우리가 스팸 발송기가 된다.
     *
     * ★그리고 HTML 폼과 JSON API 가 **같은 버킷**이어야 한다. 나눠 두면 공격자가 두 경로를 번갈아 쳐서
     * 실효 한도를 두 배로 만든다 — 이 테스트가 정확히 그 우회를 겨냥한다(경로별 테스트만 있으면 통과한다).
     */
    @Test
    fun `signup is rate limited and the html form shares the API bucket`() {
        val filter = filterWithCapacity(1)

        val first = MockHttpServletResponse()
        filter.doFilter(request("POST", "/api/accounts/signup"), first, MockFilterChain())
        assertThat(first.status).isEqualTo(200)

        // 다른 경로지만 같은 표적 → 같은 버킷이라 이미 소진돼 있어야 한다.
        val viaHtmlForm = MockHttpServletResponse()
        filter.doFilter(request("POST", "/signup"), viaHtmlForm, MockFilterChain())
        assertThat(viaHtmlForm.status).isEqualTo(429)
    }

    /**
     * ★소셜 이메일 보완(`/login/social-email`)도 **같은 표적**이다 — 요청자가 수신 주소를 정하고,
     * 스로틀 없는 `issue()` 를 타며, 호출마다 새 users 행까지 만든다. 가입만 막고 이 경로를 열어 두면
     * 우회가 한 줄이다(적대 리뷰에서 잡힌 결함 — "다른 발송 경로는 전부 묶여 있다"는 주석이 사실이 아니었다).
     */
    @Test
    fun `social email fallback shares the signup bucket`() {
        val filter = filterWithCapacity(1)

        val first = MockHttpServletResponse()
        filter.doFilter(request("POST", "/login/social-email"), first, MockFilterChain())
        assertThat(first.status).isEqualTo(200)

        val viaSignup = MockHttpServletResponse()
        filter.doFilter(request("POST", "/api/accounts/signup"), viaSignup, MockFilterChain())
        assertThat(viaSignup.status).isEqualTo(429)
    }

    /**
     * 이메일 변경도 임의 제3자 주소로 코드를 보낸다. 다만 계정이 있어야 하므로 표적이 달라
     * **별도 버킷**이다 — 가입 폭주가 정상 사용자의 주소 변경을 굶기지 않아야 한다.
     */
    @Test
    fun `email change is rate limited in its own bucket`() {
        val filter = filterWithCapacity(1)

        val signup = MockHttpServletResponse()
        filter.doFilter(request("POST", "/api/accounts/signup"), signup, MockFilterChain())
        assertThat(signup.status).isEqualTo(200)

        // 가입 버킷이 소진돼도 이메일 변경은 자기 버킷을 쓴다.
        val first = MockHttpServletResponse()
        filter.doFilter(request("POST", "/api/account/email/change"), first, MockFilterChain())
        assertThat(first.status).isEqualTo(200)

        val second = MockHttpServletResponse()
        filter.doFilter(request("POST", "/api/account/email/change"), second, MockFilterChain())
        assertThat(second.status).isEqualTo(429)
    }

    /** 가입 화면 열람(GET)은 메일을 보내지 않으므로 버킷을 쓰지 않는다(대조군). */
    @Test
    fun `signup page view is not rate limited`() {
        val filter = filterWithCapacity(1)
        repeat(3) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("GET", "/signup"), response, MockFilterChain())
            assertThat(response.status).isEqualTo(200)
        }
    }

    @Test
    fun `unmatched path passes through without consuming tokens`() {
        val filter = filterWithCapacity(1)
        // rate limit 대상이 아닌 경로는 그룹이 없어 그대로 통과(429 아님).
        repeat(3) {
            val response = MockHttpServletResponse()
            filter.doFilter(request("GET", "/account"), response, MockFilterChain())
            assertThat(response.status).isEqualTo(200)
        }
    }
}
