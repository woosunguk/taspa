"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useSyncExternalStore, type ReactNode } from "react";
import { INTERRUPTED_KEY } from "@/lib/api";
import { useMerchantAccess } from "@/lib/merchantAccess";
import { displayNameOf, goToLogin, useSession, type CurrentUser } from "@/lib/session";
import { ErrorNotice, Loading } from "./feedback";

/**
 * 세션 DTO 에 없는 접근 사실. `CurrentUser` 에 담을 수 없는 것만 여기 둔다 — 지금은 가맹 관리자 여부
 * (`/api/account/me` 에 플래그가 없어 목록 API 로 대신 판단한다, `lib/merchantAccess.ts`).
 */
interface ExtraAccess {
  merchantAdmin: boolean;
}

interface NavItem {
  href: string;
  label: string;
  /** 이 항목을 볼 수 있는 사용자인지. 권한은 서버가 최종 판정하므로 여기선 **표시 여부**만 정한다. */
  visible: (user: CurrentUser, access: ExtraAccess) => boolean;
}

/**
 * 네비게이션 항목.
 *
 * ★여기의 `visible` 은 **보안 경계가 아니다.** 실제 인가는 서버의 정책 엔진이 판정하며, 링크를 숨기는 것은
 * 쓸 수 없는 메뉴를 보여주지 않기 위한 UX 다. 숨김만 믿고 서버 검사를 빼면 안 된다(반대도 마찬가지 —
 * 링크가 보여도 서버가 거부하면 화면은 403 을 정직하게 표시한다).
 */
const NAV: NavItem[] = [
  { href: "/meal", label: "식권", visible: () => true },
  { href: "/console", label: "조직 관리", visible: (u) => u.manageableOrgs },
  {
    href: "/merchant",
    label: "매장 관리",
    visible: (_u, a) => a.merchantAdmin,
  },
  { href: "/admin", label: "플랫폼 관리", visible: (u) => u.platformAdmin },
];

/**
 * "재인증 때문에 방금 작업이 완료되지 않았습니다" 배너.
 *
 * ★step-up 리다이렉트는 화면을 통째로 갈아엎으므로 작성 중이던 입력이 사라지는데, 돌아온 화면은
 * 아무 말도 하지 않았다 — 사용자는 저장이 **끝났다고 믿고** 떠난다. 실패했다는 사실 자체가 어디에도
 * 남지 않는 형태라, 초대가 안 갔다는 것을 며칠 뒤 상대가 물어봐야 안다.
 *
 * 토스트가 아니라 **배너**인 이유: `Toaster` 는 관리·계정 레이아웃에만 있어서 조직 콘솔에서는
 * 아무것도 뜨지 않는다. 여기(모든 화면을 감싸는 껍데기)에 두면 어느 화면에서 끊겼든 보인다.
 */
/*
 * ★sessionStorage 를 **effect 에서 setState 로** 읽지 않는다(`react-hooks/set-state-in-effect`).
 * 그 패턴은 첫 프레임에 "중단된 작업이 없다"를 그린 뒤 한 박자 늦게 배너를 밀어 넣어 화면이 튄다 —
 * CLAUDE.md 의 "상태 초기화는 effect 가 아니라 렌더 중에"와 같은 이유다. 대신 브라우저 저장소를
 * 외부 스토어로 보고 `useSyncExternalStore` 로 읽는다(SSR 스냅샷이 따로 있어 하이드레이션도 안전하다).
 *
 * 값은 **처음 읽을 때 소비**한다(읽고 지운다) — 새로고침마다 같은 배너가 다시 뜨면 그것도 거짓말이다.
 */
let interruptedCache: string | null | undefined;

function readInterrupted(): string | null {
  if (interruptedCache !== undefined) return interruptedCache;
  try {
    const stored = sessionStorage.getItem(INTERRUPTED_KEY);
    if (stored) sessionStorage.removeItem(INTERRUPTED_KEY);
    // 재인증 화면 자체로 끊긴 경우는 알릴 것이 없다.
    interruptedCache = stored && !stored.startsWith("/reauth") ? stored : null;
  } catch {
    // sessionStorage 가 막혀 있으면(사생활 보호 모드) 알릴 방법이 없다 — 화면을 깨뜨리지는 않는다.
    interruptedCache = null;
  }
  return interruptedCache;
}

