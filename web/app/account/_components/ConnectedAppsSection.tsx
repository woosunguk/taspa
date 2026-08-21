"use client";

import { useState } from "react";
import { toast } from "sonner";
import { useApi, useMutation } from "@/lib/useApi";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { AUTHORIZED_CLIENTS, revokeAuthorizedClient, type AuthorizedClient } from "../_lib/endpoints";
import { formatDateTime, relativeFromNow } from "../_lib/format";
import { ConfirmDialog, FieldHint, InlineError, Section } from "./chrome";

/** 사람이 읽기 어려운 scope 문자열을 설명으로 바꾼다. 모르는 scope 는 원문 그대로 보여준다. */
function scopeLabel(scope: string): string {
  switch (scope) {
    case "openid":
      return "로그인 정보";
    case "profile":
      return "이름";
    case "email":
      return "이메일 주소";
    case "org.read":
      return "소속 조직 조회";
    default:
      return scope;
  }
}

/**
 * 연결된 앱 — 이 계정으로 로그인을 허용한 제3자 OAuth2 클라이언트.
 *
 * 철회(step-up 대상)는 발급된 토큰과 동의를 함께 삭제한다 — refresh_token 재사용이 즉시 막히고,
 * 다음에 그 앱을 쓰려면 동의 화면을 다시 거친다.
 */
export function ConnectedAppsSection() {
  const list = useApi<AuthorizedClient[]>(AUTHORIZED_CLIENTS);
  const [revoking, setRevoking] = useState<AuthorizedClient | null>(null);

  const revoke = useMutation(async (registeredClientId: string) => {
    await revokeAuthorizedClient(registeredClientId);
    return true;
  });

  return (
    <>
      <Section title="연결된 앱" description="이 계정으로 로그인하도록 허용한 외부 앱입니다.">
        <InlineError message={revoke.error} />
        {list.loading ? (
          <RowsSkeleton rows={2} />
        ) : list.error ? (
          <ErrorNotice message={list.error} onRetry={list.reload} />
        ) : (list.data?.length ?? 0) === 0 ? (
          <EmptyState
            title="연결된 앱이 없습니다"
            description="외부 앱에서 taspa 계정으로 로그인하면 여기에 표시됩니다."
          />
        ) : (
          <ul className="flex flex-col divide-y divide-border">
            {list.data?.map((client) => (
              <li key={client.registeredClientId} className="flex flex-wrap items-center gap-3 py-3">
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium text-foreground">{client.clientName}</p>
                  <p className="text-xs text-muted-foreground" title={formatDateTime(client.lastUsedAt)}>
                    마지막 사용 {relativeFromNow(client.lastUsedAt)}
                  </p>
                  <div className="mt-1.5 flex flex-wrap gap-1">
                    {client.scopes.map((scope) => (
                      <Badge key={scope} variant="outline" title={scope}>
                        {scopeLabel(scope)}
                      </Badge>
                    ))}
                  </div>
                </div>
                <Button variant="ghost" size="sm" onClick={() => setRevoking(client)}>
                  접근 철회
                </Button>
              </li>
            ))}
          </ul>
        )}
        <FieldHint>철회하면 그 앱은 즉시 접근 권한을 잃고, 다시 쓰려면 동의를 새로 받아야 합니다.</FieldHint>
      </Section>

      <ConfirmDialog
        open={revoking !== null}
        onOpenChange={(open) => {
          if (!open) {
            setRevoking(null);
            revoke.clearError();
          }
        }}
        title="이 앱의 접근을 철회할까요?"
        description={
          revoking
            ? `${revoking.clientName} 이(가) 발급받은 토큰이 모두 무효가 되고, 진행 중이던 연동이 끊깁니다.`
            : ""
        }
        confirmLabel="철회"
        busy={revoke.busy}
        error={revoke.error}
        onConfirm={async () => {
          if (!revoking) return;
          if (await revoke.mutate(revoking.registeredClientId)) {
            setRevoking(null);
            toast.success("앱 접근을 철회했습니다");
            list.reload();
          }
        }}
      />
    </>
  );
}
