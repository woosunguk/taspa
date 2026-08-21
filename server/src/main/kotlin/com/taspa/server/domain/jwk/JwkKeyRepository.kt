package com.taspa.server.domain.jwk

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface JwkKeyRepository : JpaRepository<JwkKey, String> {
    /** 회전 트랜잭션 전용 — SELECT ... FOR UPDATE 로 동시 회전을 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select k from JwkKey k where k.status = :status")
    fun findByStatusForUpdate(
        @Param("status") status: JwkKeyStatus,
    ): List<JwkKey>

    /** 정리 잡: 유예 기간을 넘긴 RETIRED 키 일괄 삭제. */
    @Modifying
    @Query("delete from JwkKey k where k.status = :status and k.retiredAt < :cutoff")
    fun deleteRetiredBefore(
        @Param("status") status: JwkKeyStatus,
        @Param("cutoff") cutoff: Instant,
    ): Int
}
