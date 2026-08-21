/**
 * 식권 화면이 쓰는 서버 계약 미러 + 표시 규칙.
 *
 * 필드 이름은 서버 DTO 와 1:1 이다(추측 금지 — 이름이 어긋나면 런타임에 조용히 undefined 가 된다):
 *  - `MyMembership`  ← `org/dto/OrgDtos.kt` 의 `MyMembershipView`
 *  - `MealQrIssue`   ← `meal/dto/MealDtos.kt` 의 `MealQrIssueResponse`
 *  - `MealTransaction` ← `meal/dto/MealDtos.kt` 의 `MealTransactionView`
 *
 * Instant 는 Jackson 기본 설정(타임스탬프 비활성)으로 ISO-8601 문자열로 직렬화된다.
 */

/** GET /api/orgs/memberships — 내가 소속된 활성 조직. */
export interface MyMembership {
  orgId: string;
  orgName: string;
  orgSlug: string;
  role: string;
  department: string | null;
  joinedAt: string;
}

/** POST /api/meal/qr — token 원문이 QR 페이로드다(응답에만 존재, 서버는 해시만 저장). */
export interface MealQrIssue {
  token: string;
  expiresAt: string;
}

/** GET /api/meal/transactions — 본인 거래 이력 1행. 금액 단위는 minor(KRW 원). */
export interface MealTransaction {
  authId: string;
  /** 어느 조직 식대였나. 한도·집계가 전부 (사용자 × 조직) 단위라 조직 구분 없이는 합산이 성립하지 않는다. */
  orgId: string;
  merchantName: string | null;
  amountMinor: number;
  selfPaidMinor: number;
  mealWindow: string;
  status: string;
  approvedAt: string;
  voidedAt: string | null;

  /**
   * 환불 누계와 그중 내가 돌려받은 금액, 그리고 환불 전 원금.
   *
   * ★부분 환불은 `amountMinor`·`selfPaidMinor` 를 **소급 변경**한다(서버가 기존 집계를 그대로 쓰기 위해
   * 택한 설계). 그래서 이 필드들이 없으면 15,000원을 쓰고 3,000원을 돌려받은 사람의 이력에 12,000원만
   * 남아, 영수증과 다른 숫자를 화면이 설명하지 못한다.
   */
  refundedMinor: number;
  selfRefundedMinor: number;
  originalAmountMinor: number;
  lastRefundedAt: string | null;
}

/** 끼니창 1회차 — `MealWindowView`. start/end 는 org 로컬 벽시계("11:30"), *At 은 절대 시각. */
export interface MealWindowSlot {
  window: string;
  start: string;
  end: string;
  startsAt: string;
  endsAt: string;
}

/**
 * GET /api/meal/entitlement?orgId= — `MealEntitlementView`.
 *
 * ★이 값은 화면이 계산하는 것이 아니라 **서버가 승인 시 쓰는 계산 그대로**다. 여기 있는 숫자를
 * 프런트에서 다시 만들어 내면(월 경계를 기기 달력으로 자르는 등) 화면은 "가능"인데 계산대에서
 * 거절되는 상황이 생긴다. 그러니 이 인터페이스의 값은 가공하지 말고 그대로 표시할 것.
 */
export interface MealEntitlement {
  orgId: string;
  orgName: string;
  /** 모든 경계 판정의 앵커가 된 조직 타임존(IANA). 화면은 이 존으로 시각을 포맷한다. */
  timezone: string;
  serverNow: string;

  /** 지금 열려 있는 끼니창. null 이면 지금 결제는 거절된다(MEAL_WINDOW_CLOSED). */
  currentWindow: MealWindowSlot | null;
  nextWindow: MealWindowSlot | null;

  perMealLimitMinor: number;
  dailyMealCount: number;
  todayApprovedCount: number;
  dailyRemaining: number;

  monthlyCapMinor: number;
  monthOrgPaidMinor: number;
  monthSelfPaidMinor: number;
  monthRemainingMinor: number;
  monthApprovedCount: number;

