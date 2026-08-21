"use client";

import { useState } from "react";
import { toast } from "sonner";
import { useMutation } from "@/lib/useApi";
import type { CurrentUser } from "@/lib/session";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { activateMfa, disableMfa, regenerateBackupCodes, setupMfa, type MfaSetup } from "../_lib/endpoints";
import { BackupCodesDialog } from "./BackupCodesDialog";
import { ConfirmDialog, FieldHint, InlineError, Section } from "./chrome";

/**
 * 2단계 인증(TOTP).
 *
 * 등록: POST /api/mfa/setup → 서버가 시크릿을 저장하고 QR 을 **data URI** 로 돌려준다(외부 요청 없음) →
 * 인증 앱이 만든 6자리로 POST /api/mfa/activate → 백업 코드 10개가 **이 응답에만** 담겨 온다.
 * 해제: POST /api/mfa/disable + 현재 코드. 해제하면 서버가 신뢰 기기도 모두 폐기한다.
 *
 * `/api/mfa/**` 는 클래스 전체가 step-up 대상이라 어느 호출에서든 401 REAUTH_REQUIRED 가 올 수 있다 —
 * api 계층이 /reauth 로 보내고 돌아온다.
 */
export function MfaSection({ user, onChanged }: { user: CurrentUser; onChanged: () => void }) {
  const [setup, setSetup] = useState<MfaSetup | null>(null);
  const [code, setCode] = useState("");
  const [backupCodes, setBackupCodes] = useState<string[] | null>(null);
  const [disableOpen, setDisableOpen] = useState(false);
  const [disableCode, setDisableCode] = useState("");

  const start = useMutation(async () => await setupMfa());
  const activate = useMutation(async (value: string) => await activateMfa(value));
  const disable = useMutation(async (value: string) => {
    await disableMfa(value);
    return true;
  });
  const regenerate = useMutation(async () => await regenerateBackupCodes());

  const closeCodes = () => {
    setBackupCodes(null);
    onChanged();
  };

  if (user.mfaEnabled) {
    return (
      <>
        <Section
          title="2단계 인증"
          description="로그인할 때 인증 앱의 6자리 코드를 추가로 확인합니다."
          action={
            <Badge variant="outline" className="text-[color:var(--taspa-success)]">
              사용 중
            </Badge>
          }
        >
          <InlineError message={regenerate.error} />
          <div className="flex flex-wrap gap-2">
            <Button
              variant="outline"
              disabled={regenerate.busy}
              onClick={async () => {
                const result = await regenerate.mutate();
                if (result) {
                  setBackupCodes(result.backupCodes);
                  toast.success("백업 코드를 새로 만들었습니다. 예전 코드는 더 이상 쓸 수 없습니다");
                }
              }}
            >
              {regenerate.busy ? "만드는 중…" : "백업 코드 재발급"}
            </Button>
            <Button variant="destructive" onClick={() => setDisableOpen(true)}>
              2단계 인증 해제
            </Button>
          </div>
          <FieldHint>
            재발급하면 이전에 받은 백업 코드는 모두 무효가 됩니다. 해제하면 신뢰하는 기기도 함께 해제됩니다.
          </FieldHint>
        </Section>

        <ConfirmDialog
          open={disableOpen}
          onOpenChange={(open) => {
            setDisableOpen(open);
            if (!open) {
              setDisableCode("");
              disable.clearError();
            }
          }}
          title="2단계 인증을 해제할까요?"
          description="계정 보호 수준이 낮아집니다. 확인을 위해 인증 앱의 현재 코드를 입력하세요."
          confirmLabel="해제"
          confirmDisabled={disableCode.trim().length === 0}
          busy={disable.busy}
          error={disable.error}
          onConfirm={async () => {
            if (await disable.mutate(disableCode.trim())) {
              setDisableOpen(false);
              setDisableCode("");
              toast.success("2단계 인증을 해제했습니다");
              onChanged();
            }
          }}
        >
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="mfa-disable-code">인증 코드</Label>
            <Input
              id="mfa-disable-code"
              inputMode="numeric"
              autoComplete="one-time-code"
              className="tabular"
              value={disableCode}
              onChange={(event) => setDisableCode(event.target.value)}
            />
          </div>
        </ConfirmDialog>

        <BackupCodesDialog codes={backupCodes} onClose={closeCodes} />
      </>
    );
  }

  return (
    <>
      <Section title="2단계 인증" description="비밀번호가 유출돼도 인증 앱 없이는 로그인할 수 없게 만듭니다.">
        {setup === null ? (
          <>
            <InlineError message={start.error} />
            <div>
              <Button
                disabled={start.busy}
                onClick={async () => {
                  const result = await start.mutate();
                  if (result) setSetup(result);
                }}
              >
                {start.busy ? "준비 중…" : "2단계 인증 설정"}
              </Button>
            </div>
          </>
        ) : (
          <form
            className="flex flex-col gap-4"
            onSubmit={async (event) => {
              event.preventDefault();
              const result = await activate.mutate(code.trim());
              if (result) {
                setSetup(null);
                setCode("");
                setBackupCodes(result.backupCodes);
                toast.success("2단계 인증을 켰습니다");
              }
            }}
          >
            <ol className="flex list-decimal flex-col gap-2 pl-5 text-sm text-muted-foreground">
              <li>인증 앱(Google Authenticator, 1Password 등)에서 QR 코드를 스캔하세요.</li>
              <li>앱에 표시된 6자리 코드를 아래에 입력하세요.</li>
            </ol>

            <div className="flex flex-wrap items-start gap-4">
              {/*
                서버가 만든 PNG data URI — 외부 요청이 없어 TOTP 시크릿이 제3자(이미지 CDN 포함)에게
                노출되지 않는다. next/image 는 원격/정적 소스를 전제로 최적화하므로 여기선 쓰지 않는다.
              */}
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={setup.qrCodeDataUri}
                alt="2단계 인증 QR 코드"
                width={180}
                height={180}
                className="rounded-lg border border-border bg-white p-2"
              />
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="mfa-secret">QR을 못 읽는 경우 직접 입력할 키</Label>
                <Input
                  id="mfa-secret"
                  readOnly
                  value={setup.secret}
                  className="max-w-xs font-mono text-xs"
                  onFocus={(event) => event.currentTarget.select()}
                />
              </div>
            </div>

            <div className="flex max-w-xs flex-col gap-1.5">
              <Label htmlFor="mfa-code">인증 코드</Label>
              <Input
                id="mfa-code"
                inputMode="numeric"
                autoComplete="one-time-code"
                className="tabular"
                value={code}
                onChange={(event) => setCode(event.target.value)}
              />
            </div>

            <InlineError message={activate.error} />
            <div className="flex gap-2">
              <Button type="submit" disabled={activate.busy || code.trim().length === 0}>
                {activate.busy ? "확인 중…" : "확인하고 켜기"}
              </Button>
              <Button
                type="button"
                variant="ghost"
                onClick={() => {
                  setSetup(null);
                  setCode("");
                  activate.clearError();
                }}
              >
                취소
              </Button>
            </div>
          </form>
        )}
      </Section>

      <BackupCodesDialog codes={backupCodes} onClose={closeCodes} />
    </>
  );
}
