"use client";

import { useEffect, useId, useMemo, useState, type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import { PageHeader as SharedPageHeader, Stat as SharedStat } from "@/components/data-display";
import { Section as SharedSection } from "@/components/section";

/*
 * 관리 콘솔 전용 조각들. 공용(@/components)에 넣지 않은 이유는 이 화면들만의 밀도·폼 규칙이기 때문이다.
 * 여러 관리 화면이 같은 표·폼·확인 대화상자를 쓰므로 여기서 한 번만 정의한다.
 */

/* ── 페이지 골격 ───────────────────────────────────────────────────────── */

export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string;
  description?: string;
  actions?: ReactNode;
}) {
  /*
   * 배치는 앱 전역 `PageHeader` 에 위임한다 — 제목 크기·여백이 화면마다 조금씩 다르면 앱 전체가
   * 덜 다듬어져 보인다. 관리 콘솔의 호출부는 `actions`(복수) 이름을 쓰므로 여기서만 이름을 잇는다
   * (호출부 30여 곳을 일괄 개명하는 것보다 이 한 줄이 안전하다).
   */
  return <SharedPageHeader title={title} description={description} action={actions} />;
}

/**
 * 관리 콘솔 섹션 — 배치·타이포는 앱 전역 `Section` 에 위임하고 **밀도만** 이 콘솔 값(compact)으로 고정한다.
 *
 * ★그전에는 여기 자체 정의가 있어 제목이 `text-sm`(14px)이었다. 같은 개념의 섹션 제목이 콘솔 15px /
 * 계정 16px / 관리 14px 로 갈려, 화면군을 오갈 때 한 제품으로 읽히지 않았다. 밀도는 실재하는 차이라
 * 남기되(관리 콘솔은 표가 빽빽하다) **타이포는 가르지 않는다**.
 *
 * 호출부가 `actions`(복수) 이름을 쓰므로 여기서만 이름을 잇는다.
 */
export function Section({
  title,
  description,
  actions,
  children,
  className,
}: {
  title?: string;
  description?: string;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <SharedSection
      title={title}
      description={description}
      action={actions}
      className={className}
      density="compact"
    >
      {children}
    </SharedSection>
  );
}

/**
 * 화면이 조용히 삼키면 안 되는 **사실**을 알린다(오류가 아니다).
 *
 * 가맹 콘솔의 같은 이름 컴포넌트(`app/merchant/_components/kit.tsx`)와 같은 모양이다 — 두 콘솔이
 * 다른 톤으로 말하면 한 제품처럼 보이지 않는다.
 */
export function Notice({ children }: { children: ReactNode }) {
  return (
    <p className="rounded-lg border border-border bg-muted/40 px-3 py-2 text-xs text-muted-foreground">
      {children}
    </p>
  );
}

/** 표는 항상 자기 컨테이너에서 가로 스크롤한다 — 페이지 본문이 가로로 밀리면 안 된다. */
export function TableScroll({ children }: { children: ReactNode }) {
  return <div className="-mx-4 overflow-x-auto px-4">{children}</div>;
}

/* ── 폼 ───────────────────────────────────────────────────────────────── */

export function Field({
  label,
  hint,
  error,
  children,
  className,
}: {
  label: string;
  hint?: string;
  error?: string;
  children: (id: string) => ReactNode;
  className?: string;
}) {
  const id = useId();
  return (
    <div className={cn("flex flex-col gap-1.5", className)}>
      <Label htmlFor={id}>{label}</Label>
      {children(id)}
      {hint && !error && <p className="text-xs text-muted-foreground">{hint}</p>}
      {error && <p className="text-xs text-destructive">{error}</p>}
    </div>
  );
}

export function TextField({
  label,
  value,
  onChange,
  hint,
  error,
  placeholder,
  type = "text",
  disabled,
  className,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  hint?: string;
  error?: string;
  placeholder?: string;
  type?: string;
  disabled?: boolean;
  className?: string;
}) {
  return (
    <Field label={label} hint={hint} error={error} className={className}>
      {(id) => (
        <Input
          id={id}
          type={type}
          value={value}
          placeholder={placeholder}
          disabled={disabled}
          autoComplete="off"
          onChange={(event) => onChange(event.target.value)}
        />
      )}
    </Field>
  );
}

const selectClass =
  "h-8 w-full min-w-0 rounded-lg border border-input bg-transparent px-2 text-sm text-foreground transition-colors outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:opacity-50 dark:bg-input/30";

/**
 * 네이티브 select. 옵션이 적고 키보드/모바일 동작이 중요한 관리 폼에서는 브라우저 기본 위젯이
 * 커스텀 팝업보다 안정적이다(스타일은 Input 과 맞춘다).
 */
