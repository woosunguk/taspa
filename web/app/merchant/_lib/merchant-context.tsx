"use client";

import { createContext, useContext, type ReactNode } from "react";
import type { MyMerchant } from "./types";

/**
 * 현재 보고 있는 가맹점.
 *
 * `merchant` 가 undefined 일 수 있는 이유: 플랫폼 관리자는 `/api/merchant-console/mine`(내가 관리자인 매장)에
 * 잡히지 않지만 서버 인가는 통과한다. 그래서 목록에 없다고 화면을 막지 않고 이름만 모르는 채로 진행한다 —
 * 접근 가능 여부는 각 API 의 응답(200/403)이 정직하게 말해준다.
 *
 * 이때 타임존도 모르는 상태가 되는데, 각 조회 응답이 `timezone` 을 함께 내려주므로 화면은 그 값으로
 * 스스로 앵커를 복구한다.
 */
interface MerchantContextValue {
  merchantId: string;
  merchant: MyMerchant | undefined;
}

const MerchantContext = createContext<MerchantContextValue | null>(null);

export function MerchantProvider({ value, children }: { value: MerchantContextValue; children: ReactNode }) {
  return <MerchantContext.Provider value={value}>{children}</MerchantContext.Provider>;
}

export function useMerchant(): MerchantContextValue {
  const value = useContext(MerchantContext);
  if (!value) throw new Error("useMerchant 는 가맹 콘솔 레이아웃 안에서만 사용할 수 있습니다");
  return value;
}

/** 가맹 스코프 API 경로. merchantId 는 URL 세그먼트라 항상 인코딩한다. */
export function merchantPath(merchantId: string, suffix = ""): string {
  return `/api/merchant-console/${encodeURIComponent(merchantId)}${suffix}`;
}
