"use client";

import { createContext, useContext, type ReactNode } from "react";
import type { AdministeredOrg } from "./types";

/**
 * 현재 보고 있는 조직.
 *
 * `org` 이 undefined 일 수 있는 이유: 플랫폼 관리자는 `/api/orgs/mine`(내가 ORG_ADMIN 인 조직)에 잡히지 않지만
 * 서버 인가는 통과한다. 그래서 목록에 없다고 화면을 막지 않고, 이름만 모르는 상태로 진행한다 —
 * 접근 가능 여부는 각 API 호출의 응답(200/403)이 정직하게 말해준다.
 */
interface OrgContextValue {
  orgId: string;
  org: AdministeredOrg | undefined;
  /** 프로필(이름·타임존)을 바꾼 뒤 헤더를 갱신하기 위한 재조회. */
  reload: () => void;
}

const OrgContext = createContext<OrgContextValue | null>(null);

export function OrgProvider({ value, children }: { value: OrgContextValue; children: ReactNode }) {
  return <OrgContext.Provider value={value}>{children}</OrgContext.Provider>;
}

export function useOrg(): OrgContextValue {
  const value = useContext(OrgContext);
  if (!value) throw new Error("useOrg 는 조직 콘솔 레이아웃 안에서만 사용할 수 있습니다");
  return value;
}

/** 조직 스코프 API 경로. orgId 는 URL 세그먼트라 항상 인코딩한다. */
export function orgPath(orgId: string, suffix = ""): string {
  return `/api/orgs/${encodeURIComponent(orgId)}${suffix}`;
}
