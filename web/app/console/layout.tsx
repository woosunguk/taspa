"use client";

import type { ReactNode } from "react";
import { AppShell, RequireAuth } from "@/components/AppShell";
import { Toaster } from "@/components/ui/sonner";

/**
 * 조직 관리 콘솔의 껍데기.
 *
 * 여기서 로그인만 강제한다. **ORG_ADMIN 인지는 화면이 판단하지 않는다** — 인가는 서버 정책 엔진이 내리고,
 * 권한이 없으면 각 API 가 403 을 준다(화면은 그 사실을 그대로 표시한다). 링크·탭을 숨기는 것은 UX 일 뿐이다.
 */
export default function ConsoleLayout({ children }: { children: ReactNode }) {
  return (
    <AppShell>
      <RequireAuth>{() => children}</RequireAuth>
      {/* 루트 레이아웃에 아직 Toaster 가 없어 콘솔 범위에 둔다. 전역으로 올라가면 이 줄을 지워야 중복이 없다. */}
      <Toaster position="bottom-right" />
    </AppShell>
  );
}
