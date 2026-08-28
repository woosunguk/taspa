package com.taspa.server.org

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.org.AbsenceType
import com.taspa.server.domain.org.MemberAbsence
import com.taspa.server.domain.org.MemberAbsenceRepository
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.org.dto.AbsenceDaySummary
import com.taspa.server.org.dto.AbsenceUpsertRequest
import com.taspa.server.org.dto.AbsenceUpsertResponse
import com.taspa.server.org.dto.AbsenceView
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/**
 * 구성원 부재(연차·반차·출장·병가) 관리 — 예측의 **재실 모수** 신호.
 *
 * 왜 iCalendar 가 아니라 전용 테이블인가: 부재는 **누가 몇 명 빠지는가**라는 수량 신호다.
 * VEVENT 는 ATTENDEE 를 실을 수는 있어도 그것이 "이 사람이 그날 없다"는 뜻인지 "초대받았다"는 뜻인지
 * 표준이 구분하지 않아, 인원 수를 세는 근거로 쓰면 조용히 틀린다. 반면 **사내 행사·휴일은 날짜에 대한
 * 선언**이라 iCalendar 가 정확히 그것을 위한 표준이고, 그쪽은 `calendar_feeds` 를 쓴다.
 */
@Service
class MemberAbsenceService(
    private val absenceRepository: MemberAbsenceRepository,
    private val membershipRepository: OrgMembershipRepository,
    private val userRepository: UserRepository,
    private val organizationService: OrganizationService,
) {
    @Transactional(readOnly = true)
    fun list(
        orgId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<AbsenceView> {
        organizationService.requireOrg(orgId)
        requireWindow(from, to)
        val rows = absenceRepository.findByOrgIdAndAbsenceDateBetweenOrderByAbsenceDateAscUserIdAsc(orgId, from, to)
        val users = userRepository.findAllById(rows.map { it.userId }.distinct()).associateBy { it.id }
        return rows.map { row ->
            val user = users[row.userId]
            AbsenceView(
                id = row.id!!,
                userId = row.userId,
                email = user?.email,
                displayName = user?.displayName,
                absenceDate = row.absenceDate,
                type = AbsenceType.parse(row.type) ?: AbsenceType.OTHER,
                source = row.source,
                weight = row.weight.toDouble(),
            )
        }
    }

    /**
     * 기간 등록(멱등). 같은 (사람, 날짜) 행이 있으면 유형만 갱신한다 — HR 연동·재업로드가 모수를
     * 두 번 깎지 않게 하는 것이 이 표면의 핵심 계약이다.
     */
    @Transactional
    fun upsert(
        orgId: UUID,
        request: AbsenceUpsertRequest,
        source: String = "MANUAL",
    ): AbsenceUpsertResponse {
        organizationService.requireOrg(orgId)
        val to = request.to ?: request.from
        requireWindow(request.from, to)
        if (java.time.temporal.ChronoUnit.DAYS
                .between(request.from, to) + 1 > MAX_RANGE_DAYS
        ) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "한 번에 등록할 수 있는 기간은 ${MAX_RANGE_DAYS}일까지입니다")
        }
        // 그 조직 사람인지 확인 — 없으면 남의 조직 사람 이름으로 우리 조직 모수를 깎을 수 있다.
        val membership =
            membershipRepository.findByOrgIdAndUserId(orgId, request.userId)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "이 조직의 구성원이 아닙니다")
        if (membership.status != "ACTIVE") {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "활성 구성원만 부재를 등록할 수 있습니다")
        }

        val weight = AbsenceType.defaultWeight(request.type)
        var created = 0
        var updated = 0
        val days = mutableListOf<LocalDate>()
        var day = request.from
        while (!day.isAfter(to)) {
            val existing = absenceRepository.findByOrgIdAndUserIdAndAbsenceDate(orgId, request.userId, day)
            if (existing == null) {
                absenceRepository.save(
                    MemberAbsence(
                        orgId = orgId,
                        userId = request.userId,
                        absenceDate = day,
                        type = request.type.name,
                        source = source,
                        weight = weight,
                    ),
                )
                created++
            } else {
                existing.type = request.type.name
                existing.weight = weight
                existing.source = source
                absenceRepository.save(existing)
                updated++
            }
            days += day
            day = day.plusDays(1)
        }
        return AbsenceUpsertResponse(created, updated, days)
    }

    /** 취소·정정. 없는 행 삭제는 성공으로 본다(멱등 — 재시도가 404 를 내면 화면이 오류를 띄운다). */
    @Transactional
    fun delete(
        orgId: UUID,
        userId: UUID,
        date: LocalDate,
    ) {
        organizationService.requireOrg(orgId)
        absenceRepository.deleteByOrgIdAndUserIdAndAbsenceDate(orgId, userId, date)
    }

    /**
     * 날짜별 요약 — 예측 화면이 "이 날 예측이 낮은 이유"를 사람 말로 설명하는 근거.
     * 예측 엔진이 쓰는 것과 **같은 질의**다(다른 숫자를 말하면 화면이 예측을 설명하지 못한다).
     */
    @Transactional(readOnly = true)
    fun summary(
        orgId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<AbsenceDaySummary> {
        organizationService.requireOrg(orgId)
        requireWindow(from, to)
        val counts =
            absenceRepository
                .findByOrgIdAndAbsenceDateBetweenOrderByAbsenceDateAscUserIdAsc(orgId, from, to)
                .groupingBy { it.absenceDate }
                .eachCount()
        return absenceRepository.sumWeightByDate(orgId, from, to).map { row ->
            val date = (row[0] as java.sql.Date).toLocalDate()
            AbsenceDaySummary(
                date = date,
                headcount = counts[date] ?: 0,
                weightedAbsent = (row[1] as Number).toDouble(),
            )
        }
    }

    private fun requireWindow(
        from: LocalDate,
        to: LocalDate,
    ) {
        if (to.isBefore(from)) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "종료일이 시작일보다 앞설 수 없습니다")
        }
        if (java.time.temporal.ChronoUnit.DAYS
                .between(from, to) > MAX_WINDOW_DAYS
        ) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "조회 구간은 ${MAX_WINDOW_DAYS}일까지입니다")
        }
    }

    private companion object {
        /** 한 번에 등록 가능한 기간 — 장기 휴직은 employment_status 로 다룰 일이라 여기서 막는다. */
        const val MAX_RANGE_DAYS = 60L

        /** 조회 창 상한(자원고갈 방어선 — 다른 org 표면과 같은 사상). */
        const val MAX_WINDOW_DAYS = 400L
    }
}
