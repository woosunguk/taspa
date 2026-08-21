import type { Metadata, Viewport } from "next";

/**
 * POS 단말 화면의 메타데이터. 페이지는 클라이언트 컴포넌트(카메라·상태 기계)라 `metadata` 를
 * 내보낼 수 없어 이 서버 레이아웃이 대신 소유한다.
 *
 * `maximumScale` 을 두지 않는다 — 계산원이 승인 번호를 확대해 읽어야 할 때가 있고, 확대 금지는
 * 접근성을 깎는다. 대신 기본 글자 크기를 크게 잡아 확대할 일 자체를 줄인다.
 */
export const metadata: Metadata = {
  title: "POS 단말",
  description: "매장 계산대에서 식권 QR 을 스캔해 결제를 승인합니다.",
  // 계산대 단말 화면이 검색·공유 대상이 될 이유가 없다.
  robots: { index: false, follow: false },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
};

export default function PosLayout({ children }: { children: React.ReactNode }) {
  return children;
}
