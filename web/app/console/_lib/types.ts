/**
 * 조직 관리 콘솔이 쓰는 서버 응답 타입.
 *
 * 모두 `server/src/main/kotlin/com/taspa/server/{org,billing,forecast}/dto/` 의 Kotlin data class 와 1:1 이다.
 * 필드를 추측해서 늘리면 런타임에 조용히 undefined 가 되므로, 서버 DTO 를 바꿀 때 이 파일도 같이 바꾼다.
 * Instant·LocalDate 는 JSON 에서 ISO-8601 문자열로 온다.
 */

export type OrgRole = "MEMBER" | "ORG_ADMIN";
export type MembershipStatus = "ACTIVE" | "SUSPENDED";
export type EmploymentStatus = "EMPLOYED" | "ON_LEAVE" | "TERMINATED";
export type EmploymentType = "FULL_TIME" | "PART_TIME" | "CONTRACT" | "INTERN";
export type MealWindow = "BREAKFAST" | "LUNCH" | "DINNER";

/** GET /api/orgs/mine — 내가 ORG_ADMIN 으로 관리하는 활성 조직. */
export interface AdministeredOrg {
  id: string;
  name: string;
  slug: string;
  status: string;
  timezone: string;
  role: string;
  memberCount: number;
  /** 조직 생성 시각(ISO). 조직이 존재하지도 않던 달을 재촉하지 않기 위한 근거. */
  createdAt: string;
}

/** PUT /api/orgs/{orgId} 응답. */
export interface OrgView {
  id: string;
  slug: string;
  name: string;
  status: string;
  timezone: string;
  memberCount: number;
  createdAt: string;
}

// ---- 대시보드 ----

export interface DepartmentRollup {
  id: string;
  parentId: string | null;
  name: string;
  directCount: number;
  /** 자기 + 모든 하위 부서 배정 합(서버가 트리 워크로 계산). */
  rollupCount: number;
}

export interface SiteCount {
  id: string;
  name: string;
  count: number;
}

export interface OrgDashboard {
  memberCount: number;
  byRole: Record<string, number>;
  byEmploymentStatus: Record<string, number>;
  /** `UNSPECIFIED` 키는 고용형태 미지정 인원 수다(서버 DTO 주석). */
  byEmploymentType: Record<string, number>;
  byDepartment: DepartmentRollup[];
  departmentUnassignedCount: number;
  bySite: SiteCount[];
  siteUnassignedCount: number;
  siteCount: number;
  pendingInvitations: number;
  recentJoins30d: number;
}

// ---- 구성원 ----

export interface Membership {
  id: string;
  orgId: string;
  userId: string;
  email: string | null;
  /** 표시 이름(users.display_name). 미설정 계정이 있어 nullable — 화면은 없으면 이메일로 내려간다. */
  displayName: string | null;
  role: string;
  /** 자유 텍스트 부서 라벨(초대 시 입력) — 구조적 배정(departmentId)과 별개. */
  department: string | null;
  departmentId: string | null;
  siteId: string | null;
  employeeId: string | null;
  jobTitle: string | null;
  employmentType: string | null;
  /** ISO 로컬 날짜(yyyy-MM-dd). */
  hireDate: string | null;
  employmentStatus: string;
  status: string;
  joinedAt: string;
}

export interface MembershipHistoryEntry {
  id: string;
  userId: string;
  role: string;
  departmentId: string | null;
  siteId: string | null;
  employmentType: string | null;
  employmentStatus: string;
  jobTitle: string | null;
  changeType: string;
  recordedAt: string;
  recordedBy: string | null;
}

// ---- 초대 ----

export interface Invitation {
  id: string;
  orgId: string;
  email: string;
  role: string;
  department: string | null;
  status: string;
  createdAt: string;
  expiresAt: string;
}

export interface BulkInvitationRow {
  /** CSV 원문 기준 1-base 행 번호. */
  line: number;
  email: string;
  status: "CREATED" | "REJECTED" | string;
  reason: string | null;
  /**
   * 초대는 됐지만 **의도한 대로 되지 않은** 부분(현재는 부서 이름 미해결). 실패가 아니므로 status 는
   * CREATED 지만, 그대로 두면 그 사람은 부서 식대 재정의를 받지 못한다.
   */
  warning?: string | null;
}

export interface BulkInvitationResult {
  total: number;
  created: number;
  rejected: number;
  results: BulkInvitationRow[];
}

// ---- 조직 구조 ----

export interface Department {
  id: string;
  parentId: string | null;
  name: string;
  /** 이 부서에 **직접** 배정된 인원(하위 부서 롤업 아님). */
  memberCount: number;
}

