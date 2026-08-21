package com.taspa.server.selfservice

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.selfservice.dto.AuthorizedClientView
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 연결된 앱(제3자 접근) 관리 — Stage 3.
 *
 * 사용자가 권한을 준 OAuth2 클라이언트를 principal_name(=이메일)으로 조회한다. SAS 표준 스키마의
 * oauth2_authorization(발급된 토큰·부여 scope·시각)과 oauth2_authorization_consent(표준 동의 scope)를
 * 함께 훑어 client 목록을 만들고, client_name 은 RegisteredClientRepository 로 복원한다.
 *
 * 철회는 해당 client+user 의 authorization·consent 행을 삭제해 활성 토큰과 표준 동의를 함께 무효화한다
 * (refresh_token 재사용 불가 — JdbcOAuth2AuthorizationService 가 행을 못 찾아 invalid_grant).
 *
 * 클래스명은 `AuthorizedClientService` 를 피한다 — 소셜 로그인 컨텍스트에서 활성화되는 Spring Security
 * OAuth2 클라이언트 자동설정이 같은 이름(`authorizedClientService`)의 빈을 등록해 정의 충돌이 난다.
 */
@Service
class ConnectedAppService(
    private val jdbcTemplate: JdbcTemplate,
    private val registeredClientRepository: RegisteredClientRepository,
    private val auditEventService: AuditEventService,
) {
    private class Aggregate {
        val scopes = linkedSetOf<String>()
        var lastUsedAt: Instant? = null

        fun addScopes(raw: String?) {
            raw?.split(",")?.forEach { token ->
                // consent 의 authorities 는 'SCOPE_' 접두사가 붙을 수 있어 정규화한다.
                val scope = token.trim().removePrefix("SCOPE_")
                if (scope.isNotEmpty()) scopes.add(scope)
            }
        }

        fun observe(candidate: Instant?) {
            if (candidate != null && (lastUsedAt == null || candidate.isAfter(lastUsedAt))) {
                lastUsedAt = candidate
            }
        }
    }

    @Transactional(readOnly = true)
    fun list(principalName: String): List<AuthorizedClientView> {
        val byClient = LinkedHashMap<String, Aggregate>()

        jdbcTemplate.query(
            "SELECT registered_client_id, authorized_scopes, authorization_code_issued_at, " +
                "access_token_issued_at, refresh_token_issued_at, oidc_id_token_issued_at " +
                "FROM oauth2_authorization WHERE principal_name = ?",
            { rs, _ ->
                val agg = byClient.getOrPut(rs.getString("registered_client_id")) { Aggregate() }
                agg.addScopes(rs.getString("authorized_scopes"))
                sequenceOf(
                    "authorization_code_issued_at",
                    "access_token_issued_at",
                    "refresh_token_issued_at",
                    "oidc_id_token_issued_at",
                ).forEach { column -> agg.observe(rs.getTimestamp(column)?.toInstant()) }
            },
            principalName,
        )

        jdbcTemplate.query(
            "SELECT registered_client_id, authorities FROM oauth2_authorization_consent WHERE principal_name = ?",
            { rs, _ ->
                byClient
                    .getOrPut(rs.getString("registered_client_id")) { Aggregate() }
                    .addScopes(rs.getString("authorities"))
            },
            principalName,
        )

        return byClient
            .mapNotNull { (registeredClientId, agg) ->
                // 등록이 사라진 클라이언트(관리 콘솔에서 삭제됨)의 잔여 행은 노출하지 않는다.
                val client = registeredClientRepository.findById(registeredClientId) ?: return@mapNotNull null
                AuthorizedClientView(
                    registeredClientId = registeredClientId,
                    clientName = client.clientName,
                    scopes = agg.scopes.sorted(),
                    lastUsedAt = agg.lastUsedAt,
                )
            }.sortedBy { it.clientName.lowercase() }
    }

    @Transactional
    fun revoke(
        userId: UUID,
        principalName: String,
        registeredClientId: String,
    ) {
        val client =
            registeredClientRepository.findById(registeredClientId)
                ?: throw AuthException(ErrorCode.NOT_FOUND)
        val deletedAuthorizations =
            jdbcTemplate.update(
                "DELETE FROM oauth2_authorization WHERE registered_client_id = ? AND principal_name = ?",
                registeredClientId,
                principalName,
            )
        val deletedConsents =
            jdbcTemplate.update(
                "DELETE FROM oauth2_authorization_consent WHERE registered_client_id = ? AND principal_name = ?",
                registeredClientId,
                principalName,
            )
        // 이 사용자가 이 클라이언트에 준 접근이 애초에 없었으면(또는 이미 철회됨) 404.
        if (deletedAuthorizations == 0 && deletedConsents == 0) {
            throw AuthException(ErrorCode.NOT_FOUND)
        }
        auditEventService.record(
            "THIRDPARTY_ACCESS_REVOKED",
            userId,
            mapOf("clientId" to client.clientId, "clientName" to client.clientName),
        )
    }
}
