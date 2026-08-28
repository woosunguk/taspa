/**
 * 서버 DTO 미러 — 필드명을 서버 Kotlin data class 와 1:1로 맞춘다.
 *
 * 여기 이름이 서버와 어긋나면 런타임에 조용히 `undefined` 가 되고 화면은 빈칸으로 보인다(오류가 아니라
 * 정상처럼 보이는 게 더 나쁘다). 각 타입 위에 출처 파일을 적어 두어 서버가 바뀔 때 추적할 수 있게 한다.
 */

/* ── 조직 (org/dto/OrgDtos.kt, org/dto/OrgDomainDtos.kt) ───────────────── */

export interface OrgView {
  id: string;
  slug: string;
  name: string;
  status: string; // ACTIVE | SUSPENDED
  timezone: string;
  memberCount: number;
  createdAt: string;
}

export interface MembershipView {
  id: string;
  orgId: string;
  userId: string;
  email: string | null;
  role: string; // MEMBER | ORG_ADMIN
  department: string | null;
  departmentId: string | null;
  siteId: string | null;
  employeeId: string | null;
  jobTitle: string | null;
  employmentType: string | null;
  hireDate: string | null;
  employmentStatus: string;
  status: string; // ACTIVE | SUSPENDED
  joinedAt: string;
}

export interface OrgDomainView {
  id: string;
  domain: string;
  verified: boolean;
  verificationToken: string;
  txtRecordName: string;
  txtRecordValue: string;
  createdAt: string;
  verifiedAt: string | null;
}

export interface OrgDomainSettingsView {
  autoJoinEnabled: boolean;
  domains: OrgDomainView[];
}

/* ── 사용자 (admin/dto/AdminUserSummary.kt · AdminUserDetail.kt) ────────── */

export interface AdminUserSummary {
  id: string;
  email: string;
  displayName: string | null;
  status: string; // ACTIVE | SUSPENDED
  role: string; // USER | ADMIN
  emailVerified: boolean;
  mfaEnabled: boolean;
  createdAt: string;
}

export interface AdminUserDetail {
  user: AdminUserSummary;
  passkeyCount: number;
  federatedProviders: string[];
  activeSessionCount: number;
  recentAuditEvents: AdminAuditEventView[];
}

/* ── 감사 (admin/dto/AdminAuditEventView.kt) ───────────────────────────── */

export interface AdminAuditEventView {
  id: string;
  type: string;
  userId: string | null;
  email: string | null;
  detail: string | null;
  createdAt: string;
}

/* ── OAuth 클라이언트 (admin/dto/AdminClientView.kt 외) ─────────────────── */

export interface AdminClientView {
  id: string;
  clientId: string;
  clientName: string;
  publicClient: boolean;
  redirectUris: string[];
  postLogoutRedirectUris: string[];
  scopes: string[];
  grantTypes: string[];
  clientIdIssuedAt: string | null;
  /** 이 클라이언트가 인가에 쓰겠다고 선언한 조직 커스텀 역할 이름. 실제 발급은 이 목록 ∩ 사용자 보유 역할. */
  roleNames: string[];
}

/** clientSecret 은 등록·재발급 응답에서 **단 1회만** 내려온다(저장은 bcrypt 해시). */
export interface ClientSecretResponse {
  client: AdminClientView;
  clientSecret: string | null;
}

export interface ClientRegisterRequest {
  clientId: string;
  clientName: string;
  redirectUris: string[];
  postLogoutRedirectUris: string[];
  scopes: string[];
  grantTypes: string[];
  publicClient: boolean;
  orgId: string | null;
  merchantId: string | null;
  roleNames: string[];
}

export interface ClientUpdateRequest {
  clientName: string;
  redirectUris: string[];
  postLogoutRedirectUris: string[];
  scopes: string[];
  /** null = 미전송(기존 유지), [] = 선언 해제. 둘을 구분하지 않으면 이름만 고친 저장이 선언을 지운다. */
  roleNames: string[] | null;
}