export interface Site {
  id: string;
  name: string;
  address: string | null;
  timezone: string;
  memberCount: number;
}

// ---- 도메인 자동 가입 ----

export interface OrgDomain {
  id: string;
  domain: string;
  verified: boolean;
  verificationToken: string;
  txtRecordName: string;
  txtRecordValue: string;
  createdAt: string;
  verifiedAt: string | null;
}

export interface OrgDomainSettings {
  autoJoinEnabled: boolean;
  domains: OrgDomain[];
}

// ---- 활동로그 ----

export interface OrgAuditEvent {
  id: string;
  userId: string | null;
  email: string | null;
  type: string;
  /** JSON 문자열(서버가 그대로 내려준다). */
  detail: string | null;
  createdAt: string;
  /** 플랫폼 운영자 행위 — 서버가 신원(userId·email)을 마스킹해 내려준다. */
  platformActor: boolean;
}

// ---- 식수 예측 ----

export type ForecastMethod = "SEASONAL_NAIVE_ADJUSTED" | "SEASONAL_NAIVE" | "FOUR_WEEK_AVG" | "NO_DATA";

export interface ForecastBasis {
  lastWeekActual: number | null;
  headcountNow: number | null;
  headcountLastWeek: number | null;
  /** 휴일 여부가 대상일과 달라 basis 후보에서 제외된 과거 주 수(0 이면 캘린더가 관여하지 않았다). */
  excludedHolidayBasis: number;
}

export interface ForecastCell {
  date: string;
  /** null 이면 조직 전체(총식수) 축. */
  siteId: string | null;
  mealWindow: string;
  /** **null 은 0 이 아니라 "데이터 없음"이다**(method=NO_DATA). */
  predicted: number | null;
  method: ForecastMethod | string;
  basis: ForecastBasis;
  /** 조직 캘린더가 그 날을 휴일로 표시했는가. **예측값과는 독립된 사실이다**(휴일에도 당직 식사가 있다). */
  holiday: boolean;
  holidayName: string | null;
  /** 캘린더가 종일 EVENT 로 선언한 사내 행사인가. 휴일과 다른 축이다(사람은 있지만 밖에서 먹는다). */
  event?: boolean;
  eventName?: string | null;
}

export interface ForecastResponse {
  orgId: string;
  from: string;
  to: string;
  siteId: string | null;
  mealWindow: string | null;
  cells: ForecastCell[];
}

export interface BacktestCell extends ForecastCell {
  actual: number;
}

export interface BacktestSummary {
  cells: number;
  scoredCells: number;
  mape: number | null;
  /** 실적 0 이라 MAPE 분모에서 제외된 셀 수(서버가 정직하게 노출). */
  mapeExcludedZeroActual: number;
  wape: number | null;
  bias: number | null;
}

export interface BacktestResponse {
  orgId: string;
  from: string;
  to: string;
  siteId: string | null;
  mealWindow: string | null;
  cells: BacktestCell[];
  summary: BacktestSummary;
}

// ---- 청구서 ----

export interface Invoice {
  id: string;
  /** org 타임존 달력 월 'YYYY-MM'. */
  period: string;
  status: string;
  /** 최소 화폐단위(KRW 원). */
  subtotalMinor: number;
  txnCount: number;
  generatedAt: string;
  finalizedAt: string | null;
}

export interface InvoiceLine {
  userId: string;
  userEmail: string;
  departmentId: string | null;
  /** 생성 시점 스냅샷 — 이후 부서명이 바뀌어도 청구서는 불변이다. */
  departmentName: string | null;
  txnCount: number;
  amountMinor: number;
}

export interface DepartmentSubtotal {
  departmentId: string | null;
  departmentName: string | null;
  txnCount: number;
  amountMinor: number;
}

export interface InvoiceDetail extends Invoice {
  lines: InvoiceLine[];
  departmentSubtotals: DepartmentSubtotal[];
}

/** 조직 식대 정책(한도 3 + 끼니창 3쌍). 시각은 org 로컬 `HH:mm:ss`. */
export interface MealPolicy {
  orgId: string;
  timezone: string;
  perMealLimitMinor: number;
  dailyMealCount: number;
  monthlyCapMinor: number;
  breakfastStart: string;
  breakfastEnd: string;
  lunchStart: string;
  lunchEnd: string;
  dinnerStart: string;
  dinnerEnd: string;
  /** 아직 한 번도 저장하지 않아 코드 기본값을 쓰는 중인지. */
  usingDefaults: boolean;
  /** 배포 단위 절대 상한 — 폼이 미리 알려 주고, 서버도 같은 값으로 거절한다. */
  ceilingPerMealLimitMinor: number;
  ceilingDailyMealCount: number;
  ceilingMonthlyCapMinor: number;
  updatedAt: string | null;
}

