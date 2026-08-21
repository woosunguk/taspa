"use client";

import { useState } from "react";
import { useMutation } from "@/lib/useApi";
import type { CurrentUser } from "@/lib/session";
import { Button, ButtonLink } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { changePassword } from "../_lib/endpoints";
import { FieldHint, InlineError, Section } from "./chrome";

/**
 * 비밀번호 변경/설정(POST /api/account/password, step-up 대상).
 *
 * 소셜 전용 계정(hasPassword=false)은 확인할 현재 비밀번호가 없으므로 "설정"이고, 서버도
 * currentPassword 없이 받는다. 어느 쪽이든 성공하면 서버가 **모든 세션과 신뢰 기기를 폐기**하므로
 * (자격 증명 변경 = 탈취 대응) 현재 세션도 끊긴다 — 화면은 그 사실을 미리 알리고 로그인으로 보낸다.
 */
export function PasswordSection({ user }: { user: CurrentUser }) {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [repeat, setRepeat] = useState("");
  const [done, setDone] = useState(false);

  const { mutate, busy, error } = useMutation(async (args: { current: string; next: string }) => {
    await changePassword(user.hasPassword ? args.current : null, args.next);
    return true;
  });

  const mismatch = repeat.length > 0 && next !== repeat;
  const submittable =
    next.length > 0 && !mismatch && repeat.length > 0 && (!user.hasPassword || current.length > 0);

  if (done) {
    return (
      <Section title={user.hasPassword ? "비밀번호" : "비밀번호 설정"}>
        <p className="text-sm text-foreground">
          비밀번호를 변경했습니다. 보안을 위해 모든 기기에서 로그아웃되었습니다.
        </p>
        <div>
          {/* 서버가 세션·쿠키를 정리해야 하므로 SPA 라우팅이 아닌 실제 이동이다. */}
          <ButtonLink href="/login" external>
            다시 로그인
          </ButtonLink>
        </div>
      </Section>
    );
  }

  return (
    <Section
      title={user.hasPassword ? "비밀번호" : "비밀번호 설정"}
      description={
        user.hasPassword
          ? "변경하면 이 기기를 포함한 모든 기기에서 로그아웃됩니다."
          : "소셜 로그인으로 만든 계정입니다. 비밀번호를 설정하면 이메일과 비밀번호로도 로그인할 수 있습니다."
      }
    >
      <form
        className="flex max-w-sm flex-col gap-3"
        onSubmit={async (event) => {
          event.preventDefault();
          if (await mutate({ current, next })) setDone(true);
        }}
      >
        {user.hasPassword && (
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="current-password">현재 비밀번호</Label>
            <Input
              id="current-password"
              type="password"
              autoComplete="current-password"
              value={current}
              onChange={(event) => setCurrent(event.target.value)}
            />
          </div>
        )}
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="new-password">새 비밀번호</Label>
          <Input
            id="new-password"
            type="password"
            autoComplete="new-password"
            value={next}
            onChange={(event) => setNext(event.target.value)}
          />
          <FieldHint>
            서버의 비밀번호 정책을 통과해야 합니다. 조건에 맞지 않으면 아래에 이유가 표시됩니다.
          </FieldHint>
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="repeat-password">새 비밀번호 확인</Label>
          <Input
            id="repeat-password"
            type="password"
            autoComplete="new-password"
            value={repeat}
            onChange={(event) => setRepeat(event.target.value)}
            aria-invalid={mismatch || undefined}
          />
          {mismatch && <p className="text-xs text-destructive">두 번 입력한 비밀번호가 다릅니다.</p>}
        </div>
        <InlineError message={error} />
        <div>
          <Button type="submit" disabled={busy || !submittable}>
            {busy ? "적용 중…" : user.hasPassword ? "비밀번호 변경" : "비밀번호 설정"}
          </Button>
        </div>
      </form>
    </Section>
  );
}