export function SelectField({
  label,
  value,
  onChange,
  options,
  hint,
  disabled,
  className,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: { value: string; label: string }[];
  hint?: string;
  disabled?: boolean;
  className?: string;
}) {
  return (
    <Field label={label} hint={hint} className={className}>
      {(id) => (
        <select
          id={id}
          className={selectClass}
          value={value}
          disabled={disabled}
          onChange={(event) => onChange(event.target.value)}
        >
          {options.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      )}
    </Field>
  );
}

export function TextAreaField({
  label,
  value,
  onChange,
  hint,
  error,
  rows = 4,
  placeholder,
  mono,
  className,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  hint?: string;
  error?: string;
  rows?: number;
  placeholder?: string;
  mono?: boolean;
  className?: string;
}) {
  return (
    <Field label={label} hint={hint} error={error} className={className}>
      {(id) => (
        <textarea
          id={id}
          rows={rows}
          value={value}
          placeholder={placeholder}
          spellCheck={false}
          onChange={(event) => onChange(event.target.value)}
          className={cn(
            "w-full rounded-lg border border-input bg-transparent px-2.5 py-1.5 text-sm outline-none transition-colors placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 dark:bg-input/30",
            mono && "font-mono text-xs leading-relaxed",
            error && "border-destructive",
          )}
        />
      )}
    </Field>
  );
}

export function CheckboxField({
  label,
  checked,
  onChange,
  hint,
  disabled,
}: {
  label: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
  hint?: string;
  disabled?: boolean;
}) {
  const id = useId();
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="flex items-center gap-2 text-sm font-medium">
        <input
          id={id}
          type="checkbox"
          checked={checked}
          disabled={disabled}
          onChange={(event) => onChange(event.target.checked)}
          className="size-4 accent-[var(--primary)]"
        />
        {label}
      </label>
      {hint && <p className="pl-6 text-xs text-muted-foreground">{hint}</p>}
    </div>
  );
}

/* ── 대화상자 ──────────────────────────────────────────────────────────── */

export function Modal({
  open,
  onOpenChange,
  title,
  description,
  children,
  footer,
  wide,
  locked = false,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description?: string;
  children: ReactNode;
  footer?: ReactNode;
  wide?: boolean;
  /**
   * ESC·바깥 클릭·X 로 닫히지 않게 한다 — **닫는 순간 되돌릴 수 없는 것을 잃는** 모달 전용.
   *
   * ★클라이언트 시크릿 화면에서 실제로 새던 구멍이다: 푸터의 '닫기'는 "저장했습니다" 체크 전까지
   * 비활성이었지만, ESC 한 번이면 그 게이트를 지나쳐 창이 닫혔다. 서버는 해시만 저장하므로 그 값은
   * **영영 사라지고** 재발급만 남는다(그 사이 그 클라이언트로 붙어 있던 연동은 전부 끊긴다).
   * 게이트가 있는데 우회로가 있으면 게이트가 아니라 장식이다.
   */
  locked?: boolean;
}) {
  return (
    <Dialog
      open={open}
      /*
       * 제어 모드라 ESC·바깥 클릭도 결국 `onOpenChange(false)` 로 들어온다. 잠겼을 때 그 호출을
       * 무시하면 `open` 이 true 로 남아 창이 닫히지 않는다 — 닫기 경로가 하나로 모이므로
       * 라이브러리 버전마다 다른 dismiss 옵션에 기대지 않는다. X 버튼은 아래에서 함께 감춘다.
       */
      onOpenChange={(next) => {
        if (locked && !next) return;
        onOpenChange(next);
      }}
    >
      <DialogContent
        showCloseButton={!locked}
        className={cn("max-h-[85vh] overflow-y-auto", wide ? "sm:max-w-2xl" : "sm:max-w-lg")}
      >
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {description && <DialogDescription>{description}</DialogDescription>}
        </DialogHeader>
        <div className="flex flex-col gap-3">{children}</div>
        {footer && <DialogFooter>{footer}</DialogFooter>}
      </DialogContent>
    </Dialog>
  );
}

/** 되돌릴 수 없는 작업 확인. 무엇이 사라지는지 명시하고 기본 초점은 취소에 둔다. */
export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  message,
  confirmLabel = "실행",
  busy,
  onConfirm,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  message: string;
  confirmLabel?: string;
  busy?: boolean;
  onConfirm: () => void;
}) {
  return (
    <Modal
      open={open}
      onOpenChange={onOpenChange}
      title={title}
      footer={
        <>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>
            취소
          </Button>
          <Button variant="destructive" onClick={onConfirm} disabled={busy}>
            {busy ? "처리 중" : confirmLabel}
          </Button>
        </>
      }
    >
      <p className="text-sm text-muted-foreground">{message}</p>
    </Modal>
  );
}

/* ── 표시 조각 ─────────────────────────────────────────────────────────── */

