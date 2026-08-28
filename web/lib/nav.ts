/**
 * 내비게이션의 **단일 출처** — 사이드바(전 화면 공용)가 이 정의만 읽는다.
 *
 * 그전에는 영역 메뉴(AppShell 상단)와 영역 안 탭(조직 콘솔 10개·관리 콘솔 11개·매장 3개)이 각자
 * 레이아웃에 흩어져 있었고, 탭은 가로 스크롤 뒤에 숨어 "무슨 기능이 있는지"가 화면에 드러나지 않았다.
 * 사이드바는 그 목록을 **세로로 전부 펼친다** — 기능의 존재 자체가 첫 화면에서 보이는 것이 목적이다.
 *
 * ★여기의 노출 조건은 **보안 경계가 아니다.** 인가는 서버 정책 엔진이 판정한다(AppShell 의 기존 규약).
 */

export interface NavLeaf {
  /** 영역 base 로부터의 세그먼트("" = 영역 홈). */
  segment: string;
  label: string;
  icon: IconName;
}

export type IconName =
  | "home"
  | "qr"
  | "users"
  | "invite"
  | "tree"
  | "meal"
  | "menu-board"
  | "globe"
  | "calendar"
  | "chart"
  | "invoice"
  | "role"
  | "audit"
  | "store"
  | "log"
  | "settlement"
  | "org"
  | "client"
  | "shield"
  | "sso"
  | "payables"
  | "reconcile"
  | "leaf"
  | "account";

/** 조직 콘솔(/console/{orgId}) 메뉴 — 이 순서가 곧 화면의 이야기 순서다. */
export const ORG_MENU: NavLeaf[] = [
  { segment: "", label: "개요", icon: "home" },
  { segment: "members", label: "구성원", icon: "users" },
  { segment: "invitations", label: "초대", icon: "invite" },
  { segment: "structure", label: "조직구조", icon: "tree" },
  { segment: "meal-policy", label: "식사정책", icon: "meal" },
  { segment: "domains", label: "도메인", icon: "globe" },
  { segment: "calendar", label: "캘린더", icon: "calendar" },
  { segment: "invoices", label: "청구서", icon: "invoice" },
  { segment: "roles", label: "역할", icon: "role" },
  { segment: "audit", label: "활동로그", icon: "audit" },
];

/** 매장 콘솔(/merchant/{merchantId}) 메뉴. */
export const MERCHANT_MENU: NavLeaf[] = [
  // 순서 = 하루의 질문 순서: 몇 인분 준비하나 → 지금 어떻게 되고 있나 → 얼마나 맞았나 → 대사 → 정산.
  { segment: "", label: "식수예측", icon: "chart" },
  { segment: "today", label: "오늘 현황", icon: "meal" },
  { segment: "report", label: "잔반 리포트", icon: "leaf" },
  { segment: "transactions", label: "밀로그", icon: "log" },
  { segment: "settlement", label: "정산", icon: "settlement" },
];

/** 플랫폼 관리(/admin) 메뉴. */
export const ADMIN_MENU: NavLeaf[] = [
  { segment: "", label: "대시보드", icon: "home" },
  { segment: "orgs", label: "조직", icon: "org" },
  { segment: "users", label: "사용자", icon: "users" },
  { segment: "clients", label: "클라이언트", icon: "client" },
  { segment: "merchants", label: "가맹점", icon: "store" },
  { segment: "iam", label: "IAM 정책", icon: "shield" },
  { segment: "sso", label: "기업 SSO", icon: "sso" },
  { segment: "calendar", label: "캘린더", icon: "calendar" },
  { segment: "payables", label: "지급 현황", icon: "payables" },
  { segment: "reconciliation", label: "정합성 대사", icon: "reconcile" },
  { segment: "audit", label: "감사 로그", icon: "audit" },
];

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export interface NavContext {
  /** 그룹 라벨(예: "조직 관리"). */
  group: string;
  /** 하위 메뉴의 base 경로(예: /console/{orgId}). */
  base: string;
  items: NavLeaf[];
}

/**
 * 현재 경로가 속한 영역의 하위 메뉴. 영역 밖(식권·계정)이면 null — 사이드바는 1차 메뉴만 그린다.
 * id 는 **경로에서만** 읽는다(UUID 형태 확인) — 목록 화면(/console, /merchant)은 컨텍스트가 아니다.
 */
export function contextOf(pathname: string): NavContext | null {
  const parts = pathname.split("/").filter(Boolean);
  if (parts[0] === "console" && parts[1] && UUID_RE.test(parts[1])) {
    return { group: "조직 관리", base: `/console/${parts[1]}`, items: ORG_MENU };
  }
  if (parts[0] === "merchant" && parts[1] && UUID_RE.test(parts[1])) {
    return { group: "매장 관리", base: `/merchant/${parts[1]}`, items: MERCHANT_MENU };
  }
  if (parts[0] === "admin") {
    return { group: "플랫폼 관리", base: "/admin", items: ADMIN_MENU };
  }
  return null;
}
