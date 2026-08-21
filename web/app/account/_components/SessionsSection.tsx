"use client";

import { useState } from "react";
import { toast } from "sonner";
import { useApi, useMutation } from "@/lib/useApi";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { SESSIONS, revokeOtherSessions, revokeSession, type SessionEntry } from "../_lib/endpoints";
import { formatDateTime, relativeFromNow } from "../_lib/format";
import { ConfirmDialog, FieldHint, InlineError, Section } from "./chrome";

/**
 * 활성 세션(원격 로그아웃).
 *
 * 표시되는 publicId 는 세션 ID 원문이 아니라 SHA-256 앞 16자다 — 서버가 원문을 절대 노출하지 않는다
 * (노출되면 곧바로 세션 하이재킹). 폐기 계열은 step-up 대상이라 401 REAUTH_REQUIRED 로 재인증을 거칠 수 있다.
 *
 * 부분 인증(MFA 대기) 세션은 SecurityContext 가 없어 이 목록에 잡히지 않는다 — 설계상 정상이다.
 */
export function SessionsSection() {
  const list = useApi<SessionEntry[]>(SESSIONS);
  const [revokeAllOpen, setRevokeAllOpen] = useState(false);

  const revokeOne = useMutation(async (publicId: string) => {
    await revokeSession(publicId);
    return true;
  });

  const revokeOthers = useMutation(async () => {
    await revokeOtherSessions();
    return true;
  });

  const others = (list.data ?? []).filter((session) => !session.current).length;

  return (
    <>
      <Section
        title="활성 세션"
        description="현재 이 계정으로 로그인되어 있는 기기 목록입니다."
        action={
          <Button variant="outline" size="sm" disabled={others === 0} onClick={() => setRevokeAllOpen(true)}>
            다른 모든 세션 종료
          </Button>
        }
      >
        <InlineError message={revokeOne.error} />
        {list.loading ? (
          <RowsSkeleton rows={3} />
        ) : list.error ? (
          <ErrorNotice message={list.error} onRetry={list.reload} />
        ) : (list.data?.length ?? 0) === 0 ? (
          <EmptyState title="표시할 세션이 없습니다" />
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>브라우저</TableHead>
                  <TableHead>IP</TableHead>
                  <TableHead>마지막 활동</TableHead>
                  <TableHead>로그인</TableHead>
                  <TableHead className="text-right">작업</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {list.data?.map((session) => (
                  <TableRow key={session.publicId}>
                    <TableCell>
                      <span className="text-foreground">{session.browser ?? "알 수 없는 기기"}</span>
                      {session.current && (
                        <Badge variant="outline" className="ml-2 text-[color:var(--taspa-success)]">
                          현재 기기
                        </Badge>
                      )}
                    </TableCell>
                    <TableCell className="tabular text-muted-foreground">{session.ip ?? "-"}</TableCell>
                    <TableCell className="text-muted-foreground" title={formatDateTime(session.lastActiveAt)}>
                      {relativeFromNow(session.lastActiveAt)}
                    </TableCell>
                    <TableCell className="text-muted-foreground">
                      {formatDateTime(session.createdAt)}
                    </TableCell>
                    <TableCell className="text-right">
                      {!session.current && (
                        <Button
                          variant="ghost"
                          size="sm"
                          disabled={revokeOne.busy}
                          onClick={async () => {
                            if (await revokeOne.mutate(session.publicId)) {
                              toast.success("세션을 종료했습니다");
                              list.reload();
                            }
                          }}
                        >
                          종료
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
        <FieldHint>
          모르는 기기가 있다면 종료한 뒤 비밀번호를 바꾸세요. 세션을 종료하면 그 기기는 즉시 로그아웃됩니다.
        </FieldHint>
      </Section>

      <ConfirmDialog
        open={revokeAllOpen}
        onOpenChange={(open) => {
          setRevokeAllOpen(open);
          if (!open) revokeOthers.clearError();
        }}
        title="다른 모든 세션을 종료할까요?"
        description={`지금 보고 있는 이 기기를 제외한 ${others}개 세션이 즉시 로그아웃됩니다.`}
        confirmLabel="모두 종료"
        busy={revokeOthers.busy}
        error={revokeOthers.error}
        onConfirm={async () => {
          if (await revokeOthers.mutate()) {
            setRevokeAllOpen(false);
            toast.success("다른 세션을 모두 종료했습니다");
            list.reload();
          }
        }}
      />
    </>
  );
}
