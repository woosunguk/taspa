import type { ReactNode } from "react";
import { Card, CardContent } from "@/components/ui/card";
import { cn } from "@/lib/utils";

/**
 * 화면 안의 한 구획 — **앱 전체가 공유한다**.
 *
 * ★그전에는 `Section` 이 네 파일(콘솔·가맹·관리·계정)에 각자 정의돼 있었고, 그래서 같은 개념의 섹션
 * 제목이 화면군마다 **14 / 15 / 16px** 로 갈렸다. 사용자는 조직 콘솔 ↔ 가맹 콘솔 ↔ 관리 콘솔을 오가는데,
 * 같은 역할의 글자가 매번 다른 크기면 한 제품으로 읽히지 않는다.
 *
 * 밀도 차이는 실재한다(관리 콘솔은 표가 빽빽하고 계정 화면은 폼이 넉넉하다) — 그건 `density` 로 가른다.
 * **타이포는 가르지 않는다**: 밀도가 달라도 "섹션 제목"이라는 역할은 같기 때문이다.
 */
export function Section({
  title,
  description,
  action,
  children,
  className,
  tone = "default",
  density = "comfortable",
}: {
  title?: string;
  description?: string;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
  /**
   * ★`attention` 은 **그 화면에서 사람이 손대야 하는 것 하나**에만 준다.
   *
   * 조직 콘솔 개요는 섹션이 8개인데 전부 같은 흰 카드였다. 그중 행동을 요구하는 것은 하나인데
   * 나머지와 시각적으로 동급이라, 긴 스크롤 안에서 그냥 '첫 번째 상자'였다. 강조를 하나로 제한하는
   * 것이 규칙의 핵심이다 — 둘이 되는 순간 강조는 사라지고 소음만 남는다.
   */
  tone?: "default" | "attention";
  /** `compact` 는 관리 콘솔용(제목 줄을 선으로 분리하고 여백을 줄인다). */
  density?: "comfortable" | "compact";
}) {
  const compact = density === "compact";

  const header = (title || action || description) && (
    /*
      ★`action` 은 **제목 줄에** 붙인다(설명문 뒤가 아니라).
      제목+설명을 한 덩어리로 묶고 그 옆에 action 을 두면, 모바일에서 덩어리가 한 줄을 다 쓸 때
      action 이 **설명문 아래로 떨어져** 무엇의 상태인지 알 수 없는 배지가 고아처럼 뜬다.
      그렇다고 제목 덩어리에 `flex-1` 만 주면 반대로 **넓은 action(검색 입력)이 제목을 눌러**
      설명이 여러 줄로 접힌다. 제목 줄과 설명을 분리하면 둘 다 성립한다.
    */
    <div className={cn("flex flex-col gap-1", compact && "border-b border-line px-5 pb-3")}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        {title && <h2 className="text-title text-foreground">{title}</h2>}
        {action}
      </div>
      {description && <p className="text-sm leading-relaxed text-muted-foreground">{description}</p>}
    </div>
  );

  return (
    <Card
      size={compact ? "sm" : "default"}
      className={cn(
        // 타일 4열이 좁아지는 시점을 카드 폭 기준으로 판단하게 한다(뷰포트 기준이면 사이드 여백에 속는다).
        // ★이게 없으면 안에 놓인 `Stat` 의 `@lg:text-metric` 이 **영영 발동하지 않는다**.
        "@container",
        tone === "attention" && "border-primary/25 bg-raised shadow-raised",
        compact && "gap-0",
        className,
      )}
    >
      {compact ? (
        <>
          {header}
          <CardContent className="pt-3">{children}</CardContent>
        </>
      ) : (
        <CardContent className="flex flex-col gap-4">
          {header}
          {children}
        </CardContent>
      )}
    </Card>
  );
}
