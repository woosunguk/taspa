"use client";

import { useCallback, useEffect, useState } from "react";
import { AppShell, RequireAuth } from "@/components/AppShell";
import { EmptyState, ErrorNotice, Loading } from "@/components/feedback";
import { Card, CardContent } from "@/components/ui/card";
import { useMerchantAccess } from "@/lib/merchantAccess";
import { displayNameOf, type CurrentUser } from "@/lib/session";
import { useApi } from "@/lib/useApi";
import { OrgPicker } from "./OrgPicker";
import { QrPanel } from "./QrPanel";
import { QuickLinks } from "./QuickLinks";
import { SpendSummary } from "./SpendSummary";
import { TransactionList } from "./TransactionList";
import type { MealEntitlement, MealTransaction, MyMembership } from "./types";

/**
 * 직원 개인 대시보드.
 *
 * 이 화면이 답하는 질문은 순서대로: ①지금 결제할 QR 을 어떻게 받나 ②이번 달 얼마 나갔나
 * ③최근에 어디서 썼나 ④내가 갈 수 있는 다른 화면은 어디인가.
 *
 * ★새 라우트를 만들지 않고 `/meal` 을 확장한 이유: 같은 질문·같은 데이터를 다루는 화면이 둘이면
 * "식권"과 "대시보드"가 서로 경쟁하고, 무엇보다 전역 네비게이션(`components/AppShell`)이 직원용
 * 진입점으로 `/meal` 하나만 노출한다. 별도 라우트는 링크 없는 고아 화면이 된다.
 */

/**
 * 이력 조회 건수. 서버 상한이 100이다(`MealQrController.MAX_LIMIT`).
 *
 * 이제 이 목록은 **집계 모수가 아니다** — 이번 달 합계·한도 잔여는 서버(`/api/meal/entitlement`)가
 * 조직 달력으로 계산해 준다. 여기서는 조직별로 걸러 최근 몇 건을 보여 주기 위한 여유분일 뿐이다.
 */
const HISTORY_LIMIT = 100;

/** 화면에 그리는 최근 건수. 대시보드는 훑어보는 곳이라 전체 목록은 목적이 아니다. */
const RECENT_SHOWN = 8;

/** 마지막으로 고른 조직을 기억한다 — 소속이 여러 곳인 사람이 매번 고르지 않도록. 표시 편의일 뿐이다. */
const LAST_ORG_KEY = "taspa.meal.lastOrgId";

/** 다음 끼니창 자동 갱신 타이머의 상한. 이보다 멀면 그 전에 화면이 다시 열린다고 보고 걸지 않는다. */
const WINDOW_WATCH_MAX_MS = 4 * 60 * 60 * 1000;

export default function MealPage() {
  return (
    <AppShell>
      <RequireAuth>{(user) => <MealDashboard user={user} />}</RequireAuth>
    </AppShell>
  );
}

