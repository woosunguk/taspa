package com.taspa.server.common.exception

import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * HTTP 프로토콜 오류 매핑([GlobalExceptionHandler])과 미인증 응답 계약([ApiAuthenticationEntryPoint] +
 * `SecurityConfig.defaultSecurityFilterChain` 의 `exceptionHandling`)을 잠그는 회귀 테스트.
 *
 * 이 저장소는 모든 보안 불변식을 회귀 테스트로 고정해 왔는데, 최근 추가된 캐치올 위 예외 핸들러 4종
 * (405/415/바인딩 실패/DateTimeException)과 진입점 이원화(API 401 JSON vs 화면 302)에는 그 테스트가
 * 없었다. 누가 advice 순서를 바꾸거나 진입점 매핑 하나를 지우면 조용히 되돌아가고 아무도 모른다.
 */
class HttpContractIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val password = "SecureP@ssw0rd123"
    private val adminEmail = "http-contract-admin@example.com"

    @BeforeEach
    fun setUp() {
        userRepository.deleteAll()
        userRepository.save(
            User(
                email = adminEmail,
                passwordHash = passwordEncoder.encode(password),
                emailVerified = true,
                role = UserRole.ADMIN.name,
            ),
        )
    }

    // ---- A. 미인증 응답 계약 ----

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/api/account/me",
            "/api/csrf",
            "/api/sessions",
            "/api/passkeys",
            "/api/trusted-devices",
            "/api/meal/transactions",
            "/api/merchant-console/mine",
        ],
    )
    fun `미인증 GET 은 API 경로에서 401 JSON 을 반환한다`(path: String) {
        mockMvc
            .perform(get(path))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"))
    }

    /**
     * ★**세션 소실 뒤의 상태변경은 401 이 아니라 CSRF 부재 403 으로 온다** — 그 403 도 우리 스키마여야
     * 프런트가 로그인으로 보낼 수 있다(`ApiAccessDeniedHandler` 가 `MissingCsrfTokenException` 을
     * `UNAUTHENTICATED` 로 표시한다).
     *
     * ★두 경로를 함께 고정하는 이유: org API 경로는 **별도 체인**(@Order 2)이라 기본 체인에 건
     * 핸들러가 닿지 않는다. 실제로 기본 체인에만 배선한 채 배포 직전까지 갔고 — SPA 상태변경의
     * 대부분(구성원·초대·식대정책·역할, 29개 호출 지점)이 그 체인이라 "세션 만료 후 저장 → 영문
     * Forbidden(403), 로그인 이동 없음"이 가장 많이 쓰는 화면들에서만 남아 있었다(적대 리뷰에서 잡혔다).
     * 한 경로만 단언하면 다른 체인의 누락이 초록불 뒤에 숨는다.
     */
    @ParameterizedTest
    @ValueSource(
        strings = [
            // 기본 체인(@Order 3)
            "/api/account/display-name",
            // org API 체인(@Order 2) — 별도 배선이라 함께 고정해야 한다
            "/api/orgs/00000000-0000-0000-0000-000000000000/invitations",
        ],
    )
    fun `세션 없는 상태변경은 본문 errorCode 가 UNAUTHENTICATED 다`(path: String) {
        /*
         * 상태코드는 체인마다 다르다(실측): 기본 체인은 익명 + CSRF 부재를 ExceptionTranslationFilter 가
         * **진입점으로 승격**해 401, org 체인은 AccessDeniedHandler 로 흘러 403. 프런트 계약의 핵심은
         * 상태코드가 아니라 **본문의 errorCode** 다 — `web/lib/api.ts` 는 401 과 "403+UNAUTHENTICATED"
         * 를 같은 경로(로그인 이동)로 처리한다. 여기서 401/403 을 못박으면 체인 내부 순서가 바뀔 때마다
         * 테스트가 의미 없이 깨지고, 정작 지켜야 할 것(영문 Forbidden 본문 금지)은 놓친다.
         */
        val status =
            mockMvc
                .perform(post(path).contentType("application/json").content("{}"))
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"))
                .andReturn()
                .response.status
        org.assertj.core.api.Assertions
            .assertThat(status)
            .describedAs("세션 소실은 401 또는 403 — 200/500 이면 계약 위반")
            .isIn(401, 403)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/account", "/admin"])
    fun `대조군 -- 미인증 GET 은 화면 경로에서 302 로 로그인 페이지로 리다이렉트된다`(path: String) {
        // 이 대조 단언이 이 파일의 핵심이다. defaultSecurityFilterChain 은 진입점을 두 개 등록한다
        // (/api/** → ApiAuthenticationEntryPoint 401, AnyRequestMatcher → LoginUrlAuthenticationEntryPoint
        // 302). DelegatingAuthenticationEntryPoint 는 매핑 리스트의 **첫 항목을 폴백**으로 삼으므로, 순서가
        // 뒤집히거나 AnyRequestMatcher 매핑이 사라지면 이 화면 경로들까지 401 JSON 이 되어 사용자가 로그인
        // 페이지로 가지 못한다(위 A 의 401 단언만으로는 이 뒤집힘을 잡지 못한다 — 둘이 함께 있어야 의미가
        // 있다). MockMvc 는 브라우저와 달리 Accept 헤더를 보내지 않으므로, 정확히 그 폴백 조건(어떤 매체
        // 타입 매처와도 안 걸리는 요청)을 재현한다.
        mockMvc
            .perform(get(path))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrlPattern("**/login"))
    }

    // ---- B. 프로토콜 오류 매핑 ----

    @Test
    fun `필수 쿼리 파라미터 누락은 400 VALIDATION_ERROR 다`() {
        // GET /login/magic 은 permitAll(부분 인증 상태 랜딩 페이지)이지만 token 이 필수다.
        // MissingServletRequestParameterException 전용 핸들러가 없으면 캐치올이 잡아 500 이 된다.
        mockMvc
            .perform(get("/login/magic"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
    }

    @Test
    fun `POST 전용 엔드포인트에 GET 은 405 이고 Allow 헤더에 POST 를 포함한다`() {
        mockMvc
            .perform(get("/api/accounts/signup"))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"))
            .andExpect(header().string("Allow", containsString("POST")))
    }

    @Test
    fun `지원하지 않는 Content-Type 은 415 다`() {
        mockMvc
            .perform(
                post("/api/accounts/signup").contentType(MediaType.TEXT_PLAIN).content("whatever"),
            ).andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"))
    }

    @Test
    fun `대조군 -- 손상된 JSON 본문은 기존대로 400 VALIDATION_ERROR 다`() {
        // Content-Type 은 지원 대상(application/json)이지만 본문이 파손된 경우. 위 415 케이스와
        // 대비시켜 "타입이 문제"와 "내용이 문제"가 각기 다른 errorCode 로 갈리는 기존 동작이
        // 새 핸들러들 때문에 깨지지 않았는지 고정한다.
        mockMvc
            .perform(
                post("/api/accounts/signup").contentType(MediaType.APPLICATION_JSON).content("{bad"),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
    }

    // ---- C. 극단 날짜 (500 으로 새지 않는다) ----

    @Test
    fun `극단적인 날짜 파라미터는 예측 조회에서 500 이 아니라 400 이다`() {
        // LocalDate.parse 는 "-999999999-01-01"(LocalDate.MIN)까지도 파싱에 성공하므로, 파싱만으로는
        // 걸러지지 않는다. 이 값이 그대로 plusDays/minusDays 창 계산에 들어가면 처리되지 않은
        // DateTimeException(오버플로)이 캐치올을 타 500 이 될 수 있다 — 여기서는 500 이 아님(400)을
        // 잠근다. 플랫폼 ADMIN 세션을 쓰는 이유: ForecastController 의 인가는 정책 엔진 판정인데,
        // platformAdmin 은 org 존재 여부와 무관하게 `*`/`*` 로 통과하므로(FORECAST_READ 는
        // PLATFORM_ADMIN_EXCLUDED_ACTIONS 가 아니다) 실재하지 않는 orgId 로도 인가 단계를 지나
        // 날짜 파싱/가드 로직까지 확실히 도달한다(조직·멤버십 셋업이 불필요).
        val session = login(adminEmail)
        session
            .perform(get("/api/orgs/{orgId}/forecast", UUID.randomUUID()).param("to", "-999999999-01-01"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `타입 불일치 쿼리 파라미터는 가맹 콘솔 거래 조회에서 400 이다`() {
        // limit 은 Int? 파라미터다. Spring MVC 는 컨트롤러 메서드의 인자를 전부 바인딩한 뒤에야 메서드
        // 본문(그 안의 authorize() 호출)을 실행하므로, limit=abc 의 타입 변환 실패는 인가 판정보다
        // 먼저 예외를 던진다 — 그래서 대상 가맹점의 관리자가 아니어도(플랫폼 ADMIN 세션이지만 이
        // merchantId 의 관리 멤버십은 없음) 항상 400 이다. merchantId 자체는 형식만 유효한 임의
        // UUID 면 충분하다(존재 여부는 이 실패 경로에 영향이 없다).
        val session = login(adminEmail)
        session
            .perform(
                get("/api/merchant-console/{merchantId}/transactions", UUID.randomUUID()).param("limit", "abc"),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
    }

    private fun login(email: String): WebSession {
        val session = webSession()
        session.perform(post("/login/identifier").param("email", email).with(csrf()))
        session
            .perform(post("/login/password").param("username", email).param("password", password).with(csrf()))
            .andExpect(status().is3xxRedirection)
        return session
    }
}