export interface MealPolicyRevision {
  id: string;
  scopeType: string;
  scopeLabel: string | null;
  changeType: string;
  /** 변경 후 전체 스냅샷 JSON 문자열. */
  document: string;
  /** false 면 플랫폼 운영자가 바꾼 것 — 조직 화면에서 감추지 않는다. */
  actorIsOrgMember: boolean;
  actorEmail: string | null;
  recordedAt: string;
}

/** 부서·사업장 단위 식대 정책 재정의. null 필드 = 재정의하지 않음(상위값 물려받음). */
export interface MealPolicyOverride {
  id: string;
  scopeType: "DEPARTMENT" | "SITE";
  scopeId: string;
  scopeLabel: string | null;
  perMealLimitMinor: number | null;
  dailyMealCount: number | null;
  monthlyCapMinor: number | null;
  breakfastStart: string | null;
  breakfastEnd: string | null;
  lunchStart: string | null;
  lunchEnd: string | null;
  dinnerStart: string | null;
  dinnerEnd: string | null;
  /** 둘 다 null 이면 상시(대상당 1건). 하나라도 있으면 기간 한정이고 상시보다 우선한다. */
  effectiveFrom: string | null;
  effectiveTo: string | null;
  reason: string | null;
  updatedAt: string;
}

/** "이 대상에게 실제로 얼마가 적용되는가" — 서버 해석기를 그대로 통과한 값. */
export interface MealPolicyPreview {
  scopeType: string;
  scopeId: string | null;
  scopeLabel: string | null;
  perMealLimitMinor: number;
  dailyMealCount: number;
  monthlyCapMinor: number;
  breakfastStart: string;
  breakfastEnd: string;
  lunchStart: string;
  lunchEnd: string;
  dinnerStart: string;
  dinnerEnd: string;
  /** 필드명 → 출처(CODE_DEFAULT|ORG|SITE|DEPARTMENT). */
  sources: Record<string, string>;
  sourceLabels: Record<string, string | null>;
}

/** 부서 서브트리 위임 — 이 사람은 이 부서와 그 하위만 관리한다. */
export interface DepartmentDelegation {
  id: string;
  userId: string;
  userEmail: string | null;
  departmentId: string;
  departmentName: string | null;
  grantedBy: string | null;
  createdAt: string;
}

/** 3-way 대사 한 축. `kind` 로 금액/건수를 구분한다(화면이 단위를 지어내지 않게). */
export interface ReconciliationLeg {
  name: string;
  kind: "AMOUNT" | "COUNT";
  value: number;
}

/**
 * 3-way 대사 결과 — 원장·장부·소비이벤트가 같은 사실을 말하는지.
 *
 * drift 가 0 이 아닌 것은 "설명이 필요한 차이"가 아니라 **버그의 직접 증거**다. 세 기록은 같은
 * 트랜잭션에서 쓰이므로 정상 동작에서는 갈라질 수 없다.
 */
export interface ReconciliationReport {
  orgId: string;
  period: string;
  timezone: string;
  periodStart: string;
  periodEnd: string;
  legs: ReconciliationLeg[];
  amountDrift: number;
  countDrift: number;
  unbalancedEntryCount: number;
  passThroughDrift: number;
  balanced: boolean;
}

/**
 * 조직 커스텀 역할 — `OrgRoleView`. `actions` 는 서버가 생성한 정책에서 되읽은 값이다.
 *
 * ★이름이 `OrgCustomRole` 인 이유: 같은 파일의 `OrgRole` 은 멤버십의 두 값(MEMBER|ORG_ADMIN)이라
 * 다른 개념이다. 둘을 같은 이름으로 두면 어느 쪽을 뜻하는지 호출부에서 알 수 없다.
 */
export interface OrgCustomRole {
  id: string;
  name: string;
  description: string | null;
  actions: string[];
  memberCount: number;
}

export interface OrgCustomRoleMember {
  userId: string;
  email: string | null;
}

export interface OrgCustomRoleDetail {
  id: string;
  name: string;
  description: string | null;
  actions: string[];
  members: OrgCustomRoleMember[];
}

/**
 * 부여 가능한 능력 — **서버가 유일한 출처**다.
 *
 * 화면이 목록을 따로 들고 있으면 서버에 능력이 추가돼도 화면에는 영영 안 나타나고, 반대로 서버가
 * 막은 능력을 화면이 계속 보여주면 사용자는 저장할 때마다 400 을 받는다.
 */
export interface GrantableAction {
  action: string;
  group: string;
  label: string;
}