function MealDashboard({ user }: { user: CurrentUser }) {
  const memberships = useApi<MyMembership[]>("/api/orgs/memberships");
  const transactions = useApi<MealTransaction[]>(`/api/meal/transactions?limit=${HISTORY_LIMIT}`);
  const merchantAdmin = useMerchantAccess(true);
  const [chosenOrgId, setChosenOrgId] = useState<string | null>(null);
  const remembered = useRememberedOrgId();

  const list = memberships.data ?? [];
  const has = (orgId: string | null) => orgId !== null && list.some((m) => m.orgId === orgId);

  /*
   * 선택은 상태가 아니라 **파생값**이다: 사용자가 고른 조직이 아직 유효하면 그것, 아니면 기억해 둔
   * 조직, 그것도 없으면 첫 번째(=소속이 하나면 자동 선택). 목록이 바뀔 때 상태를 맞추는 effect 가
   * 필요 없어지고, 목록과 선택이 어긋나는 순간도 생기지 않는다.
   */
  const selectedOrgId = has(chosenOrgId)
    ? chosenOrgId
    : has(remembered)
      ? remembered
      : (list[0]?.orgId ?? null);

  const selectOrg = useCallback((orgId: string) => {
    setChosenOrgId(orgId);
    writeLastOrgId(orgId);
  }, []);

  const selected = list.find((m) => m.orgId === selectedOrgId) ?? null;

  /*
   * 선택한 조직의 자격(끼니창·한도·소진분). 조직을 바꾸면 경로가 바뀌어 자동으로 다시 조회된다.
   * 조직이 아직 없으면 null 경로 — useApi 가 요청 자체를 보내지 않는다.
   */
  const entitlement = useApi<MealEntitlement>(
    selectedOrgId ? `/api/meal/entitlement?orgId=${encodeURIComponent(selectedOrgId)}` : null,
  );

  const reloadTransactions = transactions.reload;
  const reloadEntitlement = entitlement.reload;

  /** 결제 직후·QR 만료 후에는 이력과 자격(오늘 횟수·월 누계)이 함께 바뀐다 — 둘 다 새로 읽는다. */
  const refreshUsage = useCallback(() => {
    reloadTransactions();
    reloadEntitlement();
  }, [reloadTransactions, reloadEntitlement]);

  useReloadWhenWindowOpens(entitlement.data, reloadEntitlement);

  /*
   * 최근 내역은 선택한 조직 것만 보여 준다 — 위쪽 카드가 그 조직 기준 숫자를 말하고 있으므로,
   * 목록만 전 조직 합본이면 둘이 안 맞아 보인다(`orgId` 가 서버 응답에 있어 가능해진 구분).
   * 소속이 하나면 필터가 의미 없으니 그대로 둔다.
   */
  const visibleTransactions =
    list.length > 1 && selectedOrgId
      ? transactions.data?.filter((t) => t.orgId === selectedOrgId)
      : transactions.data;

  return (
    <div className="flex w-full flex-col gap-6">
      <header>
        <h1 className="text-display text-foreground">안녕하세요, {displayNameOf(user)}님</h1>
        {/*
          ★소속 조직이 없으면 "결제됩니다"라고 단정하지 않는다. 그 문장은 이 화면 전체를
          **쓸 수 있는 식권**으로 읽히게 하는데, 아래 카드는 곧바로 "소속 조직이 없습니다"라고 말한다 —
          한 화면이 두 가지를 주장하면 사용자는 뭔가 고장났다고 판단한다(가입 직후의 첫 화면이다).
        */}
        <p className="mt-1 text-sm text-muted-foreground">
          {!memberships.loading && list.length === 0
            ? "회사(조직)에 소속되면 이 화면에서 식권을 발급받을 수 있습니다."
            : "카운터에서 QR 을 보여주면 회사 식대로 결제됩니다."}
        </p>
        {!user.emailVerified && (
          <p className="mt-2 text-sm text-[color:var(--taspa-warning)]">
            이메일 인증이 완료되지 않았습니다. 일부 기능이 제한될 수 있습니다.
          </p>
        )}
      </header>

      <div className="grid gap-6 lg:grid-cols-[minmax(0,22rem)_minmax(0,1fr)] lg:items-start">
        {/* ① 가장 큰 액션 — 결제 직전에 누르는 버튼이라 화면 맨 앞(모바일)·왼쪽(데스크톱)에 둔다. */}
        <Card>
          <CardContent className="flex flex-col gap-5">
            {memberships.error ? (
              <ErrorNotice message={memberships.error} onRetry={memberships.reload} />
            ) : memberships.loading ? (
              <Loading label="소속 조직을 확인하는 중" />
            ) : list.length === 0 ? (
              <EmptyState
                title="소속 조직이 없습니다"
                description="식권은 조직에 소속된 구성원만 쓸 수 있습니다. 회사 관리자에게 초대를 요청하거나, 회사 이메일로 인증했는지 확인하세요."
              />
            ) : (
              <>
                <OrgPicker memberships={list} selectedOrgId={selectedOrgId} onSelect={selectOrg} />
                <QrPanel
                  orgId={selectedOrgId}
                  orgName={selected?.orgName ?? null}
                  entitlement={entitlement.data}
                  onIssued={refreshUsage}
                  onExpired={refreshUsage}
                />
              </>
            )}
          </CardContent>
        </Card>

        <div className="flex flex-col gap-6">
          {/*
            ② 이번 달 사용·한도. 조직이 정해진 뒤에만 띄운다 — 한도와 집계는 (사용자 × 조직) 단위라
            어느 조직인지 모르면 말할 수 있는 숫자가 없다.
          */}
          {selectedOrgId && (
            <SpendSummary
              entitlement={entitlement.data}
              loading={entitlement.loading}
              error={entitlement.error}
              onRetry={entitlement.reload}
            />
          )}

          {/* ③ 최근 사용 내역 — 소속이 없으면 감춘다(빈 목록이 "아직 안 썼다"로 읽혀 오해를 굳힌다). */}
          {list.length > 0 && (
            <section aria-labelledby="recent-heading">
              <h2 id="recent-heading" className="mb-2 text-base font-medium text-foreground">
                최근 사용 내역
                {list.length > 1 && selected && (
                  <span className="ml-2 text-sm font-normal text-muted-foreground">· {selected.orgName}</span>
                )}
              </h2>
              <Card>
                <CardContent>
                  <TransactionList
                    transactions={visibleTransactions?.slice(0, RECENT_SHOWN)}
                    loading={transactions.loading}
                    error={transactions.error}
                    onRetry={transactions.reload}
                  />
                </CardContent>
              </Card>
              {visibleTransactions && visibleTransactions.length > RECENT_SHOWN && (
                <p className="mt-2 text-xs text-muted-foreground">최근 {RECENT_SHOWN}건만 표시했습니다.</p>
              )}
            </section>
          )}

          {/* ④ 다른 화면 진입점 — 권한이 있을 때만 보인다(표시 여부일 뿐, 인가는 서버가 판정). */}
          <QuickLinks user={user} merchantAdmin={merchantAdmin} />
        </div>
      </div>
    </div>
  );
}

