"use client";

import type { ReactNode } from "react";
import { AppShell, RequireAuth } from "@/components/AppShell";
import { EmptyState } from "@/components/feedback";
import { Toaster } from "@/components/ui/sonner";

/**
 * 플랫폼 관리 콘솔 골격. 하위 메뉴는 좌측 사이드바(lib/nav ADMIN_MENU)가 그린다 — 정의 중복 금지.
 *
 * `platformAdmin` 이 아니면 화면을 비우지 않고 **왜 볼 수 없는지 말한다**. 링크를 숨기는 것은 UX 이고
 * 실제 인가는 서버(`/api/admin/**` hasRole ADMIN)가 판정한다 — 여기서 빈 화면을 보여주면 사용자는
 * 자기가 권한이 없는 건지 화면이 고장난 건지 구분할 수 없다.
 */
export function AdminShell({ children }: { children: ReactNode }) {
  return (
    <AppShell>
      <RequireAuth>
        {(user) =>
          user.platformAdmin ? (
            <div className="flex flex-col gap-5">{children}</div>
          ) : (
            <EmptyState
              title="플랫폼 관리자만 볼 수 있는 화면입니다"
              description="이 콘솔은 users.role=ADMIN 계정에만 열립니다. 권한이 있어야 한다면 다른 플랫폼 관리자에게 역할 부여를 요청하세요. 역할은 로그인 시점에 세션에 반영되므로, 방금 승격되었다면 다시 로그인해야 합니다."
            />
          )
        }
      </RequireAuth>
      <Toaster position="bottom-right" />
    </AppShell>
  );
}
