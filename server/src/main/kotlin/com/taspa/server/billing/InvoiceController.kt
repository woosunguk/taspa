package com.taspa.server.billing

import com.taspa.server.billing.dto.GenerateInvoiceRequest
import com.taspa.server.billing.dto.InvoiceDetailView
import com.taspa.server.billing.dto.InvoiceView
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.common.export.CsvWriter
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.iam.AuthorizationRequest
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.IamAuthorizationService
import com.taspa.server.iam.IamContextFactory
import com.taspa.server.iam.IamPrincipalKind
import com.taspa.server.iam.Trn
import com.taspa.server.org.OrganizationService
import com.taspa.server.stepup.RequireRecentAuth
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 조직 월 청구서 API — /api/orgs 체인(세션 인증 전용). ORG_ADMIN 이 자기 조직의 청구서를
 * 생성(draft)·조회·확정한다. 인가는 OrgMemberController.authorize 와 동일: **위임 베어러 거부 +
 * (플랫폼 ADMIN ∨ 해당 org 활성 ORG_ADMIN)**, 타 org 는 403. 상태변경(generate·finalize)은
 * step-up(@RequireRecentAuth) + CSRF(세션 체인 기본 강제) + org 결속 audit(서비스 계층)를 요구한다.
 * 실 수납·세금계산서는 범위 밖 — 확정(불변화)까지만.
 */
