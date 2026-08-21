import type { Metadata } from "next";

/**
 * 식권 화면의 메타데이터. 페이지 자체는 클라이언트 컴포넌트(세션 쿠키로 브라우저에서 API 를 호출한다)라
 * `metadata` 를 내보낼 수 없어 이 서버 레이아웃이 대신 소유한다.
 */
export const metadata: Metadata = {
  title: "식권",
  description: "회사 식대로 결제할 QR 을 발급하고, 이번 달 사용 금액과 최근 내역을 확인합니다.",
};

export default function MealLayout({ children }: { children: React.ReactNode }) {
  return children;
}
