"use client";

import { useMemo, useRef, useState } from "react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { api } from "@/lib/api";
import { useApi, useMutation } from "@/lib/useApi";
import { Choice, ConfirmButton, Field, Section, TableScroll } from "../../_components/console-ui";
import { orgPath, useOrg } from "../../_lib/org-context";
import { ROLE_OPTIONS, formatDateTime, invitationStatusLabel, roleLabel } from "../../_lib/labels";
import type { BulkInvitationResult, Invitation } from "../../_lib/types";

/** 서버 상한(OrgBulkInvitationService) — 화면에서도 같은 값을 알려줘 붙여넣기 전에 알 수 있게 한다. */
const MAX_CSV_ROWS = 200;
const MAX_CSV_BYTES = 64 * 1024;

/** 초대 탭 — 단건 초대, 대기 목록(재발송·취소), CSV 대량 초대. */
export default function InvitationsPage() {
  const { orgId } = useOrg();
  const invitations = useApi<Invitation[]>(orgPath(orgId, "/invitations"), [orgId]);

  return (
    <div className="flex flex-col gap-5">
      <InviteForm orgId={orgId} onCreated={invitations.reload} />
      <PendingList orgId={orgId} query={invitations} />
      <BulkInvite orgId={orgId} onDone={invitations.reload} />
    </div>
  );
}

function InviteForm({ orgId, onCreated }: { orgId: string; onCreated: () => void }) {
  const [email, setEmail] = useState("");
  const [role, setRole] = useState<string | null>("MEMBER");
  const [department, setDepartment] = useState("");

  const invite = useMutation(async () =>
    api.post<Invitation>(orgPath(orgId, "/invitations"), {
      email: email.trim(),
      role,
      department: department.trim() || null,
    }),
  );

  return (
    <Section
      title="구성원 초대"
      description="초대 메일의 링크로 수락하면 조직 구성원이 됩니다. 초대는 지정한 이메일에서만 수락할 수 있습니다."
    >
      {invite.error && <ErrorNotice message={invite.error} onDismiss={invite.clearError} />}

      <div className="grid gap-4 sm:grid-cols-3">
        <Field label="이메일" htmlFor="invite-email">
          <Input
            id="invite-email"
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            placeholder="name@example.com"
          />
        </Field>
        <Field label="역할" htmlFor="invite-role">
          <Choice id="invite-role" value={role} onChange={setRole} options={ROLE_OPTIONS} />
        </Field>
        <Field
          label="부서"
          htmlFor="invite-department"
          hint="조직구조 탭의 부서 이름과 정확히 같으면 그 부서로 배정됩니다. 같은 이름이 둘 이상이면 배정되지 않습니다.(선택)"
        >
          <Input
            id="invite-department"
            value={department}
            onChange={(event) => setDepartment(event.target.value)}
            placeholder="예: 플랫폼개발팀"
          />
        </Field>
      </div>

      <div>
        <Button
          disabled={invite.busy || email.trim().length === 0}
          onClick={async () => {
            const created = await invite.mutate();
            if (created) {
              toast.success(`${created.email} 로 초대를 보냈습니다`);
              setEmail("");
              setDepartment("");
              onCreated();
            }
          }}
        >
          {invite.busy ? "보내는 중" : "초대 보내기"}
        </Button>
      </div>
    </Section>
  );
}

