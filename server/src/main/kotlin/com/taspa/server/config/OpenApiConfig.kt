package com.taspa.server.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.OAuthFlow
import io.swagger.v3.oas.models.security.OAuthFlows
import io.swagger.v3.oas.models.security.Scopes
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Swagger UI 가 **인증 방식을 말하게 한다**.
 *
 * ★그전에는 OpenAPI 문서에 보안 스키마가 **하나도 없었다**. 연동 담당자는 Swagger 를 열어도 어떤 grant 를
 * 쓰는지, 어떤 scope 가 있는지, 왜 401·403 이 나는지를 알 수 없었고 — 이 서버의 상당수 엔드포인트가
 * M2M 전용이라 "그냥 눌러 보면" 전부 실패한다. 문서가 있는데 그 문서가 인증을 설명하지 않으면,
 * 연동 담당자는 실패를 서버 장애로 오해한다.
 *
 * 두 가지를 선언한다:
 * - `session`: 브라우저 세션 쿠키(콘솔·계정 API). Swagger UI 에서 "Try it out" 하려면 같은 브라우저로
 *   로그인해 두면 된다.
 * - `m2m`: client_credentials(POS·SCIM·소비 적재·예측). scope 목록이 **여기 한 곳**에 있어야
 *   연동 담당자가 무엇을 요청해야 하는지 추측하지 않는다.
 *
 * scope 목록은 `taspa.allowed-scopes` 와 **손으로 맞춘다**. 자동 주입하면 설명 없는 이름만 나열되어
 * 지금 상태(원문 식별자만 보이는 것)와 다를 바 없다 — 뜻을 적는 것이 이 문서의 값어치다.
 */
@Configuration
class OpenApiConfig(
    @Value("\${taspa.issuer-uri:http://localhost:9100}")
    private val issuerUri: String,
) {
    @Bean
    fun taspaOpenApi(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("taspa API")
                    .version("v1")
                    .description(
                        "중앙 인증(OIDC Provider) + 식대 플랫폼 API. " +
                            "연동 절차와 M2M 결속 규칙은 docs/integration-guide.md 를 참고할 것.",
                    ),
            ).components(
                Components()
                    .addSecuritySchemes("session", sessionScheme())
                    .addSecuritySchemes("m2m", m2mScheme()),
            )

    private fun sessionScheme(): SecurityScheme =
        SecurityScheme()
            .type(SecurityScheme.Type.APIKEY)
            .`in`(SecurityScheme.In.COOKIE)
            .name("SESSION")
            .description(
                "브라우저 세션 쿠키(콘솔·계정 API). 상태변경 요청은 CSRF 토큰(X-CSRF-TOKEN)도 필요하고, " +
                    "민감 작업은 최근 재인증(step-up)을 요구한다 — 만료 시 401 REAUTH_REQUIRED. " +
                    "사용자 위임 베어러 토큰은 이 API 들에서 **의도적으로 거부**된다(confused-deputy 차단).",
            )

    private fun m2mScheme(): SecurityScheme =
        SecurityScheme()
            .type(SecurityScheme.Type.OAUTH2)
            .description(
                "서버 대 서버(client_credentials). ★scope 만으로는 부족하다 — 어느 조직·어느 매장의 " +
                    "데이터인지는 **클라이언트 등록의 결속**(orgId/merchantId)이 정하고, 그 결속이 없으면 " +
                    "해당 API 는 전부 403 이다(fail-closed). 사용자 로그인으로 받은 토큰으로는 기계 전용 " +
                    "표면에 도달할 수 없다.",
            ).flows(
                OAuthFlows().clientCredentials(
                    OAuthFlow()
                        .tokenUrl("$issuerUri/oauth2/token")
                        .scopes(
                            Scopes()
                                .addString("meal.redeem", "식권 승인·취소·환불 (merchant 결속 필요)")
                                .addString("meal.consumption.write", "소비(식수) 이벤트 적재 (org 결속 필요)")
                                .addString("meal.consumption.read", "자기 조직 식수 집계 조회 (org 결속 필요)")
                                .addString("meal.consumption.read.all", "전 조직 집계 조회 (신뢰 플랫폼 전용)")
                                .addString("meal.forecast.read", "식수 예측 조회 (org 결속 필요)")
                                .addString("org.scim", "SCIM 2.0 사용자 프로비저닝 (org 결속 필요)")
                                .addString("calendar.read", "자기 조직 캘린더 조회 (org 결속 필요)")
                                .addString("calendar.read.all", "전 조직 캘린더 조회 (신뢰 플랫폼 전용)"),
                        ),
                ),
            )
}
