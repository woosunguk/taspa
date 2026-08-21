/**
 * 조직 달력 — **조직 타임존이 기준**이다.
 *
 * 서버는 소비 집계·청구서 기간을 전부 `organizations.timezone` 으로 앵커한다(V18). 브라우저 로컬로
 * "이번 달"을 만들면 월초·월말 거래가 옆 달로 새어, 화면 숫자와 청구서가 어긋난다. 그래서 이 파일의
 * 모든 함수는 타임존을 **인자로 받고**, 모르면 계산을 강행하지 않고 `null` 을 돌려준다
 * (플랫폼 관리자가 `/api/orgs/mine` 에 없는 조직으로 직접 들어온 경우가 실제 경로다).
 *
 * 가맹 콘솔의 `app/merchant/_lib/format.ts` 와 같은 사상이지만 기준 타임존이 다르므로(매장 vs 조직)
 * 공용 헬퍼로 합치지 않는다 — 합치면 서로 다른 앵커가 한 함수에 섞인다.
 */

const PART_OPTIONS: Intl.DateTimeFormatOptions = {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
  // h23 을 명시하지 않으면 자정이 "24" 로 나오는 로케일이 있어 하루가 밀린다.
  hourCycle: "h23",
};

const formatters = new Map<string, Intl.DateTimeFormat | null>();

/** 타임존별 포매터. 알 수 없는 IANA 식별자면 null 을 캐시해 호출부가 "계산 불가"로 분기하게 한다. */
function formatterFor(timezone: string): Intl.DateTimeFormat | null {
  if (formatters.has(timezone)) return formatters.get(timezone) ?? null;
  let formatter: Intl.DateTimeFormat | null;
  try {
    formatter = new Intl.DateTimeFormat("en-CA", { ...PART_OPTIONS, timeZone: timezone });
  } catch {
    formatter = null;
  }
  formatters.set(timezone, formatter);
  return formatter;
}

interface ZonedParts {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
  second: number;
}

function partsOf(timezone: string, at: Date): ZonedParts | null {
  const formatter = formatterFor(timezone);
  if (!formatter) return null;
  const map: Record<string, string> = {};
  for (const part of formatter.formatToParts(at)) {
    if (part.type !== "literal") map[part.type] = part.value;
  }
  const parsed = {
    year: Number(map.year),
    month: Number(map.month),
    day: Number(map.day),
    hour: Number(map.hour),
    minute: Number(map.minute),
    second: Number(map.second),
  };
  return Object.values(parsed).some(Number.isNaN) ? null : parsed;
}

/** 해당 시점의 타임존 오프셋(ms). 초 미만은 잘라 비교한다(포매터가 초까지만 준다). */
function offsetMs(timezone: string, at: Date): number | null {
  const parts = partsOf(timezone, at);
  if (!parts) return null;
  const asUtc = Date.UTC(parts.year, parts.month - 1, parts.day, parts.hour, parts.minute, parts.second);
  return asUtc - Math.floor(at.getTime() / 1000) * 1000;
}

/**
 * 조직 로컬 벽시계 `year-month-day 00:00:00` 에 해당하는 절대 시각(ISO 인스턴트).
 *
 * 오프셋을 한 번 빼면 DST 경계에서 최대 1시간 어긋나므로, 추정한 시각의 오프셋으로 한 번 더 보정한다.
 */
function startOfDayInstant(timezone: string, year: number, month: number, day: number): string | null {
  const naive = Date.UTC(year, month - 1, day, 0, 0, 0);
  const first = offsetMs(timezone, new Date(naive));
  if (first === null) return null;
  const second = offsetMs(timezone, new Date(naive - first));
  if (second === null) return null;
  return new Date(naive - second).toISOString();
}

/** 조직 타임존 기준 현재 달 'YYYY-MM'. 타임존을 모르면 null(추측하지 않는다). */
export function currentMonthIn(timezone: string | null | undefined): string | null {
  if (!timezone) return null;
  const parts = partsOf(timezone, new Date());
  if (!parts) return null;
  return `${parts.year}-${String(parts.month).padStart(2, "0")}`;
}

/**
 * 어떤 시각이 **조직 달력**으로 몇 월인가 — 'YYYY-MM'.
 *
 * ★UTC 문자열을 그대로 잘라 쓰면(`iso.slice(0, 7)`) 월 경계 근처에서 한 달이 어긋난다.
 * 예: 조직 타임존이 Asia/Seoul 이고 생성 시각이 `2026-06-30T16:30:00Z` 이면 조직 달력으로는
 * **7월 1일**인데 UTC 절단은 `2026-06` 을 준다 — "조직이 존재하지도 않던 달"의 판정이 뒤집힌다.
 * 타임존을 모르면 null(추측하지 않는다 — 이 파일의 다른 함수와 같은 규약).
 */
export function monthOfInstantIn(
  timezone: string | null | undefined,
  iso: string | null | undefined,
): string | null {
  if (!timezone || !iso) return null;
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return null;
  const parts = partsOf(timezone, at);
  if (!parts) return null;
  return `${parts.year}-${String(parts.month).padStart(2, "0")}`;
}

/** 'YYYY-MM' 의 이전 달. */
export function previousMonth(month: string): string | null {
  const parsed = parseMonth(month);
  if (!parsed) return null;
  const at = new Date(Date.UTC(parsed.year, parsed.month - 2, 1));
  return `${at.getUTCFullYear()}-${String(at.getUTCMonth() + 1).padStart(2, "0")}`;
}

function parseMonth(month: string): { year: number; month: number } | null {
  const matched = /^(\d{4})-(\d{2})$/.exec(month);
  if (!matched) return null;
  const year = Number(matched[1]);
  const value = Number(matched[2]);
  if (value < 1 || value > 12) return null;
  return { year, month: value };
}

/**
 * 조직 타임존 기준 그 달의 시작 인스턴트. 소비 집계 API 의 `from` 은 인스턴트라, 여기서 조직 달력의
 * 월초를 절대 시각으로 옮겨야 date 버킷(서버가 org 타임존으로 자른다)과 조회 창의 기준이 일치한다.
 */
export function monthStartInstant(timezone: string | null | undefined, month: string): string | null {
  if (!timezone) return null;
  const parsed = parseMonth(month);
  if (!parsed) return null;
  return startOfDayInstant(timezone, parsed.year, parsed.month, 1);
}

/** 'YYYY-MM' → "2026년 7월". 숫자만 있는 화면은 무슨 기간인지 알 수 없다. */
export function monthLabel(month: string | null | undefined): string {
  if (!month) return "—";
  const parsed = parseMonth(month);
  return parsed ? `${parsed.year}년 ${parsed.month}월` : month;
}

/** 조직 타임존으로 그린 날짜·시각. 타임존을 모르면 사용자 로컬로 그린다(그 사실을 화면이 말한다). */
export function formatInZone(iso: string | null | undefined, timezone: string | null | undefined): string {
  if (!iso) return "—";
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return iso;
  try {
    return new Intl.DateTimeFormat("ko-KR", {
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      ...(timezone ? { timeZone: timezone } : {}),
    }).format(at);
  } catch {
    return at.toISOString();
  }
}

/**
 * 증감률. 이전 값이 0 이면 비율이 정의되지 않으므로 null 을 돌려준다 — 0 에서 늘어난 것을 "+∞%"나
 * "+100%"로 적으면 거짓말이 된다.
 */
export function changeRatio(current: number, previous: number): number | null {
  if (previous === 0) return null;
  return (current - previous) / previous;
}
