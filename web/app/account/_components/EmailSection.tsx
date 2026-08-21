"use client";

import { useState } from "react";
import { toast } from "sonner";
import { useMutation } from "@/lib/useApi";
import type { CurrentUser } from "@/lib/session";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { confirmEmailChange, requestEmailChange } from "../_lib/endpoints";
import { FieldHint, InlineError, Section } from "./chrome";

/**
 * 이메일 변경 — 2단계.
 *
 * 1) POST /api/account/email/change: **새 주소로** 확인 코드를 보낸다(step-up 대상 — api 계층이 401
 *    REAUTH_REQUIRED 를 잡아 /reauth 로 보낸다). 이 시점엔 계정 이메일이 아직 바뀌지 않는다.
 * 2) POST /api/account/email/change/confirm: 코드가 맞으면 전환된다.
 *
 * 대기 중인 변경은 **서버 세션**에 있다(PendingEmailChange). 그래서 화면을 새로고침해 로컬 상태가
 * 사라져도 코드는 여전히 유효하다 — "코드를 이미 받았어요" 경로를 항상 열어 두는 이유다.
 */
export function EmailSection({ user, onChanged }: { user: CurrentUser; onChanged: () => void }) {
  const [newEmail, setNewEmail] = useState("");
  const [code, setCode] = useState("");
  const [stage, setStage] = useState<"idle" | "code">("idle");

  const request = useMutation(async (email: string) => {
    await requestEmailChange(email);
    return true;
  });

  const confirm = useMutation(async (value: string) => {
    await confirmEmailChange(value);
    return true;
  });

  return (
    <Section
      title="이메일 주소"
      description="로그인 아이디이자 보안 알림을 받는 주소입니다."
      action={
        user.emailVerified ? (
          <Badge variant="outline" className="text-[color:var(--taspa-success)]">
            인증됨
          </Badge>
        ) : (
          <Badge variant="outline" className="text-[color:var(--taspa-warning)]">
            미인증
          </Badge>
        )
      }
    >
      <p className="text-sm text-foreground">{user.email}</p>

      {stage === "idle" ? (
        <form
          className="flex flex-col gap-3"
          onSubmit={async (event) => {
            event.preventDefault();
            if (await request.mutate(newEmail.trim())) {
              setStage("code");
              toast.success("새 주소로 확인 코드를 보냈습니다");
            }
          }}
        >
          <div className="flex max-w-sm flex-col gap-1.5">
            <Label htmlFor="new-email">새 이메일 주소</Label>
            <Input
              id="new-email"
              type="email"
              autoComplete="email"
              value={newEmail}
              onChange={(event) => setNewEmail(event.target.value)}
              placeholder="new@example.com"
            />
            <FieldHint>
              확인 코드는 <b>새 주소</b>로 발송됩니다. 코드를 입력해야 변경이 완료됩니다.
            </FieldHint>
          </div>
          <InlineError message={request.error} />
          <div className="flex items-center gap-2">
            <Button type="submit" disabled={request.busy || newEmail.trim().length === 0}>
              {request.busy ? "보내는 중…" : "확인 코드 보내기"}
            </Button>
            <Button type="button" variant="ghost" onClick={() => setStage("code")}>
              코드를 이미 받았어요
            </Button>
          </div>
        </form>
      ) : (
        <form
          className="flex flex-col gap-3"
          onSubmit={async (event) => {
            event.preventDefault();
            if (await confirm.mutate(code.trim())) {
              toast.success("이메일 주소를 변경했습니다");
              setStage("idle");
              setNewEmail("");
              setCode("");
              onChanged();
            }
          }}
        >
          <div className="flex max-w-xs flex-col gap-1.5">
            <Label htmlFor="email-code">확인 코드</Label>
            <Input
              id="email-code"
              inputMode="numeric"
              autoComplete="one-time-code"
              value={code}
              onChange={(event) => setCode(event.target.value)}
              className="tabular"
            />
            <FieldHint>
              {newEmail.trim()
                ? `${newEmail.trim()} 로 보낸 코드를 입력하세요.`
                : "새 주소로 보낸 코드를 입력하세요."}
            </FieldHint>
          </div>
          <InlineError message={confirm.error} />
          <div className="flex items-center gap-2">
            <Button type="submit" disabled={confirm.busy || code.trim().length === 0}>
              {confirm.busy ? "확인 중…" : "변경 완료"}
            </Button>
            <Button
              type="button"
              variant="ghost"
              onClick={() => {
                setStage("idle");
                setCode("");
                confirm.clearError();
              }}
            >
              취소
            </Button>
          </div>
        </form>
      )}
    </Section>
  );
}
