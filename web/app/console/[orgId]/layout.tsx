"use client";

import type { ReactNode } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { ArrowLeftIcon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { ErrorNotice } from "@/components/feedback";
import { Skeleton } from "@/components/ui/skeleton";
import { useApi } from "@/lib/useApi";
import { useSession } from "@/lib/session";
import { NoAccessCard } from "@/components/feedback";
import { OrgProvider } from "../_lib/org-context";
import { formatCount, orgStatusLabel } from "../_lib/labels";
import type { AdministeredOrg } from "../_lib/types";

/**
 * 조직 하나의 관리 화면 껍데기 — 머리말(조직 이름·상태)과 탭 내비게이션.
 *
 * Next 16 에서 동적 세그먼트 `params` 는 Promise 라 서버 컴포넌트에선 await 가 필요하다. 이 화면은
 * 세션 쿠키로 브라우저에서 API 를 호출하는 클라이언트 컴포넌트라 `useParams()` 로 읽는다.
 */
export default function OrgConsoleLayout({ children }: { children: ReactNode }) {
  const params = useParams();
  const raw = params?.orgId;
  const orgId = Array.isArray(raw) ? raw[0] : (raw ?? "");

  // 조직 이름은 목록 API 한 번으로 얻는다(조직 단건 조회 API 가 따로 없다).
  const orgs = useApi<AdministeredOrg[]>("/api/orgs/mine");
  const org = orgs.data?.find((candidate) => candidate.id === orgId);
  const session = useSession();

  /*
   * 소속 여부. `/api/orgs/mine` 은 **조직관리자인 조직만** 주므로 이것만으로는 판정할 수 없다.
   */
  const memberships = useApi<{ orgId: string }[]>("/api/orgs/memberships");
  const isMember = memberships.data?.some((m) => m.orgId === orgId) ?? false;

  /*
   * ★**들어올 수 없는 사람에게는 껍데기를 그리지 않는다.**
   *
   * 그전에는 조직 링크를 받은 비멤버가 탭 10개짜리 완전한 콘솔을 보고, 그 안에서 패널마다 붉은 권한
   * 오류가 8개 뜨고, 영원히 실패할 '다시 시도' 버튼이 6개 놓였다. 화면은 "고장났다"고 말하는데
   * 실제로는 "당신 조직이 아니다"이고, 두 문장은 사용자가 할 일이 정반대다(재시도 vs 요청 철회).
   *
   * ★★**판정 기준은 조직관리자 여부가 아니라 "이 조직 사람인가"다.** 처음에 `/api/orgs/mine`
   * (=조직관리자인 조직) 하나로 막았는데, 그러면 서버가 정당하게 허용하는 **부서 서브트리 위임자**와
   * **조직 커스텀 역할 보유자**까지 콘솔 전체에서 잠긴다 — 원래 결함(모르는 사람에게 껍데기)을 고치려다
   * **권한이 있는 사람을 막는** 더 나쁜 상태를 만든 것이다(적대 리뷰에서 잡혔다).
   * 화면 게이트는 언제나 **서버 인가보다 넓게** 잡아야 한다. 좁히면 그 차이만큼이 락아웃이다.
   *
   * 남는 것: 아무 콘솔 권한이 없는 **평범한 멤버**가 URL 을 직접 치면 여전히 껍데기를 본다.
   * 그걸 없애려면 "이 사람이 이 조직 콘솔에서 뭐라도 할 수 있는가"를 서버가 답해야 하는데(전용
   * 엔드포인트), 그건 넓히는 방향이라 나중에 해도 안전하다 — 지금 상태는 도입 전과 같지 더 나쁘지 않다.
   *
   * 플랫폼 관리자는 통과시킨다(멤버가 아니어도 볼 수 있는 것이 그 역할의 정의다).
   * 인가는 여전히 서버 엔진이 판정한다 — 이 분기는 **화면 선택**이지 보안 경계가 아니다.
   */
  const accessResolved = !orgs.loading && !orgs.error && !memberships.loading && !memberships.error;
  const denied =
    accessResolved && !org && !isMember && session.status === "authenticated" && !session.user.platformAdmin;

  return (
    <OrgProvider value={{ orgId, org, reload: orgs.reload }}>
      <div className="flex flex-col gap-5">
        <div className="flex flex-col gap-2">
          <Link
            href="/console"
            className="inline-flex w-fit items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
          >
            <ArrowLeftIcon className="size-3.5" />
            조직 목록
          </Link>

          <div className="flex flex-wrap items-center gap-2">
            {orgs.loading && !org ? (
              <Skeleton className="h-8 w-48" />
            ) : (
              <h1 className="text-display text-foreground">{org?.name ?? "조직"}</h1>
            )}
            {org && org.status !== "ACTIVE" && (
              <Badge variant="destructive">{orgStatusLabel(org.status)}</Badge>
            )}
          </div>

          {org && (
            <p className="text-sm text-muted-foreground">
              {org.slug} · 구성원 {formatCount(org.memberCount)}명 · 타임존 {org.timezone}
            </p>
          )}
          {/* 비멤버는 아래 NoAccessCard 로 갈리므로, 이 안내는 **플랫폼 권한으로 열람 중인 경우**만 남는다. */}
          {!orgs.loading && !org && !orgs.error && !denied && (
            <p className="text-sm text-muted-foreground">
              내가 조직관리자로 등록된 조직은 아닙니다. 플랫폼 권한으로 열람 중이며, 각 기능의 접근 가능
              여부는 요청 결과로 표시됩니다.
            </p>
          )}
        </div>

        {orgs.error && <ErrorNotice message={orgs.error} onRetry={orgs.reload} />}

        {denied ? (
          <NoAccessCard
            title="이 조직의 관리 권한이 없습니다"
            description="조직관리자로 지정된 조직만 열 수 있습니다. 링크를 받았다면 그 조직의 관리자에게 권한 부여를 요청하세요."
            backHref="/console"
            backLabel="내 조직 목록으로"
          />
        ) : (
          <>
            {/* 하위 메뉴는 좌측 사이드바(Sidebar + lib/nav ORG_MENU)가 그린다 — 정의 중복 금지. */}

            {children}
          </>
        )}
      </div>
    </OrgProvider>
  );
}