/* ── 가맹점 (admin/dto/MerchantDtos.kt) ────────────────────────────────── */

export interface MerchantView {
  id: string;
  name: string;
  category: string; // RESTAURANT | CONVENIENCE | CAFE
  status: string; // PENDING | ACTIVE | SUSPENDED
  siteId: string | null;
  /** 가맹 그레인 집계·예측의 하루 경계 앵커(V29). 미전송 수정은 서버가 기존 값을 유지한다. */
  timezone: string;
  /** 정액 단가(원). null 이면 POS 가 금액을 직접 입력받는다. */
  defaultPriceMinor: number | null;
  createdAt: string;
  updatedAt: string;
}

/** 가맹 직원(사람 신원, V29) — /merchant 콘솔 접근 자격. 역할은 항상 MERCHANT_ADMIN. */
export interface MerchantMemberView {
  userId: string;
  email: string | null;
  displayName: string | null;
  role: string;
  status: string;
  createdAt: string;
}

/* ── 기업 SSO (enterprise/dto/SsoConnectionDtos.kt) ────────────────────── */

export interface SsoDomainView {
  domain: string;
  verified: boolean;
}

export interface SsoConnectionView {
  id: string;
  registrationId: string;
  displayName: string;
  protocol: string; // OIDC | SAML
  orgId: string | null;
  enabled: boolean;
  enforced: boolean;
  trustIdpMfa: boolean;
  domains: SsoDomainView[];
  oidcIssuer: string | null;
  oidcAuthorizationUri: string | null;
  oidcTokenUri: string | null;
  oidcJwksUri: string | null;
  oidcUserInfoUri: string | null;
  oidcUserNameAttr: string | null;
  oidcClientId: string | null;
  oidcScopes: string | null;
  hasOidcSecret: boolean;
  samlIdpEntityId: string | null;
  samlSsoUrl: string | null;
  samlVerificationCert: string | null;
  samlWantAuthnSigned: boolean;
  samlEmailAttr: string | null;
  samlNameAttr: string | null;
  spEntityId: string;
  spAcsUrl: string;
  spMetadataUrl: string;
  oidcRedirectUri: string;
}

export interface SsoConnectionRequest {
  registrationId: string;
  displayName: string;
  protocol: string;
  enabled: boolean;
  enforced: boolean;
  trustIdpMfa: boolean;
  domains: string[];
  oidcIssuer: string | null;
  oidcAuthorizationUri: string | null;
  oidcTokenUri: string | null;
  oidcJwksUri: string | null;
  oidcUserInfoUri: string | null;
  oidcUserNameAttr: string | null;
  oidcClientId: string | null;
  oidcClientSecret: string | null;
  oidcScopes: string | null;
  samlIdpEntityId: string | null;
  samlSsoUrl: string | null;
  samlVerificationCert: string | null;
  samlWantAuthnSigned: boolean;
  samlEmailAttr: string | null;
  samlNameAttr: string | null;
}

/* ── 캘린더 (calendar/dto/CalendarDtos.kt) ─────────────────────────────── */

export interface FeedView {
  id: string;
  orgId: string;
  name: string;
  type: string; // HOLIDAY | WORK | EVENT
  sourceUrl: string | null;
  subscription: boolean;
  enabled: boolean;
  lastSyncedAt: string | null;
  lastSyncStatus: string | null; // OK | ERROR
  createdAt: string;
}

export interface SyncResultView {
  feedId: string;
  status: string;
  imported: number;
}

export interface CalendarEventView {
  id: string;
  uid: string;
  summary: string | null;
  category: string | null;
  startsAt: string;
  endsAt: string | null;
  allDay: boolean;
  source: string; // UPLOAD | FEED
}

export interface CalendarEventPage {
  items: CalendarEventView[];
  page: number;
  size: number;
  total: number;
  hasNext: boolean;
}

/* ── IAM (iam/dto/IamDtos.kt) ──────────────────────────────────────────── */

export type IamPrincipalType = "USER" | "GROUP";

