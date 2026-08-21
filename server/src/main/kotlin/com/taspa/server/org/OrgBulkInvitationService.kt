package com.taspa.server.org

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.org.DepartmentRepository
import com.taspa.server.org.dto.BulkInvitationResponse
import com.taspa.server.org.dto.BulkInvitationRowResult
import org.slf4j.LoggerFactory
import org.springframework.mail.MailException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * CSV 대량 초대 — **각 행이 기존 OrgInvitationService.invite() 를 그대로 탄다.** 따라서 보안 속성
 * (토큰 해시 저장·이메일 일치 강제·계정 열거 저항·(org,email) PENDING 1건·시간당 상한 maxPerHour·
 * 재발송 쿨다운)이 새 정책 없이 자동 적용된다. 이 서비스는 파싱·행별 호출·결과 집계만 담당한다.
 *
 * ★트랜잭션 경계(핵심): 이 서비스의 메서드에는 @Transactional 을 **걸지 않는다** — invite() 가 행마다
 *   자기 트랜잭션(+커밋후 메일)을 갖게 하여, 한 행 실패가 이전 성공 행을 롤백하지 않고 롤백된 초대의
 *   '죽은 링크' 메일도 나가지 않는다. invite() 의 @Transactional 은 프록시 경유 외부 호출이어야
 *   동작하므로 같은 빈 내부 호출이 아니라 **별도 빈에서 orgInvitationService 를 주입해 호출**한다.
 */
