import type { IconName } from "@/lib/nav";

/**
 * 사이드바 아이콘 — 외부 아이콘 팩 없이 스트로크 SVG 한 벌. 색은 `currentColor` 라 활성/비활성이
 * 텍스트 색을 그대로 따라간다(별도 상태 관리 없음).
 */
const PATHS: Record<IconName, string> = {
  home: "M3 10.5 12 3l9 7.5M5 9.5V21h5v-6h4v6h5V9.5",
  qr: "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h3v3h-3zM20 17v3h-3",
  users:
    "M16 19v-1a4 4 0 0 0-8 0v1M12 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7ZM19 19v-.8a4 4 0 0 0-2.7-3.8M15.5 4.3a3.5 3.5 0 0 1 0 6.4",
  invite: "M4 6h16v12H4zM4 7l8 6 8-6",
  tree: "M12 3v5M12 8H6v4M12 8h6v4M6 12v3M18 12v3M4 15h4v4H4zM16 15h4v4h-4zM10 15h4v4h-4zM12 12v3",
  meal: "M7 3v7a2 2 0 0 0 2 2v9M5 3v4M9 3v4M16 3c-1.7 1.5-2.5 4-2.5 6.5 0 1.9 1 3 2.5 3V21M16 3v9",
  "menu-board": "M5 4h14v16H5zM8 8h8M8 12h8M8 16h5",
  globe:
    "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM3 12h18M12 3c2.5 2.5 3.5 5.5 3.5 9s-1 6.5-3.5 9c-2.5-2.5-3.5-5.5-3.5-9s1-6.5 3.5-9Z",
  calendar: "M5 5h14v15H5zM5 9h14M8 3v4M16 3v4M8 13h3M8 16h5",
  chart: "M4 20V4M4 20h16M8 16v-5M12 16V8M16 16v-8M20 16V6",
  invoice: "M6 3h9l3 3v15H6zM15 3v3h3M9 9h6M9 13h6M9 17h4",
  role: "M12 3l7 3v5c0 4.5-3 8-7 10-4-2-7-5.5-7-10V6l7-3ZM9.5 12l2 2 3.5-4",
  audit: "M5 4h14v16H5zM8 8h8M8 12h5M15 15l2 2 3-3",
  store: "M4 9l1.5-5h13L20 9M4 9v11h16V9M4 9h16M9 20v-6h6v6",
  log: "M5 4h14v16H5zM8 8h8M8 12h8M8 16h5",
  settlement:
    "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM12 7v10M9.5 9.5c.5-1 4.5-1 5 .5s-1.5 2-2.5 2-3 .5-2.5 2 4.5 1.5 5 .5",
  org: "M4 21V5l8-2v18M12 21h8V9l-8-2M7 8h2M7 12h2M7 16h2M15 12h2M15 16h2",
  client: "M4 5h16v11H4zM2 19h20M9 16v3M15 16v3",
  shield: "M12 3l7 3v5c0 4.5-3 8-7 10-4-2-7-5.5-7-10V6l7-3Z",
  sso: "M15 8a4 4 0 1 1-8 0 4 4 0 0 1 8 0ZM11 12v9M11 17h4M11 21h3",
  payables: "M3 7h18v10H3zM12 14.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5ZM6 10v.01M18 14v.01",
  reconcile: "M8 7h12M8 12h12M8 17h12M4 7h.01M4 12h.01M4 17h.01",
  account: "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM5 21a7 7 0 0 1 14 0",
  leaf: "M5 19C5 10 10 5 20 4c0 10-4 15-13 15M5 19c2-5 5-8 9-10M5 19l-1 2",
};

export function NavIcon({ name, className }: { name: IconName; className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
      className={className ?? "size-[18px]"}
    >
      <path d={PATHS[name]} />
    </svg>
  );
}
