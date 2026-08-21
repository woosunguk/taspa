"use client";

import { useCallback } from "react";
import { Button } from "@/components/ui/button";
import { formatWon } from "./types";

/**
 * 금액 입력 — 소프트 키보드가 아니라 **숫자 패드**다.
 *
 * 계산대 단말은 대개 세워 둔 태블릿이고, 계산원은 한 손으로 조작한다. 소프트 키보드는 화면 절반을
 * 가리면서 키가 작고, 숫자 외 문자가 섞여 들어온다. 패드는 항상 같은 자리에 있고 눌러야 할 것만 있다.
 *
 * 값은 **숫자 문자열**로 들고 있는다(number 로 즉시 바꾸면 "0" 으로 시작하는 중간 입력이나 빈 값을
 * 표현할 수 없다). 원 단위 정수만 만들어지므로 소수점·음수가 애초에 존재할 수 없다.
 */

/** 원 단위. 이 이상은 식대 결제로 정상적인 값이 아니고, 오타(0 하나 더)일 가능성이 압도적이다. */
const MAX_DIGITS = 7;

const KEYS = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "00", "0", "←"] as const;

export function amountFromDigits(digits: string): number {
  const parsed = Number.parseInt(digits, 10);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : 0;
}

export function AmountPad({
  digits,
  onChange,
  disabled,
}: {
  digits: string;
  onChange: (digits: string) => void;
  disabled?: boolean;
}) {
  const press = useCallback(
    (key: string) => {
      if (key === "←") {
        onChange(digits.slice(0, -1));
        return;
      }
      // 선행 0 은 만들지 않는다 — "007000" 같은 표시는 계산원이 금액을 잘못 읽게 한다.
      const next = (digits === "0" ? "" : digits) + key;
      const trimmed = next.replace(/^0+/, "");
      if (trimmed.length > MAX_DIGITS) return;
      onChange(trimmed);
    },
    [digits, onChange],
  );

  const amount = amountFromDigits(digits);

  return (
    <div className="flex flex-col gap-4">
      <div className="rounded-2xl border border-border bg-card px-5 py-4">
        <p className="text-sm text-muted-foreground">결제 금액</p>
        <p
          aria-live="polite"
          className={`tabular-nums text-right text-5xl font-semibold ${
            amount > 0 ? "text-foreground" : "text-muted-foreground"
          }`}
        >
          {formatWon(amount)}
        </p>
      </div>

      <div className="grid grid-cols-3 gap-3">
        {KEYS.map((key) => (
          <Button
            key={key}
            type="button"
            variant={key === "←" ? "outline" : "secondary"}
            size="lg"
            className="h-16 rounded-xl text-2xl font-medium"
            onClick={() => press(key)}
            disabled={disabled}
            aria-label={key === "←" ? "한 자리 지우기" : key}
          >
            {key}
          </Button>
        ))}
      </div>
    </div>
  );
}
