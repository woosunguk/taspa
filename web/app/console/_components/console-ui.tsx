"use client";

import { useState, type ReactNode } from "react";
import { CheckIcon, CopyIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { cn } from "@/lib/utils";

/*
 * 숫자·위계 조각은 앱 전역(`@/components/data-display`)으로 옮겼다 — 식권·가맹 화면도 같은 것을 쓰는데
 * 콘솔 폴더에 두면 다른 기능이 남의 내부를 임포트하게 된다. 콘솔 화면들의 import 경로는 그대로 두려고
 * 여기서 다시 내보낸다.
 */
export { Stat, ProgressMeter, PageHeader, NoValue, StatusLine } from "@/components/data-display";
export { Section } from "@/components/section";

/* 콘솔 화면들이 공유하는 작은 조각. 앱 전역 조각은 @/components/feedback 에 있고, 여기엔 조직 콘솔에서만
   쓰는 것만 둔다(다른 화면 담당자와 파일이 겹치지 않게 하려는 의도도 있다). */

/** 라벨 + 입력 한 쌍. 폼 격자 안에서 정렬을 맞춘다. */
export function Field({
  label,
  hint,
  htmlFor,
  children,
  className,
}: {
  label: string;
  hint?: string;
  htmlFor?: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("flex flex-col gap-1.5", className)}>
      <Label htmlFor={htmlFor}>{label}</Label>
      {children}
      {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
    </div>
  );
}

/**
 * 인라인 폼에서 **입력 줄에 나란히 놓는 버튼 자리**.
 *
 * ★컨테이너를 `items-end` 로 맞추면 hint 가 있는 `Field` 만 입력이 위로 밀려 한 줄이 어긋난다
 * (사업장 폼의 '타임존'이 실제로 22px 올라가 있었고, 위임 폼의 '지정' 버튼은 힌트 줄에 걸쳐 있었다).
 * `items-start` + **라벨 높이만큼의 투명 자리**로 맞추면 hint 유무·줄 수와 무관하게 항상 입력 줄에 선다
 * (매직 넘버 없이 라벨과 같은 타이포로 자리를 잡으므로 폰트가 바뀌어도 따라간다).
 */
export function FieldAction({ children }: { children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <span aria-hidden className="hidden text-sm leading-none font-medium sm:block">
        &nbsp;
      </span>
      {children}
    </div>
  );
}

export interface Option {
  value: string;
  label: string;
}

/**
 * 문자열 값 전용 셀렉트. `null` 값을 허용해 "전체"·"미지정" 같은 비어 있는 선택을 표현한다
 * (빈 문자열을 센티넬로 쓰면 서버로 그대로 새어나가기 쉬워 명시적 null 을 쓴다).
 */
export function Choice({
  value,
  onChange,
  options,
  placeholder = "선택",
  emptyLabel,
  id,
  className,
  disabled,
}: {
  value: string | null;
  onChange: (value: string | null) => void;
  options: Option[];
  placeholder?: string;
  /** 지정하면 "값 없음" 항목을 맨 위에 넣는다(예: "전체"). */
  emptyLabel?: string;
  id?: string;
  className?: string;
  disabled?: boolean;
}) {
  const EMPTY = "__none__";
  const items: Option[] = emptyLabel ? [{ value: EMPTY, label: emptyLabel }, ...options] : options;

  return (
    <Select
      value={value ?? (emptyLabel ? EMPTY : null)}
      onValueChange={(next) => onChange(next === EMPTY || next === null ? null : next)}
      disabled={disabled}
    >
      <SelectTrigger id={id} className={cn("w-full", className)}>
        <SelectValue placeholder={placeholder}>
          {(current) => items.find((o) => o.value === current)?.label ?? placeholder}
        </SelectValue>
      </SelectTrigger>
      <SelectContent>
        {items.map((option) => (
          <SelectItem key={option.value} value={option.value}>
            {option.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

/** 값 옆의 복사 버튼. DNS TXT 레코드처럼 손으로 옮겨 적으면 반드시 틀리는 값에 쓴다. */
export function CopyValue({ value, label }: { value: string; label?: string }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    } catch {
      // 클립보드 권한이 없는 환경(비 HTTPS 등) — 값은 화면에 그대로 보이므로 수동 복사로 대체된다.
      setCopied(false);
    }
  }

  return (
    <div className="flex min-w-0 items-center gap-2">
      <code className="min-w-0 flex-1 truncate rounded-md bg-muted px-2 py-1 font-mono text-xs text-foreground">
        {value}
      </code>
      <Button
        type="button"
        variant="outline"
        size="icon-sm"
        onClick={copy}
        aria-label={label ? `${label} 복사` : "복사"}
      >
        {copied ? <CheckIcon /> : <CopyIcon />}
      </Button>
    </div>
  );
}

/**
 * 되돌릴 수 없는 작업의 확인 절차. 버튼을 두 번 누르게 하고, 두 번째 상태에서 무엇이 일어나는지 문구로 말한다.
 * 브라우저 `confirm()` 을 쓰지 않는 이유: 문구를 우리말로 통제할 수 없고 모바일에서 맥락이 끊긴다.
 */
export function ConfirmButton({
  onConfirm,
  children,
  confirmLabel = "한 번 더 누르면 실행",
  disabled,
  variant = "destructive",
  size = "sm",
}: {
  onConfirm: () => void;
  children: ReactNode;
  confirmLabel?: string;
  disabled?: boolean;
  variant?: "destructive" | "outline" | "ghost";
  size?: "sm" | "xs" | "default";
}) {
  const [armed, setArmed] = useState(false);

  return (
    <Button
      type="button"
      variant={armed ? "destructive" : variant}
      size={size}
      disabled={disabled}
      onBlur={() => setArmed(false)}
      onClick={() => {
        if (!armed) {
          setArmed(true);
          return;
        }
        setArmed(false);
        onConfirm();
      }}
    >
      {armed ? confirmLabel : children}
    </Button>
  );
}

/** 좁은 화면에서도 표가 페이지를 밀지 않게 감싼다(표 자체가 스크롤 컨테이너를 가진다). */
export function TableScroll({ children }: { children: ReactNode }) {
  return <div className="w-full overflow-x-auto">{children}</div>;
}

/** 인라인 폼에서 쓰는 짧은 텍스트 입력(라벨과 함께). */
export function TextField({
  label,
  value,
  onChange,
  placeholder,
  hint,
  id,
  type = "text",
  disabled,
  className,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  hint?: string;
  id?: string;
  type?: string;
  disabled?: boolean;
  className?: string;
}) {
  return (
    <Field label={label} hint={hint} htmlFor={id} className={className}>
      <Input
        id={id}
        type={type}
        value={value}
        placeholder={placeholder}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
      />
    </Field>
  );
}
