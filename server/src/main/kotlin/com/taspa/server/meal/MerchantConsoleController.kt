package com.taspa.server.meal

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.common.export.CsvWriter
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.forecast.ForecastSignals
import com.taspa.server.forecast.SignalOverrides
import com.taspa.server.iam.AuthorizationRequest
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.IamAuthorizationService
import com.taspa.server.iam.IamContextFactory
import com.taspa.server.iam.IamPrincipalKind
import com.taspa.server.iam.Trn
import com.taspa.server.meal.dto.MerchantBacktestResponse
import com.taspa.server.meal.dto.MerchantCellDetail
import com.taspa.server.meal.dto.MerchantForecastResponse
import com.taspa.server.meal.dto.MerchantSettlementView
import com.taspa.server.meal.dto.MerchantTransactionsResponse
import com.taspa.server.meal.dto.MyMerchantsResponse
import com.taspa.server.stepup.RequireRecentAuth
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * 가맹 관리자 콘솔 API — **사람 세션 전용 조회 표면**(식수 로그·예측). 결제 승인은 여기 없다:
 * redeem/void 는 기계 신원(POS = M2M + merchant_id 클레임) 전용으로 남는다.
 *
 * ★경로 주의: 기존 `/api/merchant` 이하는 **M2M 전용 STATELESS 체인**(@Order(-1), SecurityConfig)이라
 * 그 아래에 세션 엔드포인트를 두면 로그인 쿠키로는 절대 인증되지 않는다. 그래서 접두사를
 * `/api/merchant-console` 로 분리했다 — 그 체인의 Ant 매처는 `merchant` **세그먼트**만 매칭하므로
 * 이 경로는 기본 체인(@Order(3), anyRequest().authenticated())에 떨어진다. 조회 전용(GET)이라 CSRF 는
 * 무관하다(CsrfFilter 는 안전 메서드를 제외).
 *
 * 인가 규약:
 *  - 판정자는 **정책 엔진 하나**다. 컨트롤러는 사실(플랫폼 ADMIN 여부·요청 경로 가맹점의 활성 관리 멤버십
 *    여부)만 모아 `decideSession` 에 넘기고, allow/deny 를 직접 합성하지 않는다.
 *  - `merchantAdminOf` 는 **요청 경로의 merchantId** 에 대한 활성 멤버십일 때만 넘긴다
 *    (`MerchantConsoleService.isActiveMerchantAdmin` — 멤버십 ACTIVE ∧ 매장 ACTIVE). "어딘가의 가맹
 *    관리자" 같은 원시 플래그는 넘기지 않는다.
 *  - 위임 베어러(JwtAuthenticationToken)는 **엔진 밖 하드 게이트**로 즉시 거부한다 — 조회라도 사람 세션
 *    전용 표면이며, 이 거부를 판정 입력으로 합성하지 않는다.
 */
