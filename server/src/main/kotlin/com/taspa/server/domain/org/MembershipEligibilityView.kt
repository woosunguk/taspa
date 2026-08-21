package com.taspa.server.domain.org

import java.util.UUID

/**
 * 식권 자격 판정에 필요한 멤버십 사실만 담는 **인터페이스 프로젝션**.
 *
 * ★엔티티가 아니라 프로젝션인 것이 핵심이다. 이 값은 `FOR UPDATE` 로 행을 잠근 **직후** 읽는데,
 * 같은 트랜잭션에서 그 행을 이미 엔티티로 적재했다면 Hibernate 1차 캐시가 잠금 결과로 필드를 다시
 * 채우지 않아 **옛 스냅샷**으로 판정하게 된다(`@Version` 이 없어 감지도 안 된다). 프로젝션은 캐시를
 * 타지 않으므로 항상 DB 를 재조회한다 — 초대 수락에서 실제로 터졌던 결함의 해법과 같은 패턴이다
 * (`OrgInvitationRepository.findAcceptGateByTokenHash` 참조).
 *
 * 자격(재직상태)과 정책 해석 입력(부서·사업장)을 **한 질의로** 함께 가져온다 — 잠금을 쥔 구간에서
 * 왕복을 늘리지 않기 위해서다.
 */
interface MembershipEligibilityView {
    fun getDepartmentId(): UUID?

    fun getSiteId(): UUID?

    fun getStatus(): String

    fun getEmploymentStatus(): String
}
