package com.taspa.server.oidc

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException
import org.springframework.security.web.DefaultRedirectStrategy
import org.springframework.security.web.RedirectStrategy
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.stereotype.Component
import org.springframework.web.util.HtmlUtils
import org.springframework.web.util.UriComponentsBuilder
import org.springframework.web.util.UriUtils
import java.nio.charset.StandardCharsets

/**
 * `/oauth2/authorize` 가 **리다이렉트로 돌려보낼 수 없는** 오류로 끝났을 때의 화면과 로그.
 *
 * ★그전에는 이 자리가 비어 있어서, OIDC 연동에서 가장 흔한 두 실수 — `redirect_uri` 정확일치 실패,
 * 존재하지 않는 `client_id` — 가 **Spring Boot 기본 Whitelabel 오류 페이지**로 끝났다. 그 페이지에는
 * 무엇이 잘못됐는지가 한 글자도 없고, 돌아갈 링크도 없으며, 제품의 나머지가 한국어인데 그 화면만
 * 영문이다. 더 나쁜 것은 **서버에도 흔적이 없다**는 점이었다: 기동 로그 WARN/ERROR 0건, 감사로그 무기록.
 * 즉 연동 개발자도 플랫폼 관리자도 원인을 알 수 있는 경로가 존재하지 않았고, 문의를 받아도 답할 근거가
 * 없었다(이 두 오류는 정의상 클라이언트에게 리다이렉트로 알릴 수 없다 — 그 목적지를 신뢰할 수 없으므로).
 *
 * 그래서 두 가지를 함께 한다:
 * 1. **로그를 남긴다**(WARN). 실패한 client_id·요청 redirect_uri 를 적어 관리자가 등록 정보와 대조할 수
 *    있게. 이건 요청자의 실수이지 서버 장애가 아니므로 ERROR 가 아니다(상시 ERROR 는 경보를 무디게 한다).
 * 2. **한국어 화면**을 준다. 오류 코드와 설명, 그리고 다음에 무엇을 할지.
 *
 * ★`error_description` 은 화면에 **그대로 싣는다**. 이 오류는 인증 전 단계라 사용자 데이터가 들어 있지
 * 않고(요청 파라미터의 형식 문제다), 가리면 이 화면의 유일한 값어치가 사라진다. 다만 HTML 이스케이프는
 * 반드시 거친다 — 설명에 요청 파라미터가 섞여 들어오므로 그대로 쓰면 반사형 XSS 가 된다.
 */
@Component
class OAuth2ErrorPageHandler : AuthenticationFailureHandler {
    private val log = LoggerFactory.getLogger(OAuth2ErrorPageHandler::class.java)
    private val redirectStrategy: RedirectStrategy = DefaultRedirectStrategy()

    override fun onAuthenticationFailure(
        request: HttpServletRequest,
        response: HttpServletResponse,
        exception: AuthenticationException,
    ) {
        val error = (exception as? OAuth2AuthenticationException)?.error
        val code = error?.errorCode ?: "invalid_request"
        val description = error?.description

        /*
         * ★★**RP 로 돌려보낼 수 있는 오류는 반드시 돌려보낸다**(RFC 6749 §4.1.2.1).
         *
         * 이 핸들러는 SAS 의 기본 실패 처리를 **통째로** 대체한다. 그래서 이 분기가 없으면
         * `redirect_uri` 가 이미 검증된 오류 — 사용자가 동의 화면에서 **거부**를 눌렀을 때
         * (`access_denied`), 요청 scope 가 등록에 없을 때(`invalid_scope`) 등 — 까지 400 HTML 로
         * 끝난다. 사용자는 앱으로 **돌아갈 길이 없고**, 연동 앱은 "취소됨"을 영영 통보받지 못한다.
         * 원래 고치려던 것(리다이렉트 **불가능한** 오류의 백지 화면)보다 훨씬 넓은 사고다.
         *
         * 판정과 조립은 SAS `OAuth2AuthorizationEndpointFilter.sendErrorResponse` 와 같게 한다
         * (1.4.2 바이트코드 실측): 인증 토큰에 redirect_uri 가 있으면 error·error_description·
         * error_uri·state 를 쿼리로 붙여 리다이렉트하고, 없을 때만 여기서 화면을 그린다.
         */
        val redirectable = exception as? OAuth2AuthorizationCodeRequestAuthenticationException
        val token = redirectable?.authorizationCodeRequestAuthentication
        val redirectUri = token?.redirectUri
        if (error != null && !redirectUri.isNullOrBlank()) {
            log.debug("OAuth2 인가 오류를 클라이언트로 되돌려 보냄: error={} redirect_uri={}", code, redirectUri)
            redirectStrategy.sendRedirect(request, response, errorRedirect(redirectUri, error, token.state))
            return
        }

        log.warn(
            "OAuth2 인가 요청 거절: error={} client_id={} redirect_uri={} description={}",
            code,
            request.getParameter("client_id"),
            request.getParameter("redirect_uri"),
            description,
        )

        response.status = HttpServletResponse.SC_BAD_REQUEST
        // 이 화면은 캐시되면 안 된다 — 등록을 고친 뒤 새로고침했을 때 옛 오류가 다시 보이면 고친 사람이
        // 자기 수정이 반영되지 않았다고 판단한다.
        response.setHeader("Cache-Control", "no-store")

        if (!acceptsHtml(request)) {
            // 기계(curl·SDK)에는 표준 OAuth2 오류 JSON 이 더 쓸모 있다.
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = StandardCharsets.UTF_8.name()
            response.writer.write(
                """{"error":"${jsonEscape(code)}","error_description":"${jsonEscape(description ?: "")}"}""",
            )
            return
        }

        response.contentType = MediaType.TEXT_HTML_VALUE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.writer.write(page(code, description))
    }

