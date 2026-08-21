package com.taspa.server.domain.federation

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface FederatedIdentityRepository : JpaRepository<FederatedIdentity, UUID> {
    fun findByProviderAndProviderUserId(
        provider: String,
        providerUserId: String,
    ): FederatedIdentity?

    /**
     * 기업 SSO(Stage E) 커넥션 스코프 신원 조회. 삭제된 커넥션의 잔여 신원(connection_id 가 ON DELETE
     * SET NULL 로 NULL 이 됨)이 같은 registration_id 로 재생성된 다른 조직 커넥션에 상속돼 계정 탈취로
     * 이어지는 것을 차단한다 — 조직 로그인은 이 커넥션 id 로 스코프된 신원만 인정한다.
     */
    fun findByProviderAndProviderUserIdAndConnectionId(
        provider: String,
        providerUserId: String,
        connectionId: UUID,
    ): FederatedIdentity?

    fun findByUserId(userId: UUID): List<FederatedIdentity>

    fun findByUserIdAndProvider(
        userId: UUID,
        provider: String,
    ): List<FederatedIdentity>

    fun countByUserId(userId: UUID): Long
}