export function StatusBadge({ status }: { status: string }) {
  const tone =
    status === "ACTIVE" || status === "OK" || status === "ALLOW"
      ? "success"
      : status === "SUSPENDED" || status === "ERROR" || status === "DENY"
        ? "danger"
        : "warning";
  const color =
    tone === "success"
      ? "text-[color:var(--taspa-success)] bg-[color:var(--taspa-success-soft)]"
      : tone === "danger"
        ? "text-[color:var(--taspa-danger)] bg-[color:var(--taspa-danger-soft)]"
        : "text-[color:var(--taspa-warning)] bg-[color:var(--taspa-warning-soft)]";
  return (
    <span className={cn("inline-flex h-5 items-center rounded-4xl px-2 text-xs font-medium", color)}>
      {status}
    </span>
  );
}

export function BoolBadge({
  value,
  trueLabel = "예",
  falseLabel = "아니오",
}: {
  value: boolean;
  trueLabel?: string;
  falseLabel?: string;
}) {
  return value ? (
    <Badge variant="secondary" className="border-border">
      {trueLabel}
    </Badge>
  ) : (
    <span className="text-xs text-muted-foreground">{falseLabel}</span>
  );
}

export function StatCard({
  label,
  value,
  hint,
  href,
}: {
  label: string;
  value: ReactNode;
  hint?: string;
  href?: string;
}) {
  /*
   * ★**누를 수 있을 때만 카드 표면**이다.
   *
   * 카드 표면 + hover 테두리는 "누르면 뭔가 일어난다"는 신호인데, `href` 없이 쓰면 그 신호가 거짓이 된다
   * (정합성 대사·지급 현황 화면이 그 상태였다 — 같은 성격의 지표가 어떤 화면에선 뜨고 어떤 화면에선
   * 파여서, 사용자는 둘의 차이를 누를 수 있는지로 읽을 수 없었다).
   * 링크가 아니면 전역 `Stat`(파인 타일)로 떨어뜨려 위계와 의미를 일치시킨다.
   */
  if (!href) return <SharedStat label={label} value={value} hint={hint} />;

  return (
    <a href={href} className="block rounded-xl">
      <Card className="@container h-full gap-0 py-0 transition-colors hover:border-primary">
        <CardContent className="flex flex-col gap-1 px-4 py-3.5">
          <p className="text-label text-muted-foreground">{label}</p>
          <p className="tabular text-metric-sm @lg:text-metric text-foreground">{value}</p>
          {hint && <p className="text-xs leading-snug text-muted-foreground">{hint}</p>}
        </CardContent>
      </Card>
    </a>
  );
}

/** 다시 볼 수 없는 값(클라이언트 시크릿 등)을 보여주고 복사시킨다. */
export function CopyBox({ value, label }: { value: string; label?: string }) {
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!copied) return;
    const timer = setTimeout(() => setCopied(false), 2000);
    return () => clearTimeout(timer);
  }, [copied]);

  return (
    <div className="flex flex-col gap-1.5">
      {label && <p className="text-sm font-medium">{label}</p>}
      <div className="flex items-stretch gap-2">
        <code className="min-w-0 flex-1 overflow-x-auto rounded-lg border border-border bg-muted px-2.5 py-1.5 font-mono text-xs whitespace-pre">
          {value}
        </code>
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            navigator.clipboard?.writeText(value).then(
              () => setCopied(true),
              () => setCopied(false),
            );
          }}
        >
          {copied ? "복사됨" : "복사"}
        </Button>
      </div>
    </div>
  );
}

/** 날짜·시각 표기 — 목록에서 정렬 비교가 쉬운 고정 폭 형식. */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function shortId(id: string | null | undefined): string {
  if (!id) return "—";
  return id.length > 12 ? `${id.slice(0, 8)}…` : id;
}

/* ── 값 변환 ──────────────────────────────────────────────────────────── */

/** 줄바꿈 구분 텍스트 ↔ 문자열 배열(리다이렉트 URI·도메인 등 목록 입력에 쓴다). */
export function linesToList(text: string): string[] {
  return text
    .split(/[\n,]/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
}

export function listToLines(list: string[] | undefined): string {
  return (list ?? []).join("\n");
}

/** 공백·쉼표로 구분된 토큰(스코프·UUID 목록). */
export function tokens(text: string): string[] {
  return text
    .split(/[\s,]+/)
    .map((token) => token.trim())
    .filter((token) => token.length > 0);
}

/** JSON 편집 상태 — 파싱 오류를 화면에 그대로 보여준다(무거운 에디터 의존성 없이). */
export function useJsonDraft(initial: string) {
  const [text, setText] = useState(initial);
  const error = useMemo(() => {
    if (!text.trim()) return "정책 문서를 입력하세요";
    try {
      JSON.parse(text);
      return undefined;
    } catch (cause) {
      return `JSON 형식 오류: ${cause instanceof Error ? cause.message : "파싱 실패"}`;
    }
  }, [text]);

  const pretty = () => {
    try {
      setText(JSON.stringify(JSON.parse(text), null, 2));
    } catch {
      /* 형식이 깨진 상태에서는 정렬하지 않는다 — 오류 메시지가 이미 이유를 말해준다. */
    }
  };

  return {
    text,
    setText,
    error,
    pretty,
    reset: (next: string) => setText(next),
  };
}
