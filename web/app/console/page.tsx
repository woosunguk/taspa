"use client";

import { useEffect } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ChevronRightIcon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { useApi } from "@/lib/useApi";
import { formatCount, orgStatusLabel, roleLabel } from "./_lib/labels";
import type { AdministeredOrg } from "./_lib/types";

/**
 * 조직 선택 화면. 관리 대상이 하나뿐이면 고르는 행위 자체가 군더더기라 바로 그 조직으로 보낸다
 * (`replace` — 뒤로가기가 이 화면과 조직 화면 사이를 왕복하지 않게).
 */
export default function ConsoleHomePage() {
  const router = useRouter();
  const orgs = useApi<AdministeredOrg[]>("/api/orgs/mine");
  const only = orgs.data?.length === 1 ? orgs.data[0] : undefined;

  useEffect(() => {
    if (only) router.replace(`/console/${only.id}`);
  }, [only, router]);

  return (
    <div className="flex flex-col gap-5">
      <div>
        <h1 className="text-display text-foreground">조직 관리</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          내가 조직관리자로 있는 조직입니다. 구성원·초대·조직구조·도메인·예측·청구서를 관리할 수 있습니다.
        </p>
      </div>

      {orgs.error && <ErrorNotice message={orgs.error} onRetry={orgs.reload} />}
      {orgs.loading && <RowsSkeleton rows={3} />}

      {!orgs.loading && !orgs.error && orgs.data?.length === 0 && (
        <Card>
          <CardContent>
            <EmptyState
              title="관리 중인 조직이 없습니다"
              // ★"다시 로그인하라"고 하지 않는다. 이 목록은 `/api/orgs/mine` 라이브 조회라 권한이 붙는 즉시
              //   반영된다(재로그인이 필요한 것은 **플랫폼 ADMIN** 역할뿐 — 그건 세션에 굳는다).
              //   필요 없는 절차를 안내하면 사용자는 그것부터 해 보고, 그래도 안 되면 제품을 의심한다.
              description="조직관리자 권한은 조직의 다른 관리자나 플랫폼 운영자가 부여합니다. 권한을 받으면 새로고침만으로 이 목록에 나타납니다."
            />
          </CardContent>
        </Card>
      )}

      {!orgs.loading && (orgs.data?.length ?? 0) > 0 && (
        <div className="grid gap-3 sm:grid-cols-2">
          {orgs.data?.map((org) => (
            <Link key={org.id} href={`/console/${org.id}`} className="group">
              <Card className="h-full transition-colors group-hover:border-primary">
                <CardContent className="flex items-center gap-3">
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <h2 className="truncate text-base font-semibold text-foreground">{org.name}</h2>
                      {org.status !== "ACTIVE" && (
                        <Badge variant="destructive">{orgStatusLabel(org.status)}</Badge>
                      )}
                    </div>
                    <p className="mt-1 truncate text-sm text-muted-foreground">
                      {org.slug} · 구성원 {formatCount(org.memberCount)}명 · {roleLabel(org.role)} ·{" "}
                      {org.timezone}
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
