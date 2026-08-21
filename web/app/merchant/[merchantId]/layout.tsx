"use client";

import type { ReactNode } from "react";
import Link from "next/link";
import { useParams, usePathname } from "next/navigation";
import { ArrowLeftIcon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { ErrorNotice } from "@/components/feedback";
import { Skeleton } from "@/components/ui/skeleton";
import { useApi } from "@/lib/useApi";
import { useSession } from "@/lib/session";
import { NoAccessCard } from "@/components/feedback";
import { MerchantProvider } from "../_lib/merchant-context";
import { merchantStatusLabel, merchantCategoryLabel } from "../_lib/format";
import type { MyMerchantsResponse } from "../_lib/types";

const TABS: { segment: string; label: string }[] = [
  { segment: "", label: "개요" },
  { segment: "transactions", label: "식수 로그" },
  { segment: "settlement", label: "정산" },
];

/**
 * 매장 하나의 관리 화면 껍데기 — 머리말(매장 이름·타임존)과 탭 내비게이션.
 *
 * Next 16 에서 동적 세그먼트 `params` 는 Promise 라 서버 컴포넌트에선 await 가 필요하다. 이 화면은
 * 세션 쿠키로 브라우저에서 API 를 호출하는 클라이언트 컴포넌트라 `useParams()` 로 읽는다.
 *
 * 머리말에 **타임존을 항상 노출**하는 이유: 이 콘솔의 모든 "오늘·내일"은 매장 타임존 기준이고, 그 값이
 * 바뀌면 과거 집계의 날짜 버킷이 통째로 이동한다. 보는 사람이 기준을 오해하지 않도록 화면에 못박는다.
 */
export default function MerchantConsoleLayout({ children }: { children: ReactNode }) {
  const params = useParams();
  const raw = params?.merchantId;
  const merchantId = Array.isArray(raw) ? raw[0] : (raw ?? "");
  const pathname = usePathname();

  // 매장 이름은 목록 API 한 번으로 얻는다(매장 단건 조회 API 가 따로 없다).
  const mine = useApi<MyMerchantsResponse>("/api/merchant-console/mine");
  const merchant = mine.data?.merchants.find((candidate) => candidate.merchantId === merchantId);
  // 진입 집합에는 없지만 담당자로는 지정된 매장 — 아래에서 "권한 없음"이 아니라 실제 사유를 말한다.
  const blocked = mine.data?.blocked.find((candidate) => candidate.merchantId === merchantId);
  const session = useSession();
  /*
   * 담당자도 아니고 활성화 대기 중인 매장도 아니면 — 이 매장은 이 사람 것이 아니다.
   * 조직 콘솔과 같은 이유로 껍데기를 그리지 않는다(패널마다 403 이 뜨는 화면은 "고장"으로 읽힌다).
   * 플랫폼 관리자는 통과 — 멤버가 아니어도 볼 수 있는 것이 그 역할의 정의다.
   */
  /*
   * ★`blocked`(담당자이지만 매장이 PENDING/SUSPENDED)도 **껍데기를 그리지 않는다.**
   *
   * 처음엔 blocked 를 denied 에서 제외했다 — 위 문단에서 사유를 안내하니 충분하다고 봤다. 그런데
   * 그 아래 탭·패널은 그대로 렌더되고 조회가 **전부 403** 이라, 사유 한 줄 밑에 붉은 오류 4~5개와
   * 영원히 실패할 '다시 시도' 가 깔린다 — 같은 날 세운 규칙("권한 없음은 오류가 아니라 상태다")이
   * 이 경로에서만 무효였다(적대 리뷰에서 잡혔다). 사유는 카드 안에서 말한다.
   */
  const denied =
    !mine.loading &&
    !mine.error &&
    !merchant &&
    session.status === "authenticated" &&
    !session.user.platformAdmin;

  const base = `/merchant/${merchantId}`;

  return (
    <MerchantProvider value={{ merchantId, merchant }}>
      <div className="flex flex-col gap-5">
        <div className="flex flex-col gap-2">
          <Link
            href="/merchant"
            className="inline-flex w-fit items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
          >
            <ArrowLeftIcon className="size-3.5" />
            매장 목록
          </Link>

          <div className="flex flex-wrap items-center gap-2">
            {mine.loading && !merchant ? (
              <Skeleton className="h-8 w-48" />
            ) : (
              <h1 className="text-display text-foreground">{merchant?.name ?? "매장"}</h1>
            )}
            {merchant && merchant.status !== "ACTIVE" && (
              <Badge variant="destructive">{merchantStatusLabel(merchant.status)}</Badge>
            )}
          </div>

          {merchant && (
            <p className="text-sm text-muted-foreground">
              {merchantCategoryLabel(merchant.category)} · 타임존 {merchant.timezone} (모든 날짜는 이
              기준입니다)
            </p>
          )}
          {/*
            ★"목록에 없다"로 뭉뚱그리지 않는다. 담당자로 지정됐지만 **매장이 아직 활성화되지 않은**
            경우가 실제로 가장 흔한데(등록 기본값이 PENDING 이었다), 그때 이 문구는 사실과 다르고
            아래 패널들의 403 과 합쳐져 "권한을 못 받았다"는 잘못된 결론으로 이끈다.
          */}
          {/*
            사유는 아래 NoAccessCard 가 말한다 — 같은 사실을 머리말과 카드에서 두 번 말하면
            어느 쪽이 최신인지 알 수 없고, 문구를 고칠 때 한쪽만 고치게 된다.
          */}
        </div>

        {mine.error && <ErrorNotice message={mine.error} onRetry={mine.reload} />}

        {denied ? (
          <NoAccessCard
            title={blocked ? "아직 열 수 없는 매장입니다" : "이 매장의 관리 권한이 없습니다"}
            description={
              blocked
                ? blocked.status === "PENDING"
                  ? "담당자 권한은 이미 부여돼 있습니다. 이 매장이 아직 활성화되지 않아 지금은 열리지 않습니다 — 플랫폼 운영자가 활성 상태로 바꾸면 바로 이용할 수 있습니다."
                  : "담당자 권한은 이미 부여돼 있습니다. 이 매장이 정지 상태라 조회가 막혀 있습니다 — 플랫폼 운영자에게 문의하세요."
                : "매장 담당자로 지정된 가맹점만 열 수 있습니다. 담당자 권한은 플랫폼 운영자가 부여합니다."
            }
            backHref="/merchant"
            backLabel="내 매장 목록으로"
          />
        ) : (
          <>
            <nav
              aria-label="매장 관리 메뉴"
              className="-mx-1 flex gap-1 overflow-x-auto border-b border-border px-1 pb-px"
            >
              {TABS.map((tab) => {
                const href = tab.segment ? `${base}/${tab.segment}` : base;
                const active = tab.segment ? pathname.startsWith(href) : pathname === base;
                return (
                  <Link
                    key={tab.segment || "forecast"}
                    href={href}
                    aria-current={active ? "page" : undefined}
                    className={`shrink-0 rounded-t-lg border-b-2 px-3 py-2 text-sm font-medium transition-colors ${
                      active
                        ? "border-primary text-foreground"
                        : "border-transparent text-muted-foreground hover:text-foreground"
                    }`}
                  >
                    {tab.label}
                  </Link>
                );
              })}
            </nav>

            {children}
          </>
        )}
      </div>
    </MerchantProvider>
  );
}
