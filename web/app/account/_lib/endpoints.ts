/**
 * 계정 화면이 쓰는 taspa 서버 계약 — **경로·메서드·필드명을 한 곳에만 적는다.**
 *
 * 섹션마다 문자열을 손으로 적으면 오타가 컴파일을 통과해 런타임에 조용한 undefined 로 남는다.
 * 각 함수 위 주석의 컨트롤러가 계약의 원본이다.
 *
 * `api` 헬퍼에는 PATCH 가 없어 `apiRequest` 를 직접 쓴다(공용 lib 는 읽기 전용으로 다룬다).
 */

import { api, apiRequest } from "@/lib/api";

/* ── 자기서비스: selfservice/SelfServiceController.kt ─────────────────────── */

/** PATCH /api/account/profile — 표시 이름. 공백/빈 문자열은 서버가 "없음"으로 저장한다. */
export const updateDisplayName = (displayName: string) =>
  apiRequest<void>("/api/account/profile", { method: "PATCH", body: { displayName } });

/** POST /api/account/email/change — 새 주소로 확인 코드 발송(step-up 대상, 202). */
export const requestEmailChange = (newEmail: string) =>
  api.post<void>("/api/account/email/change", { newEmail });

/** POST /api/account/email/change/confirm — 코드 확인 시 전환(현재 세션 principal 재수립). */
export const confirmEmailChange = (code: string) =>
  api.post<void>("/api/account/email/change/confirm", { code });

/**
 * POST /api/account/password — 변경/설정(step-up 대상).
 * 소셜 전용 계정(hasPassword=false)은 currentPassword 없이 최초 설정한다.
 * ★성공하면 서버가 **모든 세션과 신뢰 기기를 폐기**한다 — 호출부는 로그인 화면으로 보내야 한다.
 */
export const changePassword = (currentPassword: string | null, newPassword: string) =>
  api.post<void>("/api/account/password", { currentPassword, newPassword });

/** DELETE /api/account — 계정 하드 삭제(step-up + 이메일 재입력 확인). */
export const deleteAccount = (email: string) =>
  apiRequest<void>("/api/account", { method: "DELETE", body: { email } });

export interface AuthorizedClient {
  registeredClientId: string;
  clientName: string;
  scopes: string[];
  lastUsedAt: string | null;
}

/** GET /api/account/authorized-clients — 사용자가 권한을 준 OAuth2 클라이언트. */
export const AUTHORIZED_CLIENTS = "/api/account/authorized-clients";

/** DELETE /api/account/authorized-clients/{registeredClientId} — 철회(step-up 대상). */
export const revokeAuthorizedClient = (registeredClientId: string) =>
  api.delete<void>(`${AUTHORIZED_CLIENTS}/${encodeURIComponent(registeredClientId)}`);

export interface LoginHistoryEntry {
  occurredAt: string;
  /** password / mfa / passkey / social:{provider} / magic */
  method: string;
  ip: string | null;
  device: string | null;
}

/** GET /api/account/login-history — 최근 10건(서버 기본값). */
export const LOGIN_HISTORY = "/api/account/login-history";

/* ── MFA: mfa/MfaController.kt (클래스 전체가 step-up 대상) ───────────────── */

export interface MfaSetup {
  /** 서버가 만든 QR PNG 를 data URI 로 준다 — 그대로 <img src> 에 넣는다. */
  qrCodeDataUri: string;
  secret: string;
}

export const setupMfa = () => api.post<MfaSetup>("/api/mfa/setup");

/** 활성화 성공 시 백업 코드가 **이 응답에만** 담겨 온다(서버는 해시만 보관). */
export const activateMfa = (code: string) =>
  api.post<{ backupCodes: string[] }>("/api/mfa/activate", { code });

export const disableMfa = (code: string) => api.post<{ mfaEnabled: boolean }>("/api/mfa/disable", { code });

export const regenerateBackupCodes = () =>
  api.post<{ backupCodes: string[] }>("/api/mfa/backup-codes/regenerate");

/* ── 패스키: passkey/PasskeyController.kt ─────────────────────────────────── */

export interface Passkey {
  credentialId: string;
  label: string;
  createdAt: string;
  lastUsedAt: string | null;
}

export const PASSKEYS = "/api/passkeys";

/** PATCH /api/passkeys/{credentialId} — 이름 변경(step-up 대상). */
export const renamePasskey = (credentialId: string, label: string) =>
  apiRequest<void>(`${PASSKEYS}/${encodeURIComponent(credentialId)}`, {
    method: "PATCH",
    body: { label },
  });

/** DELETE /api/passkeys/{credentialId} — 삭제(step-up 대상, 타인 credential 은 404). */
export const deletePasskey = (credentialId: string) =>
  api.delete<void>(`${PASSKEYS}/${encodeURIComponent(credentialId)}`);

/* ── 세션: session/SessionController.kt ───────────────────────────────────── */

export interface SessionEntry {
  /** 세션 ID 원문이 아니라 SHA-256 앞 16자 — 서버가 원문을 절대 노출하지 않는다. */
  publicId: string;
  ip: string | null;
  browser: string | null;
  createdAt: string;
  lastActiveAt: string;
  current: boolean;
}

export const SESSIONS = "/api/sessions";

export const revokeSession = (publicId: string) =>
  api.delete<void>(`${SESSIONS}/${encodeURIComponent(publicId)}`);

export const revokeOtherSessions = () => api.post<void>(`${SESSIONS}/revoke-others`);

/* ── 신뢰 기기: device/TrustedDeviceController.kt ─────────────────────────── */

export interface TrustedDevice {
  id: string;
  uaLabel: string;
  createdAt: string;
  lastUsedAt: string | null;
  expiresAt: string;
}

export const TRUSTED_DEVICES = "/api/trusted-devices";

export const revokeTrustedDevice = (id: string) =>
  api.delete<void>(`${TRUSTED_DEVICES}/${encodeURIComponent(id)}`);

export const revokeAllTrustedDevices = () => api.delete<void>(TRUSTED_DEVICES);

/* ── 소셜 연결: federation/FederationController.kt ────────────────────────── */

export interface Federation {
  provider: string;
  providerLabel: string;
  emailAtLink: string | null;
  createdAt: string;
}

export const FEDERATIONS = "/api/federations";

/** DELETE /api/federations/{provider} — 마지막 로그인 수단이면 409 LAST_LOGIN_METHOD. */
export const unlinkFederation = (provider: string) =>
  api.delete<void>(`${FEDERATIONS}/${encodeURIComponent(provider)}`);

/**
 * 소셜 연결 **시작**은 API 가 아니라 서버 페이지 경로다(세션에 SocialLinkIntent 를 심어야 하므로
 * fetch 가 아닌 전체 이동이어야 한다). next.config.ts 가 이 경로만 서버로 프록시한다.
 */
export const federationLinkUrl = (provider: string) =>
  `/account/federations/link/${encodeURIComponent(provider)}`;
