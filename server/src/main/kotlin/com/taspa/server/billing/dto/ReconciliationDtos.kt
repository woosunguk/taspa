package com.taspa.server.billing.dto

import java.time.Instant
import java.util.UUID

/** 대사 한 축의 값. `kind` 로 금액과 건수를 구분한다(화면이 단위를 지어내지 않게). */
data class ReconciliationLeg(
    val name: String,
    /** AMOUNT | COUNT */
    val kind: String,
    val value: Long,
)

/**
 * 3-way 대사 결과.
 *
 * ★drift 가 0 이 아닌 것은 "설명이 필요한 차이"가 아니라 **버그의 직접 증거**다. 세 기록은 같은
 * 트랜잭션에서 쓰이므로 정상 동작에서는 갈라질 수 없다. 그래서 화면은 이 값을 참고 지표가 아니라
 * 경보로 다뤄야 한다.
 */
data class ReconciliationReport(
    val orgId: UUID,
    val period: String,
    val timezone: String,
    val periodStart: Instant,
    val periodEnd: Instant,
    val legs: List<ReconciliationLeg>,
    /** 원장 미수금 − 장부 조직부담. 0 이어야 한다. */
    val amountDrift: Long,
    /** 장부 승인 건수 − 소비 이벤트 건수. 0 이어야 한다. */
    val countDrift: Long,
    /** 분개 합이 0 이 아닌 사건 수. 0 이 아니면 원장 자체가 깨진 것이라 잔액도 못 믿는다. */
    val unbalancedEntryCount: Int,
    /** 미수금 + 미지급금. 플랫폼은 통과 지점이므로 0 이어야 한다. */
    val passThroughDrift: Long,
) {
    /** 화면·경보가 한 값으로 판단할 수 있게 — 네 지표 중 하나라도 어긋나면 불일치다. */
    val balanced: Boolean
        get() = amountDrift == 0L && countDrift == 0L && unbalancedEntryCount == 0 && passThroughDrift == 0L
}

/**
 * 플랫폼 전역 대사 요약 한 줄. 조직 하나의 [ReconciliationReport] 를 운영자 시선으로 압축한 것.
 *
 * 조직 이름을 함께 싣는 이유: 운영자는 UUID 로 조직을 알아보지 못한다. 불일치를 발견해도 "어느
 * 회사인지" 를 다시 찾아야 하면 대응이 한 단계 늦어진다.
 */
data class ReconciliationSummary(
    val orgId: java.util.UUID,
    val orgName: String,
    val timezone: String,
    val period: String,
    val balanced: Boolean,
    val amountDrift: Long,
    val countDrift: Long,
    val unbalancedEntryCount: Int,
    val passThroughDrift: Long,
)

/**
 * 전역 대사 결과.
 *
 * ★`scanned` 를 함께 내려 보내는 이유: "불일치 0건"이 **아무것도 안 봤다**는 뜻일 수도 있기 때문이다.
 * 활동이 없어 훑을 조직이 0개였던 것과, 100개를 훑어 전부 정상이었던 것은 운영자에게 전혀 다른 사실이다.
 */
data class PlatformReconciliationView(
    val period: String,
    /** 이 기간에 활동이 있어 대사를 **시도한** 조직 수. */
    val scanned: Int,
    /** 불일치가 있는 조직만. 정상 조직은 싣지 않는다(경보 화면은 이상만 보여야 눈에 띈다). */
    val unbalanced: List<ReconciliationSummary>,
    /** 상한에 걸려 검사하지 못한 조직 수 — 0 이 아니면 "이상 없음"을 믿으면 안 된다. */
    val skipped: Int,
    /**
     * 시도했으나 **대사에 실패한** 조직 수(결과를 모른다).
     *
     * ★이 값이 없으면 일시적 DB 오류로 검사에 실패한 조직이 `unbalanced` 에서 조용히 빠지고, 화면은
     * "N개 조직을 대사했고 모두 일치합니다"라고 단언한다 — **불일치가 있는 조직일수록 사라진다.**
     * 경보 화면의 목적이 그 지점에서 정확히 뒤집힌다.
     */
    val failed: Int,
)

/**
 * 확정되지 않은 청구서 한 줄.
 *
 * `state` 는 셋이다:
 *  - `DRAFT` — 초안은 있는데 조직이 아직 확정하지 않았다.
 *  - `MISSING` — 그 달에 청구할 거래가 **있는데 청구서 행 자체가 없다**. 자동 생성 잡이 실패했다는
 *    뜻이고 DRAFT 보다 심각하다.
 *  - `PENDING` — 아직 **만들 시점이 아니다**(유예 기간, `InvoiceGraceWindow`). 정상 상태라 경보가
 *    아니지만, 목록에서 빼 버리면 "전부 확정됨"이라는 거짓 완결 선언이 되므로 따로 말한다.
 */
data class UnfinalizedInvoiceLine(
    val orgId: UUID,
    val orgName: String,
    val timezone: String,
    /** DRAFT | MISSING | PENDING */
    val state: String,
    val subtotalMinor: Long?,
    val txnCount: Int?,
    val generatedAt: Instant?,
)

/**
 * 미확정 청구서 현황 — **자동 생성 루프의 마지막 구멍**을 메운다.
 *
 * 초안은 자동으로 만들어지고 조직관리자에게 메일까지 나가지만, 그 사람이 확정하지 않으면 청구서는
 * 그대로 방치된다. 그러면 회사가 쓴 식대를 우리가 **끝내 청구하지 않고**, 그 사실을 아무도 모른다 —
 * 자동 생성 잡이 막으려던 바로 그 사고(알람이 울리지 않는 매출 누락)가 한 단계 뒤에서 반복된다.
 *
 * `scanned` 를 함께 낸다: 0건이 "다 확정됐다"인지 "**아무것도 안 봤다**"인지 구별되지 않으면 이 화면은
 * 안심시키는 역할만 하고 추적 도구가 되지 못한다(전역 대사·지급 현황과 같은 이유).
 */
data class UnfinalizedInvoicesView(
    val period: String,
    /** 훑기를 **시도한** 조직 수. */
    val scanned: Int,
    /** 상한에 걸려 시도조차 못 한 수. */
    val skipped: Int,
    /**
     * 시도했으나 **판정에 실패한** 수(결과를 모른다).
     *
     * ★0 이 아니면 아래 목록을 "전부"로 읽으면 안 된다. 실패를 조용히 삼키면 '이상 없음'과 '검사 실패'가
     * 구별되지 않고, 이 화면이 존재하는 이유(누락을 드러낸다)가 정확히 뒤집힌다.
     */
    val failed: Int,
    val lines: List<UnfinalizedInvoiceLine>,
)
