"use client";

import { useState } from "react";
import { toast } from "sonner";
import { useApi, useMutation } from "@/lib/useApi";
import { Button } from "@/components/ui/button";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import {
  TRUSTED_DEVICES,
  revokeAllTrustedDevices,
  revokeTrustedDevice,
  type TrustedDevice,
} from "../_lib/endpoints";
import { formatDate } from "../_lib/format";
import { ConfirmDialog, FieldHint, InlineError, Section } from "./chrome";

/**
 * 신뢰하는 기기 — 로그인 시 "30일 동안 묻지 않음"을 선택한 기기들.
 *
 * 만료는 발급 기준 30일 **고정**(사용할 때마다 연장되지 않는다). 해제는 step-up 대상이며,
 * 비밀번호 재설정이나 2단계 인증 변경 시에는 서버가 자동으로 전부 폐기한다.
 */
export function TrustedDevicesSection() {
  const list = useApi<TrustedDevice[]>(TRUSTED_DEVICES);
  const [revokeAllOpen, setRevokeAllOpen] = useState(false);

  const revokeOne = useMutation(async (id: string) => {
    await revokeTrustedDevice(id);
    return true;
  });

  const revokeAll = useMutation(async () => {
    await revokeAllTrustedDevices();
    return true;
  });

  const count = list.data?.length ?? 0;

  return (
    <>
      <Section
        title="신뢰하는 기기"
        description="2단계 인증을 건너뛰도록 허용한 기기입니다. 등록일로부터 30일 뒤 자동으로 만료됩니다."
        action={
          <Button variant="outline" size="sm" disabled={count === 0} onClick={() => setRevokeAllOpen(true)}>
            전체 해제
          </Button>
        }
      >
        <InlineError message={revokeOne.error} />
        {list.loading ? (
          <RowsSkeleton rows={2} />
        ) : list.error ? (
          <ErrorNotice message={list.error} onRetry={list.reload} />
        ) : count === 0 ? (
          <EmptyState
            title="신뢰하는 기기가 없습니다"
            description="로그인할 때 '30일 동안 묻지 않기'를 선택하면 이 목록에 추가됩니다."
          />
        ) : (
          <ul className="flex flex-col divide-y divide-border">
            {list.data?.map((device) => (
              <li key={device.id} className="flex flex-wrap items-center gap-3 py-3">
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium text-foreground">{device.uaLabel}</p>
                  <p className="text-xs text-muted-foreground">
                    등록 {formatDate(device.createdAt)} · 마지막 사용 {formatDate(device.lastUsedAt)} · 만료{" "}
                    {formatDate(device.expiresAt)}
                  </p>
                </div>
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={revokeOne.busy}
                  onClick={async () => {
                    if (await revokeOne.mutate(device.id)) {
                      toast.success("기기 신뢰를 해제했습니다");
                      list.reload();
                    }
                  }}
                >
                  해제
                </Button>
              </li>
            ))}
          </ul>
        )}
        <FieldHint>해제하면 그 기기에서 다음 로그인부터 2단계 인증을 다시 확인합니다.</FieldHint>
      </Section>

      <ConfirmDialog
        open={revokeAllOpen}
        onOpenChange={(open) => {
          setRevokeAllOpen(open);
          if (!open) revokeAll.clearError();
        }}
        title="모든 기기의 신뢰를 해제할까요?"
        description="이 기기를 포함한 모든 기기에서 다음 로그인부터 2단계 인증을 다시 확인합니다."
        confirmLabel="전체 해제"
        busy={revokeAll.busy}
        error={revokeAll.error}
        onConfirm={async () => {
          if (await revokeAll.mutate()) {
            setRevokeAllOpen(false);
            toast.success("모든 기기의 신뢰를 해제했습니다");
            list.reload();
          }
        }}
      />
    </>
  );
}
