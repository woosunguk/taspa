package com.taspa.server.passkey

import com.taspa.server.common.security.SecureTokenGenerator
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria
import org.springframework.security.web.webauthn.api.Bytes
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions
import org.springframework.security.web.webauthn.api.PublicKeyCredentialParameters
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity
import org.springframework.security.web.webauthn.api.UserVerificationRequirement
import org.springframework.security.web.webauthn.registration.PublicKeyCredentialCreationOptionsRepository
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/**
 * 패스키 등록 옵션 저장소 — **DB 영속화**(다중 인스턴스 안전).
 *
 * 이전 구현(인메모리 맵)은 발급 인스턴스의 힙에만 옵션을 두었다. 로드밸런서 뒤에서는 옵션을 발급한
 * 인스턴스와 자격증명 POST 를 받는 인스턴스가 달라질 수 있고, 그러면 등록이 실패한다 — 사용자에게는
 * 지문을 찍었는데 아무 일도 안 일어나는 것으로 보이고, 재시도해도 같은 확률로 반복된다.
 *
 * **왜 객체를 통째로 저장하지 않는가**: `PublicKeyCredentialCreationOptions` 는 Serializable 이 아니고
 * (세션 JDK 직렬화 불가), Jackson 왕복도 안 된다 — `WebauthnJackson2Module` 의 믹스인은 직렬화 전용이라
 * 역직렬화 creator 가 없다(`CreationOptionsSerializationTest` 가 그 사실을 고정한다). 그래서 검증에
 * 필요한 필드만 컬럼으로 풀어 저장하고 로드 시 재구성한다.
 *
 * ★**재구성이 충분한 근거**(6.4.4 바이트코드 실측): `Webauthn4JRelyingPartyOperations.registerCredential`
 * 이 옵션에서 읽는 것은 `rp` · `challenge` · `authenticatorSelection.userVerification` ·
 * `pubKeyCredParams` · `user` **다섯 뿐**이고, `WebAuthnRegistrationFilter` 는 옵션 필드를 직접 읽지 않고
 * 통째로 넘긴다. `excludeCredentials`·`extensions` 는 브라우저가 **생성 시점에** 소비하는 값이라 검증에
 * 관여하지 않는다(그래서 복원하지 않는다 — 복원하는 척하면 오히려 "저장된 값이 최신"이라는 오해를 준다).
 * 최종 증거는 e2e 패스키 등록이다: 재구성이 부실하면 그 테스트가 실패한다.
 */
