package com.taspa.server.admin

import com.taspa.server.admin.dto.MerchantMemberAddRequest
import com.taspa.server.admin.dto.MerchantMemberView
import com.taspa.server.admin.dto.MerchantUpsertRequest
import com.taspa.server.admin.dto.MerchantView
import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.meal.Merchant
import com.taspa.server.domain.meal.MerchantCategory
import com.taspa.server.domain.meal.MerchantMember
import com.taspa.server.domain.meal.MerchantMemberRepository
import com.taspa.server.domain.meal.MerchantMemberStatus
import com.taspa.server.domain.meal.MerchantRepository
import com.taspa.server.domain.meal.MerchantRole
import com.taspa.server.domain.meal.MerchantStatus
import com.taspa.server.domain.org.SiteRepository
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.mail.MailService
import com.taspa.server.org.OrganizationService
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 가맹 관리(플랫폼 ADMIN 최소 CRUD — 식권 L1). site 연결은 구내식당 운영 사업장(선택) — redemption
 * 소비 이벤트의 site 귀속 근원이므로 존재하는 사업장만 허용한다.
 *
 * 가맹 직원(merchant_members, V29) 관리도 여기 둔다 — **부여 권한은 플랫폼 ADMIN 전용**이다. 가맹
 * 관리자가 스스로 동료를 추가할 수 있으면 매장 신원이 자가 증식하므로, 발급은 플랫폼이 쥐고 가맹
 * 관리자는 조회만 한다(MerchantConsoleController).
 */
