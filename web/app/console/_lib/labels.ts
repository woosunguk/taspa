/**
 * 콘솔의 한국어 라벨과 표시 포맷.
 *
 * 서버는 enum 이름(영문 대문자)을 그대로 내려준다. 화면에서 그걸 그대로 보여주면 사용자가 읽을 수 없으므로
 * 여기서 한 번만 번역한다. **모르는 값은 감추지 않고 원문을 그대로 보여준다** — 서버가 새 enum 값을 추가했을 때
 * 화면이 조용히 빈칸이 되는 것보다 낯선 코드가 보이는 게 낫다(디버깅 가능한 실패).
 */

const ROLE: Record<string, string> = {
  MEMBER: "구성원",
  ORG_ADMIN: "조직관리자",
};

const MEMBERSHIP_STATUS: Record<string, string> = {
  ACTIVE: "활성",
  SUSPENDED: "정지",
};

const EMPLOYMENT_STATUS: Record<string, string> = {
  EMPLOYED: "재직",
  ON_LEAVE: "휴직",
  TERMINATED: "퇴직",
};

const EMPLOYMENT_TYPE: Record<string, string> = {
  FULL_TIME: "정규직",
  PART_TIME: "시간제",
  CONTRACT: "계약직",
  INTERN: "인턴",
  UNSPECIFIED: "미지정",
};

const INVITATION_STATUS: Record<string, string> = {
  PENDING: "대기",
  ACCEPTED: "수락됨",
  REVOKED: "취소됨",
  EXPIRED: "만료됨",
};

const CHANGE_TYPE: Record<string, string> = {
  JOINED: "합류",
  ROLE_CHANGED: "역할 변경",
  ASSIGNED: "배정 변경",
  ATTRIBUTES_UPDATED: "속성 변경",
  REMOVED: "제거",
};

const MEAL_WINDOW: Record<string, string> = {
  BREAKFAST: "조식",
  LUNCH: "중식",
  DINNER: "석식",
};

const FORECAST_METHOD: Record<string, string> = {
  SEASONAL_NAIVE_ADJUSTED: "전주 동요일 + 재실 보정",
  SEASONAL_NAIVE: "전주 동요일",
  FOUR_WEEK_AVG: "최근 4주 평균",
  NO_DATA: "데이터 없음",
};

const INVOICE_STATUS: Record<string, string> = {
  DRAFT: "초안",
  FINALIZED: "확정",
};

const ORG_STATUS: Record<string, string> = {
  ACTIVE: "활성",
  SUSPENDED: "정지",
};

/** 활동로그 이벤트 유형 — 서버 audit type 문자열 기준. */
const AUDIT_TYPE: Record<string, string> = {
  ADMIN_ORG_CREATED: "조직 생성",
  ADMIN_ORG_UPDATED: "조직 정보 변경",
  ADMIN_ORG_MEMBER_UPSERTED: "구성원 추가·수정",
  ADMIN_ORG_MEMBER_ROLE_CHANGED: "구성원 역할 변경",
  ADMIN_ORG_MEMBER_REMOVED: "구성원 제거",
  ADMIN_ORG_MEMBER_ASSIGNED: "구성원 배정 변경",
  ADMIN_ORG_MEMBER_ATTRS_UPDATED: "구성원 속성 변경",
  ADMIN_ORG_DEPARTMENT_CREATED: "부서 생성",
  ADMIN_ORG_DEPARTMENT_RENAMED: "부서 이름 변경",
  ADMIN_ORG_DEPARTMENT_DELETED: "부서 삭제",
  ADMIN_ORG_SITE_CREATED: "사업장 생성",
  ADMIN_ORG_SITE_UPDATED: "사업장 수정",
  ADMIN_ORG_SITE_DELETED: "사업장 삭제",
  ADMIN_ORG_SSO_LINKED: "기업 SSO 연결",
  ORG_INVITE_CREATED: "초대 발송",
  ORG_INVITE_BULK: "대량 초대",
  ORG_INVITE_RESENT: "초대 재발송",
  ORG_INVITE_REVOKED: "초대 취소",
  ORG_INVITE_ACCEPTED: "초대 수락",
  ORG_DOMAIN_ADDED: "도메인 등록",
  ORG_DOMAIN_VERIFIED: "도메인 검증",
  ORG_DOMAIN_FORCE_VERIFIED: "도메인 강제 검증",
  ORG_DOMAIN_UNVERIFIED: "도메인 검증 해제",
  ORG_DOMAIN_REMOVED: "도메인 삭제",
  ORG_AUTO_JOIN_TOGGLED: "자동 가입 설정 변경",
  ORG_AUTO_JOINED: "도메인 자동 가입",
  ORG_JIT_MEMBERSHIP_CREATED: "SSO 자동 소속",
  INVOICE_GENERATED: "청구서 생성",
  INVOICE_FINALIZED: "청구서 확정",
};

function lookup(table: Record<string, string>, value: string | null | undefined): string {
  if (!value) return "—";
  return table[value] ?? value;
}