/**
 * 다음 끼니창이 열리는 순간 자격을 다시 읽는다 — 11:29 에 화면을 열어 둔 사람이 11:30 이 지나도
 * 잠긴 버튼을 보고 있으면 안 된다.
 *
 * 대기 시간은 **서버 값끼리의 차이**(nextWindow.startsAt − serverNow)로 잰다. 기기 시각과 비교하면
 * 시계가 틀어진 기기에서 타이머가 영영 안 오거나 너무 일찍 온다 — 서버가 준 두 시각의 간격은
 * 기기 시계와 무관하다.
 */
function useReloadWhenWindowOpens(entitlement: MealEntitlement | undefined, reload: () => void): void {
  // 이미 창이 열려 있으면 감시할 이유가 없다.
  const startsAt = entitlement && !entitlement.currentWindow ? entitlement.nextWindow?.startsAt : undefined;
  const serverNow = entitlement?.serverNow;

  useEffect(() => {
    if (!startsAt || !serverNow) return;
    const remaining = Date.parse(startsAt) - Date.parse(serverNow);
    if (!Number.isFinite(remaining) || remaining <= 0 || remaining > WINDOW_WATCH_MAX_MS) return;
    // 경계에서 1초 여유 — 서버가 [start, end) 로 판정하므로 딱 맞춰 부르면 아직 닫혀 있을 수 있다.
    const timer = window.setTimeout(reload, remaining + 1000);
    return () => window.clearTimeout(timer);
  }, [startsAt, serverNow, reload]);
}

/**
 * 지난번에 고른 조직. useState 지연 초기화라 최초 1회만 저장소를 읽는다. 서버 렌더에서는 null 이지만
 * 이 값이 화면에 반영되는 시점에는 이미 조직 목록이 도착해 있어(첫 렌더는 로딩 상태) 하이드레이션
 * 결과가 달라지지 않는다. localStorage 를 쓸 수 없는 환경(프라이빗 모드·차단)에서도 죽지 않게 감싼다.
 */
function useRememberedOrgId(): string | null {
  const [remembered] = useState<string | null>(() =>
    typeof window === "undefined" ? null : readLastOrgId(),
  );
  return remembered;
}

function readLastOrgId(): string | null {
  try {
    return window.localStorage.getItem(LAST_ORG_KEY);
  } catch {
    return null;
  }
}

function writeLastOrgId(orgId: string): void {
  try {
    window.localStorage.setItem(LAST_ORG_KEY, orgId);
  } catch {
    /* 기억하지 못할 뿐, 선택은 이미 화면 상태에 반영돼 있다. */
  }
}
