package com.taspa.server.domain.meal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** 정책이 적용되는 범위. 이 단계는 ORG 만 기록하지만, 컬럼과 열거는 처음부터 넓게 둔다. */
enum class PolicyScope {
    ORG,
    DEPARTMENT,
    SITE,
}

enum class PolicyChangeType {
    CREATED,
    UPDATED,
    REMOVED,
}

/**
 * 식대 정책 변경 이력 한 줄(append-only). 수정도 삭제도 하지 않는다 — 값이 바뀌면 새 행이다.
 *
 * `document` 는 변경 **후** 전체 스냅샷 JSON 이다. 델타가 아니라 전체를 남기는 이유는 재현 때문이다:
 * 델타는 처음부터 순서대로 다 재생해야 어느 시점의 값을 알 수 있고, 중간 한 줄이 유실되면 그 뒤가
 * 전부 틀려진다. 스냅샷이면 "그 시점 이하의 마지막 한 줄"만 읽으면 된다.
 *
 * 필드가 전부 `val` 인 것도 의도다 — append-only 를 타입으로 못 박는다.
 */
@Entity
@Table(name = "meal_policy_revisions")
class MealPolicyRevision(
    @Id
    @GeneratedValue
    val id: UUID? = null,
    @Column(name = "org_id", nullable = false)
    val orgId: UUID,
    @Column(name = "scope_type", nullable = false, length = 16)
    val scopeType: String = PolicyScope.ORG.name,
    @Column(name = "scope_id")
    val scopeId: UUID? = null,
    /** 부서/사업장 이름 스냅샷 — 그 노드가 삭제된 뒤에도 이력이 무엇을 가리켰는지 읽히게 한다. */
    @Column(name = "scope_label", length = 120)
    val scopeLabel: String? = null,
    @Column(name = "change_type", nullable = false, length = 24)
    val changeType: String,
    @Column(name = "document", nullable = false)
    val document: String,
    /**
     * false = 플랫폼 관리자(비멤버)가 바꿨다는 뜻.
     *
     * 조직이 "우리는 안 바꿨는데 한도가 달라졌다"를 사후에 가려낼 수 있어야 한다. 감사 로그의 행위자
     * 이메일만으로는 그 사람이 우리 조직 사람인지 플랫폼 운영자인지 조직 쪽에서 구분할 수 없다.
     */
    @Column(name = "actor_is_org_member", nullable = false)
    val actorIsOrgMember: Boolean,
    @Column(name = "recorded_at", nullable = false)
    val recordedAt: Instant = Instant.now(),
    @Column(name = "recorded_by")
    val recordedBy: UUID? = null,
)
