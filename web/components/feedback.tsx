"use client";

import Image from "next/image";
import type { ReactNode } from "react";
import { LockIcon } from "lucide-react";
import { Button, ButtonLink } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

/* 화면 상태(로딩·비어있음·오류)를 한 곳에서 정의한다. shadcn 이 제공하지 않는 앱 고유 조각만 여기 둔다. */

/**
 * 로딩 표시.
 *
 * `brand`(기본)는 **화면 전체를 기다릴 때** 쓴다 — 브랜드 심볼이 숨쉬듯 뛴다. 작은 영역(표 한 칸,
 * 버튼 옆)에서는 `variant="inline"` 의 원형 스피너를 쓴다: 큰 그림이 작은 자리에 들어가면 그 영역이
 * 무엇을 기다리는지가 아니라 그림이 먼저 읽힌다.
 */
export function Loading({
  label = "불러오는 중",
  variant = "brand",
}: {
  label?: string;
  variant?: "brand" | "inline";
}) {
  if (variant === "inline") {
    return (
      <div
        className="flex items-center justify-center gap-2 px-4 py-10 text-sm text-muted-foreground"
        role="status"
      >
        <span
          className="h-4 w-4 animate-spin rounded-full border-2 border-border border-t-primary"
          aria-hidden
        />
        {label}
      </div>
    );
  }
  return (
    <div className="flex flex-col items-center justify-center gap-3 px-4 py-14" role="status">
      {/* 회전이 아니라 **맥동**이다 — 캐릭터 얼굴이 뒤집혀 돌아가면 브랜드가 우스워진다. */}
      <Image
        src="/brand/vegetable_bowl_face_transparent.png"
        alt=""
        aria-hidden
        width={363}
        height={374}
        priority
        className="h-16 w-auto animate-pulse"
      />
      <p className="text-sm text-muted-foreground">{label}</p>
    </div>
  );
}

/** 표·목록이 로드되기 전 자리를 잡아 레이아웃이 튀지 않게 한다. */
export function RowsSkeleton({ rows = 4 }: { rows?: number }) {
  return (
    <div className="flex flex-col gap-2 py-2">
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} className="h-9 w-full" />
      ))}
    </div>
  );
}

/**
 * 비어 있음은 오류가 아니다 — 다음에 무엇을 하면 되는지 알려준다.
 *
 * ★아이콘 자리를 둔다. 텍스트 두 줄만 있으면 "비었다"가 **레이아웃이 덜 그려진 것**처럼 보였다.
 * 조용한 도형 하나가 "여기는 원래 이렇게 생긴 자리"라고 말해 준다(장식이 아니라 상태 표시다).
 */
export function EmptyState({
  title,
  description,
  action,
  icon,
  illustration,
}: {
  title: string;
  description?: string;
  action?: ReactNode;
  icon?: ReactNode;
  /**
   * 브랜드 일러스트 경로(`/brand/*.png`). 빈 화면은 사용자가 **아무것도 하지 않았을 때 가장 오래 보는
   * 화면**이라 여기서 제품의 표정이 정해진다. 다만 그림이 안내 문구를 밀어내면 안 되므로 아이콘과
   * 함께 쓰지 않는다(둘 다 주면 일러스트가 이긴다).
   */
  illustration?: string;
}) {
  return (
    <div className="flex flex-col items-center gap-3 px-4 py-12 text-center">
      {illustration && !icon && (
        <Image src={illustration} alt="" aria-hidden width={512} height={430} className="h-32 w-auto" />
      )}
      {icon && (
        <div
          aria-hidden
          className="surface-sunken flex size-11 items-center justify-center rounded-full text-muted-foreground [&_svg]:size-5"
        >
          {icon}
        </div>
      )}
      <div className="flex flex-col gap-1">
        <p className="text-title text-foreground">{title}</p>
        {description && (
          <p className="mx-auto max-w-md text-sm leading-relaxed text-muted-foreground">{description}</p>
        )}
      </div>
      {action && <div className="mt-1">{action}</div>}
    </div>
  );
}

/**
 * **권한이 없어서 열 수 없는 화면**의 단일 안내.
 *
 * ★"권한 없음"은 오류가 아니라 **상태**다. 이걸 구분하지 않으면, 남의 조직 콘솔 URL 을 연 사람이
 * 탭 10개짜리 완전한 화면 안에서 붉은 오류 8개와 영원히 실패할 '다시 시도' 버튼 6개를 만난다 —
 * 화면은 "고장났다"고 말하는데 사실은 "당신 것이 아니다"이고, 그 둘은 사용자가 할 일이 정반대다.
 * 재시도 버튼을 **의도적으로 주지 않는다**: 다시 눌러도 결과가 같은 버튼은 거짓 희망이다.
 */
export function NoAccessCard({
  title,
  description,
  backHref,
  backLabel,
}: {
  title: string;
  description: string;
  backHref?: string;
  backLabel?: string;
}) {
  return (
    <Card>
      <CardContent>
        <EmptyState
          icon={<LockIcon />}
          title={title}
          description={description}
          action={
            backHref ? (
              <ButtonLink href={backHref} variant="outline" size="sm">
                {backLabel ?? "돌아가기"}
              </ButtonLink>
            ) : undefined
          }
        />
      </CardContent>
    </Card>
  );
}

/** 오류는 무슨 일이 있었는지와 다음에 뭘 하면 되는지를 함께 말한다. */
export function ErrorNotice({
  message,
  onRetry,
  onDismiss,
}: {
  message: string;
  /** **실제로 요청을 다시 보내는** 동작만. 라벨이 "다시 시도"라 그 약속을 지켜야 한다. */
  onRetry?: () => void;
  /**
   * 오류 표시만 지우는 동작.
   *
   * ★그전에는 이것도 `onRetry` 로 넘겨서 **버튼에 "다시 시도"라고 쓰여 있는데 아무것도 재시도하지
   * 않았다**(26곳). 저장에 실패한 사용자가 그 버튼을 누르면 오류만 사라지고 화면은 성공한 것처럼
   * 조용해진다 — 실패를 알려주던 유일한 표시가 실패를 지우는 버튼이었던 셈이다.
   */
  onDismiss?: () => void;
}) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-destructive/40 bg-destructive/10 px-4 py-3">
      <p className="text-sm text-destructive">{message}</p>
      {onRetry ? (
        <Button variant="outline" size="sm" onClick={onRetry}>
          다시 시도
        </Button>
      ) : (
        onDismiss && (
          <Button variant="outline" size="sm" onClick={onDismiss}>
            닫기
          </Button>
        )
      )}
    </div>
  );
}
