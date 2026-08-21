"use client";

import { useState } from "react";
import { toast } from "sonner";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { Badge } from "@/components/ui/badge";
import { Button, ButtonLink } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api } from "@/lib/api";
import { useRevealOnChange } from "@/lib/useActiveTabScroll";
import { useApi } from "@/lib/useApi";
import {
  ConfirmDialog,
  CopyBox,
  Modal,
  PageHeader,
  Section,
  SelectField,
  StatusBadge,
  TableScroll,
  TextField,
  formatDateTime,
} from "../_components/kit";
import { adminErrorText } from "../_lib/errors";
import { UserPicker } from "./UserPicker";
import type { MembershipView, OrgDomainSettingsView, OrgView, SsoConnectionView } from "../_lib/types";

/**
 * 조직(테넌트) 관리.
 *
 * 타임존은 단순 표시값이 아니라 **소비 집계 date 버킷의 앵커**다(V18) — 바꾸면 이후 집계·청구 기간
 * 경계가 달라지므로 화면에서 그 사실을 말한다. 조직 정지는 로그인 자체를 막지 않고 조직 결속 기능
 * (SCIM·자동가입 등)이 fail-closed 로 닫히는 상태다.
 */
type Tab = "profile" | "members" | "domains" | "sso";

export default function AdminOrgsPage() {
  const orgs = useApi<OrgView[]>("/api/admin/orgs");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  /*
   * ★검색이 없던 동안 62개 조직을 눈으로 훑어야 했다. 운영자가 특정 고객사를 찾는 일이 이 화면의
   * 가장 흔한 용도인데, 그게 가장 오래 걸렸다.
   */
  const [filter, setFilter] = useState("");
  const term = filter.trim().toLowerCase();
  const visibleOrgs = (orgs.data ?? []).filter(
    (org) =>
      term.length === 0 || org.name.toLowerCase().includes(term) || org.slug.toLowerCase().includes(term),
  );
  // 사용자 화면과 같은 이유(상세가 표 아래) — 두 화면의 동작을 맞춘다.
  const detailRef = useRevealOnChange<HTMLDivElement>(selectedId);
  const [createOpen, setCreateOpen] = useState(false);

  const selected = orgs.data?.find((org) => org.id === selectedId) ?? null;

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="조직"
        description="테넌트를 만들고 상태·타임존·구성원을 관리합니다. 조직관리자(ORG_ADMIN)의 자율 콘솔은 /console 에 따로 있습니다."
        actions={<Button onClick={() => setCreateOpen(true)}>조직 만들기</Button>}
      />

      <Section
        title="조직 목록"
        description={
          orgs.data
            ? term
              ? `${visibleOrgs.length}개 / 전체 ${orgs.data.length}개`
              : `${orgs.data.length}개`
            : undefined
        }
        actions={
          <>
            <input
              type="search"
              value={filter}
              onChange={(event) => setFilter(event.target.value)}
              placeholder="이름·슬러그 검색"
              aria-label="조직 검색"
              className="h-8 w-44 rounded-md border border-input bg-background px-2 text-sm"
            />
            <Button variant="outline" size="sm" onClick={orgs.reload}>
              새로고침
            </Button>
          </>
        }
      >
        {orgs.loading ? (
          <RowsSkeleton rows={5} />
        ) : orgs.error ? (
          <ErrorNotice message={orgs.error} onRetry={orgs.reload} />
        ) : visibleOrgs.length === 0 ? (
          <EmptyState
            title={term ? "검색 결과가 없습니다" : "등록된 조직이 없습니다"}
            description={
              term
                ? "다른 이름이나 슬러그로 검색해 보세요."
                : "첫 조직을 만들면 구성원 초대와 식대 정책 설정을 시작할 수 있습니다."
            }
          />
        ) : (
          <TableScroll>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>이름</TableHead>
                  <TableHead>슬러그</TableHead>
                  <TableHead>상태</TableHead>
                  <TableHead>타임존</TableHead>
                  <TableHead className="text-right">구성원</TableHead>
                  <TableHead className="w-40">생성</TableHead>
                  <TableHead className="w-20" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {visibleOrgs.map((org) => (
                  <TableRow key={org.id}>
                    <TableCell className="font-medium whitespace-nowrap">{org.name}</TableCell>
                    <TableCell className="font-mono text-xs whitespace-nowrap text-muted-foreground">
                      {org.slug}
                    </TableCell>
                    <TableCell>
                      <StatusBadge status={org.status} />
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">{org.timezone}</TableCell>
                    <TableCell className="tabular text-right">{org.memberCount}</TableCell>
                    <TableCell className="tabular whitespace-nowrap text-muted-foreground">
                      {formatDateTime(org.createdAt)}
                    </TableCell>
                    <TableCell>
                      <Button variant="outline" size="sm" onClick={() => setSelectedId(org.id)}>
                        관리
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableScroll>
        )}
      </Section>

      {selected && (
        <div ref={detailRef} className="scroll-mt-4">
          <OrgDetail
            key={selected.id}
            org={selected}
            onClose={() => setSelectedId(null)}
            onChanged={orgs.reload}
          />
        </div>
      )}

      <CreateOrgModal
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreated={(created) => {
          orgs.reload();
          setSelectedId(created.id);
        }}
      />
    </div>
  );
}

/* ── 생성 ─────────────────────────────────────────────────────────────── */

function CreateOrgModal({
  open,
  onOpenChange,
  onCreated,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCreated: (org: OrgView) => void;
}) {
  const [name, setName] = useState("");
  const [slug, setSlug] = useState("");
  const [timezone, setTimezone] = useState("Asia/Seoul");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  async function submit() {
    setBusy(true);
    setError(undefined);
    try {
      const created = await api.post<OrgView>("/api/admin/orgs", {
        name: name.trim(),
        slug: slug.trim() || null,
        timezone: timezone.trim() || null,
      });
      toast.success(`조직 '${created.name}' 을(를) 만들었습니다`);
      onCreated(created);
      onOpenChange(false);
      setName("");
      setSlug("");
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return;
      setError(adminErrorText(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open={open}
      onOpenChange={onOpenChange}
      title="조직 만들기"
      description="슬러그는 만든 뒤 변경할 수 없습니다(식별 안정성)."
      footer={
        <>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>
            취소
          </Button>
          <Button onClick={submit} disabled={busy || name.trim().length === 0}>
            {busy ? "만드는 중" : "만들기"}
          </Button>
        </>
      }
    >
      {error && <ErrorNotice message={error} />}
      <TextField label="이름" value={name} onChange={setName} placeholder="지란지교소프트" />
      <TextField
        label="슬러그"
        value={slug}
        onChange={setSlug}
        hint="비우면 이름에서 자동 생성됩니다. 이후 변경 불가."
        placeholder="jiran"
      />
      <TextField
        label="타임존"
        value={timezone}
        onChange={setTimezone}
        hint="IANA 존 이름(예: Asia/Seoul). 소비 집계의 하루 경계를 정하는 기준이라 나중에 바꾸면 집계 구간이 달라집니다."
      />
    </Modal>
  );
}

/* ── 상세 ─────────────────────────────────────────────────────────────── */

function OrgDetail({
  org,
  onClose,
  onChanged,
}: {
  org: OrgView;
  onClose: () => void;
  onChanged: () => void;
}) {
  const [tab, setTab] = useState<Tab>("profile");

  const tabs: { key: Tab; label: string }[] = [
    { key: "profile", label: "프로필" },
    { key: "members", label: "구성원" },
    { key: "domains", label: "자동가입 도메인" },
    { key: "sso", label: "SSO 연결" },
  ];

  return (
    <Section
      title={`조직 관리 — ${org.name}`}
      description={`${org.slug} · ${org.id}`}
      actions={
        <>
          {/*
            ★조직 콘솔로 가는 링크. 이메일 기반 초대는 그 콘솔의 '초대' 탭에만 있는데(플랫폼 관리자도
            통과한다), 여기 어디에도 링크가 없어 **URL 을 직접 쳐야만** 갈 수 있었다.
          */}
          <ButtonLink href={`/console/${org.id}`} variant="outline" size="sm">
            조직 콘솔 열기
          </ButtonLink>
          <Button variant="ghost" size="sm" onClick={onClose}>
            닫기
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <div className="-mx-4 overflow-x-auto px-4">
          <div className="flex w-max items-center gap-1 rounded-lg bg-muted p-1">
            {tabs.map((item) => (
              <button
                key={item.key}
                type="button"
                onClick={() => setTab(item.key)}
                aria-current={tab === item.key ? "true" : undefined}
                className={`rounded-md px-3 py-1.5 text-sm font-medium whitespace-nowrap transition-colors ${
                  tab === item.key
                    ? "bg-card text-foreground shadow-sm"
                    : "text-muted-foreground hover:text-foreground"
                }`}
              >
                {item.label}
              </button>
            ))}
          </div>
        </div>

        {tab === "profile" && <OrgProfileForm org={org} onChanged={onChanged} />}
        {tab === "members" && <OrgMembers orgId={org.id} onChanged={onChanged} />}
        {tab === "domains" && <OrgDomains orgId={org.id} />}
        {tab === "sso" && <OrgSso orgId={org.id} />}
      </div>
    </Section>
  );
}

function OrgProfileForm({ org, onChanged }: { org: OrgView; onChanged: () => void }) {
  const [name, setName] = useState(org.name);
  const [status, setStatus] = useState(org.status);
  const [timezone, setTimezone] = useState(org.timezone);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  const dirty = name !== org.name || status !== org.status || timezone !== org.timezone;

  async function save() {
    setBusy(true);
    setError(undefined);
    try {
      await api.put<OrgView>(`/api/admin/orgs/${org.id}`, {
        name: name.trim(),
        status,
        timezone: timezone.trim(),
      });
      toast.success("조직 정보를 저장했습니다");
      onChanged();
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return;
      const text = adminErrorText(cause);
      setError(text);
      toast.error(text);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col gap-3">
      {error && <ErrorNotice message={error} />}
      <div className="grid gap-3 sm:grid-cols-3">
        <TextField label="이름" value={name} onChange={setName} />
        <SelectField
          label="상태"
          value={status}
          onChange={setStatus}
          options={[
            { value: "ACTIVE", label: "ACTIVE — 정상" },
            { value: "SUSPENDED", label: "SUSPENDED — 정지" },
          ]}
          hint="정지하면 조직 결속 기능(SCIM 프로비저닝·자동가입 등)이 닫힙니다."
        />
        <TextField
          label="타임존"
          value={timezone}
          onChange={setTimezone}
          hint="소비 집계 하루 경계의 기준입니다. 변경하면 이후 집계·예측 구간이 달라집니다."
        />
      </div>
      <div>
        <Button size="sm" onClick={save} disabled={busy || !dirty}>
          {busy ? "저장 중" : "저장"}
        </Button>
      </div>
      <p className="text-xs text-muted-foreground">
        슬러그는 변경할 수 없습니다. 확정된 청구서가 있는 조직의 타임존 변경은 확정 구간을 건드리지 않습니다.
      </p>
    </div>
  );
}

function OrgMembers({ orgId, onChanged }: { orgId: string; onChanged: () => void }) {
  const members = useApi<MembershipView[]>(`/api/admin/orgs/${orgId}/members`);
  const [addOpen, setAddOpen] = useState(false);
  const [userId, setUserId] = useState("");
  const [role, setRole] = useState("MEMBER");
  const [department, setDepartment] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);
  const [removing, setRemoving] = useState<MembershipView | null>(null);

  async function run(label: string, call: () => Promise<unknown>) {
    setBusy(true);
    setError(undefined);
    try {
      await call();
      toast.success(`${label} 완료`);
      members.reload();
      onChanged();
      return true;
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return false;
      const text = adminErrorText(cause);
      setError(text);
      toast.error(text);
      return false;
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col gap-3">
      {error && <ErrorNotice message={error} />}

      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-muted-foreground">
          {members.data ? `${members.data.length}명` : "불러오는 중"} · 초대 절차를 거치지 않는 직접 추가는
          플랫폼 관리자만 할 수 있습니다.
        </p>
        <Button size="sm" onClick={() => setAddOpen(true)}>
          구성원 추가
        </Button>
      </div>

      {members.loading ? (
        <RowsSkeleton rows={4} />
      ) : members.error ? (
        <ErrorNotice message={members.error} onRetry={members.reload} />
      ) : !members.data || members.data.length === 0 ? (
        <EmptyState
          title="구성원이 없습니다"
          description="사용자 UUID 로 직접 추가하거나, 조직 콘솔에서 초대를 보내세요."
        />
      ) : (
        <TableScroll>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>이메일</TableHead>
                <TableHead>역할</TableHead>
                <TableHead>부서</TableHead>
                <TableHead>재직</TableHead>
                <TableHead>상태</TableHead>
                <TableHead className="w-40">합류</TableHead>
                <TableHead className="w-44" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {members.data.map((member) => (
                <TableRow key={member.id}>
                  <TableCell className="font-medium whitespace-nowrap">
                    {member.email ?? member.userId}
                  </TableCell>
                  <TableCell>
                    {member.role === "ORG_ADMIN" ? (
                      <Badge>ORG_ADMIN</Badge>
                    ) : (
                      <span className="text-xs text-muted-foreground">MEMBER</span>
                    )}
                  </TableCell>
                  <TableCell className="whitespace-nowrap text-muted-foreground">
                    {member.department ?? "—"}
                  </TableCell>
                  <TableCell className="whitespace-nowrap text-muted-foreground">
                    {member.employmentStatus}
                  </TableCell>
                  <TableCell>
                    <StatusBadge status={member.status} />
                  </TableCell>
                  <TableCell className="tabular whitespace-nowrap text-muted-foreground">
                    {formatDateTime(member.joinedAt)}
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center justify-end gap-2">
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={busy}
                        onClick={() =>
                          run("역할 변경", () =>
                            api.put(`/api/admin/orgs/${orgId}/members/${member.userId}/role`, {
                              role: member.role === "ORG_ADMIN" ? "MEMBER" : "ORG_ADMIN",
                            }),
                          )
                        }
                      >
                        {member.role === "ORG_ADMIN" ? "MEMBER 로" : "ORG_ADMIN 로"}
                      </Button>
                      <Button
                        variant="destructive"
                        size="sm"
                        disabled={busy}
                        onClick={() => setRemoving(member)}
                      >
                        제거
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableScroll>
      )}

      <Modal
        open={addOpen}
        onOpenChange={setAddOpen}
        title="구성원 추가"
        description="이미 계정이 있는 사용자를 초대 없이 즉시 합류시킵니다. 계정이 없는 주소는 조직 콘솔의 '초대' 를 쓰세요."
        footer={
          <>
            <Button variant="outline" onClick={() => setAddOpen(false)} disabled={busy}>
              취소
            </Button>
            <Button
              disabled={busy || userId.trim().length === 0}
              onClick={async () => {
                const ok = await run("구성원 추가", () =>
                  api.post(`/api/admin/orgs/${orgId}/members`, {
                    userId: userId.trim(),
                    role,
                    department: department.trim() || null,
                  }),
                );
                if (ok) {
                  setAddOpen(false);
                  setUserId("");
                  setDepartment("");
                }
              }}
            >
              {busy ? "추가 중" : "추가"}
            </Button>
          </>
        }
      >
        {/*
          ★UUID 직접 입력을 이메일 검색으로 바꿨다. 그전에는 새 조직의 첫 조직관리자를 지정하려면
          화면 두 개를 오가며 UUID 를 복사해 와야 했다 — 온보딩의 첫 단계가 그러면 그 뒤가 아무리
          좋아도 제품을 쓰기 시작할 수 없다. 근거는 UserPicker KDoc.
        */}
        <UserPicker value={userId} onChange={(id) => setUserId(id)} />
        <SelectField
          label="역할"
          value={role}
          onChange={setRole}
          options={[
            { value: "MEMBER", label: "MEMBER — 일반 구성원" },
            { value: "ORG_ADMIN", label: "ORG_ADMIN — 조직 관리자" },
          ]}
        />
        <TextField
          label="부서 라벨(선택)"
          value={department}
          onChange={setDepartment}
          hint="자유 텍스트입니다. 구조적 부서 배정은 조직 콘솔에서 합니다."
        />
      </Modal>

      <ConfirmDialog
        open={removing !== null}
        onOpenChange={(open) => !open && setRemoving(null)}
        title="구성원을 제거할까요?"
        message={`${removing?.email ?? removing?.userId ?? ""} 의 멤버십이 제거됩니다. 계정 자체는 삭제되지 않으며, 멤버십 이력에는 제거 기록이 남습니다.`}
        confirmLabel="제거"
        busy={busy}
        onConfirm={async () => {
          if (!removing) return;
          const ok = await run("구성원 제거", () =>
            api.delete(`/api/admin/orgs/${orgId}/members/${removing.userId}`),
          );
          if (ok) setRemoving(null);
        }}
      />
    </div>
  );
}

function OrgDomains({ orgId }: { orgId: string }) {
  const settings = useApi<OrgDomainSettingsView>(`/api/admin/orgs/${orgId}/domains`);
  const [domain, setDomain] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);
  const [removingId, setRemovingId] = useState<string | null>(null);

  async function run(label: string, call: () => Promise<unknown>) {
    setBusy(true);
    setError(undefined);
    try {
      await call();
      toast.success(`${label} 완료`);
      settings.reload();
      return true;
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return false;
      const text = adminErrorText(cause);
      setError(text);
      toast.error(text);
      return false;
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col gap-3">
      {error && <ErrorNotice message={error} />}

      {/*
        ★"조직관리자 콘솔(/console)에 있습니다"만으로는 갈 수가 없었다 — 운영자의 `/console` 목록에는
        자기가 ORG_ADMIN 인 조직만 나오므로 **그 조직이 거기 없다**. 이 조직의 콘솔로 바로 가는 링크를
        준다(플랫폼 권한으로 열린다).
      */}
      <p className="rounded-lg border border-border bg-muted/50 px-3 py-2 text-xs text-muted-foreground">
        자동 가입은 <b>검증된 도메인 + 조직의 opt-in</b> 이 모두 켜져야 동작합니다. opt-in 스위치는 조직
        콘솔의 &lsquo;도메인&rsquo; 탭에 있고 이 화면에서는 현재 값만 보여줍니다 — 현재 상태:{" "}
        <b>{settings.data ? (settings.data.autoJoinEnabled ? "켜짐" : "꺼짐") : "확인 중"}</b>
        {" · "}
        <a href={`/console/${orgId}/domains`} className="text-primary underline-offset-2 hover:underline">
          이 조직의 도메인 설정 열기
        </a>
      </p>

      <form
        className="flex flex-wrap items-end gap-2"
        onSubmit={async (event) => {
          event.preventDefault();
          const ok = await run("도메인 등록", () =>
            api.post(`/api/admin/orgs/${orgId}/domains`, {
              domain: domain.trim(),
            }),
          );
          if (ok) setDomain("");
        }}
      >
        <TextField
          label="도메인"
          value={domain}
          onChange={setDomain}
          className="w-64"
          placeholder="example.com"
          hint="공용 메일 도메인(gmail 등)은 등록할 수 없습니다."
        />
        <Button type="submit" size="sm" disabled={busy || domain.trim().length === 0}>
          등록
        </Button>
      </form>

      {settings.loading ? (
        <RowsSkeleton rows={3} />
      ) : settings.error ? (
        <ErrorNotice message={settings.error} onRetry={settings.reload} />
      ) : !settings.data || settings.data.domains.length === 0 ? (
        <EmptyState
          title="등록된 도메인이 없습니다"
          description="회사 메일 도메인을 등록하고 DNS TXT 로 소유를 증명하면 자동 가입을 쓸 수 있습니다."
        />
      ) : (
        <div className="flex flex-col gap-3">
          {settings.data.domains.map((row) => (
            <div key={row.id} className="rounded-lg border border-border px-3 py-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                  <span className="font-medium">{row.domain}</span>
                  {row.verified ? (
                    <Badge variant="secondary" className="border-border">
                      검증됨 {row.verifiedAt ? `· ${formatDateTime(row.verifiedAt)}` : ""}
                    </Badge>
                  ) : (
                    <Badge variant="outline">미검증</Badge>
                  )}
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  {!row.verified && (
                    <>
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busy}
                        onClick={() =>
                          run("DNS 검증", () => api.post(`/api/admin/orgs/${orgId}/domains/${row.id}/verify`))
                        }
                      >
                        DNS 검증
                      </Button>
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busy}
                        onClick={() =>
                          run("수동 승인", () =>
                            api.post(`/api/admin/orgs/${orgId}/domains/${row.id}/force-verify`),
                          )
                        }
                      >
                        수동 승인
                      </Button>
                    </>
                  )}
                  {row.verified && (
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busy}
                      onClick={() =>
                        run("검증 철회", () =>
                          api.post(`/api/admin/orgs/${orgId}/domains/${row.id}/unverify`),
                        )
                      }
                    >
                      검증 철회
                    </Button>
                  )}
                  <Button
                    size="sm"
                    variant="destructive"
                    disabled={busy}
                    onClick={() => setRemovingId(row.id)}
                  >
                    삭제
                  </Button>
                </div>
              </div>
              {!row.verified && (
                <div className="mt-3 flex flex-col gap-2">
                  <p className="text-xs text-muted-foreground">
                    아래 TXT 레코드를 DNS 에 게시한 뒤 &quot;DNS 검증&quot;을 누르세요. 오프라인으로 소유를
                    확인했다면 &quot;수동 승인&quot;을 쓸 수 있습니다.
                  </p>
                  <CopyBox label="레코드 이름" value={row.txtRecordName} />
                  <CopyBox label="레코드 값" value={row.txtRecordValue} />
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      <ConfirmDialog
        open={removingId !== null}
        onOpenChange={(open) => !open && setRemovingId(null)}
        title="도메인을 삭제할까요?"
        message="삭제하면 이 도메인으로 들어오는 신규 가입자의 자동 조직 배정이 즉시 멈춥니다. 기존 구성원은 그대로 유지됩니다."
        confirmLabel="삭제"
        busy={busy}
        onConfirm={async () => {
          if (!removingId) return;
          const ok = await run("도메인 삭제", () =>
            api.delete(`/api/admin/orgs/${orgId}/domains/${removingId}`),
          );
          if (ok) setRemovingId(null);
        }}
      />
    </div>
  );
}

function OrgSso({ orgId }: { orgId: string }) {
  const connections = useApi<SsoConnectionView[]>("/api/admin/sso");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  async function link(connectionId: string) {
    setBusy(true);
    setError(undefined);
    try {
      await api.put(`/api/admin/orgs/${orgId}/sso/${connectionId}`);
      toast.success("SSO 커넥션을 이 조직에 연결했습니다");
      connections.reload();
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return;
      const text = adminErrorText(cause);
      setError(text);
      toast.error(text);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col gap-3">
      {error && <ErrorNotice message={error} />}
      <p className="text-sm text-muted-foreground">
        커넥션을 조직에 연결해야 IdP 로그인 성공 시 JIT 멤버십이 만들어집니다. 커넥션 자체의 설정은 기업 SSO
        화면에서 관리합니다.
      </p>

      {connections.loading ? (
        <RowsSkeleton rows={3} />
      ) : connections.error ? (
        <ErrorNotice message={connections.error} onRetry={connections.reload} />
      ) : !connections.data || connections.data.length === 0 ? (
        <EmptyState
          title="등록된 SSO 커넥션이 없습니다"
          description="기업 SSO 화면에서 OIDC 또는 SAML 커넥션을 먼저 만드세요."
        />
      ) : (
        <TableScroll>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>커넥션</TableHead>
                <TableHead>프로토콜</TableHead>
                <TableHead>연결된 조직</TableHead>
                <TableHead className="w-32" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {connections.data.map((connection) => {
                const linkedHere = connection.orgId === orgId;
                return (
                  <TableRow key={connection.id}>
                    <TableCell className="whitespace-nowrap">
                      <span className="font-medium">{connection.displayName}</span>
                      <span className="ml-2 font-mono text-xs text-muted-foreground">
                        {connection.registrationId}
                      </span>
                    </TableCell>
                    <TableCell>{connection.protocol}</TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">
                      {linkedHere ? (
                        <Badge>이 조직</Badge>
                      ) : connection.orgId ? (
                        `다른 조직 (${connection.orgId.slice(0, 8)}…)`
                      ) : (
                        "연결 안 됨"
                      )}
                    </TableCell>
                    <TableCell>
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busy || linkedHere}
                        onClick={() => link(connection.id)}
                      >
                        {linkedHere ? "연결됨" : "이 조직에 연결"}
                      </Button>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </TableScroll>
      )}
    </div>
  );
}