@Component
class JdbcCreationOptionsRepository(
    private val jdbcTemplate: JdbcTemplate,
) : PublicKeyCredentialCreationOptionsRepository {
    override fun save(
        request: HttpServletRequest,
        response: HttpServletResponse,
        options: PublicKeyCredentialCreationOptions?,
    ) {
        if (options == null) {
            // WebAuthnRegistrationFilter 가 옵션 소비 직후 save(null) 로 정리한다(6.4.4 실측).
            request.getSession(false)?.let { session ->
                (session.getAttribute(SESSION_KEY) as? String)?.let(::deleteByToken)
                session.removeAttribute(SESSION_KEY)
            }
            return
        }
        val session = request.getSession(true)
        // 세션당 1개 불변식(원본 HttpSession 저장소의 덮어쓰기 동작과 동일). 지우지 않으면 참조가 끊긴
        // 고아 행이 TTL 동안 쌓인다(등록 옵션 연타 시 테이블 증식).
        (session.getAttribute(SESSION_KEY) as? String)?.let(::deleteByToken)

        val token = SecureTokenGenerator.generateToken().take(TOKEN_LENGTH)
        val timeout = options.timeout ?: DEFAULT_TIMEOUT
        jdbcTemplate.update(
            """
            INSERT INTO webauthn_registration_options
                (token, challenge, user_handle, user_name, user_display_name, rp_id, rp_name,
                 user_verification, algorithms, timeout_millis, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            token,
            options.challenge.bytes,
            options.user.id.bytes,
            options.user.name,
            options.user.displayName,
            options.rp.id,
            options.rp.name,
            options.authenticatorSelection?.userVerification?.value,
            options.pubKeyCredParams.joinToString(",") { it.alg.value.toString() },
            timeout.toMillis(),
            // TTL 은 옵션의 timeout 이 아니라 **행 수명**이다 — 브라우저 타임아웃보다 넉넉해야
            // "사용자가 늦게 지문을 찍었는데 행이 이미 지워진" 상황을 만들지 않는다.
            Timestamp.from(Instant.now().plus(ROW_TTL)),
        )
        session.setAttribute(SESSION_KEY, token)
    }

    override fun load(request: HttpServletRequest): PublicKeyCredentialCreationOptions? {
        val token = request.getSession(false)?.getAttribute(SESSION_KEY) as? String ?: return null
        val rows =
            jdbcTemplate.query(
                """
                SELECT challenge, user_handle, user_name, user_display_name, rp_id, rp_name,
                       user_verification, algorithms, timeout_millis
                  FROM webauthn_registration_options
                 WHERE token = ? AND expires_at > now()
                """.trimIndent(),
                { rs, _ ->
                    val builder =
                        PublicKeyCredentialCreationOptions
                            .builder()
                            .rp(
                                PublicKeyCredentialRpEntity
                                    .builder()
                                    .id(rs.getString("rp_id"))
                                    .name(rs.getString("rp_name"))
                                    .build(),
                            ).user(
                                ImmutablePublicKeyCredentialUserEntity
                                    .builder()
                                    .id(Bytes(rs.getBytes("user_handle")))
                                    .name(rs.getString("user_name"))
                                    .displayName(rs.getString("user_display_name"))
                                    .build(),
                            ).challenge(Bytes(rs.getBytes("challenge")))
                            .pubKeyCredParams(*parseAlgorithms(rs.getString("algorithms")))
                            .timeout(Duration.ofMillis(rs.getLong("timeout_millis")))
                    rs.getString("user_verification")?.let { value ->
                        // enum 이 아니라 값 객체라 valueOf 가 없다 — 알려진 3종에서 값으로 찾는다.
                        // 알 수 없는 값이면 authenticatorSelection 을 비우지 않고 **가장 엄격한 REQUIRED**
                        // 로 떨어뜨린다(손상 데이터가 검증을 느슨하게 만들면 안 된다).
                        builder.authenticatorSelection(
                            AuthenticatorSelectionCriteria
                                .builder()
                                .userVerification(
                                    USER_VERIFICATIONS.firstOrNull { it.value == value }
                                        ?: UserVerificationRequirement.REQUIRED,
                                ).build(),
                        )
                    }
                    builder.build()
                },
                token,
            )
        return rows.firstOrNull()
    }

    /** 만료 행 정리 — RetentionCleanupJob 훅. 지연 삭제라 정상 흐름(save(null))이 먼저 지운다. */
    fun purgeExpired(): Int = jdbcTemplate.update("DELETE FROM webauthn_registration_options WHERE expires_at <= now()")

    private fun deleteByToken(token: String) {
        jdbcTemplate.update("DELETE FROM webauthn_registration_options WHERE token = ?", token)
    }

    /**
     * COSE alg 값 CSV → 파라미터 목록.
     *
     * 알 수 없는 값은 **버린다**. 목록이 통째로 비면 등록이 실패하는데, 그건 잘못된 알고리즘을
     * 허용하는 것보다 낫다(검증이 이 목록으로 알고리즘 적합성을 판정한다).
     */
    private fun parseAlgorithms(csv: String): Array<PublicKeyCredentialParameters> =
        csv
            .split(",")
            .mapNotNull { raw -> raw.trim().toLongOrNull() }
            .mapNotNull { alg -> SUPPORTED.firstOrNull { it.alg.value == alg } }
            .toTypedArray()

    companion object {
        const val SESSION_KEY = "TASPA_PASSKEY_CREATION_OPTIONS_KEY"

        /** 행 수명 — 브라우저 타임아웃(기본 5분)보다 넉넉하게 둔다. */
        private val ROW_TTL: Duration = Duration.ofMinutes(15)
        private val DEFAULT_TIMEOUT: Duration = Duration.ofMinutes(5)
        private const val TOKEN_LENGTH = 64

        private val USER_VERIFICATIONS =
            listOf(
                UserVerificationRequirement.DISCOURAGED,
                UserVerificationRequirement.PREFERRED,
                UserVerificationRequirement.REQUIRED,
            )

        /** SS 6.4.4 가 제공하는 파라미터 상수 전체 — CSV 복원의 조회표. */
        private val SUPPORTED =
            listOf(
                PublicKeyCredentialParameters.EdDSA,
                PublicKeyCredentialParameters.ES256,
                PublicKeyCredentialParameters.ES384,
                PublicKeyCredentialParameters.ES512,
                PublicKeyCredentialParameters.RS256,
                PublicKeyCredentialParameters.RS384,
                PublicKeyCredentialParameters.RS512,
                PublicKeyCredentialParameters.RS1,
            )
    }
}
