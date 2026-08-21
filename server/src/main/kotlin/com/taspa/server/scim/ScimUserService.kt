package com.taspa.server.scim

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.org.MembershipStatus
import com.taspa.server.domain.org.OrgMembership
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.org.DepartmentBinder
import com.taspa.server.org.OrganizationService
import com.taspa.server.org.dto.MemberAttributesRequest
import com.taspa.server.org.dto.MembershipRequest
import com.taspa.server.scim.dto.CORE_USER_SCHEMA
import com.taspa.server.scim.dto.ENTERPRISE_USER_SCHEMA
import com.taspa.server.scim.dto.ScimEmail
import com.taspa.server.scim.dto.ScimEnterpriseUser
import com.taspa.server.scim.dto.ScimListResponse
import com.taspa.server.scim.dto.ScimMeta
import com.taspa.server.scim.dto.ScimPatchRequest
import com.taspa.server.scim.dto.ScimUserRequest
import com.taspa.server.scim.dto.ScimUserResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * SCIM 2.0 Users 프로비저닝(조직 3c) — 효과는 전부 **토큰 org 스코프의 멤버십**에 한정된다.
 *
 * 불변식(가장 중요):
 *  - **users 테이블 비파괴**: active=false/DELETE 는 users 행을 절대 건드리지 않는다(사용자가 다른 org
 *    소속일 수 있음 — 멀티테넌시). 비활성 = 그 org 멤버십 SUSPENDED+TERMINATED, DELETE = 그 org 멤버십 제거.
 *  - **계정 생성은 소셜 전용 패턴**: password_hash NULL(폼 로그인은 LoginUserDetailsService 더미 해시로
 *    항상 실패), email_verified=false(첫 로그인 시 기존 이메일 인증 게이트가 소유를 검증). 메일 발송 없음.
 *  - **SCD 정합**: 멤버십 생성/변경/제거는 OrganizationService(upsertMember·updateAttributes·
 *    setMembershipActive·removeMember) 경유 — JOINED/ATTRIBUTES_UPDATED/REMOVED 이력이 자동 기록된다.
 *    scim_external_id 만 직접 저장한다(이력 스냅샷 비대상 메타데이터 — SCD 우회 아님).
 */
