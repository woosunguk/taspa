"use client";

import type { ReactNode } from "react";
import { SessionStateProvider, useSessionSource } from "@/lib/session";

/**
 * 앱 전체가 공유하는 세션 상태. 루트 레이아웃(서버 컴포넌트)이 이 클라이언트 컴포넌트를 렌더한다.
 *
 * 조회가 여기 한 번뿐이라는 것이 요점이다 — `/api/account/me` 왕복이 화면당 2~3회에서 1회로 줄고,
 * 무엇보다 error 상태의 `retry` 가 하나여서 재시도가 헤더와 본문을 **함께** 복구한다.
 */
export function SessionProvider({ children }: { children: ReactNode }) {
  const state = useSessionSource();
  return <SessionStateProvider value={state}>{children}</SessionStateProvider>;
}