@RestController
@RequestMapping("/api/orgs/{orgId}/invoices")
class InvoiceController(
    private val invoiceService: InvoiceService,
    private val organizationService: OrganizationService,
    private val userRepository: UserRepository,
    private val iamShadowService: IamAuthorizationService,
    private val iamContextFactory: IamContextFactory,
) {
    @RequireRecentAuth
    @PostMapping("/generate")
    fun generate(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestBody request: GenerateInvoiceRequest,
    ): ResponseEntity<InvoiceDetailView> {
        val actor =
            authorizeWithShadow(
                authentication,
                orgId,
                IamActions.BILLING_GENERATE_INVOICE,
                Trn.invoices(orgId),
                "InvoiceController.generate",
            )
        return ResponseEntity.ok(invoiceService.generate(orgId, request.period, actor.id!!))
    }

    @GetMapping
    fun list(
        authentication: Authentication,
        @PathVariable orgId: UUID,
    ): ResponseEntity<List<InvoiceView>> {
        authorizeWithShadow(
            authentication,
            orgId,
            IamActions.BILLING_READ_INVOICE,
            Trn.invoices(orgId),
            "InvoiceController.list",
        )
        return ResponseEntity.ok(invoiceService.list(orgId))
    }

    @GetMapping("/{invoiceId}")
    fun detail(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable invoiceId: UUID,
    ): ResponseEntity<InvoiceDetailView> {
        authorizeWithShadow(
            authentication,
            orgId,
            IamActions.BILLING_READ_INVOICE,
            Trn.invoice(orgId, invoiceId),
            "InvoiceController.detail",
        )
        return ResponseEntity.ok(invoiceService.detail(orgId, invoiceId))
    }

    /**
     * 청구서 CSV — 회계팀이 ERP 로 옮기는 경로. **읽기 권한과 같은 action** 을 쓴다(형식만 다를 뿐
     * 같은 사실이고, 따로 두면 "화면은 되는데 내려받기는 403" 같은 어긋남이 생긴다).
     *
     * 부서 소계는 싣지 않는다 — 라인에서 파생 가능하고, 한 파일에 성격이 다른 두 표를 넣으면 어떤
     * 스프레드시트 도구에서도 자동 파싱이 깨진다.
     */
    @GetMapping("/{invoiceId}/csv")
    fun exportCsv(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable invoiceId: UUID,
    ): ResponseEntity<String> {
        authorizeWithShadow(
            authentication,
            orgId,
            IamActions.BILLING_READ_INVOICE,
            Trn.invoice(orgId, invoiceId),
            "InvoiceController.exportCsv",
        )
        val invoice = invoiceService.detail(orgId, invoiceId)
        val body =
            CsvWriter.render(
                listOf("기간", "상태", "이메일", "부서", "건수", "조직부담(원)"),
                invoice.lines.map {
                    listOf(
                        invoice.period,
                        invoice.status,
                        it.userEmail,
                        it.departmentName ?: "",
                        it.txnCount,
                        it.amountMinor,
                    )
                },
            )
        return CsvWriter.download("taspa-청구서-${invoice.period}.csv", body)
    }

    @RequireRecentAuth
    @PostMapping("/{invoiceId}/finalize")
    fun finalize(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable invoiceId: UUID,
    ): ResponseEntity<InvoiceView> {
        val actor =
            authorizeWithShadow(
                authentication,
                orgId,
                IamActions.BILLING_FINALIZE_INVOICE,
                Trn.invoice(orgId, invoiceId),
                "InvoiceController.finalize",
            )
        return ResponseEntity.ok(invoiceService.finalize(orgId, invoiceId, actor.id!!))
    }

    /**
     * 인가 격리 — OrgMemberController.authorize 와 동일. 세션 인증 전용(위임 베어러 거부:
     * under-consented access_token 재사용 confused-deputy 차단). 플랫폼 ADMIN 또는 그 org 의
     * 활성 ORG_ADMIN 만 통과한다.
     *
     * **판정자는 정책 엔진 하나다.** 레거시 판정 계산은 없고, 엔진에 넘길 사실(플랫폼 ADMIN 여부·
     * 해당 org ORG_ADMIN 여부)만 해석한다. 거부 시 예외 종류·메시지는 전과 동일하다.
     */
    private fun authorizeWithShadow(
        authentication: Authentication,
        orgId: UUID,
        action: String,
        resource: String,
        label: String,
    ): User {
        if (authentication is JwtAuthenticationToken) {
            // 베어러는 이 표면에서 무조건 닫힌다. 위임 베어러 거부(confused-deputy)는 엔진이 모델링하는
            // 역할·테넌시 판정이 아니라 표면 계약이며(LegacyPolicyBridge KDoc 의 "엔진 경계"), 엔진이
            // 허용하더라도 청구서 작성자(actor=users.id)를 해석할 수 없는 주체라 진행 자체가 불가능하다.
            throw AuthException(ErrorCode.FORBIDDEN, "청구서 관리는 세션 인증에서만 가능합니다")
        }
        // users 행이 없으면 엔진 주체를 구성할 수 없다 → fail-closed(거부). 예외는 아래 거부와 동일하다.
        val user = userRepository.findByEmail(authentication.name) ?: throw AuthException(ErrorCode.FORBIDDEN)
        val userId = user.id ?: throw AuthException(ErrorCode.FORBIDDEN)
        val platformAdmin = user.role == UserRole.ADMIN.name
        // 판정이 아니라 **엔진에 넘길 사실**이다. 플랫폼 ADMIN 이면 조회를 건너뛴다(질의 수 불변).
        val orgAdmin = !platformAdmin && organizationService.isOrgAdmin(orgId, userId)

        val request = AuthorizationRequest(action, resource, iamContextFactory.build(IamPrincipalKind.SESSION, orgId))
        val allowed =
            iamShadowService.authorize(request, label) {
                // memberOf 는 ORG_ADMIN 일 때만 채운다: 이 표면은 단순 멤버십으로 통과시키지 않으므로
                // isActiveMember 추가 질의가 판정을 바꾸지 않는다(ORG_ADMIN 은 정의상 활성 멤버).
                iamShadowService.decideSession(
                    userId = userId,
                    platformAdmin = platformAdmin,
                    orgAdminOf = if (orgAdmin) orgId else null,
                    memberOf = if (orgAdmin) orgId else null,
                    request = request,
                )
            }
        if (!allowed) throw AuthException(ErrorCode.FORBIDDEN)
        return user
    }
}
