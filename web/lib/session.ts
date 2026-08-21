"use client";

import { createContext, useCallback, useContext, useEffect, useState } from "react";
import { api, UnauthenticatedError } from "./api";
import { messageOf } from "./useApi";

/** GET /api/account/me 응답 — 서버 DTO(CurrentUserView)와 1:1. */
export interface CurrentUser {
  userId: string;
  email: string;
  displayName: string | null;
  emailVerified: boolean;
  mfaEnabled: boolean;
  hasPassword: boolean;
  platformAdmin: boolean;
  manageableOrgs: boolean;
}

export interface Membership {
  orgId: string;
  orgName: string;
  role: "MEMBER" | "ORG_ADMIN";
  status: string;
}

export type SessionState =
  | { status: "loading" }
  | { status: "authenticated"; user: CurrentUser }
  | { status: "anonymous" }
  | { status: "error"; message: string; retry: () => void };

/** 신원 조회가 실패한 이유 — 로그인이 없는 것과 확인 자체를 못 한 것은 다른 사실이다. */
export type SessionFailure = { status: "anonymous" } | { status: "error"; message: string };

const SESSION_ERROR = "로그인 상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요";

/**
 * `/api/account/me` 실패를 **미인증**과 **확인 불가**로 가른다.
 *
 * 예전엔 catch 가 전부 anonymous 로 수렴했다. 그 결과 서버가 500 을 내거나 네트워크가 끊기면
 * 세션이 멀쩡한 사용자가 `RequireAuth` 에 의해 로그인 화면으로 튕겼다 — 장애를 로그아웃으로 위장한 셈이라
 * 사용자는 로그인해도 같은 곳으로 돌아오며 원인을 알 수 없었다. **미인증의 근거는 서버의 401 뿐이고**,
 * 그 근거가 없으면 우리는 모르는 것이지 익명인 것이 아니다.
 *
 * 순수 함수로 떼어 둔 이유는 이 분기 자체가 회귀 지점이라 훅 없이 단언할 수 있어야 하기 때문이다.
 */
export function classifySessionFailure(cause: unknown): SessionFailure {
  if (cause instanceof UnauthenticatedError) return { status: "anonymous" };
  const detail = messageOf(cause);
  // 원인을 지우지 않는다 — 사용자가 스크린샷 한 장으로 상황을 전달할 수 있어야 한다.
  return {
    status: "error",
    message: detail ? `${SESSION_ERROR} (${detail})` : SESSION_ERROR,
  };
}

/**
 * 세션은 앱 전체에서 **하나**다(`SessionProvider` 가 루트에서 심는다).
 *
 * ★훅이 각자 조회하게 두면 `retry` 도 각자가 된다. 실제로 그 상태를 만들어 봤다: `AppShell`(헤더)과
 * `RequireAuth`(본문)가 따로 조회하니, error 화면에서 "다시 시도"를 눌러 성공해도 **본문만 복구되고
 * 헤더는 error 로 남아** 네비게이션·사용자 이름·로그아웃이 사라진 채 유지됐다. 사용자에겐 절반만
 * 살아난 화면이 장애보다 더 헷갈린다. 상태가 하나여야 재시도도 하나다.
 */
const SessionContext = createContext<SessionState | null>(null);

/** `SessionProvider` 가 값으로 넣을 상태를 만든다 — 실제 조회는 여기 한 곳에서만 일어난다. */
export function useSessionSource(): SessionState {
  const [state, setState] = useState<SessionState>({ status: "loading" });
  const [nonce, setNonce] = useState(0);
  const retry = useCallback(() => setNonce((n) => n + 1), []);

  // "다시 시도"는 조회를 다시 여는 것이므로 화면도 loading 으로 돌아가야 한다. 이 초기화를 effect 안에서
  // 하면 렌더가 한 번 더 도는 사이 **방금 실패한 오류 화면이 그대로 남아**, 버튼을 눌러도 아무 반응이
  // 없는 것처럼 보인다(그래서 사용자가 연타한다). 렌더 중 조정이 React 가 권하는 방식이다.
  const [attempted, setAttempted] = useState(nonce);
  if (attempted !== nonce) {
    setAttempted(nonce);
    setState({ status: "loading" });
  }

  useEffect(() => {
    let cancelled = false;
    api
      .get<CurrentUser>("/api/account/me", { noRedirect: true })
      .then((user) => {
        if (!cancelled) setState({ status: "authenticated", user });
      })
      .catch((cause) => {
        if (cancelled) return;
        const failure = classifySessionFailure(cause);
        setState(failure.status === "anonymous" ? failure : { ...failure, retry });
      });
    return () => {
      cancelled = true;
    };
  }, [nonce, retry]);

  return state;
}

export const SessionStateProvider = SessionContext.Provider;

/**
 * 현재 로그인 사용자.
 *
 * 인증이 없으면 로그인으로 **자동 이동하지 않는다**(`noRedirect`) — 어떤 화면은 익명 상태를 그대로
 * 보여주는 게 맞고, 이동 여부는 화면이 정하는 것이 옳다. 보호가 필요한 화면은 `RequireAuth` 를 쓴다.
 *
 * 프로바이더가 없으면 **던진다**. 조용히 자체 조회로 폴백하면 위 주석의 "재시도가 갈라지는" 상태가
 * 그대로 되살아나는데, 증상이 화면 절반에서만 나타나 발견이 늦다 — 배선 실수는 개발 중에 즉시 깨지는 편이 낫다.
 */
export function useSession(): SessionState {
  const state = useContext(SessionContext);
  if (state === null) {
    throw new Error("useSession 은 SessionProvider 안에서만 쓸 수 있습니다 (app/layout.tsx 확인)");
  }
  return state;
}

/** 사용자에게 보여줄 이름 — 표시 이름이 없으면 이메일 로컬파트로 대체(서버 토큰 클레임과 같은 규칙). */
export function displayNameOf(user: CurrentUser): string {
  return user.displayName?.trim() || user.email.split("@")[0];
}

/** 로그인 페이지로 보낸다. 서버가 로컬 경로만 허용하므로 경로만 넘긴다(open redirect 방지). */
export function goToLogin(): void {
  const here = window.location.pathname + window.location.search;
  window.location.href = `/login?continue=${encodeURIComponent(here)}`;
}
