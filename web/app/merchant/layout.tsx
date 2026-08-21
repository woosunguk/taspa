"use client";

import type { ReactNode } from "react";
import { AppShell, RequireAuth } from "@/components/AppShell";

/**
 * 가맹 관리자 콘솔의 껍데기.
 *
 * 여기서 로그인만 강제한다. **이 매장의 관리자인지는 화면이 판단하지 않는다** — 인가는 서버 정책 엔진이
 * 내리고, 권한이 없으면 각 API 가 403 을 준다(화면은 그 사실을 그대로 표시한다). 링크·탭을 숨기는 것은
 * UX 일 뿐 보안 경계가 아니다.
 *
 * 이 콘솔은 **조회 전용**이다. 결제 승인·취소는 기계 신원(POS = M2M + merchant_id 클레임) 전용으로 남으므로,
 * 여기엔 어떤 변경 작업도 없다(계정이 털려도 무단 결제가 되지 않는다).
 */
export default function MerchantLayout({ children }: { children: ReactNode }) {
  return (
    <AppShell>
      <RequireAuth>{() => children}</RequireAuth>
    </AppShell>
  );
}