/*
 * ★**해제 상태도 모듈 레벨이어야 한다.**
 *
 * 값(`interruptedCache`)은 모듈에 남는데 '닫기'만 컴포넌트 상태로 두면, 라우트를 옮겨 이 컴포넌트가
 * 다시 마운트될 때마다 배너가 **되살아난다** — 탭을 새로고침할 때까지 계속 따라다니는 셈이라
 * "한 번 알린다"는 의도가 정확히 뒤집힌다(적대 리뷰에서 잡혔다).
 * 그래서 해제도 같은 저장소에 담고 구독자에게 알린다.
 */
let interruptedDismissed = false;
const interruptedListeners = new Set<() => void>();

function subscribeInterrupted(listener: () => void): () => void {
  interruptedListeners.add(listener);
  return () => {
    interruptedListeners.delete(listener);
  };
}

function dismissInterrupted(): void {
  interruptedDismissed = true;
  interruptedListeners.forEach((listener) => listener());
}

/** 스냅샷 = "지금 보여줄 값". 해제됐으면 null — 참조 동일성이 유지되어 재렌더 루프가 없다. */
function interruptedSnapshot(): string | null {
  return interruptedDismissed ? null : readInterrupted();
}

/**
 * "재인증 때문에 방금 작업이 완료되지 않았습니다" 배너.
 *
 * ★step-up 리다이렉트는 화면을 통째로 갈아엎으므로 작성 중이던 입력이 사라지는데, 돌아온 화면은
 * 아무 말도 하지 않았다 — 사용자는 저장이 **끝났다고 믿고** 떠난다. 실패했다는 사실 자체가 어디에도
 * 남지 않는 형태라, 초대가 안 갔다는 것을 며칠 뒤 상대가 물어봐야 안다.
 *
 * 토스트가 아니라 **배너**인 이유: `Toaster` 는 관리·계정 레이아웃에만 있어서 조직 콘솔에서는
 * 아무것도 뜨지 않는다. 여기(모든 화면을 감싸는 껍데기)에 두면 어느 화면에서 끊겼든 보인다.
 */
function InterruptedNotice() {
  const interrupted = useSyncExternalStore(subscribeInterrupted, interruptedSnapshot, () => null);

  if (!interrupted) return null;
  return (
    <div className="border-b border-warning/30 bg-warning-soft">
      <div className="mx-auto flex w-full max-w-6xl flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2.5 text-sm text-warning">
        <span className="font-medium">방금 작업은 완료되지 않았습니다.</span>
        <span>재인증이 필요해 중단됐습니다 — 다시 시도해 주세요.</span>
        <button
          type="button"
          className="ml-auto underline-offset-2 hover:underline"
          onClick={dismissInterrupted}
        >
          닫기
        </button>
      </div>
    </div>
  );
}

