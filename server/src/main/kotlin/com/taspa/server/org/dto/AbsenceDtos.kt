package com.taspa.server.org.dto

import com.taspa.server.domain.org.AbsenceType
import java.time.LocalDate
import java.util.UUID

/**
 * 부재 한 건. [weight] 는 **그날 결식 인원 기여도**다 — 종일 부재 1.00, 반차 0.50.
 * 유형에서 파생되므로 요청에는 없다(반차만 0.50, 나머지 1.00). 클라이언트가 정하게 하면
 * "출장인데 0.1" 같은 값이 들어와 재실 모수가 조용히 왜곡된다.
 */
data class AbsenceView(
    val id: UUID,
    val userId: UUID,
    val email: String?,
    val displayName: String?,
    val absenceDate: LocalDate,
    val type: AbsenceType,
    val source: String,
    val weight: Double,
)

data class AbsenceUpsertRequest(
    val userId: UUID,
    val from: LocalDate,
    /** 미전송이면 [from] 하루만. 연차 3일은 from/to 로 한 번에 넣는다. */
    val to: LocalDate? = null,
    val type: AbsenceType,
)

/**
 * 기간 등록 결과. **주말·공휴일을 서버가 걸러내지 않는다** — 그 판정은 조직 캘린더의 몫이고
 * 부재 기록은 "신청된 사실"이다. 예측은 배식하지 않는 날을 이미 다른 경로로 제외한다.
 */
data class AbsenceUpsertResponse(
    val created: Int,
    val updated: Int,
    val days: List<LocalDate>,
)

/** 날짜별 부재 인원 요약 — 예측 화면이 "왜 이 날 예측이 낮은가"를 설명하는 근거. */
data class AbsenceDaySummary(
    val date: LocalDate,
    val headcount: Int,
    val weightedAbsent: Double,
)
