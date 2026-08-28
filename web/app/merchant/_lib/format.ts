/**
 * 가맹 콘솔의 한국어 라벨·표시 포맷·매장 달력 계산.
 *
 * 조직 콘솔(`app/console/_lib/labels.ts`)과 일부 겹치지만 의도적으로 따로 둔다: 가맹 그레인은 라벨 집합이
 * 다르고(재실 보정 방법이 존재하지 않는다), 무엇보다 **날짜 계산의 기준이 매장 타임존**이라 조직 화면의
 * "사용자 로컬" 가정을 그대로 쓰면 하루가 밀린다.
 *
 * 모르는 enum 값은 감추지 않고 원문을 그대로 보여준다 — 화면이 조용히 빈칸이 되는 것보다 낯선 코드가 낫다.
 */

const MEAL_WINDOW: Record<string, string> = {
  BREAKFAST: "조식",
  LUNCH: "중식",
  DINNER: "석식",
};

/** 표에서 항상 이 순서로 그린다(서버 enum 선언 순서와 같다 — 하루의 시간 순). */
export const MEAL_WINDOWS = ["BREAKFAST", "LUNCH", "DINNER"] as const;

/**
 * 산출 방법 라벨.
 *
 * `SEASONAL_NAIVE_ADJUSTED`(재실 인원 보정)는 **가맹 그레인에서 절대 나오지 않는다** — 매장에는 재실 인원
 * 모수가 없다. 그래도 서버 enum 에는 존재하므로, 만약 내려온다면 원문이 보이도록 표에 넣지 않는다.
 */
const FORECAST_METHOD: Record<string, string> = {
  SEASONAL_NAIVE: "전주 동요일",
  SEASONAL_NAIVE_ADJUSTED: "전주 동요일·연차 보정",
  FOUR_WEEK_AVG: "최근 4주 평균",
  // 조직 분해 합산 셀 — 조직마다 방법이 달라 하나의 이름이 성립하지 않는다(조직별 방법은 분해에 있다).
  COMPOSITE: "조직별 합산",
  NO_DATA: "데이터 없음",
};

const MERCHANT_STATUS: Record<string, string> = {
  ACTIVE: "영업중",
  SUSPENDED: "정지",
};

/** 업종 — 서버 enum 을 화면에 그대로 노출하면 한국어 화면에서 그 한 단어만 영문으로 튄다. */
const MERCHANT_CATEGORY: Record<string, string> = {
  RESTAURANT: "식당",
  CAFE: "카페",
  CONVENIENCE: "편의점",
  CAFETERIA: "구내식당",
};

const MERCHANT_ROLE: Record<string, string> = {
  MERCHANT_ADMIN: "매장 관리자",
};

const TRANSACTION_STATUS: Record<string, string> = {
  APPROVED: "승인",
  VOIDED: "취소됨",
};

function lookup(table: Record<string, string>, value: string | null | undefined): string {
  if (!value) return "—";
  return table[value] ?? value;
}

export const mealWindowLabel = (v: string | null | undefined) => lookup(MEAL_WINDOW, v);
export const forecastMethodLabel = (v: string | null | undefined) => lookup(FORECAST_METHOD, v);
export const merchantStatusLabel = (v: string | null | undefined) => lookup(MERCHANT_STATUS, v);
export const merchantRoleLabel = (v: string | null | undefined) => lookup(MERCHANT_ROLE, v);
// lookup 은 모르는 값을 원문 그대로 돌려주므로, 서버가 업종을 늘려도 빈칸이 되지 않는다.
export const merchantCategoryLabel = (v: string | null | undefined) => lookup(MERCHANT_CATEGORY, v);
export const transactionStatusLabel = (v: string | null | undefined) => lookup(TRANSACTION_STATUS, v);

// ---- 포맷 ----

const DATE_TIME_OPTIONS: Intl.DateTimeFormatOptions = {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
};

/**
 * 타임존별 포매터 캐시.
 *
 * `Intl.DateTimeFormat` 생성은 싸지 않아서 거래 표(최대 500행 × 2개 시각)에서 행마다 만들면 체감이 된다.
 * 화면 하나가 다루는 타임존은 사실상 한 개라 Map 이 무한정 커지지 않는다.
 */
const dateTimeFormatters = new Map<string, Intl.DateTimeFormat>();

function dateTimeFormatter(timezone: string | null | undefined): Intl.DateTimeFormat {
  const key = timezone ?? "";
  const cached = dateTimeFormatters.get(key);
  if (cached) return cached;

  let formatter: Intl.DateTimeFormat;
  try {
    formatter = new Intl.DateTimeFormat(
      "ko-KR",
      timezone ? { ...DATE_TIME_OPTIONS, timeZone: timezone } : DATE_TIME_OPTIONS,
    );
  } catch {
    // 알 수 없는 IANA 식별자(서버가 검증하므로 실질적으로 오지 않는다) — 던지느니 UTC 로 그린다.
    //
    // 브라우저 로컬이 아니라 UTC 인 이유: 같은 상황에서 **서버도 UTC 로 버킷팅한다**
    // (MerchantConsoleService.zoneOf). 여기서만 사용자 로컬로 그리면 표의 시각과 서버가 필터한
    // 구간이 서로 다른 기준이 되어, 조회 구간 밖 날짜가 표에 섞여 보인다. 폴백에서도 양쪽 기준을
    // 일치시키는 편이 "매장 시간"이라는 화면의 약속을 덜 어긴다.
    formatter = new Intl.DateTimeFormat("ko-KR", { ...DATE_TIME_OPTIONS, timeZone: "UTC" });
  }
  dateTimeFormatters.set(key, formatter);
  return formatter;
}

