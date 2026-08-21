/**
 * 패스키(WebAuthn) 등록 — 브라우저 API 직접 사용.
 *
 * ★인코딩 규약은 서버가 서빙하는 벤더링 공식 스크립트(`server/src/main/resources/static/js/webauthn.js`,
 * spring-security-web 6.4.4)와 **바이트 단위로 같아야** 한다. 서버는 base64url(패딩 없음)만 받고,
 * 형식이 어긋나면 예외 대신 검증 실패로 조용히 끝난다. 그래서 여기서는 그 스크립트의 encode/decode 와
 * 요청 바디 모양(`{publicKey: {credential, label}}`)을 그대로 옮겼다.
 *
 * 등록 플로우를 벤더링 스크립트에 위임하지 않는 이유: 그 스크립트는 성공 시
 * `/webauthn/register?success` 로 **페이지를 이동**시키는 서버 렌더링 전용 동작을 갖고 있어 SPA 와 맞지 않는다.
 * (서버의 account.html 도 같은 이유로 직접 fetch 로 구현돼 있다.)
 */

import { ApiError, UnauthenticatedError, api } from "@/lib/api";

const base64url = {
  encode(buffer: ArrayBuffer): string {
    const bytes = new Uint8Array(buffer);
    let binary = "";
    // btoa 는 바이너리 문자열을 받는다. spread 는 큰 버퍼에서 스택을 넘길 수 있어 루프로 만든다.
    for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
    return window.btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
  },
  decode(value: string): ArrayBuffer {
    const binary = window.atob(value.replace(/-/g, "+").replace(/_/g, "/"));
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes.buffer;
  },
};

/** 서버가 주는 등록 옵션 — 바이너리 필드가 base64url 문자열로 직렬화돼 있다. */
interface ServerCreationOptions {
  challenge: string;
  user: { id: string; name: string; displayName: string };
  excludeCredentials?: { id: string; type: string; transports?: string[] }[];
  [key: string]: unknown;
}

interface RegistrationResult {
  success?: boolean;
}

/** 이 브라우저가 WebAuthn 을 지원하는지. 지원하지 않으면 버튼을 비활성화하고 이유를 보여준다. */
export function isWebAuthnSupported(): boolean {
  return (
    typeof window !== "undefined" &&
    typeof window.PublicKeyCredential !== "undefined" &&
    typeof navigator !== "undefined" &&
    !!navigator.credentials
  );
}

/** 사용자가 인증기 프롬프트를 취소했을 때 — 오류가 아니라 "취소"로 다루기 위해 따로 식별한다. */
export class PasskeyCancelledError extends Error {}

/**
 * 등록 세리머니 전체.
 *
 * step-up 사전 점검을 먼저 한다: 등록 경로는 `StepUpEnforcementFilter`(필터 단계)가 막기 때문에
 * 인증기 프롬프트를 띄운 뒤에 401 을 만나면 사용자가 지문까지 찍고 실패하는 최악의 순서가 된다.
 * `/api/reauth/check` 로 먼저 확인해 필요하면 인증기 호출 전에 재인증으로 보낸다.
 */
export async function registerPasskey(label: string): Promise<void> {
  const check = await api.get<{ reauthRequired: boolean }>("/api/reauth/check").catch((cause: unknown) => {
    // 이동이 이미 시작됐으면(세션 만료 → /login, step-up → /reauth) 그 신호는 삼키지 않는다.
    // lib/api 의 redirectTo 가 던지는 navigation 예외와 미인증이 여기에 해당한다.
    if (cause instanceof Error && cause.message === "navigating") throw cause;
    if (cause instanceof UnauthenticatedError) throw cause;
    // 서버가 사실을 말해 준 4xx(예: 403)는 낙관 통과시킬 근거가 없다 — 그대로 올려 사용자에게
    // 실패를 알린다. 여기서 삼키면 인증기 프롬프트를 띄운 뒤에야 같은 이유로 실패한다.
    if (cause instanceof ApiError && cause.status < 500 && cause.status !== 404) throw cause;
    // 점검 엔드포인트 부재(404)·서버 일시 오류·네트워크 실패만 통과 — 최종 방어선은
    // 서버측 StepUpEnforcementFilter 다.
    return { reauthRequired: false };
  });
  if (check.reauthRequired) {
    const here = window.location.pathname + window.location.search;
    window.location.href = `/reauth?continue=${encodeURIComponent(here)}`;
    // 이동이 시작되면 이후 코드는 의미가 없다(lib/api 의 규약과 같은 신호를 쓴다).
    throw new Error("navigating");
  }

  const options = await api.post<ServerCreationOptions>("/webauthn/register/options");

  const publicKey: PublicKeyCredentialCreationOptions = {
    ...(options as unknown as PublicKeyCredentialCreationOptions),
    challenge: base64url.decode(options.challenge),
    user: {
      ...options.user,
      id: base64url.decode(options.user.id),
    },
    excludeCredentials: (options.excludeCredentials ?? []).map((credential) => ({
      ...credential,
      type: "public-key" as const,
      id: base64url.decode(credential.id),
      transports: credential.transports as AuthenticatorTransport[] | undefined,
    })),
  };

  let created: PublicKeyCredential | null;
  try {
    created = (await navigator.credentials.create({ publicKey })) as PublicKeyCredential | null;
  } catch (cause) {
    // NotAllowedError = 사용자가 취소했거나 시간이 초과됨. 실패 문구를 다르게 줘야 한다.
    if (cause instanceof DOMException && (cause.name === "NotAllowedError" || cause.name === "AbortError")) {
      throw new PasskeyCancelledError("패스키 등록이 취소되었습니다");
    }
    throw cause;
  }
  if (!created) throw new PasskeyCancelledError("패스키 등록이 취소되었습니다");

  const response = created.response as AuthenticatorAttestationResponse;
  const body = {
    publicKey: {
      credential: {
        id: created.id,
        rawId: base64url.encode(created.rawId),
        response: {
          attestationObject: base64url.encode(response.attestationObject),
          clientDataJSON: base64url.encode(response.clientDataJSON),
          transports: response.getTransports ? response.getTransports() : [],
        },
        type: created.type,
        clientExtensionResults: created.getClientExtensionResults(),
        authenticatorAttachment: created.authenticatorAttachment,
      },
      label,
    },
  };

  const result = await api.post<RegistrationResult>("/webauthn/register", body);
  // 서버는 200 + {success:false} 로도 실패를 알린다 — 상태코드만 보고 성공으로 단정하지 않는다.
  if (!result?.success) {
    throw new Error("서버가 패스키를 검증하지 못했습니다. 다시 시도해 주세요");
  }
}