@Service
class ScimUserService(
    private val userRepository: UserRepository,
    private val membershipRepository: OrgMembershipRepository,
    private val organizationService: OrganizationService,
    private val departmentBinder: DepartmentBinder,
    private val auditEventService: AuditEventService,
    @Value("\${taspa.issuer-uri}") private val issuerUri: String,
) {
    // ---- 조회 ----

    @Transactional(readOnly = true)
    fun list(
        ctx: ScimClientContext,
        filter: String?,
        startIndex: Int?,
        count: Int?,
    ): ScimListResponse {
        val effectiveStart = (startIndex ?: 1).coerceAtLeast(1)
        val effectiveCount = (count ?: DEFAULT_PAGE_SIZE).coerceIn(0, MAX_PAGE_SIZE)
        val raw = filter?.trim()?.takeIf { it.isNotEmpty() }
        // 무필터 목록은 DB 페이지네이션(offset/limit + count 쿼리)으로만 읽는다 — org 전체 멤버십을
        // 매 페이지 요청마다 메모리에 적재하면 대형 테넌트 전체 동기화가 O(N×페이지수)가 된다(자원고갈).
        val (total, memberships) =
            if (raw == null) {
                val totalCount = membershipRepository.countByOrgId(ctx.orgId).toInt()
                val page =
                    if (effectiveCount == 0) {
                        emptyList()
                    } else {
                        membershipRepository.findByOrgId(
                            ctx.orgId,
                            OffsetPageable((effectiveStart - 1).toLong(), effectiveCount, Sort.by("joinedAt")),
                        )
                    }
                totalCount to page
            } else {
                // 지원 필터(userName/externalId eq)는 결과가 항상 0~1건 — 인메모리 슬라이스로 충분하다.
                val matches = resolveFilter(ctx.orgId, raw)
                matches.size to matches.drop(effectiveStart - 1).take(effectiveCount)
            }
        val users = userRepository.findAllById(memberships.map { it.userId }).associateBy { it.id }
        val page = memberships.mapNotNull { m -> users[m.userId]?.let { u -> toResponse(m, u) } }
        return ScimListResponse(
            totalResults = total,
            startIndex = effectiveStart,
            itemsPerPage = page.size,
            resources = page,
        )
    }

    @Transactional(readOnly = true)
    fun get(
        ctx: ScimClientContext,
        id: String,
    ): ScimUserResponse {
        val (membership, user) = findProvisioned(ctx.orgId, id)
        return toResponse(membership, user)
    }

    // ---- 생성 ----

    @Transactional
    fun create(
        ctx: ScimClientContext,
        request: ScimUserRequest,
    ): ScimUserResponse {
        val email = normalizeEmail(request.userName)
        val user =
            userRepository.findByEmail(email) ?: userRepository.save(
                // 소셜 전용 패턴: 비밀번호 없음(폼 로그인 불가), 이메일 미검증(첫 로그인 게이트가 검증), 무통지.
                User(
                    email = email,
                    passwordHash = null,
                    emailVerified = false,
                    displayName = request.resolvedDisplayName(),
                ),
            )
        if (membershipRepository.existsByOrgIdAndUserId(ctx.orgId, user.id!!)) {
            throw ScimException(HttpStatus.CONFLICT, "uniqueness", "User is already provisioned in this organization")
        }
        // JOINED 이력 자동 기록. 신규 멤버는 MEMBER 역할 고정(SCIM 으로 ORG_ADMIN 부여 불가 — 권한은 콘솔 전용).
        organizationService.upsertMember(
            ctx.orgId,
            MembershipRequest(
                userId = user.id!!,
                role = null,
                department = request.enterprise?.department,
                // HR 이 보낸 부서 이름이 조직도의 부서와 **정확히 하나** 일치하면 구조 배정까지 잇는다.
                // 그래야 SCIM 으로 입사한 사람이 그 부서의 식대 정책을 받는다(라벨만으로는 안 된다).
                departmentId = departmentBinder.resolve(ctx.orgId, null, request.enterprise?.department),
            ),
        )
        applyEnterpriseAttributes(ctx.orgId, user.id!!, request.enterprise)
        request.externalId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { setExternalId(ctx.orgId, user.id!!, it) }
        if (request.active == false) {
            organizationService.setMembershipActive(ctx.orgId, user.id!!, false)
        }
        recordAudit("SCIM_USER_CREATED", ctx, user.id!!)
        val membership = membershipRepository.findByOrgIdAndUserId(ctx.orgId, user.id!!)!!
        return toResponse(membership, user)
    }

    // ---- 전체 교체(PUT) ----

    @Transactional
    fun replace(
        ctx: ScimClientContext,
        id: String,
        request: ScimUserRequest,
    ): ScimUserResponse {
        val (membership, user) = findProvisioned(ctx.orgId, id)
        // userName(이메일)은 전역 크로스-org 식별자 — SCIM 으로 불변. 조용히 무시하면 IdP-SP 식별자
        // 드리프트(이후 filter 미조회 → 중복 계정 분기)가 무통보로 진행되므로 명시적으로 거부한다.
        request.userName?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (!it.equals(user.email, ignoreCase = true)) {
                throw ScimException(HttpStatus.BAD_REQUEST, "mutability", "userName is immutable via SCIM")
            }
        }
        // displayName 은 users 전역 속성 — org 스코프 밖이라 갱신하지 않는다(신규 생성 시에만 설정).
        // 수신 값은 무시하고 응답에는 현재 전역 값을 에코한다(SCIM 클라이언트 호환).
        request.externalId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { setExternalId(ctx.orgId, user.id!!, it) }
        val departmentChanged = applyDepartment(membership, request.enterprise?.department)
        applyEnterpriseAttributes(ctx.orgId, user.id!!, request.enterprise, departmentChanged)
        val deactivated = applyActive(ctx.orgId, user.id!!, membership, request.active)
        recordAudit(if (deactivated) "SCIM_USER_DEACTIVATED" else "SCIM_USER_UPDATED", ctx, user.id!!)
        return toResponse(membershipRepository.findByOrgIdAndUserId(ctx.orgId, user.id!!)!!, user)
    }

    // ---- 부분 갱신(PATCH, RFC 7644 최소 — Azure AD 호환 핵심은 active 토글) ----

    @Transactional
    fun patch(
        ctx: ScimClientContext,
        id: String,
        patch: ScimPatchRequest,
    ): ScimUserResponse {
        val (membership, user) = findProvisioned(ctx.orgId, id)
        if (patch.operations.isEmpty()) {
            throw ScimException(HttpStatus.BAD_REQUEST, "invalidValue", "Operations must not be empty")
        }
        var requestedActive: Boolean? = null
        var enterprise = ScimEnterpriseUser()
        var hasEnterprise = false
        patch.operations.forEach { op ->
            val opName = op.op?.trim()?.lowercase()
            if (opName != "add" && opName != "replace" && opName != "remove") {
                throw ScimException(HttpStatus.BAD_REQUEST, "invalidValue", "Unsupported patch op: ${op.op}")
            }
            // remove(RFC 7644 유효 op — Azure 가 속성 클리어 시 전송)는 무해하게 무시한다. 400 으로 거부하면
            // 같은 PatchOp 에 묶인 active 토글까지 통째로 실패해 오프보딩이 막힌다(quarantine).
            if (opName == "remove") return@forEach
            when (val path = op.path?.trim()) {
                null, "" -> {
                    val value =
                        op.value as? Map<*, *>
                            ?: throw ScimException(
                                HttpStatus.BAD_REQUEST,
                                "invalidValue",
                                "Patch value must be an object when path is absent",
                            )
                    value["active"]?.let { requestedActive = parseBoolean(it) }
                    // displayName 은 users 전역 속성 — org 스코프 밖이라 갱신하지 않는다(무시 후 현재값 에코).
                    (value[ENTERPRISE_USER_SCHEMA] as? Map<*, *>)?.let { ext ->
                        enterprise = mergeEnterprise(enterprise, "employeeNumber", ext["employeeNumber"])
                        enterprise = mergeEnterprise(enterprise, "title", ext["title"])
                        enterprise = mergeEnterprise(enterprise, "department", ext["department"])
                        hasEnterprise = true
                    }
                    // Azure 가 평탄화 키("urn:...:User:attr")로 보내는 변형도 수용한다.
                    value.forEach { (k, v) ->
                        val key = k?.toString() ?: return@forEach
                        if (key.startsWith("$ENTERPRISE_USER_SCHEMA:")) {
                            enterprise = mergeEnterprise(enterprise, key.removePrefix("$ENTERPRISE_USER_SCHEMA:"), v)
                            hasEnterprise = true
                        }
                    }
                }
                else ->
                    when {
                        path.equals("active", ignoreCase = true) -> requestedActive = parseBoolean(op.value)
                        path.startsWith("$ENTERPRISE_USER_SCHEMA:") -> {
                            enterprise = mergeEnterprise(enterprise, path.removePrefix("$ENTERPRISE_USER_SCHEMA:"), op.value)
                            hasEnterprise = true
                        }
                        // 그 외 경로(displayName·name.* 등 Azure 기본 매핑 포함)는 무해하게 무시한다 —
                        // POST/PUT 의 미지원 속성 무시 정책(@JsonIgnoreProperties)·path 부재 형식과 동일 계약.
                        // 400 을 던지면 같은 요청에 묶인 active 토글까지 실패한다.
                        else -> Unit
                    }
            }
        }
        if (hasEnterprise) {
            val departmentChanged = applyDepartment(membership, enterprise.department)
            applyEnterpriseAttributes(ctx.orgId, user.id!!, enterprise, departmentChanged)
        }
        val deactivated = applyActive(ctx.orgId, user.id!!, membership, requestedActive)
        recordAudit(if (deactivated) "SCIM_USER_DEACTIVATED" else "SCIM_USER_UPDATED", ctx, user.id!!)
        return toResponse(membershipRepository.findByOrgIdAndUserId(ctx.orgId, user.id!!)!!, user)
    }

    // ---- 제거 ----

    /** 계정 삭제가 아니다 — 그 org 멤버십만 제거한다(removeMember 경유 → REMOVED 이력). users 행 잔존. */
    @Transactional
    fun delete(
        ctx: ScimClientContext,
        id: String,
    ) {
        val (_, user) = findProvisioned(ctx.orgId, id)
        translateLastAdminConflict { organizationService.removeMember(ctx.orgId, user.id!!) }
        recordAudit("SCIM_USER_DELETED", ctx, user.id!!)
    }

    // ---- 내부 ----

    private fun resolveFilter(
        orgId: UUID,
        raw: String,
    ): List<OrgMembership> {
        val match =
            FILTER_PATTERN.matchEntire(raw)
                ?: throw ScimException(
                    HttpStatus.BAD_REQUEST,
                    "invalidFilter",
                    "Only 'userName eq \"x\"' and 'externalId eq \"x\"' filters are supported",
                )
        val (attribute, value) = match.destructured
        return when (attribute.lowercase()) {
            "username" -> {
                val user = userRepository.findByEmail(value)
                listOfNotNull(user?.id?.let { membershipRepository.findByOrgIdAndUserId(orgId, it) })
            }
            "externalid" -> listOfNotNull(membershipRepository.findByOrgIdAndScimExternalId(orgId, value))
            else -> throw ScimException(HttpStatus.BAD_REQUEST, "invalidFilter", "Unsupported filter attribute: $attribute")
        }
    }

    /** id(users.id UUID) → 그 org 에 프로비저닝된 (멤버십, 사용자). org 밖 사용자·비UUID 는 동일하게 404. */
    private fun findProvisioned(
        orgId: UUID,
        id: String,
    ): Pair<OrgMembership, User> {
        val userId =
            runCatching { UUID.fromString(id) }.getOrNull()
                ?: throw ScimException(HttpStatus.NOT_FOUND, null, "User not found")
        val membership =
            membershipRepository.findByOrgIdAndUserId(orgId, userId)
                ?: throw ScimException(HttpStatus.NOT_FOUND, null, "User not found")
        val user =
            userRepository.findById(userId).orElse(null)
                ?: throw ScimException(HttpStatus.NOT_FOUND, null, "User not found")
        return membership to user
    }

    /**
     * department(자유 라벨) 반영 — 실제 값이 바뀌었을 때만 true. 미전달(null)은 보존.
     * 반환값이 true 면 호출부가 applyEnterpriseAttributes 로 ATTRIBUTES_UPDATED 이력 기록을 강제한다
     * (department 단독 변경이 dirty-checking 만으로 커밋돼 이력 이벤트가 누락되는 것을 막는다).
     */
    private fun applyDepartment(
        membership: OrgMembership,
        department: String?,
    ): Boolean {
        val value = department?.trim() ?: return false
        val newValue = value.ifEmpty { null }
        if (membership.department == newValue) return false
        membership.department = newValue
        // 라벨이 바뀌면 구조 배정도 따라간다 — 이름이 조직도의 부서와 정확히 하나 일치할 때만.
        // ★모호하거나 없는 이름이면 **기존 배정을 지운다.** HR 이 "개발팀 → 영업팀"으로 옮겼는데 구조는
        //   개발팀에 남으면, 화면은 영업팀인데 식대는 개발팀 예산에서 나간다 — 그 어긋남이 조용히 지속되는
        //   것보다 미배정(조직 기본값)이 낫다. 조직관리자는 배정 화면에서 정확히 이을 수 있다.
        membership.departmentId = departmentBinder.resolve(membership.orgId, null, newValue)
        return true
    }

    /** HR 속성(사번·직함) 반영 — updateAttributes 경유(ATTRIBUTES_UPDATED 이력). 변경이 없으면 no-op. */
    private fun applyEnterpriseAttributes(
        orgId: UUID,
        userId: UUID,
        enterprise: ScimEnterpriseUser?,
        departmentChanged: Boolean = false,
    ) {
        val employeeNumber = enterprise?.employeeNumber?.trim()?.takeIf { it.isNotEmpty() }
        val title = enterprise?.title?.trim()?.takeIf { it.isNotEmpty() }
        if (employeeNumber == null && title == null && !departmentChanged) return
        val current = membershipRepository.findByOrgIdAndUserId(orgId, userId)
        organizationService.updateAttributes(
            orgId,
            userId,
            // updateAttributes 는 full-replace 라 미전달 속성이 지워지지 않게 현재값을 승계한다.
            MemberAttributesRequest(
                employeeId = employeeNumber ?: current?.employeeId,
                jobTitle = title ?: current?.jobTitle,
                employmentType = current?.employmentType,
                hireDate = current?.hireDate?.toString(),
                employmentStatus = null,
            ),
        )
    }

    /** active 반영 — 실제 전환이 "비활성화"였을 때만 true 반환(audit 유형 분기용). */
    private fun applyActive(
        orgId: UUID,
        userId: UUID,
        membership: OrgMembership,
        active: Boolean?,
    ): Boolean {
        if (active == null) return false
        val wasActive = membership.statusEnum() == MembershipStatus.ACTIVE
        if (wasActive == active) return false
        translateLastAdminConflict { organizationService.setMembershipActive(orgId, userId, active) }
        return !active
    }

    /**
     * 마지막 ORG_ADMIN 가드(guardLastAdmin, VALIDATION_ERROR)를 SCIM 표면에서는 409 로 번역한다 —
     * 400 은 프로비저너(Azure AD)가 요청 자체의 결함(영구 실패/quarantine)으로 해석하므로 상태 충돌인 409 가 맞다.
     * setMembershipActive/removeMember 의 VALIDATION_ERROR 발생원은 guardLastAdmin 뿐이다.
     */
    private inline fun <T> translateLastAdminConflict(block: () -> T): T =
        try {
            block()
        } catch (ex: AuthException) {
            if (ex.errorCode == ErrorCode.VALIDATION_ERROR) {
                throw ScimException(HttpStatus.CONFLICT, "mutability", ex.message)
            }
            throw ex
        }

    private fun setExternalId(
        orgId: UUID,
        userId: UUID,
        externalId: String,
    ) {
        val membership = membershipRepository.findByOrgIdAndUserId(orgId, userId) ?: return
        if (membership.scimExternalId == externalId) return
        // org 범위 유니크(V23) 사전 검사 — 경합 시 UNIQUE 위반은 어차피 커밋에서 거부된다(fail-closed).
        membershipRepository.findByOrgIdAndScimExternalId(orgId, externalId)?.let {
            if (it.userId != userId) {
                throw ScimException(HttpStatus.CONFLICT, "uniqueness", "externalId is already assigned in this organization")
            }
        }
        membership.scimExternalId = externalId
        membershipRepository.save(membership)
    }

    private fun mergeEnterprise(
        base: ScimEnterpriseUser,
        attribute: String,
        value: Any?,
    ): ScimEnterpriseUser {
        val text = value?.toString() ?: return base
        return when (attribute.trim().lowercase()) {
            "employeenumber" -> base.copy(employeeNumber = text)
            "title" -> base.copy(title = text)
            "department" -> base.copy(department = text)
            // manager 등 미지원 enterprise 속성은 무해하게 무시한다(중첩 객체 형식·POST/PUT 정책과 동일 —
            // Azure 기본 매핑에 포함된 속성을 400 으로 거부하면 그 테넌트의 모든 갱신이 실패한다).
            else -> base
        }
    }

    private fun parseBoolean(value: Any?): Boolean =
        when {
            value is Boolean -> value
            // Azure AD 프로비저너는 active 를 "True"/"False" 문자열로 보내는 것으로 악명 높다 — 수용한다.
            value is String && value.equals("true", ignoreCase = true) -> true
            value is String && value.equals("false", ignoreCase = true) -> false
            else -> throw ScimException(HttpStatus.BAD_REQUEST, "invalidValue", "active must be a boolean")
        }

    private fun normalizeEmail(userName: String?): String {
        val email =
            userName?.trim()?.lowercase()
                ?: throw ScimException(HttpStatus.BAD_REQUEST, "invalidValue", "userName is required")
        if (email.isEmpty() || !email.contains("@") || email.length > User.MAX_EMAIL_LENGTH) {
            throw ScimException(HttpStatus.BAD_REQUEST, "invalidValue", "userName must be a valid email address")
        }
        return email
    }

    /** audit — orgId 결속 + 행위 클라이언트 + 대상 userId 만(PII 최소 — 이메일·속성값 미기록). M2M 라 userId(행위자)=null. */
    private fun recordAudit(
        type: String,
        ctx: ScimClientContext,
        targetUserId: UUID,
    ) {
        auditEventService.record(
            type = type,
            userId = null,
            orgId = ctx.orgId,
            detail = mapOf("clientId" to ctx.clientId, "targetUserId" to targetUserId.toString()),
        )
    }

    private fun toResponse(
        membership: OrgMembership,
        user: User,
    ): ScimUserResponse {
        val enterprise =
            if (membership.employeeId != null || membership.jobTitle != null || membership.department != null) {
                ScimEnterpriseUser(
                    employeeNumber = membership.employeeId,
                    title = membership.jobTitle,
                    department = membership.department,
                )
            } else {
                null
            }
        val schemas = if (enterprise != null) listOf(CORE_USER_SCHEMA, ENTERPRISE_USER_SCHEMA) else listOf(CORE_USER_SCHEMA)
        return ScimUserResponse(
            schemas = schemas,
            id = user.id.toString(),
            externalId = membership.scimExternalId,
            userName = user.email,
            displayName = user.displayName,
            active = membership.statusEnum() == MembershipStatus.ACTIVE,
            emails = listOf(ScimEmail(value = user.email, primary = true)),
            enterprise = enterprise,
            meta = ScimMeta(resourceType = "User", location = "$issuerUri/scim/v2/Users/${user.id}"),
        )
    }

    private companion object {
        val FILTER_PATTERN = Regex("""^(\w+)\s+eq\s+"([^"]*)"$""", RegexOption.IGNORE_CASE)
        const val DEFAULT_PAGE_SIZE = 100
        const val MAX_PAGE_SIZE = 200
    }

    /**
     * SCIM startIndex(1-base, 페이지 경계와 무관한 임의 오프셋)를 DB LIMIT/OFFSET 으로 전달하는
     * offset 기반 Pageable — PageRequest 는 페이지 번호 기반이라 임의 오프셋을 표현할 수 없다.
     * limit > 0 전제(호출부가 count=0 을 사전 차단).
     */
    private class OffsetPageable(
        private val offset: Long,
        private val limit: Int,
        private val sort: Sort,
    ) : Pageable {
        override fun getPageNumber(): Int = (offset / limit).toInt()

        override fun getPageSize(): Int = limit

        override fun getOffset(): Long = offset

        override fun getSort(): Sort = sort

        override fun next(): Pageable = OffsetPageable(offset + limit, limit, sort)

        override fun previousOrFirst(): Pageable = if (offset >= limit) OffsetPageable(offset - limit, limit, sort) else first()

        override fun first(): Pageable = OffsetPageable(0, limit, sort)

        override fun withPage(pageNumber: Int): Pageable = OffsetPageable(pageNumber.toLong() * limit, limit, sort)

        override fun hasPrevious(): Boolean = offset > 0
    }
}