@Service
class AdminMerchantService(
    private val merchantRepository: MerchantRepository,
    private val merchantMemberRepository: MerchantMemberRepository,
    private val siteRepository: SiteRepository,
    private val userRepository: UserRepository,
    private val organizationService: OrganizationService,
    private val auditEventService: AuditEventService,
    private val mailService: MailService,
    /**
     * 담당자 안내 메일의 링크 base. 초대 메일과 **같은 키**를 쓴다 — 새 키를 만들면 한쪽만 고친 순간
     * 메일이 조용히 잘못된 호스트를 가리킨다(청구서 초안 알림이 같은 이유로 이 키를 공유한다).
     */
    @Value("\${taspa.org-invitation.base-url:http://localhost:9100}")
    private val consoleBaseUrl: String,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(AdminMerchantService::class.java)

    fun list(): List<MerchantView> = merchantRepository.findAll(Sort.by("createdAt")).map { MerchantView.from(it) }

    fun get(id: UUID): MerchantView =
        MerchantView.from(merchantRepository.findById(id).orElse(null) ?: throw AuthException(ErrorCode.NOT_FOUND))

    /**
     * 정액 단가 검증 — 0 이하와 배포 상한 초과를 막는다. 상한을 두는 이유는 식대 정책과 같다:
     * 자릿수 오타 한 번이 그 매장의 모든 결제를 잘못된 금액으로 자동 승인한다(금액 입력 화면이 없으니
     * 계산원이 알아챌 기회도 없다).
     */
    private fun validatedPrice(value: Long?): Long? {
        if (value == null) return null
        if (value <= 0 || value > MAX_DEFAULT_PRICE_MINOR) {
            throw AuthException(
                ErrorCode.VALIDATION_ERROR,
                "defaultPriceMinor 는 1 이상 ${MAX_DEFAULT_PRICE_MINOR} 이하여야 합니다",
            )
        }
        return value
    }

    fun create(
        request: MerchantUpsertRequest,
        actorId: UUID,
    ): MerchantView {
        val name = request.name.trim()
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "name 은 1~${MAX_NAME_LENGTH}자여야 합니다")
        }
        val merchant =
            merchantRepository.save(
                Merchant(
                    name = name,
                    category = parseCategory(request.category),
                    status = parseStatus(request.status),
                    siteId = validatedSiteId(request.siteId),
                    // 미지정이면 UTC(컬럼 기본과 동일). 검증은 org/site 와 같은 헬퍼로 수렴한다 —
                    // 잘못된 존이 저장되면 집계 쿼리(AT TIME ZONE)가 런타임에 깨진다.
                    timezone = organizationService.requireValidTimezone(request.timezone) ?: "UTC",
                    defaultPriceMinor = validatedPrice(request.defaultPriceMinor),
                ),
            )
        auditEventService.record(
            "ADMIN_MERCHANT_CREATED",
            actorId,
            mapOf("merchantId" to merchant.id.toString(), "name" to merchant.name, "status" to merchant.status),
        )
        return MerchantView.from(merchant)
    }

    @Transactional
    fun update(
        id: UUID,
        request: MerchantUpsertRequest,
        actorId: UUID,
    ): MerchantView {
        val merchant = merchantRepository.findById(id).orElse(null) ?: throw AuthException(ErrorCode.NOT_FOUND)
        val name = request.name.trim()
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "name 은 1~${MAX_NAME_LENGTH}자여야 합니다")
        }
        merchant.name = name
        merchant.category = parseCategory(request.category)
        merchant.status = parseStatus(request.status)
        merchant.siteId = validatedSiteId(request.siteId)
        // ★timezone 만 full-replace 하지 않는다(미전송 = 유지) — DTO 주석의 소급 이동 사고 방지.
        organizationService.requireValidTimezone(request.timezone)?.let { merchant.timezone = it }
        // 정액 단가도 같은 규약(미전송 = 유지). 해제는 명시 플래그로만 — 조용한 삭제를 만들지 않는다.
        if (request.clearDefaultPrice) {
            merchant.defaultPriceMinor = null
        } else {
            validatedPrice(request.defaultPriceMinor)?.let { merchant.defaultPriceMinor = it }
        }
        val saved = merchantRepository.save(merchant)
        auditEventService.record(
            "ADMIN_MERCHANT_UPDATED",
            actorId,
            mapOf(
                "merchantId" to id.toString(),
                "name" to saved.name,
                "status" to saved.status,
                "timezone" to saved.timezone,
            ),
        )
        return MerchantView.from(saved)
    }

    /**
     * 삭제 — 승인 거래가 있는 가맹은 FK(meal_transactions.merchant_id)가 막는다(→ 409, 장부 불변).
     * 거래 이력이 있으면 삭제 대신 SUSPENDED 전환이 올바른 운영 경로다.
     */
    fun delete(
        id: UUID,
        actorId: UUID,
    ) {
        val merchant = merchantRepository.findById(id).orElse(null) ?: throw AuthException(ErrorCode.NOT_FOUND)
        merchantRepository.delete(merchant)
        auditEventService.record(
            "ADMIN_MERCHANT_DELETED",
            actorId,
            mapOf("merchantId" to id.toString(), "name" to merchant.name),
        )
    }

    // ---- 가맹 직원(사람 신원) 관리 ----

    /**
     * 가맹 직원 목록(ACTIVE 만) — 이 API 의 부여는 항상 ACTIVE, 해제는 행 삭제라 SUSPENDED 는 정상
     * 경로로 생기지 않는다(수기 DB 조작의 잔재만 숨는다 — 인가도 그 행을 인정하지 않는다).
     * 계정이 하드 삭제된 잔여 행은 email/displayName 을 null 로 남긴다(행은 보존).
     */
    @Transactional(readOnly = true)
    fun listMembers(merchantId: UUID): List<MerchantMemberView> {
        requireMerchant(merchantId)
        val members = merchantMemberRepository.findByMerchantIdAndStatus(merchantId, MerchantMemberStatus.ACTIVE.name)
        if (members.isEmpty()) return emptyList()
        val users = userRepository.findAllById(members.map { it.userId }).associateBy { it.id }
        return members
            .map { member ->
                val user = users[member.userId]
                MerchantMemberView(
                    userId = member.userId,
                    email = user?.email,
                    displayName = user?.displayName,
                    role = member.role,
                    status = member.status,
                    createdAt = member.createdAt,
                )
            }.sortedBy { it.email ?: "" }
    }

    /**
     * 가맹 직원 부여(MERCHANT_ADMIN 고정) — 이메일로 **기존 계정**을 찾는다. 여기서 계정을 새로 만들지
     * 않는 이유: 가맹 신원 부여가 곧 계정 생성이 되면, 오타 하나로 유령 계정이 생기고 그 계정이 매장
     * 데이터에 접근 가능한 상태가 된다. 초대·가입은 기존 경로를 쓴다.
     *
     * 멱등: 이미 행이 있으면 ACTIVE 로 되살리고 역할을 고정한다(중복 409 대신 수렴 — 재부여가 정상 운영).
     * 부여 대상 계정의 조직 소속 여부는 보지 않는다 — 식당 사장이 어느 회사 직원일 필요는 없다(V29).
     */
    @Transactional
    fun addMember(
        merchantId: UUID,
        request: MerchantMemberAddRequest,
        actorId: UUID,
    ): MerchantMemberView {
        val merchant = requireMerchant(merchantId)
        val email = request.email.trim().lowercase()
        if (email.isEmpty()) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "email 을 입력하세요")
        }
        val user =
            userRepository.findByEmail(email)
                ?: throw AuthException(ErrorCode.VALIDATION_ERROR, "해당 이메일의 사용자를 찾을 수 없습니다")
        val userId = user.id!!

        val existing = merchantMemberRepository.findByMerchantIdAndUserId(merchantId, userId)
        val member =
            if (existing != null) {
                existing.role = MerchantRole.MERCHANT_ADMIN.name
                existing.status = MerchantMemberStatus.ACTIVE.name
                merchantMemberRepository.save(existing)
            } else {
                merchantMemberRepository.save(MerchantMember(merchantId = merchantId, userId = userId))
            }
        auditEventService.record(
            "ADMIN_MERCHANT_MEMBER_ADDED",
            actorId,
            mapOf(
                "merchantId" to merchantId.toString(),
                "merchantName" to merchant.name,
                "targetUserId" to userId.toString(),
                "targetEmail" to user.email,
                "role" to member.role,
                // 되살림인지 신규인지 구분해 감사 로그만 보고 이력을 복원할 수 있게 한다.
                "reactivated" to (existing != null),
            ),
        )
        /*
         * ★당사자에게 알린다. 이게 없으면 부여는 성공했지만 **아는 사람이 없다** — 사장은 우연히
         * 로그인해 헤더가 바뀐 것을 보기 전까지 모르고, 운영자는 제품 밖(전화·메신저)에서 알려야 한다.
         * 매장이 아직 ACTIVE 가 아니면 그 사실도 함께 말한다(들어갔다가 빈 화면을 보고 권한 문제로
         * 오해하는 것이 정확히 이 감사에서 나온 블로커였다).
         *
         * 발송 실패는 **비전파**다 — 메일 장애로 권한 부여가 롤백되면, 알림 문제를 고치려다
         * 부여 자체를 잃는다(청구서 초안 알림과 같은 규칙).
         */
        runCatching {
            mailService.sendMerchantAdminGranted(
                email = user.email,
                merchantName = merchant.name,
                consoleUrl = "$consoleBaseUrl/merchant/$merchantId",
                merchantActive = merchant.statusEnum() == MerchantStatus.ACTIVE,
            )
        }.onFailure { log.warn("가맹 담당자 안내 메일 발송 실패: merchant={}, email={}", merchantId, user.email, it) }

        return MerchantMemberView(
            userId = userId,
            email = user.email,
            displayName = user.displayName,
            role = member.role,
            status = member.status,
            createdAt = member.createdAt,
        )
    }

    /**
     * 가맹 직원 해제 — 행을 삭제한다(가맹 멤버십은 조직 멤버십과 달리 SCD 이력 요구가 없다).
     * 해제 즉시 인가 사실이 사라진다: 콘솔 인가는 요청마다 `isActiveMerchantAdmin` 을 다시 읽으므로
     * 이미 로그인한 세션도 다음 요청에서 403 이 된다(세션에 굳지 않는다).
     */
    @Transactional
    fun removeMember(
        merchantId: UUID,
        userId: UUID,
        actorId: UUID,
    ) {
        val merchant = requireMerchant(merchantId)
        val member =
            merchantMemberRepository.findByMerchantIdAndUserId(merchantId, userId)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "가맹 직원을 찾을 수 없습니다")
        merchantMemberRepository.delete(member)
        auditEventService.record(
            "ADMIN_MERCHANT_MEMBER_REMOVED",
            actorId,
            mapOf(
                "merchantId" to merchantId.toString(),
                "merchantName" to merchant.name,
                "targetUserId" to userId.toString(),
                "role" to member.role,
            ),
        )
    }

    private fun requireMerchant(merchantId: UUID): Merchant =
        merchantRepository.findById(merchantId).orElse(null)
            ?: throw AuthException(ErrorCode.NOT_FOUND, "가맹점을 찾을 수 없습니다")

    private fun validatedSiteId(siteId: UUID?): UUID? {
        if (siteId == null) return null
        if (!siteRepository.existsById(siteId)) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "연결할 사업장을 찾을 수 없습니다")
        }
        return siteId
    }

    private fun parseCategory(value: String?): String =
        value
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { parseEnum { MerchantCategory.parse(it).name } }
            ?: MerchantCategory.RESTAURANT.name

    private fun parseStatus(value: String?): String =
        value
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { parseEnum { MerchantStatus.parse(it).name } }
            ?: MerchantStatus.ACTIVE.name

    /** enum 파싱 실패를 400 으로 정규화한다(ConsumptionEventService.validateEnum 과 동일 사상). */
    private fun <T> parseEnum(parse: () -> T): T =
        try {
            parse()
        } catch (ex: IllegalArgumentException) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, ex.message ?: "잘못된 값입니다")
        }

    private companion object {
        /** merchants.name 컬럼 상한(VARCHAR(200), V25). */
        const val MAX_NAME_LENGTH = 200

        /** 정액 단가 상한(원). 자릿수 오타가 그 매장의 전 결제를 잘못된 금액으로 자동 승인하는 것을 막는다. */
        const val MAX_DEFAULT_PRICE_MINOR = 1_000_000L
    }
}
