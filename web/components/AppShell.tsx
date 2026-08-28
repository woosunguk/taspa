"use client";

import Image from "next/image";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState, useSyncExternalStore, type ReactNode } from "react";
import { Sidebar } from "./Sidebar";
import { INTERRUPTED_KEY } from "@/lib/api";
import { useMerchantAccess } from "@/lib/merchantAccess";
import { goToLogin, useSession, type CurrentUser } from "@/lib/session";
import { ErrorNotice, Loading } from "./feedback";

/**
 * 세션 DTO 에 없는 접근 사실. `CurrentUser` 에 담을 수 없는 것만 여기 둔다 — 지금은 가맹 관리자 여부
 * (`/api/account/me` 에 플래그가 없어 목록 API 로 대신 판단한다, `lib/merchantAccess.ts`).
 */
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
      <div className="mx-auto flex w-full max-w-[1560px] flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2.5 text-sm text-warning">
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
  // 모바일 드로어 — 경로가 바뀌면 닫는다(열린 채 화면만 바뀌면 내용이 가려진 채 남는다).
  // ★effect 의 setState 가 아니라 **렌더 중 신호 비교**로 닫는다(이 저장소의 CI 게이트 규칙,
  //   `react-hooks/set-state-in-effect`) — effect 방식은 새 화면 위에 드로어가 한 프레임 남는다.
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [drawerPath, setDrawerPath] = useState(pathname);
  if (drawerPath !== pathname) {
    setDrawerPath(pathname);
    if (drawerOpen) setDrawerOpen(false);
  }

  const authenticated = session.status === "authenticated";

  return (
    <div className="flex min-h-full">
      {/*
        ★데스크톱 좌측 사이드바(Gemini/Gmail 셸 패턴). 이전의 상단 탭은 하위 기능(조직 10개·관리 11개)을
        가로 스크롤 뒤에 숨겼다 — 사이드바는 1차 영역과 현재 영역의 하위 기능 전체를 세로로 펼쳐
        "무슨 기능이 있는지"가 첫 화면에서 보이게 한다. sticky + h-screen 이라 스크롤해도 남는다.
      */}
      {authenticated && (
        <aside className="sticky top-0 hidden h-screen w-60 shrink-0 border-r border-line bg-card lg:block">
          <Sidebar user={session.user} merchantAdmin={merchantAdmin} />
        </aside>
      )}

      {/* 모바일 드로어 — 햄버거로 연다. 사이드바와 같은 내용(정의가 한 곳이어야 두 화면이 갈리지 않는다). */}
      {authenticated && drawerOpen && (
        <div className="fixed inset-0 z-50 lg:hidden" role="dialog" aria-modal="true" aria-label="메뉴">
          <button
            type="button"
            aria-label="메뉴 닫기"
            className="absolute inset-0 bg-foreground/40"
            onClick={() => setDrawerOpen(false)}
          />
          <div className="absolute inset-y-0 left-0 w-72 max-w-[85vw] border-r border-line bg-card shadow-xl">
            <Sidebar
              user={session.user}
              merchantAdmin={merchantAdmin}
              onNavigate={() => setDrawerOpen(false)}
            />
          </div>
        </div>
      )}

      <div className="flex min-w-0 flex-1 flex-col">
        {/*
          상단 바 — 모바일에서는 햄버거 + 워드마크, **비로그인 상태에서는 데스크톱에서도** 보인다.
          사이드바는 로그인 사용자 전용이라, 익명 데스크톱에서 이 바까지 숨기면 브랜드도 로그인 진입점도
          없는 맨 화면이 된다(실측으로 발견 — 홈이 문패 없는 문서처럼 떴다).
        */}
        <header
          className={`sticky top-0 z-40 border-b border-line bg-card/85 backdrop-blur-md ${
            authenticated ? "lg:hidden" : ""
          }`}
        >
          <div className="flex items-center gap-3 px-4 py-2.5">
            {authenticated && (
              <button
                type="button"
                aria-label="메뉴 열기"
                className="rounded-lg p-1.5 text-muted-foreground hover:bg-line hover:text-foreground"
                onClick={() => setDrawerOpen(true)}
              >
                <svg
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                  aria-hidden
                  className="size-5"
                >
                  <path d="M4 6h16M4 12h16M4 18h16" />
                </svg>
              </button>
            )}
            <Link href="/" className="flex items-center" aria-label="taspa 홈">
              <Image
                src="/brand/taspa_combined_logo_transparent.png"
                alt="taspa"
                width={1041}
                height={258}
                priority
                className="h-6 w-auto"
              />
            </Link>
            <div className="ml-auto">
              {session.status === "anonymous" && (
                <a href="/login" className="text-sm font-medium text-primary hover:underline">
                  로그인
                </a>
              )}
            </div>
          </div>
        </header>

        <InterruptedNotice />

        <main className="mx-auto w-full max-w-[1560px] flex-1 px-4 py-5 sm:px-6">
          {session.status === "loading" ? <Loading /> : children}
        </main>
      </div>
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