export const roleLabel = (v: string | null | undefined) => lookup(ROLE, v);
export const membershipStatusLabel = (v: string | null | undefined) => lookup(MEMBERSHIP_STATUS, v);
export const employmentStatusLabel = (v: string | null | undefined) => lookup(EMPLOYMENT_STATUS, v);
export const employmentTypeLabel = (v: string | null | undefined) => lookup(EMPLOYMENT_TYPE, v);
export const invitationStatusLabel = (v: string | null | undefined) => lookup(INVITATION_STATUS, v);
export const changeTypeLabel = (v: string | null | undefined) => lookup(CHANGE_TYPE, v);
export const mealWindowLabel = (v: string | null | undefined) => lookup(MEAL_WINDOW, v);
export const forecastMethodLabel = (v: string | null | undefined) => lookup(FORECAST_METHOD, v);
export const invoiceStatusLabel = (v: string | null | undefined) => lookup(INVOICE_STATUS, v);
export const orgStatusLabel = (v: string | null | undefined) => lookup(ORG_STATUS, v);
export const auditTypeLabel = (v: string | null | undefined) => lookup(AUDIT_TYPE, v);

export const ROLE_OPTIONS: { value: string; label: string }[] = [
  { value: "MEMBER", label: ROLE.MEMBER },
  { value: "ORG_ADMIN", label: ROLE.ORG_ADMIN },
];

export const EMPLOYMENT_STATUS_OPTIONS: { value: string; label: string }[] = [
  { value: "EMPLOYED", label: EMPLOYMENT_STATUS.EMPLOYED },
  { value: "ON_LEAVE", label: EMPLOYMENT_STATUS.ON_LEAVE },
  { value: "TERMINATED", label: EMPLOYMENT_STATUS.TERMINATED },
];

export const EMPLOYMENT_TYPE_OPTIONS: { value: string; label: string }[] = [
  { value: "FULL_TIME", label: EMPLOYMENT_TYPE.FULL_TIME },
  { value: "PART_TIME", label: EMPLOYMENT_TYPE.PART_TIME },
  { value: "CONTRACT", label: EMPLOYMENT_TYPE.CONTRACT },
  { value: "INTERN", label: EMPLOYMENT_TYPE.INTERN },
];

export const MEAL_WINDOW_OPTIONS: { value: string; label: string }[] = [
  { value: "BREAKFAST", label: MEAL_WINDOW.BREAKFAST },
  { value: "LUNCH", label: MEAL_WINDOW.LUNCH },
  { value: "DINNER", label: MEAL_WINDOW.DINNER },
];

// ---- 포맷 ----

const DATE_TIME = new Intl.DateTimeFormat("ko-KR", {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
});

const DATE_ONLY = new Intl.DateTimeFormat("ko-KR", {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});

/** ISO 인스턴트 → 사용자 로컬 시각. 파싱 실패 시 원문을 그대로 보여준다(빈칸으로 숨기지 않는다). */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const at = new Date(iso);
  return Number.isNaN(at.getTime()) ? iso : DATE_TIME.format(at);
}

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  // yyyy-MM-dd 는 UTC 자정으로 파싱돼 로컬 타임존에서 하루 밀릴 수 있어 직접 자른다.
  const plain = /^\d{4}-\d{2}-\d{2}$/.exec(iso);
  if (plain) return iso.replaceAll("-", ".");
  const at = new Date(iso);
  return Number.isNaN(at.getTime()) ? iso : DATE_ONLY.format(at);
}

/** yyyy-MM-dd 문자열의 요일(월~일). 예측 표에서 "왜 이 값인지"를 읽기 쉽게 한다. */
const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];
export function weekdayOf(isoDate: string): string {
  const at = new Date(`${isoDate}T00:00:00`);
  return Number.isNaN(at.getTime()) ? "" : WEEKDAYS[at.getDay()];
}

/** 최소 화폐단위(원) → "12,000원". 서버 금액은 전부 minor 단위 정수다. */
export function formatMinor(minor: number | null | undefined): string {
  if (minor === null || minor === undefined) return "—";
  return `${minor.toLocaleString("ko-KR")}원`;
}

export function formatCount(value: number | null | undefined): string {
  if (value === null || value === undefined) return "—";
  return value.toLocaleString("ko-KR");
}

/** 0.1234 → "12.3%". null 은 "—"(0% 와 구분). */
export function formatRatio(value: number | null | undefined): string {
  if (value === null || value === undefined) return "—";
  return `${(value * 100).toFixed(1)}%`;
}

/** 오늘부터 offset 일 뒤의 yyyy-MM-dd(사용자 로컬 기준 — 서버는 org 타임존으로 재해석한다). */
export function isoDateOffset(days: number): string {
  const at = new Date();
  at.setDate(at.getDate() + days);
  return at.toISOString().slice(0, 10);
}

/** 이번 달(또는 offset 개월 전) 'YYYY-MM'. */
export function monthOffset(months: number): string {
  const at = new Date();
  at.setDate(1);
  at.setMonth(at.getMonth() + months);
  return `${at.getFullYear()}-${String(at.getMonth() + 1).padStart(2, "0")}`;
}
