"use client";

import type { ReactNode } from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

/* 계정 화면 전용 공통 조각. 여러 섹션이 같은 골격을 공유해 설정 화면이 한 제품처럼 보이게 한다. */

/*
 * 섹션은 앱 전역 것을 쓴다(`@/components/section`). 여기 자체 정의가 있던 동안 계정 화면의 섹션
 * 제목만 `CardTitle`(16px)이라 콘솔·관리와 갈렸다 — 같은 역할의 글자는 같은 크기여야 한다.
 */
export { Section } from "@/components/section";

/** 섹션 안의 인라인 오류. ErrorNotice 는 화면 전체 실패용이라 여기선 더 조용한 형태를 쓴다. */
export function InlineError({ message }: { message?: string }) {
  if (!message) return null;
  return (
    <p role="alert" className="text-sm text-destructive">
      {message}
    </p>
  );
}

export function FieldHint({ children }: { children: ReactNode }) {
  return <p className="text-xs text-muted-foreground">{children}</p>;
}

/** 되돌릴 수 없는 작업은 반드시 한 번 멈춘다. 무엇이 사라지는지 문장으로 말한다. */
export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel = "확인",
  destructive = true,
  busy = false,
  confirmDisabled = false,
  error,
  onConfirm,
  children,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: string;
  confirmLabel?: string;
  destructive?: boolean;
  busy?: boolean;
  /** 입력이 아직 확인 조건을 만족하지 않을 때(예: 이메일 재입력 불일치). */
  confirmDisabled?: boolean;
  error?: string;
  onConfirm: () => void;
  children?: ReactNode;
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        {children}
        <InlineError message={error} />
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>
            취소
          </Button>
          <Button
            variant={destructive ? "destructive" : "default"}
            onClick={onConfirm}
            disabled={busy || confirmDisabled}
          >
            {busy ? "처리 중…" : confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
