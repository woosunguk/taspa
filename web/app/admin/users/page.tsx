"use client";

import { useState } from "react";
import { toast } from "sonner";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api } from "@/lib/api";
import { useSession } from "@/lib/session";
import { useRevealOnChange } from "@/lib/useActiveTabScroll";
import { useApi } from "@/lib/useApi";
import {
  BoolBadge,
  ConfirmDialog,
  PageHeader,
  Section,
  StatusBadge,
  TableScroll,
  formatDateTime,
} from "../_components/kit";
import { adminErrorText } from "../_lib/errors";
import type { AdminUserDetail, AdminUserSummary } from "../_lib/types";

/**
 * 사용자 관리 — 검색·상세·정지/해제·전 세션 종료·역할 변경.
 *
 * 자기 자신에 대한 정지·ADMIN 해제는 서버가 409(`ADMIN_SELF_ACTION`)로 거부한다(마지막 관리자가 스스로를
 * 잠그는 사고 방지). 화면은 자기 계정 행에 미리 표시해 두되, **판정은 서버가 한다** — 거부되면 그 이유를
 * 그대로 보여준다.
 */
export default function AdminUsersPage() {
  const session = useSession();
  const [term, setTerm] = useState("");
  const [query, setQuery] = useState("");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  // 상세는 표 아래에 렌더된다 — 끌어오지 않으면 '상세'를 눌러도 화면에 변화가 없어 보인다.
  const detailRef = useRevealOnChange<HTMLDivElement>(selectedId);

  const list = useApi<AdminUserSummary[]>(
    `/api/admin/users${query ? `?query=${encodeURIComponent(query)}` : ""}`,
  );
  const detail = useApi<AdminUserDetail>(selectedId ? `/api/admin/users/${selectedId}` : null);

  const meId = session.status === "authenticated" ? session.user.userId : null;

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="사용자"
        description="이메일 부분일치로 검색합니다(서버 상한 50건). 정지·세션 종료·역할 변경은 최근 재인증이 필요합니다."
      />

      <Section title="검색">
        <form
          className="flex flex-wrap items-center gap-2"
          onSubmit={(event) => {
            event.preventDefault();
            setQuery(term.trim());
            setSelectedId(null);
          }}
        >
          <Input
            value={term}
            onChange={(event) => setTerm(event.target.value)}
            placeholder="이메일 일부 (비우면 최근 가입 순)"
            className="max-w-xs"
            autoComplete="off"
          />
          <Button type="submit">검색</Button>
          {query && (
            <Button
              type="button"
              variant="ghost"
              onClick={() => {
                setTerm("");
                setQuery("");
              }}
            >
              초기화
            </Button>
          )}
        </form>
      </Section>

      <Section
        title="계정 목록"
        description={
          list.data
            ? `${list.data.length}건${list.data.length >= 50 ? " (상한 50건 — 검색어로 좁히세요)" : ""}`
            : undefined
        }
        actions={
          <Button variant="outline" size="sm" onClick={list.reload}>
            새로고침
          </Button>
        }
      >
        {list.loading ? (
          <RowsSkeleton rows={6} />
        ) : list.error ? (
          <ErrorNotice message={list.error} onRetry={list.reload} />
        ) : !list.data || list.data.length === 0 ? (
          <EmptyState
            title="일치하는 계정이 없습니다"
            description="검색어를 줄이거나 비워서 최근 가입 계정을 확인해 보세요."
          />
        ) : (
          <TableScroll>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>이메일</TableHead>
                  <TableHead>표시 이름</TableHead>
                  <TableHead>상태</TableHead>
                  <TableHead>역할</TableHead>
                  <TableHead>이메일 인증</TableHead>
                  <TableHead>2단계</TableHead>
                  <TableHead className="w-40">가입</TableHead>
                  <TableHead className="w-20" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {list.data.map((user) => (
                  <TableRow key={user.id} data-state={selectedId === user.id ? "selected" : undefined}>
                    <TableCell className="font-medium whitespace-nowrap">
                      {user.email}
                      {meId === user.id && (
                        <Badge variant="outline" className="ml-2">
                          나
                        </Badge>
                      )}
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">
                      {user.displayName ?? "—"}
                    </TableCell>
                    <TableCell>
                      <StatusBadge status={user.status} />
                    </TableCell>
                    <TableCell>
                      {user.role === "ADMIN" ? (
                        <Badge>ADMIN</Badge>
                      ) : (
                        <span className="text-xs text-muted-foreground">USER</span>
                      )}
                    </TableCell>
                    <TableCell>
                      <BoolBadge value={user.emailVerified} trueLabel="완료" falseLabel="미인증" />
                    </TableCell>
                    <TableCell>
                      <BoolBadge value={user.mfaEnabled} trueLabel="사용" falseLabel="미사용" />
                    </TableCell>
                    <TableCell className="tabular whitespace-nowrap text-muted-foreground">
                      {formatDateTime(user.createdAt)}
                    </TableCell>
                    <TableCell>
                      <Button variant="outline" size="sm" onClick={() => setSelectedId(user.id)}>
                        상세
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableScroll>
        )}
      </Section>

      {selectedId && (
        <div ref={detailRef} className="scroll-mt-4">
          <UserDetailPanel
            key={selectedId}
            userId={selectedId}
            isSelf={meId === selectedId}
            detail={detail.data}
            loading={detail.loading}
            error={detail.error}
            onClose={() => setSelectedId(null)}
            onChanged={() => {
              detail.reload();
              list.reload();
            }}
          />
        </div>
      )}
    </div>
  );
}

function UserDetailPanel({
  userId,
  isSelf,
  detail,
  loading,
  error,
  onClose,
  onChanged,
}: {
  userId: string;
  isSelf: boolean;
  detail: AdminUserDetail | undefined;
  loading: boolean;
  error: string | undefined;
  onClose: () => void;
  onChanged: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | undefined>(undefined);
  const [confirm, setConfirm] = useState<null | "suspend" | "revoke" | "demote">(null);

  async function run(label: string, call: () => Promise<unknown>) {
    setBusy(true);
    setActionError(undefined);
    try {
      await call();
      toast.success(`${label} 완료`);
      onChanged();
      setConfirm(null);
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return;
      const text = adminErrorText(cause);
      setActionError(text);
      toast.error(text);
    } finally {
      setBusy(false);
    }
  }

  const suspend = () => run("계정 정지", () => api.post(`/api/admin/users/${userId}/suspend`));
  const unsuspend = () => run("정지 해제", () => api.post(`/api/admin/users/${userId}/unsuspend`));
  const revokeSessions = () =>
    run("전 세션 종료", () => api.post(`/api/admin/users/${userId}/sessions/revoke`));
  const changeRole = (role: string) =>
    run("역할 변경", () => api.post(`/api/admin/users/${userId}/role`, { role }));

  const user = detail?.user;

  return (
    <Section
      title="계정 상세"
      description={user?.email}
      actions={
        <Button variant="ghost" size="sm" onClick={onClose}>
          닫기
        </Button>
      }
    >
      {loading ? (
        <RowsSkeleton rows={4} />
      ) : error ? (
        <ErrorNotice message={error} />
      ) : !detail || !user ? (
        <EmptyState title="상세 정보를 불러오지 못했습니다" />
      ) : (
        <div className="flex flex-col gap-4">
          {isSelf && (
            <p className="rounded-lg border border-[color:var(--taspa-warning)]/40 bg-[color:var(--taspa-warning-soft)] px-3 py-2 text-sm text-[color:var(--taspa-warning)]">
              지금 로그인한 본인 계정입니다. 자기 자신의 정지와 ADMIN 해제는 서버가 거부합니다(409
              ADMIN_SELF_ACTION).
            </p>
          )}

          {actionError && <ErrorNotice message={actionError} />}

          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Fact label="상태" value={<StatusBadge status={user.status} />} />
            <Fact label="역할" value={user.role} />
            <Fact label="활성 세션" value={`${detail.activeSessionCount}개`} />
            <Fact label="패스키" value={`${detail.passkeyCount}개`} />
            <Fact label="이메일 인증" value={user.emailVerified ? "완료" : "미인증"} />
            <Fact label="2단계 인증" value={user.mfaEnabled ? "사용" : "미사용"} />
            <Fact
              label="소셜 연결"
              value={detail.federatedProviders.length > 0 ? detail.federatedProviders.join(", ") : "없음"}
            />
            <Fact label="가입" value={formatDateTime(user.createdAt)} />
          </div>

          <div className="flex flex-wrap items-center gap-2 border-t border-border pt-3">
            {user.status === "SUSPENDED" ? (
              <Button size="sm" disabled={busy} onClick={unsuspend}>
                정지 해제
              </Button>
            ) : (
              <Button size="sm" variant="destructive" disabled={busy} onClick={() => setConfirm("suspend")}>
                계정 정지
              </Button>
            )}
            <Button size="sm" variant="outline" disabled={busy} onClick={() => setConfirm("revoke")}>
              전 세션 종료
            </Button>
            {user.role === "ADMIN" ? (
              <Button size="sm" variant="outline" disabled={busy} onClick={() => setConfirm("demote")}>
                ADMIN 해제
              </Button>
            ) : (
              <Button size="sm" variant="outline" disabled={busy} onClick={() => changeRole("ADMIN")}>
                ADMIN 부여
              </Button>
            )}
            <p className="text-xs text-muted-foreground">
              역할은 로그인 시점에 세션에 반영됩니다 — 승격은 대상이 다시 로그인해야 적용되고, 강등은 즉시
              세션이 폐기됩니다.
            </p>
          </div>

          <div>
            <h3 className="mb-2 text-sm font-semibold">최근 감사 이벤트</h3>
            {detail.recentAuditEvents.length === 0 ? (
              <p className="text-sm text-muted-foreground">기록된 이벤트가 없습니다.</p>
            ) : (
              <TableScroll>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead className="w-40">시각</TableHead>
                      <TableHead>유형</TableHead>
                      <TableHead>상세</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {detail.recentAuditEvents.map((event) => (
                      <TableRow key={event.id}>
                        <TableCell className="tabular whitespace-nowrap text-muted-foreground">
                          {formatDateTime(event.createdAt)}
                        </TableCell>
                        <TableCell className="whitespace-nowrap">{event.type}</TableCell>
                        <TableCell
                          className="max-w-md truncate font-mono text-xs text-muted-foreground"
                          title={event.detail ?? ""}
                        >
                          {event.detail ?? "—"}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableScroll>
            )}
          </div>
        </div>
      )}

      <ConfirmDialog
        open={confirm === "suspend"}
        onOpenChange={(open) => !open && setConfirm(null)}
        title="계정을 정지할까요?"
        message="정지하면 이 계정의 모든 세션과 신뢰 기기가 즉시 폐기되고 로그인이 차단됩니다. 해제는 언제든 가능합니다."
        confirmLabel="정지"
        busy={busy}
        onConfirm={suspend}
      />
      <ConfirmDialog
        open={confirm === "revoke"}
        onOpenChange={(open) => !open && setConfirm(null)}
        title="모든 세션을 종료할까요?"
        message="이 계정으로 로그인된 모든 기기가 즉시 로그아웃됩니다. 계정 자체는 그대로 유지됩니다."
        confirmLabel="세션 종료"
        busy={busy}
        onConfirm={revokeSessions}
      />
      <ConfirmDialog
        open={confirm === "demote"}
        onOpenChange={(open) => !open && setConfirm(null)}
        title="관리자 역할을 해제할까요?"
        message="ADMIN 역할을 해제하면 대상의 모든 세션이 즉시 폐기됩니다. 자기 자신은 해제할 수 없습니다."
        confirmLabel="ADMIN 해제"
        busy={busy}
        onConfirm={() => changeRole("USER")}
      />
    </Section>
  );
}

function Fact({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="rounded-lg border border-border px-3 py-2">
      <p className="text-xs text-muted-foreground">{label}</p>
      <div className="mt-0.5 text-sm font-medium text-foreground">{value}</div>
    </div>
  );
}
