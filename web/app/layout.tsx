import type { Metadata } from "next";
import { Geist } from "next/font/google";
import "./globals.css";
import { SessionProvider } from "@/components/SessionProvider";
import { cn } from "@/lib/utils";

/**
 * 라틴 문자는 Geist(shadcn 기본), 한글은 시스템 폰트로 폴백된다(globals.css 의 --font-sans).
 * Geist 에는 한글 글리프가 없어 폴백을 명시하지 않으면 한글이 브라우저 기본 폰트로 제각각 잡힌다.
 */
const geist = Geist({
  subsets: ["latin"],
  variable: "--font-geist",
  display: "swap",
});

export const metadata: Metadata = {
  title: {
    default: "taspa",
    template: "%s · taspa",
  },
  description: "사내 계정과 식대를 한곳에서 — taspa",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ko" className={cn("h-full antialiased", geist.variable)}>
      <body className="min-h-full flex flex-col">
        {/* 세션은 앱 전체에서 하나 — 헤더와 본문이 같은 상태·같은 retry 를 본다(lib/session.ts 참고). */}
        <SessionProvider>{children}</SessionProvider>
      </body>
    </html>
  );
}
