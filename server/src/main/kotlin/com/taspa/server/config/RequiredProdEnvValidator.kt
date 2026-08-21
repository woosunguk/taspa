package com.taspa.server.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.Profiles

/**
 * prod 필수 환경변수 **존재**를 기동 최초 단계에서 확인한다 — 없으면 **한 번에 전부** 알려준다.
 *
 * ## 왜 별도 검증기가 필요한가
 *
 * `application-prod.yml` 은 시크릿·외부 엔드포인트를 기본값 없는 `${ENV}` 로 두어 미주입 시 기동을
 * 막는다(의도된 fail-fast). 문제는 **실패하는 방식**이었다. 변수 하나가 비면 스프링의 플레이스홀더
 * 해석이 먼저 터지는데, 그 예외는 하필 그 값을 처음 건드린 자동설정에서 나온다 — 실제로 `MAIL_HOST`
 * 하나가 없을 때 배포자가 받는 것은
 * `BeanDefinitionStoreException ... Error processing condition on MailSenderAutoConfiguration`
 * 로 시작하는 40줄짜리 스택트레이스였다. "메일 설정이 잘못됐나?" 로 오해하기 딱 좋고, 진짜 원인
 * (환경변수 미주입)은 맨 아래 `Caused by` 한 줄에 묻힌다.
 *
 * 더 나쁜 것은 **순서**다. [ProductionSafetyValidator] 는 값이 이미 풀린 뒤에 도는 빈이라, 앞의 어떤
 * 변수든 하나 비면 그 검증기가 **아예 실행되지 않는다** — 암호화 키 강도·issuer https 같은 공들인
 * 안내가 나올 기회가 없다. 그래서 존재 확인만 떼어 **플레이스홀더 해석보다 앞선 단계**로 옮겼다.
 *
 * ## 역할 분담
 *
 * - 이 클래스: **있는가** (누락된 것 전부를 한 번에 나열)
 * - [ProductionSafetyValidator] / `EncryptionConfig`: **쓸 만한 값인가**
 *   (https·비-localhost·키 강도·두 키 분리)
 *
 * prod 프로파일이 아니면 아무 것도 하지 않는다.
 */
class RequiredProdEnvValidator : EnvironmentPostProcessor {
    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        // ★`activeProfiles` 를 직접 보지 않는다. `application-prod.yml` 로드와 `@Profile("prod")` 빈 생성은
        // `acceptsProfiles` 로 판정되는데, 그건 active 가 비어 있으면 **default 프로파일로 폴백**한다.
        // 따라서 `spring.profiles.default=prod` 로 뜨는 배포에서는 prod 설정이 실제로 적용되는데도
        // activeProfiles 는 비어 있어 이 검증기만 침묵한다 — 발동 조건을 설정 적용 조건과 같은 술어로 맞춘다.
        if (!environment.acceptsProfiles(Profiles.of(PROFILE))) return

        val missing = REQUIRED.filter { environment.getProperty(it.name).isNullOrBlank() }
        if (missing.isEmpty()) return

        val detail = missing.joinToString("\n") { "  - ${it.name}: ${it.purpose}" }
        throw IllegalStateException(
            "prod 프로파일 필수 환경변수가 설정되지 않았습니다(${missing.size}개). " +
                "설정 방법은 README '프로덕션 배포' 를 참고하세요.\n$detail",
        )
    }

    data class RequiredEnv(
        val name: String,
        val purpose: String,
    )

    companion object {
        const val PROFILE = "prod"

        /**
         * `application-prod.yml` 에서 **기본값 없는** `${ENV}` 전부와 일치해야 한다.
         *
         * ★이 대응은 주석이 아니라 테스트가 강제한다(`RequiredProdEnvValidatorTest` 가 yml 을 직접
         * 파싱해 양방향으로 대조). 목록에서 빠진 변수는 예전처럼 자동설정 스택트레이스로 실패하고,
         * 반대로 yml 에서 기본값이 생긴 변수를 여기 남겨 두면 **불필요한 주입을 강요**한다 —
         * 둘 다 조용히 어긋나는 종류라 사람 눈으로는 오래 안 보인다.
         */
        val REQUIRED =
            listOf(
                RequiredEnv("DB_URL", "관리형 PostgreSQL JDBC URL (예: jdbc:postgresql://db:5432/taspa)"),
                RequiredEnv("DB_USERNAME", "DB 계정"),
                RequiredEnv("DB_PASSWORD", "DB 비밀번호 (dev 기본값 'taspa' 는 거부된다)"),
                RequiredEnv("MAIL_HOST", "실제 SMTP 호스트 (인증 코드·초대 메일 발송)"),
                RequiredEnv(
                    "TASPA_TRUSTED_PROXIES",
                    "신뢰 프록시 IP 대역 정규식 — 이 대역의 X-Forwarded-For 만 반영한다",
                ),
                RequiredEnv("TASPA_ISSUER_URI", "OIDC issuer (https 필수, 발급 토큰 iss 에 노출)"),
                RequiredEnv("TASPA_WEBAUTHN_RP_ID", "패스키 RP ID — 접속 도메인과 정확히 같아야 한다"),
                RequiredEnv("TASPA_WEBAUTHN_ALLOWED_ORIGINS", "패스키 허용 오리진(쉼표 구분, https 필수)"),
                RequiredEnv("MFA_ENCRYPTION_KEY", "TOTP·SSO 시크릿 암호화 키 (openssl rand -base64 32)"),
                RequiredEnv(
                    "TASPA_JWK_ENCRYPTION_KEY",
                    "JWT 서명 개인키 암호화 키 — MFA 키와 **다른 값**이어야 한다",
                ),
            )
    }
}