export interface PolicyView {
  id: string;
  name: string;
  orgId: string | null;
  description: string | null;
  document: string;
  systemManaged: boolean;
  statementCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface GroupView {
  id: string;
  name: string;
  orgId: string | null;
  description: string | null;
  systemManaged: boolean;
  memberCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface GroupMemberView {
  userId: string;
  email: string | null;
  createdAt: string;
}

/** source: inline | attached | group-inline | group-attached */
export interface PrincipalPolicyView {
  source: string;
  policyId: string | null;
  name: string;
  document: string;
  statementCount: number;
}

export type SimulateSubjectType = "SESSION" | "M2M" | "DELEGATED";

export interface SimulateRequest {
  subjectType: SimulateSubjectType;
  userId?: string | null;
  orgId?: string | null;
  stepUp?: boolean;
  scopes?: string[];
  boundOrgs?: string[];
  merchantId?: string | null;
  scimOrg?: string | null;
  action: string;
  resource: string;
  context?: Record<string, string>;
}

export interface SimulateResponse {
  effect: string; // ALLOW | DENY
  reason: string;
  matchedSid: string | null;
}

/** 전역 대사(플랫폼) 요약 한 줄 — 불일치가 있는 조직만 내려온다. */
export interface ReconciliationSummary {
  orgId: string;
  orgName: string;
  timezone: string;
  period: string;
  balanced: boolean;
  amountDrift: number;
  countDrift: number;
  unbalancedEntryCount: number;
  passThroughDrift: number;
}

export interface PlatformReconciliationView {
  period: string;
  /** 이 기간에 원장 활동이 있어 **실제로 대사한** 조직 수. */
  scanned: number;
  unbalanced: ReconciliationSummary[];
  /** 상한에 걸려 못 본 조직 수 — 0 이 아니면 "이상 없음"을 믿으면 안 된다. */
  skipped: number;
  /** 시도했으나 대사에 **실패한** 조직 수 — 0 이 아니면 결과를 "전부"로 읽으면 안 된다. */
  failed: number;
}

/** 전역 지급 현황의 매장별 한 줄 — `PlatformPayableLine`. */
export interface PlatformPayableLine {
  merchantId: string;
  merchantName: string;
  timezone: string;
  approvedCount: number;
  /** 그 매장에 지급할 금액(= 그 매장 정산 명세의 payableMinor). */
  payableMinor: number;
  refundedMinor: number;
}

/**
 * GET /api/admin/payables — `PlatformPayablesView`.
 *
 * ★`scanned`/`skipped` 는 총액 0 이 "지급할 게 없다"인지 "**아무것도 안 봤다**"인지 구분한다.
 * 창은 매장마다 **그 매장의 타임존** 월 경계라, 조직 청구서 총액과 경계일 거래만큼 정당하게 다르다.
 */
export interface PlatformPayablesView {
  period: string;
  scanned: number;
  skipped: number;
  /** 시도했으나 집계에 실패한 매장 수 — 총액이 실제보다 적을 수 있다. */
  failed: number;
  totalPayableMinor: number;
  totalRefundedMinor: number;
  totalApprovedCount: number;
  lines: PlatformPayableLine[];
}

/** 확정되지 않은 청구서 한 줄 — `state` 는 DRAFT(사람이 안 눌렀다) 또는 MISSING(시스템이 못 만들었다). */
export interface UnfinalizedInvoiceLine {
  orgId: string;
  orgName: string;
  timezone: string;
  state: string;
  subtotalMinor: number | null;
  txnCount: number | null;
  generatedAt: string | null;
}

/** GET /api/admin/invoices/unfinalized — `UnfinalizedInvoicesView`. */
export interface UnfinalizedInvoicesView {
  period: string;
  scanned: number;
  skipped: number;
  /** 시도했으나 판정에 실패한 조직 수 — 0 이 아니면 목록을 "전부"로 읽으면 안 된다. */
  failed: number;
  lines: UnfinalizedInvoiceLine[];
}
