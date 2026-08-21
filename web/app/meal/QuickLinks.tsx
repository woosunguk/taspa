"use client";

import Link from "next/link";
import { Card, CardContent } from "@/components/ui/card";
import type { CurrentUser } from "@/lib/session";

/**
 * "내가 갈 수 있는 다른 화면" — 여러 역할을 겸하는 사람(직원이면서 조직관리자/매장 관리자)이
 * 여기서 길을 찾는다.
 *
 * ★노출 판정은 **UX 지 보안 경계가 아니다.** 인가는 서버 정책 엔진이 내린다(링크가 보여도 서버가
 * 거부하면 그 화면이 정직하게 403 을 표시한다). 그래서 판정 근거도 서버가 준 사실만 쓴다 —
 * `manageableOrgs`(/api/account/me), 매장은 `/api/merchant-console/mine` 결과 유무.
 */
export function QuickLinks({ user, merchantAdmin }: { user: CurrentUser; merchantAdmin: boolean }) {
  const links: LinkItem[] = [
    user.manageableOrgs && {
      href: "/console",
      label: "조직 관리",
      description: "구성원·초대·식수 예측·청구서",
    },
    merchantAdmin && {
      href: "/merchant",
      label: "매장 관리",
      description: "우리 매장 예측과 거래 내역",
    },
    {
      href: "/account",
      label: "계정 설정",
      description: "비밀번호·2단계 인증·패스키·기기",
      // 계정 화면은 서버 렌더링이라 SPA 라우팅이 아닌 실제 이동이어야 한다.
      external: true,
    },
  ].filter((item): item is LinkItem => Boolean(item));

  return (
    <section aria-labelledby="quick-links-heading">
      <h2 id="quick-links-heading" className="mb-2 text-base font-medium text-foreground">
        바로 가기
      </h2>
      <div className="grid gap-3 sm:grid-cols-2">
        {links.map((item) => (
          <QuickLink key={item.href} {...item} />
        ))}
      </div>
    </section>
  );
}

interface LinkItem {
  href: string;
  label: string;
  description: string;
  external?: boolean;
}

function QuickLink({ href, label, description, external }: LinkItem) {
  const body = (
    <Card size="sm" className="h-full transition-colors hover:border-primary">
      <CardContent>
        <p className="text-sm font-medium text-foreground">{label}</p>
        <p className="mt-0.5 text-xs text-muted-foreground">{description}</p>
      </CardContent>
    </Card>
  );

  return external ? <a href={href}>{body}</a> : <Link href={href}>{body}</Link>;
}
