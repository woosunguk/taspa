package com.taspa.server.domain.org

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/**
 * [OrgInvitationRepository.findAcceptGateByTokenHash] 의 반환 형태 — 만료 사전 판정에 필요한 최소 필드.
 * 인터페이스 프로젝션이라 조회 결과가 영속성 컨텍스트에 적재되지 않는다(그 이유는 해당 메서드 KDoc 참고).
 */
interface InvitationAcceptGate {
    val id: UUID
    val status: String
    val expiresAt: Instant
}

interface OrgInvitationRepository : JpaRepository<OrgInvitation, UUID> {
    /** 수락 흐름: 토큰 해시로 초대 조회(원문 토큰은 저장하지 않는다). */
    fun findByTokenHash(tokenHash: String): OrgInvitation?

    /**
     * accept() 의 **사전 판정 전용** 프로젝션 — 만료를 잠금 획득보다 먼저 걸러 내기 위한 조회다.
     *
     * ★엔티티가 아니라 프로젝션인 것이 본질이다(성능 문제가 아니다). 같은 트랜잭션에서 이 행을
     * **엔티티로** 먼저 읽으면 뒤이은 [findByTokenHashForUpdate] 가 1차 캐시의 옛 스냅샷을 돌려준다 —
     * Hibernate 는 이미 초기화된 엔티티를 result set 으로 다시 채우지 않고, `OrgInvitation` 에는
     * `@Version` 이 없어 stale 을 감지할 장치도 없다. 그러면 FOR UPDATE 는 행을 잠그되 "잠금 후 재확인"은
     * **DB 의 현재 값이 아닌 과거 값**으로 이뤄져, 그 사이 커밋된 ACCEPTED/REVOKED 를 통과시킨다
     * (단일 사용 위반·취소 우회). 프로젝션은 영속성 컨텍스트에 아무것도 넣지 않으므로 FOR UPDATE 조회가
     * 그 트랜잭션의 **첫 적재 = 권위 있는 최신 읽기**로 남는다.
     * 회귀 테스트: `OrgInvitationServiceIntegrationTest` 의 "사전 조회 이후 …" 두 케이스.
     */
    @Query(
        "select i.id as id, i.status as status, i.expiresAt as expiresAt " +
            "from OrgInvitation i where i.tokenHash = :tokenHash",
    )
    fun findAcceptGateByTokenHash(
        @Param("tokenHash") tokenHash: String,
    ): InvitationAcceptGate?

    /**
     * 수락 경합 직렬화: 토큰 해시로 초대 행을 비관적 쓰기 잠금(SELECT ... FOR UPDATE)으로 조회한다.
     * 같은 토큰의 동시 수락(더블클릭)을 직렬화해, 두 요청이 모두 '멤버 아님'을 관측하고 org_memberships
     * UNIQUE(org_id,user_id) 를 동시에 INSERT 하다 한쪽이 500(DataIntegrityViolation)이 되는 것을 막는다.
     * 뒤늦은 요청은 잠금 해제 후 이미 ACCEPTED 로 전이된 초대를 보고 INVITATION_INVALID 로 깔끔히 거절된다.
     * accept() 전용(트랜잭션 필수).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from OrgInvitation i where i.tokenHash = :tokenHash")
    fun findByTokenHashForUpdate(
        @Param("tokenHash") tokenHash: String,
    ): OrgInvitation?

    /** 목록: 조직의 특정 상태 초대(생성 최신순). */
    fun findByOrgIdAndStatusOrderByCreatedAtDesc(
        orgId: UUID,
        status: String,
    ): List<OrgInvitation>

    /** 재초대: (org, email) PENDING 1건 재사용/갱신 대상 조회. */
    fun findByOrgIdAndEmailAndStatus(
        orgId: UUID,
        email: String,
        status: String,
    ): OrgInvitation?

    /** 취소/조회: 조직 격리 검증용(타 org id 로 조회하면 null → 404). */
    fun findByIdAndOrgId(
        id: UUID,
        orgId: UUID,
    ): OrgInvitation?

    /**
     * 재발송 경합 직렬화: (org, id) 로 초대 행을 비관적 쓰기 잠금(SELECT ... FOR UPDATE)으로 조회한다.
     * resend() 가 잠금 없이 읽으면 동시 accept() 와 lost-update 가 나 소비된(ACCEPTED) 초대를 PENDING 으로
     * 되살릴 수 있다(단일 사용 불변식 위반). 이 잠금으로 accept() 의 커밋을 직렬화해, 재발송은 잠금 해제 후
     * 이미 전이된 상태를 관측하고 PENDING 재검사에서 거절된다. resend() 전용(트랜잭션 필수).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from OrgInvitation i where i.id = :id and i.orgId = :orgId")
    fun findByIdAndOrgIdForUpdate(
        @Param("id") id: UUID,
        @Param("orgId") orgId: UUID,
    ): OrgInvitation?

    /** 남용 방지: org·시간당 발송 상한 판정용 카운트. */
    fun countByOrgIdAndCreatedAtAfter(
        orgId: UUID,
        createdAt: Instant,
    ): Long

    /**
     * 대시보드: 아직 만료되지 않은 PENDING 초대 수. lazy 만료 전이(expireOverdue) 이전이라도
     * expires_at 판정으로 이미 만료된 초대는 세지 않는다(읽기 경로에서 상태 변이 없이 정확 카운트).
     */
    fun countByOrgIdAndStatusAndExpiresAtAfter(
        orgId: UUID,
        status: String,
        expiresAt: Instant,
    ): Long

    /**
     * lazy/정리 잡: 만료 시각이 지난 PENDING 을 EXPIRED 로 일괄 전이한다. 반환은 전이된 행 수.
     * 만료/재사용 거부의 일부 — 만료된 초대는 목록에서 사라지고 수락도 거부된다.
     */
    @Modifying(clearAutomatically = true)
    @Query(
        "update OrgInvitation i set i.status = 'EXPIRED' " +
            "where i.status = 'PENDING' and i.expiresAt < :now",
    )
    fun expireOverdue(
        @Param("now") now: Instant,
    ): Int
}
