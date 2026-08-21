package com.taspa.server.org

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.org.EmploymentStatus
import com.taspa.server.domain.org.EmploymentType
import com.taspa.server.domain.org.MembershipChangeType
import com.taspa.server.domain.org.MembershipStatus
import com.taspa.server.domain.org.OrgMembership
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrgRole
import com.taspa.server.domain.org.OrgStatus
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserStatus
import com.taspa.server.org.dto.AdministeredOrgView
import com.taspa.server.org.dto.MemberAttributesRequest
import com.taspa.server.org.dto.MembershipHistoryView
import com.taspa.server.org.dto.MembershipRequest
import com.taspa.server.org.dto.MembershipView
import com.taspa.server.org.dto.MyMembershipView
import com.taspa.server.org.dto.OrgCreateRequest
import com.taspa.server.org.dto.OrgUpdateRequest
import com.taspa.server.org.dto.OrgView
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * 조직 테넌시(Phase 0-A) — 조직 CRUD + 멤버십 upsert/조회/역할변경/제거.
 *
 * slug 정규화: 소문자·[a-z0-9-], 공백/특수문자는 하이픈으로, 앞뒤 하이픈 제거. 유니크 강제.
 * 자기보호(최소 구현): 마지막 ORG_ADMIN 을 강등/제거하지 못하게 막는다(조직 잠금 방지).
 */