@RestController
@RequestMapping("/api/merchant-console")
class MerchantConsoleController(
    private val merchantConsoleService: MerchantConsoleService,
    private val merchantForecastService: MerchantForecastService,
    private val userRepository: UserRepository,
    private val iamAuthorizationService: IamAuthorizationService,
    private val iamContextFactory: IamContextFactory,
    private val auditEventService: AuditEventService,
) {
    /**
     * 내가 관리하는 가맹점 목록(매장 선택). 인가는 "로그인만" — 결과가 자기 멤버십으로 자연 필터되므로
     * 리소스 단위 판정이 성립하지 않는다(엔진에 넘길 merchant TRN 이 아직 없다).
     */
    @GetMapping("/mine")
    fun mine(authentication: Authentication): ResponseEntity<MyMerchantsResponse> {
        val user = currentUser(authentication)
        return ResponseEntity.ok(merchantConsoleService.listMyMerchants(user.id!!))
    }

    /** 자기 매장 거래(식수 로그). 손님 개인정보는 응답 DTO 에 자리가 없다(MerchantTransactionView). */
    @GetMapping("/{merchantId}/transactions")
    fun transactions(
        authentication: Authentication,
        @PathVariable merchantId: UUID,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) limit: Int?,
    ): ResponseEntity<MerchantTransactionsResponse> {
        authorize(authentication, merchantId, IamActions.MERCHANT_READ_TRANSACTIONS, "MerchantConsoleController.transactions")
        return ResponseEntity.ok(
            merchantConsoleService.transactions(merchantId, parseDate(from, "from"), parseDate(to, "to"), limit),
        )
    }

    /**
     * 월 정산 명세 — "이번 달 우리가 얼마를 받는가". 창은 **매장 타임존** 월 경계라 조직 청구서와
     * 경계일 거래만큼 다를 수 있다(응답의 periodStart/End 가 실제 창을 드러낸다).
     *
     * @param period 'YYYY-MM'. 생략하면 매장 로컬 달력의 이번 달 — 정산은 진행 중인 달도 봐야 하므로
     *   전역 대사(지난달 기본)와 기본값이 다르다. 매장은 "지금까지 얼마 쌓였나"를 묻는다.
     */
    @GetMapping("/{merchantId}/settlement")
    fun settlement(
        authentication: Authentication,
        @PathVariable merchantId: UUID,
        @RequestParam(required = false) period: String?,
    ): ResponseEntity<MerchantSettlementView> {
        authorize(authentication, merchantId, IamActions.MERCHANT_READ_SETTLEMENT, "MerchantConsoleController.settlement")
        val target = period?.takeIf { it.isNotBlank() } ?: merchantConsoleService.currentPeriod(merchantId)
        return ResponseEntity.ok(merchantConsoleService.settlement(merchantId, target))
    }

    /**
     * 정산 명세 CSV — 매장 회계가 자기 장부에 옮기는 경로. 화면과 **같은 action·같은 집계**를 쓴다
     * (형식만 다른데 권한이나 숫자가 갈리면 그게 더 큰 사고다).
     */
    @GetMapping("/{merchantId}/settlement/csv")
    fun settlementCsv(
        authentication: Authentication,
        @PathVariable merchantId: UUID,
        @RequestParam(required = false) period: String?,
    ): ResponseEntity<String> {
        authorize(authentication, merchantId, IamActions.MERCHANT_READ_SETTLEMENT, "MerchantConsoleController.settlementCsv")
        val target = period?.takeIf { it.isNotBlank() } ?: merchantConsoleService.currentPeriod(merchantId)
        val view = merchantConsoleService.settlement(merchantId, target)
        val body =
            CsvWriter.render(
                listOf("기간", "조직", "승인건수", "지급예정액(원)", "손님직접결제(원)", "환불(원)"),
                view.lines.map {
                    listOf(
                        view.period,
                        it.orgName ?: "(삭제된 조직)",
                        it.approvedCount,
                        it.orgPaidMinor,
                        it.selfPaidMinor,
                        it.refundedMinor,
                    )
                },
            )
        return CsvWriter.download("taspa-정산명세-${view.period}.csv", body)
    }

    /**
     * 거래 로그 CSV — 이의제기·대사용. 손님 개인정보는 여기에도 없다(DTO 에 자리가 없으므로 구조적으로
     * 새어 나갈 수 없다 — 파일로 나가는 경로가 화면보다 위험한데, 그 위험이 여기선 성립하지 않는다).
     */
    @GetMapping("/{merchantId}/transactions/csv")
    fun transactionsCsv(
        authentication: Authentication,
        @PathVariable merchantId: UUID,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) limit: Int?,
    ): ResponseEntity<String> {
        authorize(authentication, merchantId, IamActions.MERCHANT_READ_TRANSACTIONS, "MerchantConsoleController.transactionsCsv")
        val view = merchantConsoleService.transactions(merchantId, parseDate(from, "from"), parseDate(to, "to"), limit)
        val body =
            CsvWriter.render(
                listOf(
                    "승인시각(UTC)",
                    "조직",
                    "끼니",
                    "결제액(원)",
                    "조직부담(원)",
                    "개인부담(원)",
                    "환불(원)",
                    "원금(원)",
                    "상태",
                    "거래번호",
                    "승인번호",
                ),
                view.rows.map {
                    listOf(
                        // 시각은 **UTC ISO-8601** 로 낸다. 매장 로컬 문자열로 내면 받는 쪽이 어느 타임존인지
                        // 알 수 없고(파일에는 화면의 머리말이 따라가지 않는다), 엑셀이 제멋대로 날짜로 재해석한다.
                        it.approvedAt.toString(),
                        it.orgName ?: "(삭제된 조직)",
                        it.mealWindow,
                        it.amountMinor,
                        it.orgPaidMinor,
                        it.selfPaidMinor,
                        it.refundedMinor,
                        it.originalAmountMinor,
                        it.status,
                        it.posTxnId,
                        it.authId,
                    )
                },
            )
        return CsvWriter.download("taspa-거래로그-${view.from}_${view.to}.csv", body)
    }

    /** 가맹 그레인 식수예측(날짜 × 끼니). 인원 보정 없음 — MerchantForecastService KDoc 참조. */
    @GetMapping("/{merchantId}/forecast")
    fun forecast(
        authentication: Authentication,
        @PathVariable merchantId: UUID,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) mealWindow: String?,
        /*
         * 신호 스위치 — 요청 단위 실험(조직 예측과 같은 규약). 각 신호는 **이용 조직별로** 적용된다:
         * 실적을 조직별로 분해해 그 조직의 캘린더·연차를 그 조각에만 적용하고 합산한다.
         */
        @RequestParam(required = false) headcountAdjust: Boolean?,
        @RequestParam(required = false) absenceAware: Boolean?,
        @RequestParam(required = false) holidayAware: Boolean?,
        @RequestParam(required = false) eventAware: Boolean?,
        @RequestParam(required = false) menuAware: Boolean?,
        @RequestParam(required = false) nowcast: Boolean?,
        @RequestParam(required = false) methodSelection: Boolean?,
    ): ResponseEntity<MerchantForecastResponse> {
        authorize(authentication, merchantId, IamActions.MERCHANT_READ_FORECAST, "MerchantConsoleController.forecast")
        return ResponseEntity.ok(
            merchantForecastService.forecast(
                merchantId,
                parseDate(from, "from"),
                parseDate(to, "to"),
                mealWindow,
                SignalOverrides(headcountAdjust, absenceAware, holidayAware, eventAware, menuAware, nowcast, methodSelection),
            ),
        )
    }

    /** 예측 정확도 백테스트(과거 구간). 같은 폴백 체인 재현 — 미래정보 누수 없음. */
    @GetMapping("/{merchantId}/backtest")
    fun backtest(
        authentication: Authentication,
        @PathVariable merchantId: UUID,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) mealWindow: String?,
        /*
         * 신호 스위치 — 요청 단위 실험(조직 예측과 같은 규약). 각 신호는 **이용 조직별로** 적용된다:
         * 실적을 조직별로 분해해 그 조직의 캘린더·연차를 그 조각에만 적용하고 합산한다.
         */
        @RequestParam(required = false) headcountAdjust: Boolean?,
        @RequestParam(required = false) absenceAware: Boolean?,
        @RequestParam(required = false) holidayAware: Boolean?,
        @RequestParam(required = false) eventAware: Boolean?,
        @RequestParam(required = false) menuAware: Boolean?,
        @RequestParam(required = false) nowcast: Boolean?,
        @RequestParam(required = false) methodSelection: Boolean?,
    ): ResponseEntity<MerchantBacktestResponse> {
        authorize(authentication, merchantId, IamActions.MERCHANT_READ_FORECAST, "MerchantConsoleController.backtest")
        return ResponseEntity.ok(
            merchantForecastService.backtest(
                merchantId,
                parseDate(from, "from"),
                parseDate(to, "to"),
                mealWindow,
                SignalOverrides(headcountAdjust, absenceAware, holidayAware, eventAware, menuAware, nowcast, methodSelection),
            ),
        )
    }

    /**
     * (날짜 × 끼니) 셀 하나의 근거 상세 — 목록의 숫자를 클릭했을 때 "왜 이 숫자인가"에 답한다.
     * 조직 분해(basis 날짜·실적 포함)와 메뉴별 분해("어떤 메뉴가 몇 인분")를 싣는다.
     */
    @GetMapping("/{merchantId}/forecast/cell")
    fun forecastCell(
        authentication: Authentication,
        @PathVariable merchantId: UUID,
        @RequestParam date: String,
        @RequestParam mealWindow: String,
        @RequestParam(required = false) headcountAdjust: Boolean?,
        @RequestParam(required = false) absenceAware: Boolean?,
        @RequestParam(required = false) holidayAware: Boolean?,
        @RequestParam(required = false) eventAware: Boolean?,
        @RequestParam(required = false) menuAware: Boolean?,
        @RequestParam(required = false) nowcast: Boolean?,
        @RequestParam(required = false) methodSelection: Boolean?,
    ): ResponseEntity<MerchantCellDetail> {
        authorize(authentication, merchantId, IamActions.MERCHANT_READ_FORECAST, "MerchantConsoleController.forecastCell")
        return ResponseEntity.ok(
            merchantForecastService.cellDetail(
                merchantId,
                parseDate(date, "date") ?: throw AuthException(ErrorCode.VALIDATION_ERROR, "date 는 필수입니다"),
                mealWindow,
                SignalOverrides(headcountAdjust, absenceAware, holidayAware, eventAware, menuAware, nowcast, methodSelection),
            ),
        )
    }

    /** 저장된 예측 신호 설정. 행이 없으면 코드 기본값(도입 전과 같은 동작)이다. */
    @GetMapping("/{merchantId}/forecast-settings")
    fun forecastSettings(
        authentication: Authentication,
        @PathVariable merchantId: UUID,
    ): ResponseEntity<ForecastSignals> {
        authorize(authentication, merchantId, IamActions.MERCHANT_READ_FORECAST, "MerchantConsoleController.forecastSettings")
        return ResponseEntity.ok(merchantForecastService.readSettings(merchantId))
    }

    /**
     * 예측 신호 설정 저장 — 이 매장 예측을 보는 **모든 화면**의 숫자를 바꾸므로 전용 action
     * (`merchant:UpdateForecastSettings`) + 감사 이벤트를 남긴다("저장하면 누가 언제 켰는지 모른다"는
     * 원래 우려에 대한 답이 이 감사 기록이다).
     */
    @RequireRecentAuth
    @PutMapping("/{merchantId}/forecast-settings")
    fun saveForecastSettings(
        authentication: Authentication,
        @PathVariable merchantId: UUID,
        @RequestBody request: ForecastSignals,
    ): ResponseEntity<ForecastSignals> {
        authorize(
            authentication,
            merchantId,
            IamActions.MERCHANT_UPDATE_FORECAST_SETTINGS,
            "MerchantConsoleController.saveForecastSettings",
        )
        val saved = merchantForecastService.saveSettings(merchantId, request)
        auditEventService.record(
            "MERCHANT_FORECAST_SETTINGS_UPDATED",
            currentUser(authentication).id,
            mapOf(
                "merchantId" to merchantId.toString(),
                "headcountAdjust" to saved.headcountAdjust,
                "absenceAware" to saved.absenceAware,
                "holidayAware" to saved.holidayAware,
                "eventAware" to saved.eventAware,
                "menuAware" to saved.menuAware,
                "nowcast" to saved.nowcast,
                "methodSelection" to (saved.methodSelection ?: false),
            ),
        )
        return ResponseEntity.ok(saved)
    }

    // ---- 인가 ----

    /**
     * **판정은 엔진이 한다.** 이 함수는 엔진에 넘길 사실만 모은다:
     *  - platformAdmin: 세션 권한 또는 users.role. 지원·운영 목적의 전역 조회를 브리지가 허용한다
     *    (merchant:Read* 는 PLATFORM_ADMIN_EXCLUDED_ACTIONS 가 아니다 — 손님 식별정보가 없는 집계·로그이고,
     *    실지출을 만드는 능력이 아니라서 제외 대상이 아니다).
     *  - merchantAdminOf: 요청 경로 가맹점의 활성 관리 멤버십일 때만(멤버십 ACTIVE ∧ 매장 ACTIVE).
     *
     * 존재하지 않는 가맹점 id 는 비관리자에게 403 으로 수렴한다(멤버십이 없어 엔진이 거부) — 열거 방지.
     */
    private fun authorize(
        authentication: Authentication,
        merchantId: UUID,
        action: String,
        label: String,
    ) {
        val user = currentUser(authentication)
        val userId = user.id!!
        val platformAdmin =
            authentication.authorities.any { it.authority == "ROLE_ADMIN" } ||
                user.role == UserRole.ADMIN.name
        val merchantAdmin = merchantConsoleService.isActiveMerchantAdmin(merchantId, userId)

        val request =
            AuthorizationRequest(
                action = action,
                resource = Trn.merchant(merchantId),
                // 가맹점은 org 스코프 자원이 아니다 — 테넌시는 merchant UUID 정확 일치가 가른다(LegacyPolicyBridge).
                context = iamContextFactory.build(IamPrincipalKind.SESSION, orgId = null),
            )
        val allowed =
            iamAuthorizationService.authorize(request, label) {
                iamAuthorizationService.decideSession(
                    userId = userId,
                    platformAdmin = platformAdmin,
                    orgAdminOf = null,
                    memberOf = null,
                    request = request,
                    merchantAdminOf = if (merchantAdmin) merchantId else null,
                )
            }
        if (!allowed) throw AuthException(ErrorCode.FORBIDDEN, "해당 가맹점의 관리자가 아닙니다")
    }

    /**
     * 세션 principal(name=이메일) → users 행. 베어러는 **엔진 밖 하드 게이트**로 먼저 잘라낸다(판정 입력으로
     * 합성하지 않는다). 조회 실패는 요청에 전파하지 않고 403 으로 정직하게 표현한다(500 아님).
     */
    private fun currentUser(authentication: Authentication): User {
        if (authentication is JwtAuthenticationToken) {
            throw AuthException(ErrorCode.FORBIDDEN, "가맹 콘솔은 로그인 세션 전용입니다 (베어러 토큰 불가)")
        }
        val user = runCatching { userRepository.findByEmail(authentication.name) }.getOrNull()
        return user?.takeIf { it.id != null } ?: throw AuthException(ErrorCode.FORBIDDEN)
    }

    /**
     * ISO-8601 파싱 + **업무 범위 가드**. LocalDate 는 ±999999999년까지 파싱되므로 `-999999999-01-01`
     * 같은 극단값이 그대로 통과하면 서비스의 `plusDays`/`minusDays` 창 계산이 오버플로해
     * 처리되지 않은 DateTimeException → 500 이 된다. 전역 핸들러가 그걸 400 으로 받아 주긴 하지만
     * 문구가 "입력값이 올바르지 않습니다" 뿐이라, 어느 파라미터가 왜 틀렸는지는 여기서만 말해 줄 수 있다.
     */
    private fun parseDate(
        value: String?,
        field: String,
    ): LocalDate? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parsed =
            try {
                LocalDate.parse(raw)
            } catch (ex: DateTimeParseException) {
                throw AuthException(ErrorCode.VALIDATION_ERROR, "$field 는 ISO-8601 날짜여야 합니다 (예: 2026-07-20)")
            }
        if (parsed < MIN_QUERY_DATE || parsed > MAX_QUERY_DATE) {
            throw AuthException(
                ErrorCode.VALIDATION_ERROR,
                "$field 는 $MIN_QUERY_DATE ~ $MAX_QUERY_DATE 범위여야 합니다",
            )
        }
        return parsed
    }

    private companion object {
        val MIN_QUERY_DATE: LocalDate = LocalDate.of(2000, 1, 1)
        val MAX_QUERY_DATE: LocalDate = LocalDate.of(2100, 12, 31)
    }
}
