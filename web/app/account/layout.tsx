import type { Metadata } from "next";
import type { ReactNode } from "react";
import { Toaster } from "@/components/ui/sonner";

export const metadata: Metadata = {
  title: "계정 설정",
  description: "프로필·비밀번호·2단계 인증·패스키·세션을 관리합니다",
};

/**
 * 계정 화면 레이아웃.
 *
 * 토스트 컨테이너를 여기에 둔다 — 이 화면은 "저장했습니다"류 확인이 잦고, 루트 레이아웃은 다른 화면과
 * 공유하는 파일이라 이번 작업 범위에서 건드리지 않는다.
 * (루트에 `<Toaster />` 가 추가되면 이 줄은 제거해야 한다 — 토스트가 두 번 뜬다.)
 */
export default function AccountLayout({ children }: { children: ReactNode }) {
  return (
    <>
      {children}
      <Toaster position="bottom-right" />
    </>
  );
}