/**
 * ISO 인스턴트 → 표시 문자열. 파싱 실패 시 원문을 그대로(빈칸으로 숨기지 않는다).
 *
 * `timezone` 은 **매장 타임존**이다. 서버가 거래를 매장 달력으로 필터해 내려주므로 브라우저 로컬로 그리면
 * 조회 구간 밖 날짜가 표에 나타난다(UTC 매장을 KST 로 보면 15:00 이후가 다음 날로 밀린다). 응답 DTO 의
 * `timezone` 을 그대로 넘길 것.
 */
export function formatDateTime(iso: string | null | undefined, timezone?: string | null): string {
  if (!iso) return "—";
  const at = new Date(iso);
  return Number.isNaN(at.getTime()) ? iso : dateTimeFormatter(timezone).format(at);
}

/** yyyy-MM-dd → "2026.07.27". 로컬 타임존 재해석으로 하루가 밀지 않게 문자열을 직접 다룬다. */
export function formatDate(isoDate: string | null | undefined): string {
  if (!isoDate) return "—";
  return /^\d{4}-\d{2}-\d{2}$/.test(isoDate) ? isoDate.replaceAll("-", ".") : isoDate;
}

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];

/** yyyy-MM-dd 의 요일. 예측 표에서 "왜 이 값인지"(전주 동요일)를 읽기 쉽게 한다. */
export function weekdayOf(isoDate: string): string {
  const at = new Date(`${isoDate}T00:00:00Z`);
  return Number.isNaN(at.getTime()) ? "" : WEEKDAYS[at.getUTCDay()];
}

/** 주말 강조용 — 매장 수요가 평일과 크게 다른 날을 표에서 구분한다. */
export function isWeekend(isoDate: string): boolean {
  const day = weekdayOf(isoDate);
  return day === "토" || day === "일";
}

export function formatCount(value: number | null | undefined): string {
  if (value === null || value === undefined) return "—";
  return value.toLocaleString("ko-KR");
}

/** 금액은 minor 단위 정수(원). */
export function formatWon(minor: number | null | undefined): string {
  if (minor === null || minor === undefined) return "—";
  return `${minor.toLocaleString("ko-KR")}원`;
}

/** 0.1234 → "12.3%". null 은 "—"(0% 와 구분한다). */
export function formatRatio(value: number | null | undefined): string {
  if (value === null || value === undefined) return "—";
  return `${(value * 100).toFixed(1)}%`;
}

/** +12 / -3 형태의 오차 표시. */
export function formatDelta(value: number | null): string {
  if (value === null) return "—";
  return value > 0 ? `+${formatCount(value)}` : formatCount(value);
}

// ---- 매장 달력 ----

/**
 * **매장 타임존 기준 오늘**(yyyy-MM-dd).
 *
 * 사용자의 브라우저 타임존이 아니라 매장 타임존으로 물어야 한다 — 서버가 모든 날짜 버킷을
 * `merchant.timezone` 으로 앵커하므로, 브라우저 로컬로 계산한 "내일"은 매장의 "내일"과 다를 수 있다.
 * 타임존을 아직 모르면 null 을 돌려주고, 호출부는 파라미터 없이 서버 기본값(매장-로컬 앵커)에 맡긴다.
 */
export function todayIn(timezone: string | null | undefined): string | null {
  if (!timezone) return null;
  try {
    // en-CA 로케일은 yyyy-MM-dd 를 만든다(직접 조립하면 자릿수 패딩을 놓치기 쉽다).
    return new Intl.DateTimeFormat("en-CA", {
      timeZone: timezone,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    }).format(new Date());
  } catch {
    return null;
  }
}

/** yyyy-MM-dd 에 일수를 더한다. UTC 자정 기준 순수 달력 연산이라 DST 로 흔들리지 않는다. */
export function addDays(isoDate: string, days: number): string {
  const at = new Date(`${isoDate}T00:00:00Z`);
  if (Number.isNaN(at.getTime())) return isoDate;
  at.setUTCDate(at.getUTCDate() + days);
  return at.toISOString().slice(0, 10);
}

/** 두 날짜 사이 일수(양끝 포함). 입력이 잘못되면 null. */
export function daysBetween(from: string, to: string): number | null {
  const start = Date.parse(`${from}T00:00:00Z`);
  const end = Date.parse(`${to}T00:00:00Z`);
  if (Number.isNaN(start) || Number.isNaN(end)) return null;
  return Math.floor((end - start) / 86_400_000) + 1;
}

/** from/to 를 쿼리스트링으로. 값이 없으면 **보내지 않는다**(서버가 매장-로컬 기본값을 정한다). */
export function rangeQuery(params: Record<string, string | number | null | undefined>): string {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== null && value !== undefined && value !== "") query.set(key, String(value));
  }
  const encoded = query.toString();
  return encoded ? `?${encoded}` : "";
}
