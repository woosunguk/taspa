"use client";

import { useState } from "react";
import { toast } from "sonner";
import { useMutation } from "@/lib/useApi";
import type { CurrentUser } from "@/lib/session";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { updateDisplayName } from "../_lib/endpoints";
import { FieldHint, InlineError, Section } from "./chrome";

/**
 * 표시 이름 편집(PATCH /api/account/profile).
 * 비파괴 작업이라 서버가 step-up 을 요구하지 않는다 — 저장 버튼 하나로 끝난다.
 */
export function ProfileSection({ user, onChanged }: { user: CurrentUser; onChanged: () => void }) {
  const [displayName, setDisplayName] = useState(user.displayName ?? "");

  // 성공/실패를 boolean 으로 되돌린다 — void 를 돌려주면 useMutation 결과로는 둘을 구분할 수 없다.
  const { mutate, busy, error } = useMutation(async (value: string) => {
    await updateDisplayName(value);
    return true;
  });

  const fallbackName = user.email.split("@")[0];
  const dirty = (user.displayName ?? "") !== displayName;

  return (
    <Section title="프로필" description="다른 화면과 알림 메일에 표시되는 이름입니다.">
      <form
        className="flex flex-col gap-3"
        onSubmit={async (event) => {
          event.preventDefault();
          if (await mutate(displayName)) {
            toast.success("표시 이름을 저장했습니다");
            onChanged();
          }
        }}
      >
        <div className="flex max-w-sm flex-col gap-1.5">
          <Label htmlFor="display-name">표시 이름</Label>
          <Input
            id="display-name"
            value={displayName}
            maxLength={100}
            placeholder={fallbackName}
            onChange={(event) => setDisplayName(event.target.value)}
          />
          <FieldHint>비워 두면 이메일 앞부분({fallbackName})이 대신 표시됩니다.</FieldHint>
        </div>
        <InlineError message={error} />
        <div>
          <Button type="submit" disabled={busy || !dirty}>
            {busy ? "저장 중…" : "저장"}
          </Button>
        </div>
      </form>
    </Section>
  );
}