@Service
class OrganizationService(
    private val organizationRepository: OrganizationRepository,
    private val membershipRepository: OrgMembershipRepository,
    private val userRepository: UserRepository,
    private val departmentRepository: com.taspa.server.domain.org.DepartmentRepository,
    private val siteRepository: com.taspa.server.domain.org.SiteRepository,
    private val membershipHistoryService: MembershipHistoryService,
) {
    // ---- 조직 CRUD ----

    @Transactional(readOnly = true)
    fun list(): List<OrgView> =
        organizationRepository
            .findAllByOrderByCreatedAtDesc()
            .map { OrgView.from(it, membershipRepository.countByOrgId(it.id!!)) }

    @Transactional(readOnly = true)
    fun get(id: UUID): OrgView = findOrg(id).let { OrgView.from(it, membershipRepository.countByOrgId(it.id!!)) }

    @Transactional
    fun create(request: OrgCreateRequest): OrgView {
        val name = normalizeName(request.name)
        val slug = normalizeSlug(request.slug?.takeIf { it.isNotBlank() } ?: name)
        if (organizationRepository.existsBySlug(slug)) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "이미 존재하는 slug 입니다: $slug")
        }
        val timezone = normalizeTimezone(request.timezone) ?: "UTC"
        val saved = organizationRepository.save(Organization(slug = slug, name = name, timezone = timezone))
        return OrgView.from(saved)
    }

    @Transactional
    fun update(
        id: UUID,
        request: OrgUpdateRequest,
    ): OrgView {
        val org = findOrg(id)
        request.name?.let { org.name = normalizeName(it) }
        request.status?.let { org.status = parseOrgStatus(it) }
        normalizeTimezone(request.timezone)?.let { org.timezone = it }
        val saved = organizationRepository.save(org)
        return OrgView.from(saved, membershipRepository.countByOrgId(saved.id!!))
    }

    /**
     * 조직 프로필 자율 편집(ORG_ADMIN 콘솔) — name·timezone 만 갱신한다. status·slug 는 **절대 건드리지 않는다**
     * (정지 해제·slug 탈취는 플랫폼 관리자 전용 — update() 유지). SUSPENDED 조직은 편집을 거부한다(정지 실효성).
     * name 은 create/update 와 동일하게 normalizeName(trim·필수·200자 상한) 검증을 통과할 때만 반영하고
     * (빈 이름·과길이는 400 VALIDATION_ERROR), timezone 은 normalizeTimezone 검증을 통과한다.
     */
    @Transactional
    fun updateProfile(
        orgId: UUID,
        name: String?,
        timezone: String?,
    ): OrgView {
        val org = findOrg(orgId)
        if (org.statusEnum() != OrgStatus.ACTIVE) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "정지된 조직은 편집할 수 없습니다")
        }
        name?.let { org.name = normalizeName(it) }
        normalizeTimezone(timezone)?.let { org.timezone = it }
        val saved = organizationRepository.save(org)
        return OrgView.from(saved, membershipRepository.countByOrgId(saved.id!!))
    }

    // ---- 멤버십 ----

    @Transactional(readOnly = true)
    fun listMembers(orgId: UUID): List<MembershipView> = listMembers(orgId, null)

    /**
     * 조직 구성원 목록. [departmentIds] 를 주면 그 부서들(호출부가 서브트리로 펼친 집합)에 배정된
     * 멤버만 남긴다 — 부서 위임자가 자기 인원을 보는 경로다.
     *
     * ★필터를 서비스에 두는 이유: 인가가 통과한 뒤 화면에서 거르면 **응답에는 이미 전사 명단이 실려 있다.**
     * 위임의 요점은 "다른 본부 사람을 못 보는 것"이므로 걸러진 다음에 나가야 한다.
     */
    @Transactional(readOnly = true)
    fun listMembers(
        orgId: UUID,
        departmentIds: Set<UUID>?,
    ): List<MembershipView> {
        findOrg(orgId) // 조직 존재 검증
        val memberships =
            membershipRepository
                .findByOrgId(orgId)
                .let { all -> if (departmentIds == null) all else all.filter { it.departmentId in departmentIds } }
        val emails =
            userRepository
                .findAllById(memberships.map { it.userId })
                .associate { it.id to it.email }
        return memberships.map { MembershipView.from(it, emails[it.userId]) }
    }

    /**
     * 로그인 사용자가 ORG_ADMIN 으로 관리하는 조직 목록(자율 콘솔). "활성 멤버십 + 활성 org" 만 남긴다
     * (TokenCustomizerConfig.addOrgClaims 와 동일한 정지 제어 필터). 각 항목에 현재 멤버 수를 채운다.
     */
    @Transactional(readOnly = true)
    fun listAdministeredOrgs(userId: UUID): List<AdministeredOrgView> {
        val adminMemberships =
            membershipRepository
                .findByUserId(userId)
                .filter { it.statusEnum() == MembershipStatus.ACTIVE && it.roleEnum() == OrgRole.ORG_ADMIN }
        if (adminMemberships.isEmpty()) return emptyList()
        val activeOrgs =
            organizationRepository
                .findAllById(adminMemberships.map { it.orgId })
                .filter { it.statusEnum() == OrgStatus.ACTIVE }
                .associateBy { it.id }
        return adminMemberships
            .mapNotNull { m ->
                val org = activeOrgs[m.orgId] ?: return@mapNotNull null
                AdministeredOrgView(
                    id = org.id!!,
                    name = org.name,
                    slug = org.slug,
                    status = org.status,
                    timezone = org.timezone,
                    role = m.role,
                    memberCount = membershipRepository.countByOrgId(org.id!!),
                    createdAt = org.createdAt,
                )
            }.sortedBy { it.name.lowercase() }
    }

    /**
     * 로그인 사용자가 소속된 조직 목록(계정 페이지 "내 조직", 읽기 전용). "활성 멤버십 + 활성 org" 만 남긴다
     * (listAdministeredOrgs 와 동일한 정지 제어 필터). 역할은 무관 — MEMBER·ORG_ADMIN 모두 포함한다.
     */
    @Transactional(readOnly = true)
    fun listMyMemberships(userId: UUID): List<MyMembershipView> {
        val memberships =
            membershipRepository
                .findByUserId(userId)
                .filter { it.statusEnum() == MembershipStatus.ACTIVE }
        if (memberships.isEmpty()) return emptyList()
        val activeOrgs =
            organizationRepository
                .findAllById(memberships.map { it.orgId })
                .filter { it.statusEnum() == OrgStatus.ACTIVE }
                .associateBy { it.id }
        return memberships
            .mapNotNull { m ->
                val org = activeOrgs[m.orgId] ?: return@mapNotNull null
                MyMembershipView(
                    orgId = org.id!!,
                    orgName = org.name,
                    orgSlug = org.slug,
                    role = m.role,
                    department = m.department,
                    joinedAt = m.joinedAt,
                )
            }.sortedBy { it.orgName.lowercase() }
    }

    @Transactional
    fun upsertMember(
        orgId: UUID,
        request: MembershipRequest,
        actorId: UUID? = null,
    ): MembershipView {
        val org = findOrg(orgId)
        val user =
            userRepository.findById(request.userId).orElse(null)
                ?: throw AuthException(ErrorCode.USER_NOT_FOUND)
        val role = request.role?.let { parseRole(it) } ?: OrgRole.MEMBER
        val existing = membershipRepository.findByOrgIdAndUserId(orgId, request.userId)
        val isNew = existing == null
        // 기존 멤버십에서 역할이 실제로 바뀌는지(승격/강등) — save 로 값이 변경되기 전에 포착한다.
        val roleChanged = existing != null && existing.roleEnum() != role
        val membership =
            if (existing != null) {
                // 마지막 ORG_ADMIN 을 강등하는 upsert 는 막는다(자기보호).
                if (existing.roleEnum() == OrgRole.ORG_ADMIN && role != OrgRole.ORG_ADMIN) {
                    guardLastAdmin(orgId)
                }
                existing.role = role.name
                request.department?.let { existing.department = it.trim().ifEmpty { null } }
                // 구조 배정은 **덮어쓰지 않는다** — 이미 배정된 사람의 부서를 초대 수락이 조용히 옮기면
                // 그 사람의 식대 정책이 바뀐다. 미배정일 때만 채운다(초대가 나른 값의 용도가 그것이다).
                if (existing.departmentId == null) {
                    request.departmentId?.let { existing.departmentId = it }
                }
                existing
            } else {
                OrgMembership(
                    orgId = org.id!!,
                    userId = user.id!!,
                    role = role.name,
                    department = request.department?.trim()?.takeIf { it.isNotEmpty() },
                ).apply { departmentId = request.departmentId }
            }
        val saved = membershipRepository.save(membership)
        // 신규 생성은 JOINED, 기존 멤버십의 역할 변경(upsert 경로로도 도달 가능 — AdminOrgController.upsertMember·
        // OrgInvitationService.accept 승격)은 ROLE_CHANGED 이력을 남긴다. 이력은 시점별 역할 재구성용 정답데이터라
        // 어떤 API 경로로 오든 역할 변경은 누락 없이 append 돼야 한다. 역할이 그대로인 no-op 재upsert 는 미기록.
        when {
            isNew -> membershipHistoryService.record(saved, MembershipChangeType.JOINED, actorId)
            roleChanged -> membershipHistoryService.record(saved, MembershipChangeType.ROLE_CHANGED, actorId)
        }
        return MembershipView.from(saved, user.email)
    }

    @Transactional
    fun changeRole(
        orgId: UUID,
        userId: UUID,
        roleValue: String,
        actorId: UUID? = null,
    ): MembershipView {
        findOrg(orgId)
        val membership =
            membershipRepository.findByOrgIdAndUserId(orgId, userId)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "멤버십을 찾을 수 없습니다")
        val newRole = parseRole(roleValue)
        if (membership.roleEnum() == OrgRole.ORG_ADMIN && newRole != OrgRole.ORG_ADMIN) {
            guardLastAdmin(orgId)
        }
        membership.role = newRole.name
        val saved = membershipRepository.save(membership)
        membershipHistoryService.record(saved, MembershipChangeType.ROLE_CHANGED, actorId)
        val email = userRepository.findById(userId).orElse(null)?.email
        return MembershipView.from(saved, email)
    }

    /**
     * 멤버 구조적 배정(부서·사업장). full-replace — departmentId/siteId 가 null 이면 그 배정을 해제한다.
     * **핵심 격리**: departmentId·siteId 는 그 org 소속일 때만 허용한다(타 org 부서/사업장 배정 금지 —
     * 조직 간 구조 하이재킹 원천 차단). 대상은 그 org 의 멤버여야 한다(비멤버 → 404).
     */
    @Transactional
    fun assignMember(
        orgId: UUID,
        userId: UUID,
        departmentId: UUID?,
        siteId: UUID?,
        actorId: UUID? = null,
    ): MembershipView {
        findOrg(orgId)
        val membership =
            membershipRepository.findByOrgIdAndUserId(orgId, userId)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "멤버십을 찾을 수 없습니다")
        if (departmentId != null && departmentRepository.findByIdAndOrgId(departmentId, orgId) == null) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "부서를 찾을 수 없습니다")
        }
        if (siteId != null && siteRepository.findByIdAndOrgId(siteId, orgId) == null) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "사업장을 찾을 수 없습니다")
        }
        membership.departmentId = departmentId
        membership.siteId = siteId
        val saved = membershipRepository.save(membership)
        membershipHistoryService.record(saved, MembershipChangeType.ASSIGNED, actorId)
        val email = userRepository.findById(userId).orElse(null)?.email
        return MembershipView.from(saved, email)
    }

    /**
     * 임직원 HR 속성 갱신(사번·직함·고용형태·입사일·재직상태). 대상은 그 org 의 멤버여야 한다(비멤버 → 404;
     * changeRole/assignMember 와 동일하게 멤버십 status 는 강제하지 않는다 — SUSPENDED 멤버도 대상).
     * enum/날짜 검증 실패는 400(VALIDATION_ERROR). full-replace 시맨틱(nullable 필드 null → clear), 단
     * employmentStatus 는 NOT NULL 이라 null 이면 기존값 유지. 갱신 후 ATTRIBUTES_UPDATED 이력을 남긴다.
     */
    @Transactional
    fun updateAttributes(
        orgId: UUID,
        userId: UUID,
        request: MemberAttributesRequest,
        actorId: UUID? = null,
    ): MembershipView {
        findOrg(orgId)
        val membership =
            membershipRepository.findByOrgIdAndUserId(orgId, userId)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "멤버십을 찾을 수 없습니다")
        val employmentType =
            request.employmentType
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { parseEmploymentType(it) }
        val hireDate =
            request.hireDate
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { parseHireDate(it) }
        val employmentStatus =
            request.employmentStatus
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { parseEmploymentStatus(it) }

        membership.employeeId = request.employeeId?.trim()?.takeIf { it.isNotEmpty() }
        membership.jobTitle = request.jobTitle?.trim()?.takeIf { it.isNotEmpty() }
        membership.employmentType = employmentType?.name
        membership.hireDate = hireDate
        if (employmentStatus != null) membership.employmentStatus = employmentStatus.name

        val saved = membershipRepository.save(membership)
        membershipHistoryService.record(saved, MembershipChangeType.ATTRIBUTES_UPDATED, actorId)
        val email = userRepository.findById(userId).orElse(null)?.email
        return MembershipView.from(saved, email)
    }

    /**
     * 멤버십 활성/비활성 전환(SCIM 프로비저닝의 active 매핑 — 조직 3c). **users 테이블은 절대 건드리지 않는다**
     * (사용자가 다른 org 소속일 수 있음 — 멀티테넌시). 비활성(active=false)은 그 org 멤버십만
     * SUSPENDED + TERMINATED 로, 재활성은 ACTIVE + EMPLOYED 로 되돌린다. 상태가 이미 같으면 no-op(이력 미기록).
     * ORG_ADMIN 을 비활성화해 실효 관리자가 0명이 되는 것은 removeMember 와 동일하게 guardLastAdmin 이 막는다.
     * 이력은 ATTRIBUTES_UPDATED 스냅샷으로 남는다(employment_status 가 스냅샷에 포함돼 시점 재구성 가능).
     */
    @Transactional
    fun setMembershipActive(
        orgId: UUID,
        userId: UUID,
        active: Boolean,
        actorId: UUID? = null,
    ): OrgMembership {
        findOrg(orgId)
        val membership =
            membershipRepository.findByOrgIdAndUserId(orgId, userId)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "멤버십을 찾을 수 없습니다")
        val targetStatus = if (active) MembershipStatus.ACTIVE else MembershipStatus.SUSPENDED
        if (membership.statusEnum() == targetStatus) return membership
        if (!active && membership.roleEnum() == OrgRole.ORG_ADMIN) {
            guardLastAdmin(orgId)
        }
        membership.status = targetStatus.name
        membership.employmentStatus =
            if (active) EmploymentStatus.EMPLOYED.name else EmploymentStatus.TERMINATED.name
        val saved = membershipRepository.save(membership)
        membershipHistoryService.record(saved, MembershipChangeType.ATTRIBUTES_UPDATED, actorId)
        return saved
    }

    /** 멤버십 변경 이력(SCD) 최신순 조회. org 격리는 orgId 조건이 강제한다. */
    @Transactional(readOnly = true)
    fun listMembershipHistory(
        orgId: UUID,
        userId: UUID,
    ): List<MembershipHistoryView> {
        findOrg(orgId)
        return membershipHistoryService.listHistory(orgId, userId).map { MembershipHistoryView.from(it) }
    }

    @Transactional
    fun removeMember(
        orgId: UUID,
        userId: UUID,
        actorId: UUID? = null,
    ) {
        findOrg(orgId)
        val membership =
            membershipRepository.findByOrgIdAndUserId(orgId, userId)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "멤버십을 찾을 수 없습니다")
        if (membership.roleEnum() == OrgRole.ORG_ADMIN) {
            guardLastAdmin(orgId)
        }
        // 삭제 직전 최종 상태를 이력으로 남긴다(REMOVED) — 멤버십 행이 사라진 뒤에도 마지막 소속을 재구성 가능.
        membershipHistoryService.record(membership, MembershipChangeType.REMOVED, actorId)
        membershipRepository.delete(membership)
    }

    /**
     * JIT 멤버십 upsert(Phase 0-A) — 조직 IdP 로그인 성공 시 호출된다. 멤버십이 없으면 MEMBER 로 생성,
     * 이미 있으면 그대로 둔다(역할·부서 보존). 별도 트랜잭션 없이 호출자 컨텍스트에서 실행된다.
     * 반환: 새로 만들어졌으면 true.
     */
    @Transactional
    fun ensureJitMembership(
        orgId: UUID,
        userId: UUID,
    ): Boolean {
        if (membershipRepository.existsByOrgIdAndUserId(orgId, userId)) return false
        val saved =
            membershipRepository.save(
                OrgMembership(orgId = orgId, userId = userId, role = OrgRole.MEMBER.name),
            )
        // 실제 생성했을 때만 JOINED 이력을 남긴다(멱등 재로그인 no-op 엔 기록 금지 — 이력 폭증 방지).
        // 자가 합류라 recorded_by 는 대상 본인(userId).
        membershipHistoryService.record(saved, MembershipChangeType.JOINED, userId)
        return true
    }

    // ---- 인가 헬퍼 ----

    /**
     * 사용자가 해당 조직의 활성 멤버인가(org 격리 검사에 사용). 조직이 SUSPENDED 면 멤버십이 ACTIVE 여도
     * false 다 — 정지 조직의 접근을 차단한다(정지 제어에 실효성 부여).
     */
    @Transactional(readOnly = true)
    fun isActiveMember(
        orgId: UUID,
        userId: UUID,
    ): Boolean =
        isOrgActive(orgId) &&
            membershipRepository
                .findByOrgIdAndUserId(orgId, userId)
                ?.statusEnum() == MembershipStatus.ACTIVE

    /** 사용자가 해당 조직의 활성 ORG_ADMIN 인가(조직 관리 권한 검사에 사용). SUSPENDED 조직에서는 false. */
    @Transactional(readOnly = true)
    fun isOrgAdmin(
        orgId: UUID,
        userId: UUID,
    ): Boolean =
        isOrgActive(orgId) &&
            membershipRepository
                .findByOrgIdAndUserId(orgId, userId)
                ?.takeIf { it.statusEnum() == MembershipStatus.ACTIVE }
                ?.roleEnum() == OrgRole.ORG_ADMIN

    private fun isOrgActive(orgId: UUID): Boolean = organizationRepository.findById(orgId).orElse(null)?.statusEnum() == OrgStatus.ACTIVE

    /**
     * org 존재 + ACTIVE 검증 후 반환 — 구조(부서·사업장) 생성·변경의 전제 가드. 부재는 404, SUSPENDED 는 400
     * (정지 조직은 편집 불가 — updateProfile 과 동일 계약). 구조 서비스가 이 헬퍼로 org 격리 진입점을 수렴한다.
     */

    /**
     * org 존재만 검증(부재는 404) — 읽기 경로(부서 트리·사업장 목록 조회)용. ACTIVE 를 강제하지 않으므로
     * 정지된 조직의 구조도 플랫폼 관리자가 조회할 수 있다(requireActiveOrg 는 편집 전용 가드).
     */
    @Transactional(readOnly = true)
    fun requireOrg(orgId: UUID): Organization = findOrg(orgId)

    @Transactional(readOnly = true)
    fun requireActiveOrg(orgId: UUID): Organization {
        val org = findOrg(orgId)
        if (org.statusEnum() != OrgStatus.ACTIVE) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "정지된 조직은 편집할 수 없습니다")
        }
        return org
    }

    // ---- 내부 ----

    private fun findOrg(id: UUID): Organization =
        organizationRepository.findById(id).orElse(null)
            ?: throw AuthException(ErrorCode.NOT_FOUND, "조직을 찾을 수 없습니다")

    private fun guardLastAdmin(orgId: UUID) {
        // 동시 강등/제거로 실효 관리자가 0명이 되는 write-skew(TOCTOU)를 막는다: 이 org 의 ORG_ADMIN 멤버십
        // 행들을 먼저 PESSIMISTIC_WRITE 로 잠가 아래 count-check-write 를 직렬화한다(초대 accept 경로의 잠금
        // 패턴과 정합). 잠금 없이는 두 트랜잭션이 각각 count=2 를 관측해 서로 다른 관리자를 지울 수 있다.
        membershipRepository.lockByOrgIdAndRoleForUpdate(orgId, OrgRole.ORG_ADMIN.name)
        val effectiveAdmins =
            membershipRepository.countEffectiveAdmins(
                orgId,
                OrgRole.ORG_ADMIN.name,
                MembershipStatus.ACTIVE.name,
                UserStatus.ACTIVE.name,
            )
        if (effectiveAdmins <= 1) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "마지막 조직 관리자는 강등/제거할 수 없습니다")
        }
    }

    /**
     * 구조 엔티티(부서·사업장) 이름 정규화·검증 — trim 후 필수, VARCHAR(120) 상한. DepartmentService·SiteService
     * 가 이 헬퍼로 수렴해 조직명(normalizeName, 200자)과 동일한 400 계약을 준다(과길이 → Postgres 오류·오해성 409 방지).
     */
    fun normalizeStructureName(value: String): String {
        val name = value.trim()
        if (name.isEmpty()) throw AuthException(ErrorCode.VALIDATION_ERROR, "이름을 입력하세요")
        if (name.length > 120) throw AuthException(ErrorCode.VALIDATION_ERROR, "이름은 120자 이하여야 합니다")
        return name
    }

    /** 타임존 검증 공개 래퍼 — 사업장 등 org-스코프 엔티티가 재사용한다. null/공백이면 null(호출부에서 기본 UTC/미변경). */
    fun requireValidTimezone(value: String?): String? = normalizeTimezone(value)

    private fun parseRole(value: String): OrgRole =
        OrgRole.entries.firstOrNull { it.name == value.trim().uppercase() }
            ?: throw AuthException(ErrorCode.VALIDATION_ERROR, "role 은 MEMBER 또는 ORG_ADMIN 이어야 합니다")

    private fun parseOrgStatus(value: String): String =
        OrgStatus.entries.firstOrNull { it.name == value.trim().uppercase() }?.name
            ?: throw AuthException(ErrorCode.VALIDATION_ERROR, "status 는 ACTIVE 또는 SUSPENDED 여야 합니다")

    private fun parseEmploymentType(value: String): EmploymentType =
        EmploymentType.entries.firstOrNull { it.name == value.trim().uppercase() }
            ?: throw AuthException(ErrorCode.VALIDATION_ERROR, "employmentType 은 FULL_TIME/PART_TIME/CONTRACT/INTERN 중 하나여야 합니다")

    private fun parseEmploymentStatus(value: String): EmploymentStatus =
        EmploymentStatus.entries.firstOrNull { it.name == value.trim().uppercase() }
            ?: throw AuthException(ErrorCode.VALIDATION_ERROR, "employmentStatus 는 EMPLOYED/ON_LEAVE/TERMINATED 중 하나여야 합니다")

    private fun parseHireDate(value: String): LocalDate =
        try {
            LocalDate.parse(value.trim())
        } catch (ex: DateTimeParseException) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "hireDate 는 yyyy-MM-dd 형식이어야 합니다")
        }

    /**
     * 조직명 정규화·검증. trim 후 비어 있으면 400(조직명 필수), 200자(컬럼 상한 VARCHAR(200)) 초과면 400.
     * 앱 계층 상한이 없으면 과길이 이름이 Postgres 'value too long' → DataIntegrityViolationException →
     * 오해를 부르는 409(재시도 안내)로 나가므로, create/update/updateProfile 이 이 헬퍼로 수렴해 명확한
     * 400 을 준다.
     */
    private fun normalizeName(value: String): String {
        val name = value.trim()
        if (name.isEmpty()) throw AuthException(ErrorCode.VALIDATION_ERROR, "조직명을 입력하세요")
        if (name.length > 200) throw AuthException(ErrorCode.VALIDATION_ERROR, "조직명은 200자 이하여야 합니다")
        return name
    }

    /**
     * 타임존 정규화·검증. null/공백이면 null 반환(호출부에서 기본 UTC 또는 미변경). 유효한 IANA/Postgres 존만
     * 허용한다 — 잘못된 값이 저장돼 집계 쿼리(AT TIME ZONE)가 런타임에 실패하는 것을 막는다.
     */
    private fun normalizeTimezone(value: String?): String? {
        val tz = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalized =
            try {
                java.time.ZoneId
                    .of(tz)
                    .id
            } catch (ex: java.time.DateTimeException) {
                throw AuthException(ErrorCode.VALIDATION_ERROR, "유효하지 않은 타임존입니다: $tz")
            }
        // Postgres 수용집합 재확인 — ZoneId 는 통과하나 AT TIME ZONE 이 거부하는 값(집계 500)을 저장 전 차단한다.
        // UTC 는 항상 유효하므로 pg 조회를 생략한다.
        if (normalized != "UTC" && !organizationRepository.existsPgTimezone(normalized)) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "유효하지 않은 시간대입니다")
        }
        return normalized
    }

    /**
     * slug 정규화. 소문자화 → 영숫자 외 문자를 하이픈으로 → 연속 하이픈 축약 → 앞뒤 하이픈 제거.
     * 결과가 비면(순수 비ASCII 이름 등) 실패시키지 않고 임의 접미사를 붙여 유효 slug 를 보장한다.
     */
    private fun normalizeSlug(raw: String): String {
        val slug =
            raw
                .trim()
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(64)
        return slug.ifEmpty { "org-" + UUID.randomUUID().toString().take(8) }
    }
}