@Service
class OrgBulkInvitationService(
    private val orgInvitationService: OrgInvitationService,
    private val auditEventService: AuditEventService,
    private val departmentRepository: DepartmentRepository,
) {
    companion object {
        private val log = LoggerFactory.getLogger(OrgBulkInvitationService::class.java)

        /** 요청 1건 행 수 상한 — 동기 처리·메일 발송 자원 상한(초과 시 400). 하드코딩 상수(설정화 불요). */
        const val MAX_ROWS = 200

        /** CSV 원문 길이 상한(64KB) — 행 상한과 별개로 초대형 페이로드를 파싱 전에 조기 거부한다. */
        const val MAX_CSV_LENGTH = 64 * 1024
        private const val STATUS_CREATED = "CREATED"
        private const val STATUS_REJECTED = "REJECTED"
    }

    /** 파싱된 CSV 한 행. line 은 원문 기준 1-base 행 번호(헤더·빈 행 포함 위치). */
    private data class CsvRow(
        val line: Int,
        val email: String,
        val role: String?,
        val department: String?,
    )

    /**
     * CSV 를 파싱해 행별로 invite() 를 호출하고 결과를 집계한다. **행별 독립 실패**: invite() 의
     * AuthException(이미 멤버·시간당 상한·쿨다운·이메일/역할 형식 등)은 그 행의 REJECTED 결과로
     * 기록하고 다음 행을 계속 처리한다 — 기존 가드를 존중하고 행별 결과만 정직하게 보고한다.
     * AuthException 외의 예외도 그 행의 REJECTED 로 격리한다(배치 전체 중단 금지): 커밋 후
     * afterCommit 메일 발송의 MailException(초대는 이미 생성됨 — 재발송으로 복구 가능)과
     * 동시 요청 경합의 UNIQUE 위반 등 — 단일 행 장애가 이전 성공 행의 결과 보고와 집계 audit 을
     * 유실시키지 않는다.
     * 행별 audit 는 invite() 가 이미 ORG_INVITE_CREATED 를 남기므로, 여기서는 집계 1건만 남긴다.
     */
    fun bulkInvite(
        orgId: UUID,
        csv: String,
        inviterId: UUID?,
        /** 단건 초대와 같은 규칙 — ORG_ADMIN 행은 승격 권한이 있을 때만 통과한다(행별 REJECTED). */
        mayGrantOrgAdmin: Boolean = false,
    ): BulkInvitationResponse {
        if (csv.length > MAX_CSV_LENGTH) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "CSV 가 너무 큽니다(최대 ${MAX_CSV_LENGTH / 1024}KB)")
        }
        val rows = parse(csv)
        if (rows.isEmpty()) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "초대할 행이 없습니다")
        }
        if (rows.size > MAX_ROWS) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "한 번에 ${MAX_ROWS}행까지 초대할 수 있습니다")
        }

        /*
         * 부서 이름 해석은 **한 번만** 읽어 메모리에서 판정한다(행마다 질의하면 200행 × 조직 부서 수).
         * 판정 규칙은 `DepartmentBinder.resolve` 와 같아야 한다 — 이름이 **정확히 하나**일 때만 이어진다
         * (형제 사이에서만 유일하므로 트리에 같은 이름이 둘 있을 수 있고, 아무 쪽이나 고르면 절반의
         * 확률로 틀린 부서의 예산을 쓴다).
         */
        val departmentNameCounts =
            departmentRepository
                .findByOrgId(orgId)
                .groupingBy { it.name.lowercase() }
                .eachCount()

        fun departmentWarning(label: String?): String? {
            val name = label?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return when (departmentNameCounts[name.lowercase()] ?: 0) {
                1 -> null
                0 ->
                    "부서 '$name' 을(를) 찾지 못해 구조 배정 없이 초대했습니다. " +
                        "조직구조 탭에서 부서를 만든 뒤 구성원 화면에서 배정해야 부서 식대 정책이 적용됩니다"
                else ->
                    "부서 이름 '$name' 이(가) 조직에 둘 이상 있어 어느 쪽인지 정할 수 없습니다. " +
                        "구성원 화면에서 직접 배정해 주세요"
            }
        }

        val results =
            rows.map { row ->
                try {
                    orgInvitationService.invite(
                        orgId,
                        row.email,
                        row.role,
                        row.department,
                        inviterId,
                        mayGrantOrgAdmin = mayGrantOrgAdmin,
                    )
                    BulkInvitationRowResult(
                        line = row.line,
                        email = row.email,
                        status = STATUS_CREATED,
                        warning = departmentWarning(row.department),
                    )
                } catch (e: AuthException) {
                    BulkInvitationRowResult(line = row.line, email = row.email, status = STATUS_REJECTED, reason = e.message)
                } catch (e: MailException) {
                    // 초대 트랜잭션은 이미 커밋됐고 afterCommit 메일만 실패 — 재발송으로 복구 가능함을 구분 보고.
                    log.warn("Bulk invitation mail send failed: org={}, line={}", orgId, row.line, e)
                    BulkInvitationRowResult(
                        line = row.line,
                        email = row.email,
                        status = STATUS_REJECTED,
                        reason = "초대는 생성됐지만 메일 발송에 실패했습니다. 초대 목록에서 재발송할 수 있습니다",
                    )
                } catch (e: Exception) {
                    // 동시 요청 경합의 UNIQUE 위반 등 — 행 단위로 격리하고 상세는 로그에만 남긴다.
                    log.warn("Bulk invitation row failed: org={}, line={}", orgId, row.line, e)
                    BulkInvitationRowResult(
                        line = row.line,
                        email = row.email,
                        status = STATUS_REJECTED,
                        reason = "처리 중 오류가 발생했습니다",
                    )
                }
            }
        val created = results.count { it.status == STATUS_CREATED }
        val rejected = results.size - created

        auditEventService.record(
            "ORG_INVITE_BULK",
            inviterId,
            orgId,
            mapOf(
                "orgId" to orgId.toString(),
                "total" to results.size,
                "created" to created,
                "rejected" to rejected,
            ),
        )
        return BulkInvitationResponse(total = results.size, created = created, rejected = rejected, results = results)
    }

    /**
     * 단순 파싱: 행당 `email[,role[,department]]`, 콤마 split + 필드 트림. **따옴표 필드는 미지원**
     * (형식이 단순해 외부 CSV 라이브러리 없이 직접 파싱 — 의존성 최소). 빈 행은 스킵하고, 첫 번째
     * 비어있지 않은 행의 첫 필드가 "email"(대소문자 무관)이면 헤더로 간주해 스킵한다.
     * 4번째 이후 필드는 무시한다. 이메일/역할 형식 검증은 invite() 에 위임한다(행별 REJECTED 로 보고).
     */
    private fun parse(csv: String): List<CsvRow> {
        val rows = mutableListOf<CsvRow>()
        var firstContentLine = true
        csv.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed
            val parts = line.split(',').map { it.trim() }
            if (firstContentLine) {
                firstContentLine = false
                if (parts[0].equals("email", ignoreCase = true)) return@forEachIndexed // 헤더 스킵
            }
            rows +=
                CsvRow(
                    line = index + 1,
                    email = parts[0],
                    role = parts.getOrNull(1)?.takeIf { it.isNotEmpty() },
                    department = parts.getOrNull(2)?.takeIf { it.isNotEmpty() },
                )
        }
        return rows
    }
}
