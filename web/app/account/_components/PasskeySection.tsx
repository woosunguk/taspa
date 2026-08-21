"use client";

import { useState } from "react";
import { toast } from "sonner";
import { useApi, useMutation } from "@/lib/useApi";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { PASSKEYS, deletePasskey, renamePasskey, type Passkey } from "../_lib/endpoints";
import { formatDate, relativeFromNow } from "../_lib/format";
import { isWebAuthnSupported, registerPasskey } from "../_lib/webauthn";
import { ConfirmDialog, FieldHint, InlineError, Section } from "./chrome";

/**
 * 패스키 목록·등록·이름 변경·삭제.
 *
 * 등록은 브라우저 WebAuthn API 를 직접 호출한다(`_lib/webauthn.ts` — 인코딩 규약은 서버가 서빙하는
 * 벤더링 스크립트와 동일). 등록 경로는 필터 단계에서 step-up 을 강제하므로, 인증기 프롬프트를 띄우기
 * **전에** `/api/reauth/check` 로 먼저 확인한다(지문까지 찍고 실패하는 순서를 피한다).
 */
export function PasskeySection() {
  const list = useApi<Passkey[]>(PASSKEYS);
  const [label, setLabel] = useState("내 기기");
  const [renaming, setRenaming] = useState<{
    id: string;
    label: string;
  } | null>(null);
  const [deleting, setDeleting] = useState<Passkey | null>(null);

  const supported = isWebAuthnSupported();

  const create = useMutation(async (value: string) => {
    await registerPasskey(value);
    return true;
  });

  const rename = useMutation(async (args: { id: string; label: string }) => {
    await renamePasskey(args.id, args.label);
    return true;
  });

  const remove = useMutation(async (credentialId: string) => {
    await deletePasskey(credentialId);
    return true;
  });

  return (
    <>
      <Section
        title="패스키"
        description="지문·얼굴·기기 잠금으로 비밀번호 없이 로그인합니다. 피싱에 강한 로그인 수단입니다."
      >
        {list.loading ? (
          <RowsSkeleton rows={2} />
        ) : list.error ? (
          <ErrorNotice message={list.error} onRetry={list.reload} />
        ) : (list.data?.length ?? 0) === 0 ? (
          <EmptyState
            title="등록된 패스키가 없습니다"
            description="이 기기에 패스키를 만들면 다음 로그인부터 비밀번호를 입력하지 않아도 됩니다."
          />
        ) : (
          <ul className="flex flex-col divide-y divide-border">
            {list.data?.map((passkey) => (
              <li key={passkey.credentialId} className="flex flex-wrap items-center gap-3 py-3">
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium text-foreground">{passkey.label}</p>
                  <p className="text-xs text-muted-foreground">
                    등록 {formatDate(passkey.createdAt)} · 마지막 사용{" "}
                    <span title={passkey.lastUsedAt ?? undefined}>{relativeFromNow(passkey.lastUsedAt)}</span>
                  </p>
                </div>
                <div className="flex gap-1">
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() =>
                      setRenaming({
                        id: passkey.credentialId,
                        label: passkey.label,
                      })
                    }
                  >
                    이름 변경
                  </Button>
                  <Button variant="ghost" size="sm" onClick={() => setDeleting(passkey)}>
                    삭제
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        )}

        <div className="flex flex-col gap-3 border-t border-border pt-4">
          <div className="flex max-w-sm flex-col gap-1.5">
            <Label htmlFor="passkey-label">새 패스키 이름</Label>
            <Input
              id="passkey-label"
              maxLength={100}
              value={label}
              onChange={(event) => setLabel(event.target.value)}
              disabled={!supported}
            />
            <FieldHint>나중에 어떤 기기인지 알아볼 수 있는 이름을 적어 두세요. 예: 회사 노트북.</FieldHint>
          </div>

          <InlineError message={create.error} />
          {!supported && (
            <p className="text-sm text-[color:var(--taspa-warning)]">
              이 브라우저는 패스키(WebAuthn)를 지원하지 않습니다. 최신 Chrome·Safari·Edge 에서 다시 시도해
              주세요.
            </p>
          )}

          <div>
            <Button
              disabled={!supported || create.busy || label.trim().length === 0}
              onClick={async () => {
                if (await create.mutate(label.trim())) {
                  toast.success("패스키를 등록했습니다");
                  list.reload();
                }
              }}
            >
              {create.busy ? "인증기 확인 중…" : "패스키 만들기"}
            </Button>
          </div>
        </div>
      </Section>

      <ConfirmDialog
        open={renaming !== null}
        onOpenChange={(open) => {
          if (!open) {
            setRenaming(null);
            rename.clearError();
          }
        }}
        title="패스키 이름 변경"
        description="목록에서 이 패스키를 구분할 이름입니다."
        confirmLabel="저장"
        destructive={false}
        confirmDisabled={(renaming?.label.trim().length ?? 0) === 0}
        busy={rename.busy}
        error={rename.error}
        onConfirm={async () => {
          if (!renaming) return;
          const value = renaming.label.trim();
          if (value.length === 0) return;
          if (await rename.mutate({ id: renaming.id, label: value })) {
            setRenaming(null);
            toast.success("이름을 변경했습니다");
            list.reload();
          }
        }}
      >
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="passkey-rename">이름</Label>
          <Input
            id="passkey-rename"
            maxLength={100}
            value={renaming?.label ?? ""}
            onChange={(event) =>
              setRenaming((prev) => (prev ? { ...prev, label: event.target.value } : prev))
            }
          />
        </div>
      </ConfirmDialog>

      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => {
          if (!open) {
            setDeleting(null);
            remove.clearError();
          }
        }}
        title="패스키를 삭제할까요?"
        description={
          deleting
            ? `"${deleting.label}" 로는 더 이상 로그인할 수 없습니다. 기기에 남아 있는 패스키도 직접 지워 주세요.`
            : ""
        }
        confirmLabel="삭제"
        busy={remove.busy}
        error={remove.error}
        onConfirm={async () => {
          if (!deleting) return;
          if (await remove.mutate(deleting.credentialId)) {
            setDeleting(null);
            toast.success("패스키를 삭제했습니다");
            list.reload();
          }
        }}
      />
    </>
  );
}
