"use client";

import { useEffect } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ChevronRightIcon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { useApi } from "@/lib/useApi";
import { merchantRoleLabel, merchantStatusLabel, merchantCategoryLabel } from "./_lib/format";
import type { MyMerchantsResponse } from "./_lib/types";

/**
 * 매장 선택 화면. 관리 대상이 하나뿐이면 고르는 행위 자체가 군더더기라 바로 그 매장으로 보낸다
 * (`replace` — 뒤로가기가 이 화면과 매장 화면 사이를 왕복하지 않게).
 */
export default function MerchantHomePage() {
  const router = useRouter();
  const mine = useApi<MyMerchantsResponse>("/api/merchant-console/mine");
  const open = mine.data?.merchants ?? [];
  const blocked = mine.data?.blocked ?? [];
  /*
   * 하나뿐일 때만 자동 이동한다 — 열 수 없는 매장이 함께 있으면 그 사유를 봐야 하므로 이 화면에 머문다.
   */
  const only = open.length === 1 && blocked.length === 0 ? open[0] : undefined;

  useEffect(() => {
    if (only) router.replace(`/merchant/${only.merchantId}`);
  }, [only, router]);

  return (
    <div className="flex flex-col gap-5">
      <div>
        <h1 className="text-display text-foreground">매장 관리</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          내가 매장 관리자로 있는 가맹점입니다. 식수 예측과 식수 로그를 확인할 수 있습니다.
        </p>
      </div>

      {mine.error && <ErrorNotice message={mine.error} onRetry={mine.reload} />}
      {mine.loading && <RowsSkeleton rows={2} />}

      {!mine.loading && !mine.error && open.length === 0 && blocked.length === 0 && (
        <Card>
          <CardContent>
            <EmptyState
              title="관리 중인 매장이 없습니다"
              description="매장 관리자 권한은 플랫폼 운영자가 부여합니다. 권한을 받은 뒤 이 목록에 나타납니다."
            />
          </CardContent>
        </Card>
      )}

      {/*
        ★열 수 없는 매장을 **사유와 함께** 먼저 보여준다.
        이 블록이 없던 동안, 플랫폼 관리자가 절차대로 매장을 만들고(등록 모달 기본값이 PENDING 이었다)
        담당자를 지정해도 — 양쪽 다 성공을 보고한다 — 그 담당자에게는 매장이 존재하지 않는 것과 똑같이
        보였고, 빈 화면은 **이미 가진 권한을 다시 요청하라**고 안내했다. 사장은 플랫폼에 문의하고
        관리자 화면에는 담당자가 ACTIVE 로 보이니 서로 어긋난 채 온보딩이 멈춘다.
        링크로 만들지 않는 이유: 진입 집합이 아니라 누르면 403 이다(목록과 인가의 조건 일치 불변식).
      */}
      {!mine.loading && blocked.length > 0 && (
        <Card>
          <CardContent className="flex flex-col gap-3">
            <div>
              <h2 className="text-base font-semibold text-foreground">아직 열 수 없는 매장</h2>
              <p className="mt-1 text-sm text-muted-foreground">
                담당자로 지정돼 있지만 매장 상태 때문에 지금은 콘솔과 결제가 열리지 않습니다. 플랫폼 운영자가
                매장을 활성 상태로 바꾸면 바로 이용할 수 있습니다.
              </p>
            </div>
            <ul className="flex flex-col gap-2">
              {blocked.map((merchant) => (
                <li
                  key={merchant.merchantId}
                  className="surface-sunken flex flex-wrap items-center gap-x-3 gap-y-1 rounded-lg px-3 py-2.5"
                >
                  <span className="min-w-0 flex-1 truncate font-medium text-foreground">{merchant.name}</span>
                  <Badge variant="secondary">{merchantStatusLabel(merchant.status)}</Badge>
                  <span className="text-sm text-muted-foreground">
                    {merchant.status === "PENDING"
                      ? "활성화 대기 중 — 운영자 승인이 필요합니다"
                      : "정지됨 — 운영자에게 문의하세요"}
                  </span>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      )}

      {!mine.loading && open.length > 0 && (
        <div className="grid gap-3 sm:grid-cols-2">
          {open.map((merchant) => (
            <Link key={merchant.merchantId} href={`/merchant/${merchant.merchantId}`} className="group">
              <Card className="h-full transition-colors group-hover:border-primary">
                <CardContent className="flex items-center gap-3">
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <h2 className="truncate text-base font-semibold text-foreground">{merchant.name}</h2>
                      {merchant.status !== "ACTIVE" && (
                        <Badge variant="destructive">{merchantStatusLabel(merchant.status)}</Badge>
                      )}
                    </div>
                    <p className="mt-1 truncate text-sm text-muted-foreground">
                      {merchantCategoryLabel(merchant.category)} · {merchantRoleLabel(merchant.role)} ·{" "}
                      {merchant.timezone}
                    </p>
                  </div>
                  <ChevronRightIcon className="size-4 shrink-0 text-muted-foreground" />
                </CardContent>
              </Card>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
