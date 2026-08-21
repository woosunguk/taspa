"use client";

import { useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { api } from "@/lib/api";
import { useApi, useMutation } from "@/lib/useApi";
import {
  Choice,
  ConfirmButton,
  Field,
  FieldAction,
  Section,
  TableScroll,
  type Option,
} from "../../_components/console-ui";
import { orgPath, useOrg } from "../../_lib/org-context";
import { formatCount } from "../../_lib/labels";
import { flattenTree } from "../../_lib/tree";
import type { Department, Site } from "../../_lib/types";
import { DelegationSection } from "./DelegationSection";

/** 조직구조 탭 — 계층형 부서 트리와 사업장. */
export default function StructurePage() {
  const { orgId } = useOrg();
  return (
    <div className="flex flex-col gap-5">
      <Departments orgId={orgId} />
      <Sites orgId={orgId} />
      <DelegationSection orgId={orgId} />
    </div>
  );
}

function Departments({ orgId }: { orgId: string }) {
  const departments = useApi<Department[]>(orgPath(orgId, "/departments"), [orgId]);
  const [name, setName] = useState("");
  const [parentId, setParentId] = useState<string | null>(null);
  const [renaming, setRenaming] = useState<{
    id: string;
    value: string;
  } | null>(null);

  const nodes = flattenTree(departments.data ?? []);
  const parentOptions: Option[] = nodes.map(({ item, depth }) => ({
    value: item.id,
    label: `${"  ".repeat(depth)}${depth > 0 ? "└ " : ""}${item.name}`,
  }));

  const create = useMutation(async () =>
    api.post<Department>(orgPath(orgId, "/departments"), {
      name: name.trim(),
      parentId,
    }),
  );

  const rename = useMutation(async (deptId: string, value: string) =>
    api.put<Department>(orgPath(orgId, `/departments/${encodeURIComponent(deptId)}`), {
      name: value.trim(),
    }),
  );

  const remove = useMutation(async (deptId: string) => {
    await api.delete<void>(orgPath(orgId, `/departments/${encodeURIComponent(deptId)}`));
    return true;
  });

  return (
    <Section
      title="부서"
      description="상위 부서를 지정하면 하위 부서가 됩니다. 하위 부서가 있는 부서는 삭제할 수 없습니다(먼저 하위를 정리하세요)."
    >
      {departments.error && <ErrorNotice message={departments.error} onRetry={departments.reload} />}
      {create.error && <ErrorNotice message={create.error} onDismiss={create.clearError} />}
      {rename.error && <ErrorNotice message={rename.error} onDismiss={rename.clearError} />}
      {remove.error && <ErrorNotice message={remove.error} onDismiss={remove.clearError} />}

      <div className="grid items-start gap-3 sm:grid-cols-[1fr_1fr_auto]">
        <Field label="부서 이름" htmlFor="dept-name">
          <Input
            id="dept-name"
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="예: 플랫폼개발팀"
          />
        </Field>
        <Field label="상위 부서" htmlFor="dept-parent">
          <Choice
            id="dept-parent"
            value={parentId}
            onChange={setParentId}
            options={parentOptions}
            emptyLabel="최상위"
          />
        </Field>
        <FieldAction>
          <Button
            disabled={create.busy || name.trim().length === 0}
            onClick={async () => {
              const created = await create.mutate();
              if (created) {
                toast.success(`부서 ‘${created.name}’을(를) 만들었습니다`);
                setName("");
                departments.reload();
              }
            }}
          >
            부서 추가
          </Button>
        </FieldAction>
      </div>

      {departments.loading && <RowsSkeleton rows={3} />}
      {!departments.loading && nodes.length === 0 && (
        <EmptyState
          title="등록된 부서가 없습니다"
          description="부서를 만들면 구성원을 배정하고 부서별 인원·청구 내역을 볼 수 있습니다."
        />
      )}

      {nodes.length > 0 && (
        <TableScroll>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>부서</TableHead>
                <TableHead className="text-right">직접 배정</TableHead>
                <TableHead className="text-right">관리</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {nodes.map(({ item, depth, hasChildren }) => (
                <TableRow key={item.id}>
                  <TableCell>
                    {renaming?.id === item.id ? (
                      <div className="flex items-center gap-2">
                        <Input
                          value={renaming.value}
                          autoFocus
                          onChange={(event) =>
                            setRenaming({
                              id: item.id,
                              value: event.target.value,
                            })
                          }
                          className="h-8 w-56"
                          aria-label="부서 이름"
                        />
                        <Button
                          size="sm"
                          disabled={rename.busy || renaming.value.trim().length === 0}
                          onClick={async () => {
                            const updated = await rename.mutate(item.id, renaming.value);
                            if (updated) {
                              toast.success("부서 이름을 바꿨습니다");
                              setRenaming(null);
                              departments.reload();
                            }
                          }}
                        >
                          저장
                        </Button>
                        <Button variant="ghost" size="sm" onClick={() => setRenaming(null)}>
                          취소
                        </Button>
                      </div>
                    ) : (
                      <span style={{ paddingLeft: `${depth * 16}px` }} className="inline-block">
                        {depth > 0 && <span className="text-muted-foreground">└ </span>}
                        {item.name}
                        {hasChildren && (
                          <span className="ml-2 text-xs text-muted-foreground">하위 부서 있음</span>
                        )}
                      </span>
                    )}
                  </TableCell>
                  <TableCell className="tabular text-right">{formatCount(item.memberCount)}</TableCell>
                  <TableCell>
                    <div className="flex justify-end gap-1">
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => setRenaming({ id: item.id, value: item.name })}
                      >
                        이름 변경
                      </Button>
                      <ConfirmButton
                        variant="ghost"
                        disabled={remove.busy}
                        confirmLabel="삭제 확정"
                        onConfirm={async () => {
                          const done = await remove.mutate(item.id);
                          if (done) {
                            toast.success("부서를 삭제했습니다");
                            departments.reload();
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
    </Section>
  );
}

function Sites({ orgId }: { orgId: string }) {
  const sites = useApi<Site[]>(orgPath(orgId, "/sites"), [orgId]);
  const [name, setName] = useState("");
  const [address, setAddress] = useState("");
  const [timezone, setTimezone] = useState("");
  const [editing, setEditing] = useState<Site | null>(null);

  const create = useMutation(async () =>
    api.post<Site>(orgPath(orgId, "/sites"), {
      name: name.trim(),
      address: address.trim() || null,
      timezone: timezone.trim() || null,
    }),
  );

  const update = useMutation(async (site: Site, next: { name: string; address: string; timezone: string }) =>
    api.put<Site>(orgPath(orgId, `/sites/${encodeURIComponent(site.id)}`), {
      name: next.name.trim(),
      // 빈 문자열은 서버에서 "주소 해제"로 해석된다(null 은 미변경) — 의도적으로 구분해 보낸다.
      address: next.address.trim(),
      timezone: next.timezone.trim() || null,
    }),
  );

  const remove = useMutation(async (site: Site) => {
    await api.delete<void>(orgPath(orgId, `/sites/${encodeURIComponent(site.id)}`));
    return true;
  });

  const rows = sites.data ?? [];

  return (
    <Section
      title="사업장"
      description="구내식당이 있는 장소 단위입니다. 식수 예측과 가맹점 연결의 축이 됩니다."
    >
      {sites.error && <ErrorNotice message={sites.error} onRetry={sites.reload} />}
      {create.error && <ErrorNotice message={create.error} onDismiss={create.clearError} />}
      {update.error && <ErrorNotice message={update.error} onDismiss={update.clearError} />}
      {remove.error && <ErrorNotice message={remove.error} onDismiss={remove.clearError} />}

      <div className="grid items-start gap-3 sm:grid-cols-[1fr_1fr_1fr_auto]">
        <Field label="사업장 이름" htmlFor="site-name">
          <Input
            id="site-name"
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="예: 판교 본사"
          />
        </Field>
        <Field label="주소" htmlFor="site-address">
          <Input
            id="site-address"
            value={address}
            onChange={(event) => setAddress(event.target.value)}
            placeholder="선택 입력"
          />
        </Field>
        <Field label="타임존" htmlFor="site-timezone" hint="비우면 UTC 로 생성됩니다.">
          <Input
            id="site-timezone"
            value={timezone}
            onChange={(event) => setTimezone(event.target.value)}
            placeholder="Asia/Seoul"
          />
        </Field>
        <FieldAction>
          <Button
            disabled={create.busy || name.trim().length === 0}
            onClick={async () => {
              const created = await create.mutate();
              if (created) {
                toast.success(`사업장 ‘${created.name}’을(를) 만들었습니다`);
                setName("");
                setAddress("");
                setTimezone("");
                sites.reload();
              }
            }}
          >
            사업장 추가
          </Button>
        </FieldAction>
      </div>

      {sites.loading && <RowsSkeleton rows={2} />}
      {!sites.loading && rows.length === 0 && (
        <EmptyState
          title="등록된 사업장이 없습니다"
          description="사업장을 만들면 구성원을 배정할 수 있습니다."
        />
      )}

      {rows.length > 0 && (
        <TableScroll>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>이름</TableHead>
                <TableHead>주소</TableHead>
                <TableHead>타임존</TableHead>
                <TableHead className="text-right">인원</TableHead>
                <TableHead className="text-right">관리</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((site) => (
                <TableRow key={site.id}>
                  <TableCell className="font-medium">{site.name}</TableCell>
                  <TableCell>{site.address ?? "—"}</TableCell>
                  <TableCell>{site.timezone}</TableCell>
                  <TableCell className="tabular text-right">{formatCount(site.memberCount)}</TableCell>
                  <TableCell>
                    <div className="flex justify-end gap-1">
                      <Button variant="outline" size="sm" onClick={() => setEditing(site)}>
                        편집
                      </Button>
                      <ConfirmButton
                        variant="ghost"
                        disabled={remove.busy}
                        confirmLabel="삭제 확정"
                        onConfirm={async () => {
                          const done = await remove.mutate(site);
                          if (done) {
                            toast.success("사업장을 삭제했습니다");
                            sites.reload();
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

      {editing && (
        <SiteEditor
          site={editing}
          busy={update.busy}
          onCancel={() => setEditing(null)}
          onSave={async (next) => {
            const saved = await update.mutate(editing, next);
            if (saved) {
              toast.success("사업장을 수정했습니다");
              setEditing(null);
              sites.reload();
            }
          }}
        />
      )}
    </Section>
  );
}

function SiteEditor({
  site,
  busy,
  onCancel,
  onSave,
}: {
  site: Site;
  busy: boolean;
  onCancel: () => void;
  onSave: (next: { name: string; address: string; timezone: string }) => void;
}) {
  const [name, setName] = useState(site.name);
  const [address, setAddress] = useState(site.address ?? "");
  const [timezone, setTimezone] = useState(site.timezone);

  return (
    <div className="rounded-lg border border-border bg-muted/40 p-4">
      <h3 className="text-sm font-medium text-foreground">사업장 편집 — {site.name}</h3>
      <div className="mt-3 grid gap-3 sm:grid-cols-3">
        <Field label="이름" htmlFor="site-edit-name">
          <Input id="site-edit-name" value={name} onChange={(event) => setName(event.target.value)} />
        </Field>
        <Field label="주소" htmlFor="site-edit-address" hint="비우면 주소를 지웁니다.">
          <Input
            id="site-edit-address"
            value={address}
            onChange={(event) => setAddress(event.target.value)}
          />
        </Field>
        <Field label="타임존" htmlFor="site-edit-timezone">
          <Input
            id="site-edit-timezone"
            value={timezone}
            onChange={(event) => setTimezone(event.target.value)}
          />
        </Field>
      </div>
      <div className="mt-3 flex gap-2">
        <Button
          disabled={busy || name.trim().length === 0}
          onClick={() => onSave({ name, address, timezone })}
        >
          {busy ? "저장 중" : "저장"}
        </Button>
        <Button variant="outline" onClick={onCancel} disabled={busy}>
          취소
        </Button>
      </div>
    </div>
  );
}
