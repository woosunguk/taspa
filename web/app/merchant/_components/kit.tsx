"use client";

import type { ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

/* 가맹 콘솔 화면들이 공유하는 작은 조각. 앱 전역 조각은 @/components/feedback 에 있고, 여기엔 이 콘솔에서만
   쓰는 것만 둔다(다른 화면 담당자와 파일이 겹치지 않게 하려는 의도도 있다). */

export { Stat } from "@/components/data-display";
export { Section } from "@/components/section";

/** 좁은 화면에서도 표가 페이지를 밀지 않게 감싼다(표 자체가 스크롤 컨테이너를 가진다). */
export function TableScroll({ children }: { children: ReactNode }) {
  return <div className="w-full overflow-x-auto">{children}</div>;
}

/**
 * 결과가 요청과 다를 때의 고지(기간 절단·행 수 상한 등).
 *
 * 오류가 아니라 **사실**이다 — 서버가 조용히 자르지 않고 알려준 것을 화면도 조용히 삼키지 않는다.
 * 부분 데이터를 전부인 것처럼 보여주면 매장이 잘못된 수량으로 준비하게 된다.
 */
export function Notice({ children }: { children: ReactNode }) {
  return (
    <p className="rounded-lg border border-border bg-muted/40 px-3 py-2 text-xs text-muted-foreground">
      {children}
    </p>
  );
}

export interface SegmentOption<T extends string | number> {
  value: T;
  label: string;
}

/** 몇 개 안 되는 선택지를 고르는 세그먼트 버튼(기간·표시 건수). 셀렉트보다 손가락 하나 덜 든다. */
export function Segmented<T extends string | number>({
  value,
  onChange,
  options,
  ariaLabel,
}: {
  value: T;
  onChange: (value: T) => void;
  options: SegmentOption<T>[];
  ariaLabel: string;
}) {
  return (
    <div className="flex flex-wrap items-center gap-1" role="group" aria-label={ariaLabel}>
      {options.map((option) => {
        const active = option.value === value;
        return (
          <Button
            key={String(option.value)}
            type="button"
            size="sm"
            variant={active ? "default" : "outline"}
            aria-pressed={active}
            onClick={() => onChange(option.value)}
          >
            {option.label}
          </Button>
        );
      })}
    </div>
  );
}

/**
 * 예측 수량 한 개의 표시.
 *
 * **`predicted === null` 은 "0 인분"이 아니라 "데이터 없음"이다.** 0 으로 보이면 매장은 재료를 사지 않는다 —
 * 그래서 숫자 자리에 숫자를 넣지 않고 문구로 바꿔 시각적으로 완전히 구분한다.
 */
export function Portions({
  value,
  className,
  emptyLabel = "데이터 없음",
}: {
  value: number | null;
  className?: string;
  emptyLabel?: string;
}) {
  if (value === null) {
    return <span className={cn("text-muted-foreground", className)}>{emptyLabel}</span>;
  }
  return <span className={cn("tabular font-medium", className)}>{value.toLocaleString("ko-KR")}</span>;
}