    /**
     * SAS 기본 처리와 **같은 형태**의 오류 리다이렉트 URL. 값은 전부 URI 인코딩한다
     * (설명에 공백·한글이 들어가면 인코딩 없이는 잘린 URL 이 된다).
     */
    private fun errorRedirect(
        redirectUri: String,
        error: OAuth2Error,
        state: String?,
    ): String {
        val builder =
            UriComponentsBuilder
                .fromUriString(redirectUri)
                .queryParam("error", error.errorCode)
        if (!error.description.isNullOrBlank()) {
            builder.queryParam("error_description", UriUtils.encode(error.description, StandardCharsets.UTF_8))
        }
        if (!error.uri.isNullOrBlank()) {
            builder.queryParam("error_uri", UriUtils.encode(error.uri, StandardCharsets.UTF_8))
        }
        if (!state.isNullOrBlank()) {
            builder.queryParam("state", UriUtils.encode(state, StandardCharsets.UTF_8))
        }
        return builder.build(true).toUriString()
    }

    private fun acceptsHtml(request: HttpServletRequest): Boolean = request.getHeader("Accept")?.contains(MediaType.TEXT_HTML_VALUE) == true

    /**
     * 템플릿 엔진을 쓰지 않는 이유: 이 핸들러는 **필터 안**에서 응답을 직접 쓰는 자리라(DispatcherServlet
     * 밖) 뷰 해석에 기댈 수 없다. 공용 `auth.css` 를 그대로 참조해 로그인 화면과 같은 옷을 입는다.
     */
    private fun page(
        code: String,
        description: String?,
    ): String {
        val hint =
            when (code) {
                "invalid_client" ->
                    "요청에 실린 client_id 로 등록된 클라이언트를 찾지 못했습니다. " +
                        "관리 콘솔의 '클라이언트' 목록에 있는 ID 와 정확히 같은지 확인해 주세요."
                "invalid_request" ->
                    "요청 파라미터가 등록 정보와 맞지 않습니다. redirect_uri 는 등록된 값과 " +
                        "<b>문자 단위로 정확히</b> 같아야 합니다(끝의 / 하나, http/https, 포트 번호까지)."
                else -> "요청을 처리할 수 없습니다. 아래 오류 코드를 연동 담당자에게 전달해 주세요."
            }
        val detailRow =
            if (description.isNullOrBlank()) {
                ""
            } else {
                "<div class=\"info-row\"><span class=\"label\">상세</span><span>" + esc(description) + "</span></div>"
            }
        return buildString {
            append("<!DOCTYPE html>\n")
            append("<html lang=\"ko\"><head>\n")
            append("<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            append("<title>로그인 요청을 처리할 수 없습니다 · taspa</title>\n")
            append("<link rel=\"stylesheet\" href=\"/css/auth.css\">\n")
            append("</head><body>\n")
            append("<main class=\"auth-card\">\n")
            append("<div class=\"wordmark\"><b>taspa</b></div>\n")
            append("<h1 class=\"auth-title\">로그인 요청을 처리할 수 없습니다</h1>\n")
            append("<p class=\"auth-subtitle\">").append(hint).append("</p>\n")
            append("<div class=\"info-row\"><span class=\"label\">오류 코드</span><span>")
            append(esc(code)).append("</span></div>\n")
            append(detailRow).append("\n")
            append("<p class=\"hint\">연결하려는 앱으로 되돌려 보낼 수 없어 여기서 멈췄습니다 — ")
            append("되돌아갈 주소 자체를 신뢰할 수 없기 때문입니다. 앱을 처음부터 다시 실행해 주세요.</p>\n")
            append("<div class=\"stack-links\"><a class=\"link\" href=\"/login\">taspa 로그인으로 이동</a></div>\n")
            append("</main>\n</body></html>\n")
        }
    }

    private fun esc(value: String): String = HtmlUtils.htmlEscape(value)

    private fun jsonEscape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ")
}
