"use client";

import { useMemo, useState } from "react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { api } from "@/lib/api";
import { useApi, useMutation } from "@/lib/useApi";
import { ConfirmButton, Section, TableScroll } from "../../_components/console-ui";
import { orgPath, useOrg } from "../../_lib/org-context";
import type { GrantableAction, Membership, OrgCustomRole, OrgCustomRoleDetail } from "../../_lib/types";

/**
 * 조직 커스텀 역할.
 *
 * 그전까지 조직 역할은 **구성원 / 조직관리자 둘뿐**이라, "구성원 목록과 청구서는 보되 식대 정책은 못
 * 바꾸는 인사 담당"에게 권한을 주려면 조직 전체 관리자를 줘야 했다. 이 화면이 그 중간을 만든다.
 *
 * ★**능력 목록은 서버에서 받는다**(`/roles/grantable-actions`). 화면이 목록을 들고 있으면 서버에 능력이
 * 추가돼도 여기엔 영영 안 나타나고, 서버가 막은 능력을 계속 보여주면 저장할 때마다 400 을 받는다.
 * 정책 문서 자체도 서버가 만든다 — 화면은 **어떤 능력을 줄지만** 고른다.
 */
export default function OrgRolesPage() {
  const { orgId } = useOrg();
  const roles = useApi<OrgCustomRole[]>(orgPath(orgId, "/roles"), [orgId]);
  const grantable = useApi<GrantableAction[]>(orgPath(orgId, "/roles/grantable-actions"), [orgId]);
  const members = useApi<Membership[]>(orgPath(orgId, "/members"), [orgId]);

  const [editing, setEditing] = useState<OrgCustomRoleDetail | "new" | null>(null);
  const [managing, setManaging] = useState<OrgCustomRoleDetail | null>(null);

  const remove = useMutation(async (role: OrgCustomRole) => api.delete(orgPath(orgId, `/roles/${role.id}`)));

  async function openEdit(roleId: string) {
    const detail = await api.get<OrgCustomRoleDetail>(orgPath(orgId, `/roles/${roleId}`));
    setEditing(detail);
  }

  async function openMembers(roleId: string) {
    const detail = await api.get<OrgCustomRoleDetail>(orgPath(orgId, `/roles/${roleId}`));
    setManaging(detail);
  }

  return (
    <div className="flex flex-col gap-5">
      <Section
        title="역할"
        description="구성원에게 줄 권한 묶음을 조직 안에서 직접 정의합니다. 조직관리자 권한을 통째로 주지 않고 필요한 만큼만 줄 수 있습니다."
        action={<Button onClick={() => setEditing("new")}>역할 만들기</Button>}
      >
        {roles.loading && !roles.data ? (
          <RowsSkeleton rows={3} />
        ) : roles.error ? (
          <ErrorNotice message={roles.error} onRetry={roles.reload} />
        ) : !roles.data || roles.data.length === 0 ? (
          <EmptyState
            title="아직 만든 역할이 없습니다"
            description="예: 구성원 조회와 초대만 가능한 '인사 담당', 청구서만 보는 '회계 담당'."
          />
        ) : (
          <TableScroll>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>역할</TableHead>
                  <TableHead>권한</TableHead>
                  <TableHead className="text-right">부여된 구성원</TableHead>
                  <TableHead className="w-56" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {roles.data.map((role) => (
                  <TableRow key={role.id}>
                    <TableCell>
                      <p className="font-medium">{role.name}</p>
                      {role.description && (
                        <p className="mt-0.5 text-xs text-muted-foreground">{role.description}</p>
                      )}
                    </TableCell>
                    <TableCell>
                      <ActionSummary actions={role.actions} catalog={grantable.data} />
                    </TableCell>
                    <TableCell className="tabular text-right whitespace-nowrap">
                      {role.memberCount}명
                    </TableCell>
                    <TableCell>
                      <div className="flex justify-end gap-1">
                        <Button variant="outline" size="sm" onClick={() => openMembers(role.id)}>
                          구성원
                        </Button>
                        <Button variant="outline" size="sm" onClick={() => openEdit(role.id)}>
                          편집
                        </Button>
                        <ConfirmButton
                          variant="outline"
                          disabled={remove.busy}
                          confirmLabel="삭제 실행"
                          onConfirm={async () => {
                            const ok = await remove.mutate(role);
                            if (ok !== undefined) {
                              toast.success(`'${role.name}' 역할을 삭제했습니다`);
                              roles.reload();
                            }
                          }}
                        >
                          삭제
                        </ConfirmButton>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableScroll>
        )}
        {remove.error && <ErrorNotice message={remove.error} onDismiss={remove.clearError} />}
      </Section>

      <p className="rounded-lg border border-border bg-muted/40 px-3 py-2.5 text-sm text-muted-foreground">
        역할로 줄 수 있는 것은 <b className="font-medium text-foreground">조직관리자가 가진 권한까지</b>
        입니다. 역할 관리·구성원 역할 변경·부서 위임은 역할로 넘길 수 없습니다 — 그러면 한 번 부여한 역할이
        스스로 권한을 넓힐 수 있기 때문입니다.
      </p>

      {editing && (
        <RoleEditor
          orgId={orgId}
          role={editing === "new" ? null : editing}
          catalog={grantable}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            roles.reload();
          }}
        />
      )}

      {managing && (
        <RoleMembers
          orgId={orgId}
          role={managing}
          members={members.data ?? []}
          onChanged={(next) => setManaging(next)}
          onClose={() => {
            setManaging(null);
            roles.reload();
          }}
        />
      )}
    </div>
  );
}

/** 권한을 한 줄로 요약 — 전부 나열하면 표가 읽히지 않는다. 라벨은 서버가 준 것을 쓴다. */
function ActionSummary({ actions, catalog }: { actions: string[]; catalog: GrantableAction[] | undefined }) {
  const labels = useMemo(() => {
    const byAction = new Map((catalog ?? []).map((item) => [item.action, item.label]));
    return actions.map((action) => byAction.get(action) ?? action);
  }, [actions, catalog]);

  if (labels.length === 0) return <span className="text-sm text-muted-foreground">—</span>;
  const shown = labels.slice(0, 3);
  return (
    <div className="flex flex-wrap gap-1">
      {shown.map((label) => (
        <Badge key={label} variant="secondary">
          {label}
        </Badge>
      ))}
      {labels.length > shown.length && <Badge variant="outline">+{labels.length - shown.length}</Badge>}
    </div>
  );
}

function RoleEditor({
  orgId,
  role,
  catalog,
  onClose,
  onSaved,
}: {
  orgId: string;
  role: OrgCustomRoleDetail | null;
  catalog: ReturnType<typeof useApi<GrantableAction[]>>;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [name, setName] = useState(role?.name ?? "");
  const [description, setDescription] = useState(role?.description ?? "");
  const [selected, setSelected] = useState<string[]>(role?.actions ?? []);
  const [localIssue, setLocalIssue] = useState<string | null>(null);

  const save = useMutation(async () => {
    const body = {
      name: name.trim(),
      description: description.trim() || null,
      actions: selected,
    };
    return role
      ? api.put<OrgCustomRoleDetail>(orgPath(orgId, `/roles/${role.id}`), body)
      : api.post<OrgCustomRoleDetail>(orgPath(orgId, "/roles"), body);
  });

  // 서버가 준 순서를 유지하되 묶음별로 나눠 보여준다 — 30개가 넘는 체크박스를 한 줄로 두면 못 고른다.
  const groups = useMemo(() => {
    const map = new Map<string, GrantableAction[]>();
    (catalog.data ?? []).forEach((item) => {
      map.set(item.group, [...(map.get(item.group) ?? []), item]);
    });
    return [...map.entries()];
  }, [catalog.data]);

  function toggle(action: string) {
    setSelected((prev) => (prev.includes(action) ? prev.filter((a) => a !== action) : [...prev, action]));
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{role ? "역할 편집" : "역할 만들기"}</DialogTitle>
          <DialogDescription>
            줄 권한을 고르면 나머지는 서버가 처리합니다. 여기서 고른 것만 열리고, 나머지는 그대로 막힙니다.
          </DialogDescription>
        </DialogHeader>

        <form
          className="flex flex-col gap-4"
          // 네이티브 검증 말풍선은 브라우저 언어의 영문이라 한국어 화면과 문구가 갈린다.
          noValidate
          onSubmit={async (event) => {
            event.preventDefault();
            setLocalIssue(null);
            if (!name.trim()) {
              setLocalIssue("역할 이름을 입력하세요.");
              return;
            }
            if (selected.length === 0) {
              setLocalIssue("권한을 하나 이상 선택하세요.");
              return;
            }
            const saved = await save.mutate();
            if (saved) {
              toast.success(`'${saved.name}' 역할을 저장했습니다`);
              onSaved();
            }
          }}
        >
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="role-name">역할 이름</Label>
              <Input
                id="role-name"
                value={name}
                onChange={(event) => setName(event.target.value)}
                placeholder="예: 인사 담당"
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="role-desc">설명 (선택)</Label>
              <Input
                id="role-desc"
                value={description}
                onChange={(event) => setDescription(event.target.value)}
                placeholder="이 역할이 무엇을 하는 사람인지"
              />
            </div>
          </div>

          <div className="flex flex-col gap-3">
            <div className="flex items-baseline justify-between">
              <p className="text-sm font-medium text-foreground">권한</p>
              <p className="text-xs text-muted-foreground">{selected.length}개 선택됨</p>
            </div>

            {catalog.loading && !catalog.data ? (
              <RowsSkeleton rows={4} />
            ) : catalog.error ? (
              <ErrorNotice message={catalog.error} onRetry={catalog.reload} />
            ) : (
              groups.map(([group, items]) => (
                <fieldset key={group} className="rounded-lg border border-border px-3 py-2.5">
                  <legend className="px-1 text-xs font-medium text-muted-foreground">{group}</legend>
                  <div className="grid gap-1.5 sm:grid-cols-2">
                    {items.map((item) => (
                      <label
                        key={item.action}
                        className="flex cursor-pointer items-center gap-2 rounded-md px-1.5 py-1 text-sm hover:bg-accent/40"
                      >
                        <input
                          type="checkbox"
                          className="size-4 accent-[color:var(--primary)]"
                          checked={selected.includes(item.action)}
                          onChange={() => toggle(item.action)}
                        />
                        <span className="text-foreground">{item.label}</span>
                      </label>
                    ))}
                  </div>
                </fieldset>
              ))
            )}
          </div>

          {localIssue && <p className="text-sm text-destructive">{localIssue}</p>}
          {save.error && <ErrorNotice message={save.error} onDismiss={save.clearError} />}

          <div className="flex justify-end gap-2">
            <Button type="button" variant="ghost" onClick={onClose}>
              취소
            </Button>
            <Button type="submit" disabled={save.busy}>
              {save.busy ? "저장 중…" : "저장"}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function RoleMembers({
  orgId,
  role,
  members,
  onChanged,
  onClose,
}: {
  orgId: string;
  role: OrgCustomRoleDetail;
  members: Membership[];
  onChanged: (next: OrgCustomRoleDetail) => void;
  onClose: () => void;
}) {
  const [picked, setPicked] = useState("");
  const assign = useMutation(async (userId: string) =>
    api.post<OrgCustomRoleDetail>(orgPath(orgId, `/roles/${role.id}/members`), {
      userId,
    }),
  );
  const unassign = useMutation(async (userId: string) =>
    api.delete<OrgCustomRoleDetail>(orgPath(orgId, `/roles/${role.id}/members/${userId}`)),
  );

  const assigned = new Set(role.members.map((m) => m.userId));
  // 이미 부여된 사람은 후보에서 뺀다 — 눌러도 아무 일이 없는 선택지는 혼란만 준다.
  const candidates = members.filter((m) => !assigned.has(m.userId));

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{role.name} — 구성원</DialogTitle>
          <DialogDescription>이 조직의 활성 구성원에게만 역할을 줄 수 있습니다.</DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4">
          <div className="flex items-end gap-2">
            <div className="flex flex-1 flex-col gap-1.5">
              <Label htmlFor="role-member">구성원 추가</Label>
              <select
                id="role-member"
                className="h-9 rounded-md border border-input bg-transparent px-3 text-sm"
                value={picked}
                onChange={(event) => setPicked(event.target.value)}
              >
                <option value="">선택하세요</option>
                {candidates.map((m) => (
                  <option key={m.userId} value={m.userId}>
                    {m.email}
                  </option>
                ))}
              </select>
            </div>
            <Button
              disabled={!picked || assign.busy}
              onClick={async () => {
                const next = await assign.mutate(picked);
                if (next) {
                  toast.success("역할을 부여했습니다");
                  setPicked("");
                  onChanged(next);
                }
              }}
            >
              추가
            </Button>
          </div>

          {(assign.error || unassign.error) && (
            <ErrorNotice
              message={assign.error ?? unassign.error ?? ""}
              // 재시도가 아니라 오류 표시만 지운다 — 라벨이 "다시 시도"면 그 약속을 어긴다.
              onDismiss={() => {
                assign.clearError();
                unassign.clearError();
              }}
            />
          )}

          {role.members.length === 0 ? (
            <EmptyState
              title="아직 부여된 구성원이 없습니다"
              description="위에서 구성원을 골라 추가하세요."
            />
          ) : (
            <ul className="divide-y divide-border">
              {role.members.map((member) => (
                <li key={member.userId} className="flex items-center justify-between py-2">
                  <span className="truncate text-sm text-foreground">
                    {member.email ?? <span className="text-muted-foreground">(탈퇴한 계정)</span>}
                  </span>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={unassign.busy}
                    onClick={async () => {
                      const next = await unassign.mutate(member.userId);
                      if (next) {
                        toast.success("역할을 해제했습니다");
                        onChanged(next);
                      }
                    }}
                  >
                    해제
                  </Button>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="flex justify-end">
          <Button variant="ghost" onClick={onClose}>
            닫기
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
