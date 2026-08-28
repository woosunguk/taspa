"use client";

import Image from "next/image";
import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { contextOf, type IconName, type NavLeaf } from "@/lib/nav";
import { NavIcon } from "./NavIcon";
import { displayNameOf, type CurrentUser } from "@/lib/session";

/**
 * 좌측 사이드바 — Gemini/Gmail 셸 패턴.
 *
 * 구조가 곧 요점이다: **1차 영역**(식권·조직 관리·매장 관리·플랫폼 관리)과, 지금 들어와 있는 영역의
 * **하위 기능 전체**(그룹 라벨 아래 세로 목록)를 한 기둥에 함께 그린다. 그전에는 하위 기능이 가로
 * 스크롤 탭 뒤에 숨어 있어 "무슨 기능이 있는지"가 화면에 드러나지 않았다 — 사이드바는 목록을 다 펼쳐
 * 기능의 존재 자체가 첫 화면에서 보이게 한다.
 *
 * 활성 항목은 **알약(pill)** — 참고한 셸들(Gemini 의 '새 채팅', Gmail 의 받은편지함)과 같은 문법이라
 * 사용자가 이미 아는 시각 언어다.
 */
interface AreaItem {
  href: string;
  label: string;
  icon: IconName;
  visible: boolean;
}

export function Sidebar({
  user,
  merchantAdmin,
  onNavigate,
}: {
  user: CurrentUser;
  merchantAdmin: boolean;
  /** 모바일 드로어가 링크 이동 시 닫히도록 상위가 주입한다(데스크톱은 미전달). */
  onNavigate?: () => void;
}) {
  const pathname = usePathname();
  const context = contextOf(pathname);

  const areas: AreaItem[] = [
    { href: "/meal", label: "식권", icon: "qr", visible: true },
    { href: "/console", label: "조직 관리", icon: "org", visible: user.manageableOrgs },
    { href: "/merchant", label: "매장 관리", icon: "store", visible: merchantAdmin },
    { href: "/admin", label: "플랫폼 관리", icon: "shield", visible: user.platformAdmin },
  ];

  return (
    <div className="flex h-full flex-col gap-1 overflow-y-auto px-3 py-4">
      <Link href="/" className="mb-3 flex items-center px-2" aria-label="taspa 홈" onClick={onNavigate}>
        <Image
          src="/brand/taspa_combined_logo_transparent.png"
          alt="taspa"
          width={1041}
          height={258}
          priority
          className="h-7 w-auto"
        />
      </Link>

      <nav aria-label="주요 메뉴" className="flex flex-col gap-0.5">
        {areas
          .filter((area) => area.visible)
          .map((area) => {
            // 컨텍스트가 열려 있는 영역은 아래 그룹이 활성을 표현한다 — 1차 항목까지 칠하면 두 곳이
            // 동시에 "현재"를 주장해 어디에 있는지 오히려 흐려진다.
            const inContext = context !== null && pathname.startsWith(area.href);
            const active = !inContext && (pathname === area.href || pathname.startsWith(`${area.href}/`));
            return (
              <SideLink
                key={area.href}
                href={area.href}
                icon={area.icon}
                active={active}
                onNavigate={onNavigate}
              >
                {area.label}
              </SideLink>
            );
          })}
      </nav>

      {context && (
        <nav aria-label={`${context.group} 메뉴`} className="mt-4 flex flex-col gap-0.5">
          <p className="px-3 pb-1 text-xs font-medium text-muted-foreground">{context.group}</p>
          {context.items.map((item: NavLeaf) => {
            const href = item.segment ? `${context.base}/${item.segment}` : context.base;
            const active = item.segment ? pathname.startsWith(href) : pathname === context.base;
            return (
              <SideLink
                key={item.segment || "home"}
                href={href}
                icon={item.icon}
                active={active}
                onNavigate={onNavigate}
              >
                {item.label}
              </SideLink>
            );
          })}
        </nav>
      )}

      <div className="mt-auto flex flex-col gap-0.5 border-t border-line pt-3">
        <SideLink
          href="/account"
          icon="account"
          active={pathname === "/account" || pathname.startsWith("/account/")}
          onNavigate={onNavigate}
        >
          <span className="truncate">{displayNameOf(user)}</span>
        </SideLink>
        {/* 로그아웃은 서버가 세션·쿠키를 정리해야 하므로 서버 경로 전체 내비게이션이다(SPA 라우팅 아님). */}
        <a
          href="/logout"
          className="flex items-center gap-3 rounded-full px-3 py-2 text-sm font-medium text-muted-foreground transition-colors hover:bg-line hover:text-foreground"
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.7"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden
            className="size-[18px]"
          >
            <path d="M14 4H6v16h8M10 12h11M18 8l3.5 4L18 16" />
          </svg>
          로그아웃
        </a>
      </div>
    </div>
  );
}

function SideLink({
  href,
  icon,
  active,
  onNavigate,
  children,
}: {
  href: string;
  icon: IconName;
  active: boolean;
  onNavigate?: () => void;
  children: ReactNode;
}) {
  return (
    <Link
      href={href}
      aria-current={active ? "page" : undefined}
      onClick={onNavigate}
      className={`flex items-center gap-3 rounded-full px-3 py-2 text-sm transition-colors ${
        active
          ? "bg-accent font-semibold text-accent-foreground"
          : "font-medium text-muted-foreground hover:bg-line hover:text-foreground"
      }`}
    >
      <NavIcon name={icon} />
      {children}
    </Link>
  );
}
