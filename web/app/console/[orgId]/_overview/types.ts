/**
 * 개요(대시보드) 탭이 추가로 쓰는 서버 응답 타입.
 *
 * 공용 타입은 `app/console/_lib/types.ts` 에 있고, 여기엔 소비 집계처럼 개요 탭만 쓰는 것만 둔다.
 * `server/src/main/kotlin/com/taspa/server/consumption/dto/ConsumptionDtos.kt` 와 1:1 이다.
 */

/**
 * 집계 1행 — date × meal_window[× menu].
 *
 * `date` 는 **조직 타임존으로 자른 날짜**다(V18). 서버가 그렇게 버킷팅하므로 화면에서 다시 로컬로
 * 재해석하면 안 된다(문자열 그대로 쓴다).
 */
export interface ConsumptionAggregateRow {
  date: string;
  mealWindow: string;
  menuRef: string | null;
  /** CONFIRMED 이벤트 건수(VOIDED 제외). */
  count: number;
  /** 수량 합. 한 이벤트가 2인분일 수 있어 건수와 다르다. */
  quantity: number;
}

/**
 * 집계 응답. **개별 이벤트·user_sub 는 절대 포함되지 않는다** — 서버가 집계만 노출하는 설계이며,
 * 화면도 그 경계를 넘는 표시를 만들지 않는다.
 */
export interface ConsumptionAggregateResponse {
  orgId: string;
  /** 실효 조회 창(ISO 인스턴트). 서버가 상한으로 절단했을 수 있어 **응답 값을 표시 기준으로 쓴다**. */
  from: string;
  to: string;
  groupBy: string[];
  rows: ConsumptionAggregateRow[];
}

/* ---- 식대 집계(GET /api/orgs/{orgId}/spend) ----
   `server/.../billing/dto/SpendDtos.kt` 와 1:1. 청구서를 만들지 않고 진행 중인 달의 금액을 돌려준다. */

/** 부서별 조직부담 소계. 부서명은 **현재 멤버십 기준**(청구서 라인은 생성 시점 스냅샷이라 다를 수 있다). */
export interface DepartmentSpend {
  departmentId: string | null;
  departmentName: string | null;
  txnCount: number;
  orgPaidMinor: number;
}

/** 전월 **동기간**(이번 달과 같은 경과 구간까지만 자른 비교 대상). */
export interface PreviousSpend {
  period: string;
  periodStart: string;
  periodEnd: string;
  orgPaidMinor: number;
  txnCount: number;
  /** 전월이 0 이면 비율이 정의되지 않아 null 이다. */
  changeRatio: number | null;
}

/** 같은 period 의 청구서 상태. 아직 생성 전이면 응답에서 통째로 null. */
export interface SpendInvoice {
  id: string;
  status: string;
  subtotalMinor: number;
  generatedAt: string;
  finalizedAt: string | null;
}

/**
 * 식대 집계 응답.
 *
 * ★기간 경계는 **서버가 org 타임존으로 계산해 내려준다**(periodStart/periodEnd). 화면에서 다시 계산하지
 * 않는다 — 청구서와 같은 창임을 보증하는 것이 이 API 의 존재 이유이고, 브라우저 달력으로 재해석하는 순간
 * 그 보증이 사라진다. 개인별 라인은 응답에 없다(정산 문서의 몫).
 */
export interface OrgSpend {
  orgId: string;
  period: string;
  timezone: string;
  periodStart: string;
  periodEnd: string;
  asOf: string;
  inProgress: boolean;
  orgPaidMinor: number;
  selfPaidMinor: number;
  txnCount: number;
  departments: DepartmentSpend[];
  previous: PreviousSpend | null;
  invoice: SpendInvoice | null;
}