export function AppShell({ children }: { children: ReactNode }) {
  const session = useSession();
  const pathname = usePathname();
  const merchantAdmin = useMerchantAccess(session.status === "authenticated");
  const access: ExtraAccess = { merchantAdmin };

  return (
    <div className="flex min-h-full flex-col">
      {/*
        ★헤더는 **고정**한다. 조직 콘솔 개요는 세로 2,000px 을 넘고 관리 콘솔도 비슷한데, 스크롤을
        내리는 순간 네비게이션이 사라져 다른 영역으로 가려면 매번 맨 위로 올라와야 했다.
        반투명 + blur 는 장식이 아니라 "이 줄은 내용 위에 떠 있다"는 신호다(불투명하면 붙어 있는지
        떠 있는지 구분되지 않아 스크롤 중 내용이 잘린 것처럼 보인다).
      */}
      <header className="sticky top-0 z-40 border-b border-line bg-card/85 backdrop-blur-md">
        {/*
          ★모바일은 **두 줄**로 접는다(위: 브랜드+계정, 아래: 가로 스크롤 메뉴).
          한 줄에 다 넣으면 390px 에서 메뉴가 눌려 "조/직/관/리" 처럼 **글자마다 줄이 바뀌어**
          헤더를 읽을 수 없게 된다(실측). `sm:contents` 로 ≥sm 에서는 이 래퍼가 사라져
          기존 데스크톱 한 줄 배치가 그대로 유지된다.
        */}
        <div className="mx-auto flex w-full max-w-6xl flex-col gap-2 px-4 py-3 sm:flex-row sm:items-center sm:gap-6">
          <div className="flex items-center justify-between gap-4 sm:contents">
            <Link href="/" className="text-lg font-medium tracking-tight whitespace-nowrap text-foreground">
              tas<b className="font-bold text-brand">pa</b>
            </Link>

            <div className="flex items-center gap-3 whitespace-nowrap sm:order-last sm:ml-auto">
              {session.status === "authenticated" ? (
                <>
                  <a
                    href="/account"
                    className="max-w-32 truncate text-sm text-muted-foreground hover:text-foreground sm:max-w-none"
                  >
                    {displayNameOf(session.user)}
                  </a>
                  {/* 로그아웃은 서버가 세션·쿠키를 정리해야 하므로 서버 경로로 보낸다(SPA 라우팅 아님). */}
                  <a href="/logout" className="text-sm text-muted-foreground hover:text-foreground">
                    로그아웃
                  </a>
                </>
              ) : session.status === "anonymous" ? (
                <a href="/login" className="text-sm font-medium text-primary hover:underline">
                  로그인
                </a>
              ) : null}
            </div>
          </div>

          {session.status === "authenticated" && (
            <nav
              className="-mx-4 overflow-x-auto px-4 sm:mx-0 sm:overflow-visible sm:px-0"
              aria-label="주요 메뉴"
            >
              <div className="flex w-max items-center gap-1 sm:w-auto">
                {NAV.filter((item) => item.visible(session.user, access)).map((item) => {
                  const active = pathname === item.href || pathname.startsWith(`${item.href}/`);
                  return (
                    <Link
                      key={item.href}
                      href={item.href}
                      aria-current={active ? "page" : undefined}
                      className={`rounded-lg px-3 py-1.5 text-sm whitespace-nowrap transition-colors ${
                        active
                          ? "bg-accent font-semibold text-accent-foreground"
                          : "font-medium text-muted-foreground hover:bg-line hover:text-foreground"
                      }`}
                    >
                      {item.label}
                    </Link>
                  );
                })}
              </div>
            </nav>
          )}
        </div>
      </header>

      <InterruptedNotice />

      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-6">
        {session.status === "loading" ? <Loading /> : children}
      </main>
    </div>
  );
}

/**
 * 로그인이 필요한 화면을 감싼다. 익명이면 서버 로그인 화면으로 보낸다(SPA 가 로그인 UI 를 재구현하지 않는다 —
 * MFA·이메일 인증·패스키·소셜 게이트가 전부 서버 플로우에 있다).
 *
 * ★`error`(신원 확인 실패)를 익명과 **같이 다루지 않는다.** 서버가 500 을 내는 동안 로그인으로 보내면
 * 세션이 멀쩡한 사용자가 이유 없이 튕기고, 로그인에 성공해도 기본 착지가 이 화면이라 제자리로 돌아온다 —
 * 사용자는 장애를 인지하지 못한 채 같은 일을 반복한다. 여기서는 사실을 말하고 재시도를 준다.
 * `/account`·`/meal`·`/console`·`/merchant`·`/admin/*` 5개 화면이 모두 이 분기에 의존한다.
 *
 * `useSession` 은 루트 프로바이더의 상태를 읽으므로 여기서 준 `retry` 가 헤더(`AppShell`)까지
 * 함께 복구한다 — 둘이 각자 조회하던 시절엔 본문만 살아나고 헤더는 error 로 남았다.
 */
export function RequireAuth({ children }: { children: (user: CurrentUser) => ReactNode }) {
  const session = useSession();
  const anonymous = session.status === "anonymous";

  // 로그인 이동은 **렌더 중에 하지 않는다.** 렌더는 여러 번 실행될 수 있어(동시성 렌더·StrictMode)
  // 같은 이동이 중복으로 걸리고, 그 사이 `continue` 로 실릴 현재 경로가 바뀌어 있을 수도 있다.
  // 부수효과는 커밋 이후에 한 번만. 이동 자체는 `goToLogin` 한 곳이 정한다(경로만 넘김 — open redirect 방지).
  useEffect(() => {
    if (anonymous) goToLogin();
  }, [anonymous]);

  if (session.status === "loading") return <Loading />;
  if (session.status === "error") return <ErrorNotice message={session.message} onRetry={session.retry} />;
  if (anonymous) return <Loading label="로그인 화면으로 이동합니다" />;
  return <>{children(session.user)}</>;
}
