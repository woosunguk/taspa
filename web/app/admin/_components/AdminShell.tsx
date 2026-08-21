"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { AppShell, RequireAuth } from "@/components/AppShell";
import { EmptyState } from "@/components/feedback";
import { Toaster } from "@/components/ui/sonner";
import { useActiveTabScroll } from "@/lib/useActiveTabScroll";

/**
 * 플랫폼 관리 콘솔 골격.
 *
 * `platformAdmin` 이 아니면 화면을 비우지 않고 **왜 볼 수 없는지 말한다**. 링크를 숨기는 것은 UX 이고
 * 실제 인가는 서버(`/api/admin/**` hasRole ADMIN)가 판정한다 — 여기서 빈 화면을 보여주면 사용자는
 * 자기가 권한이 없는 건지 화면이 고장난 건지 구분할 수 없다.
 */
const TABS: { href: string; label: string }[] = [
  { href: "/admin", label: "대시보드" },
  { href: "/admin/orgs", label: "조직" },
  { href: "/admin/users", label: "사용자" },
  { href: "/admin/clients", label: "클라이언트" },
  { href: "/admin/merchants", label: "가맹점" },
  { href: "/admin/iam", label: "IAM 정책" },
  { href: "/admin/sso", label: "기업 SSO" },
  { href: "/admin/calendar", label: "캘린더" },
  { href: "/admin/payables", label: "지급 현황" },
  { href: "/admin/reconciliation", label: "정합성 대사" },
  { href: "/admin/audit", label: "감사 로그" },
];

export function AdminShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const activeTabRef = useActiveTabScroll<HTMLAnchorElement>(pathname);

  return (
    <AppShell>
      <RequireAuth>
        {(user) =>
          user.platformAdmin ? (
            <div className="flex flex-col gap-5">
              <nav aria-label="관리 메뉴" className="-mx-4 overflow-x-auto px-4">
                <div className="flex w-max items-center gap-1 rounded-lg bg-muted p-1">
                  {TABS.map((tab) => {
                    // 대시보드(/admin)는 정확 일치여야 한다 — 하위 경로 전부에 활성 표시가 붙으면 안 된다.
                    const active =
                      tab.href === "/admin" ? pathname === "/admin" : pathname.startsWith(tab.href);
                    return (
                      <Link
                        key={tab.href}
                        ref={active ? activeTabRef : undefined}
                        href={tab.href}
                        aria-current={active ? "page" : undefined}
                        className={`rounded-md px-3 py-1.5 text-sm font-medium whitespace-nowrap transition-colors ${
                          active
                            ? "bg-card text-foreground shadow-sm"
                            : "text-muted-foreground hover:text-foreground"
                        }`}
                      >
                        {tab.label}
                      </Link>
                    );
                  })}
                </div>
              </nav>
              {children}
            </div>
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
