package com.taspa.server.org.dto

import com.taspa.server.domain.org.OrgInvitation
import java.time.Instant
import java.util.UUID

/** 초대 생성 요청(관리 API). email 은 서비스에서 소문자 정규화한다. */
data class InvitationCreateRequest(
    val email: String = "",
    val role: String? = null,
    val department: String? = null,
    /** 부서 선택기로 고른 구조적 부서(departments.id). 라벨(department)보다 우선한다. */
    val departmentId: UUID? = null,
)

/**
 * CSV 대량 초대 요청 — 텍스트 붙여넣기 기반(multipart 불요, 콘솔에서 파일 선택 시 JS FileReader 가
 * 텍스트로 읽어 이 필드로 전송한다). 행 형식: `email[,role[,department]]`.
 */
data class BulkInvitationRequest(
    val csv: String = "",
)

/** 대량 초대의 행별 결과. reason 은 invite() 가 던진 AuthException 메시지를 그대로 재사용한다. */
data class BulkInvitationRowResult(
    /** CSV 원문 기준 1-base 행 번호(헤더·빈 행 포함 위치) — 사용자가 원문에서 바로 찾을 수 있게. */
    val line: Int,
    val email: String,
    /** CREATED | REJECTED */
    val status: String,
    val reason: String? = null,
    /**
     * 초대는 만들어졌지만 **의도한 대로 되지 않은** 부분.
     *
     * ★지금 유일한 용도는 "부서 이름을 못 이었다"이고, 그게 이 필드가 생긴 이유다. CSV 의 부서 열은
     * 자유 텍스트라 오타·미생성·동명 부서면 `DepartmentBinder` 가 **조용히 잇지 않는데**, 행 결과는
     * 그대로 CREATED 였다. 그러면 그 신입은 자유 텍스트 라벨만 갖고 입사해 **부서 식대 재정의를 받지
     * 못한다** — 개발팀에 18,000원을 설정해 두어도 그 사람만 12,000원이고, 화면 어디에도 이유가 없다.
     * 실패가 아니므로 REJECTED 로 만들 수는 없다(초대는 정상이다). 그래서 성공과 나란히 경고를 싣는다.
     */
    val warning: String? = null,
)

/** 대량 초대 응답 — 행별 독립 처리 결과 집계. total = created + rejected. */
data class BulkInvitationResponse(
    val total: Int,
    val created: Int,
    val rejected: Int,
    val results: List<BulkInvitationRowResult>,
)

/**
 * 초대 뷰(목록·생성 응답). **토큰/토큰해시는 절대 포함하지 않는다**(원문은 메일 링크로만 전달).
 */
data class InvitationView(
    val id: UUID,
    val orgId: UUID,
    val email: String,
    val role: String,
    val department: String?,
    /** 구조 배정 — null 이면 라벨만 있고 정책 재정의는 적용되지 않는다(화면이 그 사실을 알려야 한다). */
    val departmentId: UUID?,
    val status: String,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    companion object {
        fun from(i: OrgInvitation) =
            InvitationView(
                id = i.id!!,
                orgId = i.orgId,
                email = i.email,
                role = i.role,
                department = i.department,
                departmentId = i.departmentId,
                status = i.status,
                createdAt = i.createdAt,
                expiresAt = i.expiresAt,
            )
    }
}
