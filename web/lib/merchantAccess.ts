"use client";

import { useApi } from "./useApi";

/** `/api/merchant-console/mine` 응답 중 이 훅이 쓰는 부분만(개수만 보므로 이걸로 충분하다). */
interface MerchantRefs {
  merchants: { merchantId: string }[];
  blocked: { merchantId: string }[];
}

/**
 * 이 사용자가 가맹 관리자 콘솔을 볼 수 있는지 — **네비게이션 노출 판단 전용**.
 *
 * 왜 별도 조회인가: 세션 DTO(`/api/account/me` → `CurrentUser`)에는 `platformAdmin`·`manageableOrgs` 는
 * 있어도 가맹 관리자 여부가 없다. 서버 DTO 를 이 작업에서 바꾸면 다른 작업과 충돌하므로, 목록 API 의
 * 결과가 비어 있지 않은지로 대신 판단한다. (서버에 `merchantAdmin` 플래그가 생기면 이 파일은 지우고
 * `NAV` 의 `visible` 을 그 플래그로 바꾸는 것이 맞다.)
 *
 * ★이건 **보안 경계가 아니다.** 인가는 서버 정책 엔진이 내리고, 링크를 숨기는 건 쓸 수 없는 메뉴를
 * 보여주지 않기 위한 UX 다. 조회가 실패하면 `false` 로 수렴한다(fail-closed — 표시에 한해서).
 */
export function useMerchantAccess(enabled: boolean): boolean {
  const mine = useApi<MerchantRefs>(enabled ? "/api/merchant-console/mine" : null);
  /*
   * ★`blocked` 도 센다. 링크가 나타나는 조건을 "들어갈 수 있는 매장이 있을 때"로만 두면, 아직 활성화되지
   * 않은 매장의 담당자에게는 **메뉴 자체가 없어** 사유를 설명하는 화면에 도달할 길이 사라진다
   * (그게 정확히 신규 가맹 온보딩이 침묵 속에 멈추던 형태다).
   */
  return (mine.data?.merchants.length ?? 0) + (mine.data?.blocked.length ?? 0) > 0;
}
