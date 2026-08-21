"use client";

import { useState } from "react";
import { useMutation } from "@/lib/useApi";
import type { CurrentUser } from "@/lib/session";
import { Button, ButtonLink } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { deleteAccount } from "../_lib/endpoints";
import { ConfirmDialog, FieldHint, Section } from "./chrome";

/**
 * 계정 삭제(DELETE /api/account) — 되돌릴 수 없는 하드 삭제.
 *
 * 서버는 step-up(최근 재인증)과 **이메일 재입력**을 함께 요구한다. 화면도 같은 게이트를 그대로 보여준다 —
 * 확인 문구를 클라이언트에서만 검사하고 서버엔 아무거나 보내는 식으로 흉내 내지 않는다(불일치는 서버가
 * CONFIRMATION_MISMATCH 로 거절한다).
 *
 * 성공하면 서버가 세션과 쿠키를 정리하므로, 이후에는 SPA 상태를 믿을 수 없다 — 통째로 이동시킨다.
 */
export function DangerZone({ user }: { user: CurrentUser }) {
  const [open, setOpen] = useState(false);
  const [confirmEmail, setConfirmEmail] = useState("");
  const [done, setDone] = useState(false);

  const remove = useMutation(async (email: string) => {
    await deleteAccount(email);
    return true;
  });

  const matches = confirmEmail.trim().toLowerCase() === user.email.toLowerCase();

  if (done) {
    return (
      <Section title="계정 삭제">
        <p className="text-sm text-foreground">계정이 삭제되었습니다. 이용해 주셔서 감사합니다.</p>
        <div>
          {/* 세션은 서버가 이미 정리했다. 홈은 SPA 라우트라 Link 로 보내면 익명 화면이 그대로 렌더된다. */}
          <ButtonLink href="/">처음 화면으로</ButtonLink>
        </div>
      </Section>
    );
  }

  return (
    <>
      <Section
        title="계정 삭제"
        description="계정과 관련된 로그인 정보, 패스키, 연결된 앱 권한이 모두 삭제됩니다. 되돌릴 수 없습니다."
      >
        <FieldHint>
          조직에 소속되어 있다면 식대 사용 내역 등 조직이 보관해야 하는 기록은 조직 관리자에게 문의하세요.
        </FieldHint>
        <div>
          <Button variant="destructive" onClick={() => setOpen(true)}>
            계정 삭제
          </Button>
        </div>
      </Section>

      <ConfirmDialog
        open={open}
        onOpenChange={(next) => {
          setOpen(next);
          if (!next) {
            setConfirmEmail("");
            remove.clearError();
          }
        }}
        title="정말 계정을 삭제할까요?"
        description="이 작업은 되돌릴 수 없습니다. 확인을 위해 계정 이메일을 그대로 입력하세요."
        confirmLabel="영구 삭제"
        confirmDisabled={!matches}
        busy={remove.busy}
        error={remove.error}
        onConfirm={async () => {
          if (!matches) return;
          if (await remove.mutate(confirmEmail.trim())) {
            setOpen(false);
            setDone(true);
          }
        }}
      >
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="delete-confirm-email">{user.email}</Label>
          <Input
            id="delete-confirm-email"
            type="email"
            autoComplete="off"
            value={confirmEmail}
            placeholder={user.email}
            onChange={(event) => setConfirmEmail(event.target.value)}
            aria-invalid={confirmEmail.length > 0 && !matches ? true : undefined}
          />
          {confirmEmail.length > 0 && !matches && (
            <p className="text-xs text-destructive">이메일이 계정 주소와 다릅니다.</p>
          )}
        </div>
      </ConfirmDialog>
    </>
  );
}