  periodStart: string;
  periodEnd: string;
  dayStart: string;
  dayEnd: string;

  /** 끼니창 열림 AND 일 횟수 잔여. 월 cap 소진은 포함하지 않는다(초과분은 개인부담으로 승인된다). */
  canIssueNow: boolean;

  /**
   * 각 값의 출처(`CODE_DEFAULT` | `ORG` | `SITE` | `DEPARTMENT`). **표시 전용**이다.
   *
   * 지금은 조직 단위 기준뿐이라 화면에서 큰 의미가 없지만, 부서별 기준이 붙으면 "왜 내 한도가
   * 옆자리와 다른가"가 가장 흔한 질문이 된다. 그때 직원이 스스로 답을 볼 수 있는 자리다.
   */
  perMealLimitSource?: string | null;
  dailyMealCountSource?: string | null;
  monthlyCapSource?: string | null;
  windowSource?: string | null;
}

/** 서버 enum(domain/consumption/ConsumptionEnums.kt) 라벨. 모르는 값은 원문 그대로 — 숨기지 않는다. */
const MEAL_WINDOW_LABEL: Record<string, string> = {
  BREAKFAST: "아침",
  LUNCH: "점심",
  DINNER: "저녁",
};

export function mealWindowLabel(value: string): string {
  return MEAL_WINDOW_LABEL[value] ?? value;
}

/** 거래 상태(domain/meal/MealEnums.kt) — 폐쇄루프 장부라 취소는 VOIDED 하나로 일원화돼 있다. */
export function statusLabel(value: string): string {
  if (value === "APPROVED") return "승인";
  if (value === "VOIDED") return "취소됨";
  return value;
}

/** 금액은 KRW 원 단위 정수다(minor = 원). 자릿수 구분만 붙인다. */
export function formatWon(minor: number): string {
  return `${minor.toLocaleString("ko-KR")}원`;
}

const DATE_TIME = new Intl.DateTimeFormat("ko-KR", {
  month: "long",
  day: "numeric",
  hour: "2-digit",
  minute: "2-digit",
});

export function formatDateTime(iso: string): string {
  const ms = Date.parse(iso);
  return Number.isFinite(ms) ? DATE_TIME.format(new Date(ms)) : iso;
}

/**
 * 조직 타임존 기준 시각 표기(예: "내일 06:00").
 *
 * 기기 타임존으로 포맷하면 안 된다 — 끼니창은 조직 달력으로 판정되므로, 출장·해외 근무처럼 기기와
 * 조직 타임존이 다를 때 화면이 정책과 다른 시각을 말하게 된다. 존 이름이 유효하지 않으면 기기
 * 기준으로 물러난다(표시가 사라지는 것보다 낫다).
 */
export function formatTimeInZone(iso: string, timeZone: string): string {
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) return iso;
  try {
    return new Intl.DateTimeFormat("ko-KR", {
      timeZone,
      month: "numeric",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
    }).format(new Date(ms));
  } catch {
    return formatDateTime(iso);
  }
}

/** "점심 10:30~15:00" — 정책 창을 조직 벽시계 그대로 읽어 준다(재계산 없음). */
export function windowRangeLabel(slot: { window: string; start: string; end: string }): string {
  return `${mealWindowLabel(slot.window)} ${trimSeconds(slot.start)}~${trimSeconds(slot.end)}`;
}

/** 서버는 LocalTime.toString() 을 준다("10:30" 또는 "10:30:30"). 분 단위까지만 보여 준다. */
function trimSeconds(time: string): string {
  const parts = time.split(":");
  return parts.length >= 2 ? `${parts[0]}:${parts[1]}` : time;
}

/** 조직 부담액 — 서버는 총액과 개인부담만 주므로 차액이 조직 부담(청구 대상)이다. */
export function orgPaidMinor(transaction: MealTransaction): number {
  return Math.max(0, transaction.amountMinor - transaction.selfPaidMinor);
}
