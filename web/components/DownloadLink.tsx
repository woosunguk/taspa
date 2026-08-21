"use client";

import { DownloadIcon } from "lucide-react";
import { useState, type ReactNode } from "react";
import { buttonVariants } from "@/components/ui/button";
import { api } from "@/lib/api";
import { cn } from "@/lib/utils";

/**
 * 파일 내려받기 링크(버튼 모양).
 *
 * **평범한 앵커 내비게이션으로 받는다**(fetch + Blob 이 아니다). 이유 셋:
 *  1. 동일 오리진 프록시(next.config.ts) 덕에 세션 쿠키가 그대로 실린다.
 *  2. 파일명은 서버의 `Content-Disposition`(RFC 5987 한글 파일명)이 **단독으로** 정한다. Blob 방식이면
 *     프런트가 파일명을 다시 지어야 하고, 그 순간 두 곳이 갈라진다.
 *  3. 큰 파일도 메모리에 통째로 담지 않는다.
 *
 * ★그런데 앵커만으로는 **세션 만료가 조용히 실패한다**. `/api/**` 미인증 응답은 (Accept 헤더와 무관하게)
 * `ApiAuthenticationEntryPoint` 의 **401 JSON** 이다 — 로그인 리다이렉트가 아니다(실측 확인). 그래서
 * 만료된 세션으로 이 링크를 누르면 브라우저가 그 401 본문을 그대로 내려받고, 회계 담당자는 CSV 대신
 * 영문 JSON 조각을 얻는다. 오류 표시도 로그인 이동도 없다.
 *
 * 그래서 **누를 때 세션을 먼저 확인**하고(가벼운 인증 요청 1회), 통과했을 때만 실제 다운로드로 넘어간다.
 * 만료였다면 `lib/api.ts` 가 이미 하는 것과 같은 방식으로 로그인 화면으로 보낸다 — 화면마다 다른 실패
 * 처리를 만들지 않는다. 확인이 네트워크 오류 등으로 실패하면 **막지 않고 그냥 진행**한다(다운로드를
 * 못 하게 만드는 것이 이 가드의 목적이 아니다).
 */
export function DownloadLink({
  href,
  children,
  variant = "outline",
  size = "sm",
  className,
}: {
  href: string;
  children: ReactNode;
  variant?: "default" | "outline" | "ghost" | "destructive" | "secondary";
  size?: "default" | "sm" | "lg" | "icon";
  className?: string;
}) {
  const [checking, setChecking] = useState(false);

  async function handleClick(event: React.MouseEvent<HTMLAnchorElement>) {
    // 새 탭·다운로드 저장 등 사용자가 의도한 보조 클릭은 그대로 둔다.
    if (event.defaultPrevented || event.metaKey || event.ctrlKey || event.shiftKey || event.button !== 0) {
      return;
    }
    event.preventDefault();
    setChecking(true);
    try {
      // 401 이면 api 계층이 로그인으로 이동시키고 "navigating" 을 던진다 — 여기서 멈추는 것이 맞다.
      await api.get("/api/account/me");
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return;
      // 확인 자체가 실패한 것(오프라인·일시 오류)은 다운로드를 막을 이유가 되지 않는다.
    } finally {
      setChecking(false);
    }
    /*
     * ★`window.location.href` 로 이동시키지 **않는다**. 그건 최상위 내비게이션이라, 서버가 첨부가 아닌
     * 응답(예: 잘못된 기간으로 400 JSON)을 주면 브라우저가 그 문서로 **화면 전체를 대체한다** —
     * 다운로드 실패가 "관리 콘솔이 원시 JSON 으로 바뀌고 입력하던 조건이 사라지는" 사고가 된다.
     * 대신 `download` 를 단 앵커를 합성해 누른다: 동일 오리진이라 브라우저가 다운로드로 처리하므로
     * 실패해도 **보고 있던 화면은 그대로 남는다**(가로채기 도입 전의 안전한 성질을 되돌린 것).
     * 파일명은 여전히 서버의 Content-Disposition 이 정한다.
     */
    const anchor = document.createElement("a");
    anchor.href = href;
    anchor.download = "";
    anchor.rel = "noopener";
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  }

  return (
    <a
      href={href}
      onClick={handleClick}
      // download 는 힌트일 뿐 — 실제 파일명은 서버 헤더가 이긴다(그래야 한 곳에서만 정해진다).
      download
      aria-busy={checking || undefined}
      className={cn(buttonVariants({ variant, size }), "gap-1.5", className)}
    >
      <DownloadIcon className="size-3.5" />
      {children}
    </a>
  );
}