function PendingList({ orgId, query }: { orgId: string; query: ReturnType<typeof useApi<Invitation[]>> }) {
  const resend = useMutation(async (invitation: Invitation) =>
    api.post<Invitation>(orgPath(orgId, `/invitations/${encodeURIComponent(invitation.id)}/resend`)),
  );

  const revoke = useMutation(async (invitation: Invitation) => {
    await api.delete<void>(orgPath(orgId, `/invitations/${encodeURIComponent(invitation.id)}`));
    return true;
  });

  const rows = query.data ?? [];

  return (
    <Section title="대기 중인 초대" description="아직 수락되지 않은 초대입니다.">
      {query.error && <ErrorNotice message={query.error} onRetry={query.reload} />}
      {resend.error && <ErrorNotice message={resend.error} onDismiss={resend.clearError} />}
      {revoke.error && <ErrorNotice message={revoke.error} onDismiss={revoke.clearError} />}
      {query.loading && <RowsSkeleton rows={3} />}

      {!query.loading && !query.error && rows.length === 0 && (
        <EmptyState title="대기 중인 초대가 없습니다" description="위에서 새 초대를 보낼 수 있습니다." />
      )}

      {rows.length > 0 && (
        <TableScroll>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>이메일</TableHead>
                <TableHead>역할</TableHead>
                <TableHead>부서</TableHead>
                <TableHead>상태</TableHead>
                <TableHead>보낸 시각</TableHead>
                <TableHead>만료</TableHead>
                <TableHead className="text-right">관리</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((invitation) => (
                <TableRow key={invitation.id}>
                  <TableCell className="font-medium">{invitation.email}</TableCell>
                  <TableCell>{roleLabel(invitation.role)}</TableCell>
                  <TableCell>{invitation.department ?? "—"}</TableCell>
                  <TableCell>
                    <Badge variant="secondary">{invitationStatusLabel(invitation.status)}</Badge>
                  </TableCell>
                  <TableCell className="tabular whitespace-nowrap">
                    {formatDateTime(invitation.createdAt)}
                  </TableCell>
                  <TableCell className="tabular whitespace-nowrap">
                    {formatDateTime(invitation.expiresAt)}
                  </TableCell>
                  <TableCell>
                    <div className="flex justify-end gap-1">
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={resend.busy}
                        onClick={async () => {
                          const done = await resend.mutate(invitation);
                          if (done) {
                            toast.success("초대를 다시 보냈습니다");
                            query.reload();
                          }
                        }}
                      >
                        재발송
                      </Button>
                      <ConfirmButton
                        variant="ghost"
                        disabled={revoke.busy}
                        confirmLabel="취소 확정"
                        onConfirm={async () => {
                          const done = await revoke.mutate(invitation);
                          if (done) {
                            toast.success("초대를 취소했습니다");
                            query.reload();
                          }
                        }}
                      >
                        취소
                      </ConfirmButton>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableScroll>
      )}
    </Section>
  );
}

/**
 * CSV 대량 초대. 서버는 행마다 단건 초대와 똑같은 검사를 하고 실패한 행만 사유와 함께 돌려준다
 * (부분 성공). 그래서 화면도 "전부 성공/전부 실패"가 아니라 행별 결과를 보여준다.
 */
function BulkInvite({ orgId, onDone }: { orgId: string; onDone: () => void }) {
  const [csv, setCsv] = useState("");
  const [result, setResult] = useState<BulkInvitationResult | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  // ★서버와 **같은 기준**으로 센다. 서버는 `csv.length`(자바 문자 수)로 크기를 재고, 비어있지 않은 첫 행의
  // 첫 필드가 "email" 이면 헤더로 보고 건너뛴다. UTF-8 바이트로 재면 한글 CSV 가 약 3배로 계산돼
  // **유효한 입력을 화면이 먼저 막아버리고**, 헤더를 행으로 세면 200행짜리 파일이 201행으로 거부된다.
  const size = csv.length;
  const rows = useMemo(() => {
    const lines = csv.split(/\r?\n/).filter((line) => line.trim().length > 0);
    const hasHeader = lines.length > 0 && lines[0].split(",")[0].trim().toLowerCase() === "email";
    return hasHeader ? lines.length - 1 : lines.length;
  }, [csv]);
  const tooManyRows = rows > MAX_CSV_ROWS;
  const tooLarge = size > MAX_CSV_BYTES;

  const send = useMutation(async () =>
    api.post<BulkInvitationResult>(orgPath(orgId, "/invitations/bulk"), {
      csv,
    }),
  );

  return (
    <Section
      title="CSV 대량 초대"
      description={`한 줄에 한 명씩 ‘이메일,역할,부서’ 형식입니다. 역할·부서는 생략할 수 있습니다. 부서는 조직구조 탭의 부서 이름과 정확히 같을 때만 배정됩니다. 한 번에 최대 ${MAX_CSV_ROWS}행 / ${MAX_CSV_BYTES / 1024}KB 까지 보낼 수 있습니다.`}
    >
      {send.error && <ErrorNotice message={send.error} onDismiss={send.clearError} />}

      <Field label="CSV" htmlFor="bulk-csv">
        <Textarea
          id="bulk-csv"
          value={csv}
          rows={6}
          onChange={(event) => setCsv(event.target.value)}
          placeholder={"hong@example.com,MEMBER,플랫폼개발팀\nkim@example.com"}
          className="font-mono text-xs"
        />
      </Field>

      <div className="flex flex-wrap items-center gap-3 text-xs">
        <span className={tooManyRows ? "text-destructive" : "text-muted-foreground"}>
          {rows}행 / 최대 {MAX_CSV_ROWS}행
        </span>
        <span className={tooLarge ? "text-destructive" : "text-muted-foreground"}>
          {(size / 1024).toFixed(1)}KB / 최대 {MAX_CSV_BYTES / 1024}KB
        </span>
      </div>

      {(tooManyRows || tooLarge) && (
        <p className="text-sm text-destructive">
          상한을 넘었습니다. 파일을 나눠서 여러 번 보내주세요(서버가 같은 상한으로 거절합니다).
        </p>
      )}

      <div className="flex flex-wrap gap-2">
        <input
          ref={fileInput}
          type="file"
          accept=".csv,text/csv,text/plain"
          className="hidden"
          onChange={async (event) => {
            const file = event.target.files?.[0];
            if (!file) return;
            setCsv(await file.text());
            setResult(null);
            // 같은 파일을 다시 고를 수 있게 초기화한다.
            event.target.value = "";
          }}
        />
        <Button variant="outline" onClick={() => fileInput.current?.click()}>
          CSV 파일 불러오기
        </Button>
        <Button
          disabled={send.busy || csv.trim().length === 0 || tooManyRows || tooLarge}
          onClick={async () => {
            const response = await send.mutate();
            if (response) {
              setResult(response);
              if (response.created > 0) {
                toast.success(`${response.created}건을 초대했습니다`);
                onDone();
              }
              if (response.rejected > 0) {
                toast.error(`${response.rejected}건은 처리하지 못했습니다`);
              }
            }
          }}
        >
          {send.busy ? "보내는 중" : "대량 초대 보내기"}
        </Button>
      </div>

      {result && (
        <div className="flex flex-col gap-2">
          <p className="text-sm text-foreground">
            전체 {result.total}건 · 성공 {result.created}건 · 실패 {result.rejected}건
          </p>
          <TableScroll>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-16">행</TableHead>
                  <TableHead>이메일</TableHead>
                  <TableHead>결과</TableHead>
                  <TableHead>사유</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {result.results.map((row) => (
                  <TableRow key={`${row.line}-${row.email}`}>
                    <TableCell className="tabular">{row.line}</TableCell>
                    <TableCell>{row.email || "—"}</TableCell>
                    <TableCell>
                      {/*
                        ★경고가 있는 행은 '초대됨'과 다른 색으로 말한다. 부서 이름을 잇지 못한 행도
                        초대 자체는 성공이라 예전엔 그냥 '초대됨'이었는데, 그 사람은 자유 텍스트 라벨만
                        갖고 입사해 **부서 식대 재정의를 받지 못한다**(개발팀에 18,000원을 설정해도
                        그 신입만 12,000원이고 화면 어디에도 이유가 없다).
                      */}
                      <Badge
                        variant={
                          row.status !== "CREATED" ? "destructive" : row.warning ? "outline" : "secondary"
                        }
                      >
                        {row.status === "CREATED"
                          ? row.warning
                            ? "초대됨 · 확인 필요"
                            : "초대됨"
                          : "거절됨"}
                      </Badge>
                    </TableCell>
                    <TableCell className={row.warning ? "text-warning" : "text-muted-foreground"}>
                      {row.reason ?? row.warning ?? "—"}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableScroll>
        </div>
      )}
    </Section>
  );
}
