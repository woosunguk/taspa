"use client";

import { useState } from "react";
import { toast } from "sonner";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api } from "@/lib/api";
import { useApi, type Query } from "@/lib/useApi";
import { cn } from "@/lib/utils";
import {
  CheckboxField,
  ConfirmDialog,
  Field,
  Modal,
  Notice,
  PageHeader,
  Section,
  SelectField,
  TableScroll,
  TextAreaField,
  TextField,
  formatDateTime,
  tokens,
  useJsonDraft,
} from "../_components/kit";
import { adminErrorText } from "../_lib/errors";
import type {
  GroupMemberView,
  GroupView,
  IamPrincipalType,
  OrgView,
  PolicyView,
  PrincipalPolicyView,
  SimulateResponse,
  SimulateSubjectType,
} from "../_lib/types";

/**
 * IAM 정책 관리 + 시뮬레이터.
 *
 * ★이 화면의 존재 이유는 마지막 탭이다 — "왜 이 사람이 이걸 못 하지?"를 관리자가 스스로 재현하게 한다.
 * 정책 편집은 서버가 최종 판정자다: 문서는 저장 시점에 엄격 파싱(미지원 Statement 요소·중복 키 거부)되고
 * 시스템 정책은 409 로 불변이다. 그래서 화면은 검증을 흉내 내지 않고 **서버 메시지를 그대로 보여준다** —
 * 여기서 지어낸 문구는 실제 거절 사유와 어긋나 관리자를 엉뚱한 곳으로 보낸다.
 */
type Tab = "policies" | "groups" | "principals" | "simulator";

const TABS: { key: Tab; label: string }[] = [
  { key: "policies", label: "정책" },
  { key: "groups", label: "그룹" },
  { key: "principals", label: "유효 정책" },
  { key: "simulator", label: "시뮬레이터" },
];

const PRINCIPAL_TYPES = [
  { value: "USER", label: "USER — 사용자" },
  { value: "GROUP", label: "GROUP — 그룹" },
];

/** 서버 `iam/IamActions.kt` 의 상수. 자유 입력이되 흔한 값을 고를 수 있게 힌트로 노출한다. */
const ACTION_HINTS = [
  "org:ListMembers",
  "org:ChangeMemberRole",
  "org:RemoveMember",
  "org:AssignMember",
  "org:UpdateMemberAttributes",
  "org:CreateInvitation",
  "org:BulkInvite",
  "org:ListDomains",
  "org:RegisterDomain",
  "org:VerifyDomain",
  "org:ConfigureAutoJoin",
  "org:CreateDepartment",
  "org:CreateSite",
  "org:UpdateProfile",
  "org:ReadAudit",
  "org:ReadDashboard",
  "billing:GenerateInvoice",
  "billing:ReadInvoice",
  "billing:FinalizeInvoice",
  "meal:IssueQr",
  "meal:ReadTransactions",
  "meal:Redeem",
  "meal:VoidRedeem",
  "consumption:Write",
  "consumption:ReadAggregate",
  "forecast:Read",
  "forecast:Backtest",
  "calendar:ReadEvents",
  "scim:ManageDirectory",
  "iam:ListPolicies",
  "iam:CreatePolicy",
  "iam:AttachPolicy",
  "iam:SimulatePolicy",
];

/** TRN 형식: `trn:taspa:{service}:{org}:{type}[/{id}]`. org 세그먼트가 비면 플랫폼 전역이다. */
function resourceHints(orgId: string): string[] {
  const org = orgId.trim() || "*";
  return [
    `trn:taspa:org:${org}:member/*`,
    `trn:taspa:org:${org}:invitation/*`,
    `trn:taspa:org:${org}:department/*`,
    `trn:taspa:org:${org}:domain/*`,
    `trn:taspa:org:${org}:dashboard`,
    `trn:taspa:org:${org}:audit`,
    `trn:taspa:billing:${org}:invoice/*`,
    `trn:taspa:meal:${org}:qr`,
    `trn:taspa:meal:${org}:transaction/*`,
    `trn:taspa:consumption:${org}:log`,
    `trn:taspa:forecast:${org}:forecast`,
    `trn:taspa:calendar:${org}:events`,
    `trn:taspa:scim:${org}:directory`,
  ];
}

const DEFAULT_DOCUMENT = JSON.stringify(
  {
    Version: "2026-07-25",
    Statement: [
      {
        Sid: "ReadOrgMembers",
        Effect: "Allow",
        Action: ["org:ListMembers"],
        Resource: ["trn:taspa:org:${taspa:OrgId}:member/*"],
      },
    ],
  },
  null,
  2,
);

const DOCUMENT_HINT =
  "Version 과 Statement[] 만 있는 JSON. Statement 요소는 Sid·Effect·Action·Resource·Condition 만 지원하며 NotAction·NotResource·Principal 은 저장이 거부됩니다(조용한 무시가 과대부여가 되므로).";

/* ── 페이지 ───────────────────────────────────────────────────────────── */

export default function AdminIamPage() {
  const orgs = useApi<OrgView[]>("/api/admin/orgs");
  const [scopeOrgId, setScopeOrgId] = useState("");
  const [tab, setTab] = useState<Tab>("policies");

  const scopeQuery = scopeOrgId ? `?orgId=${encodeURIComponent(scopeOrgId)}` : "";
  const policies = useApi<PolicyView[]>(`/api/admin/iam/policies${scopeQuery}`);
  const groups = useApi<GroupView[]>(`/api/admin/iam/groups${scopeQuery}`);

  const orgOptions = [
    { value: "", label: "플랫폼 전역 (org 결속 없음)" },
    ...(orgs.data ?? []).map((org) => ({
      value: org.id,
      label: `${org.name} (${org.slug})`,
    })),
  ];

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="IAM 정책"
        description="AWS IAM 형식의 정책으로 action·resource 단위 권한을 정의하고, 사용자·그룹에 부착합니다. 판정 규칙은 명시적 Deny 우선, 그다음 Allow, 그 외 암묵적 거부입니다."
      />

      {/*
        ★가드의 존재를 **미리** 알린다. 저장 버튼을 누른 뒤 409 만 받으면 운영자는 그것이 설계된 보호인지
        장애인지 구분할 수 없다 — 특히 "관리자 권한을 좁히자"는 정당한 작업 중에 걸리기 때문이다.
      */}
      <Notice>
        <strong className="font-medium text-foreground">저장 전 자기 락아웃을 검사합니다.</strong> 변경을
        적용해도 IAM 정책을 되돌릴 수 있는 플랫폼 관리자가 최소 한 명 남는지 서버가 실제로 평가하고, 아무도
        남지 않으면 저장하지 않습니다. 명시적 Deny 는 Allow 를 이기므로 <code>iam:*</code> 를 전부 막으면 그
        정책을 지울 수 있는 사람도 없어지기 때문입니다.
      </Notice>

      <Section
        title="스코프"
        description="정책·그룹 목록은 이 스코프로 필터됩니다. 전역 정책은 플랫폼 권한 자체를 나눠주므로 조직 단위 권한은 조직 스코프로 만드세요."
      >
        <SelectField
          label="조직"
          value={scopeOrgId}
          onChange={setScopeOrgId}
          options={orgOptions}
          disabled={orgs.loading}
          hint="조직 스코프 정책은 그 조직의 활성 멤버·같은 조직 그룹에만 부착할 수 있습니다(서버가 409 로 강제)."
          className="max-w-md"
        />
      </Section>

      <div className="-mx-4 overflow-x-auto px-4">
        <div className="flex w-max items-center gap-1 rounded-lg bg-muted p-1">
          {TABS.map((item) => (
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

      {tab === "policies" && <PoliciesTab scopeOrgId={scopeOrgId} policies={policies} groups={groups.data} />}
      {tab === "groups" && <GroupsTab scopeOrgId={scopeOrgId} groups={groups} />}
      {tab === "principals" && <PrincipalsTab />}
      {tab === "simulator" && <SimulatorTab defaultOrgId={scopeOrgId} orgOptions={orgOptions} />}
    </div>
  );
}

/* ── 공통 조각 ────────────────────────────────────────────────────────── */

/** 정책 문서 표시 — 파싱되면 정렬해서, 깨졌으면 원문 그대로(서버가 무엇을 들고 있는지 숨기지 않는다). */
function DocumentBlock({ document }: { document: string }) {
  let text = document;
  try {
    text = JSON.stringify(JSON.parse(document), null, 2);
  } catch {
    /* 저장된 문서가 깨진 상태일 수 있다 — 그때는 원문을 보여야 고칠 수 있다. */
  }
  return (
    <pre className="max-h-80 overflow-auto rounded-lg border border-border bg-muted px-3 py-2 font-mono text-xs leading-relaxed whitespace-pre">
      {text}
    </pre>
  );
}

/** 자유 입력 + 흔한 값 제안(네이티브 datalist — 팝업 라이브러리 없이 키보드/모바일 모두 동작). */
function SuggestField({
  label,
  value,
  onChange,
  options,
  hint,
  placeholder,
  listId,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: string[];
  hint?: string;
  placeholder?: string;
  listId: string;
}) {
  return (
    <Field label={label} hint={hint}>
      {(id) => (
        <>
          <Input
            id={id}
            list={listId}
            value={value}
            placeholder={placeholder}
            autoComplete="off"
            className="font-mono text-xs"
            onChange={(event) => onChange(event.target.value)}
          />
          <datalist id={listId}>
            {options.map((option) => (
              <option key={option} value={option} />
            ))}
          </datalist>
        </>
      )}
    </Field>
  );
}

/* ── 정책 ─────────────────────────────────────────────────────────────── */

function PoliciesTab({
  scopeOrgId,
  policies,
  groups,
}: {
  scopeOrgId: string;
  policies: Query<PolicyView[]>;
  groups: GroupView[] | undefined;
}) {
  const [editing, setEditing] = useState<PolicyView | "new" | null>(null);
  const [viewing, setViewing] = useState<PolicyView | null>(null);
  const [attaching, setAttaching] = useState<PolicyView | null>(null);
  const [deleting, setDeleting] = useState<PolicyView | null>(null);
  const [busy, setBusy] = useState(false);

  async function remove() {
    if (!deleting) return;
    setBusy(true);
    try {
      await api.delete(`/api/admin/iam/policies/${deleting.id}`);
      toast.success(`정책 '${deleting.name}' 을(를) 삭제했습니다`);
      setDeleting(null);
      policies.reload();
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return;
      toast.error(adminErrorText(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col gap-5">
      <Section
        title="정책"
        description={policies.data ? `${policies.data.length}개` : undefined}
        actions={
          <>
            <Button variant="outline" size="sm" onClick={policies.reload}>
              새로고침
            </Button>
            <Button size="sm" onClick={() => setEditing("new")}>
              정책 만들기
            </Button>
          </>
        }
      >
        {policies.loading ? (
          <RowsSkeleton rows={4} />
        ) : policies.error ? (
          <ErrorNotice message={policies.error} onRetry={policies.reload} />
        ) : !policies.data || policies.data.length === 0 ? (
          <EmptyState
            title="이 스코프에 정책이 없습니다"
            description="정책을 만들어 사용자나 그룹에 부착하면 그 principal 의 유효 권한이 됩니다."
          />
        ) : (
          <TableScroll>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>이름</TableHead>
                  <TableHead>설명</TableHead>
                  <TableHead className="text-right">Statement</TableHead>
                  <TableHead>유형</TableHead>
                  <TableHead className="w-40">마지막 수정</TableHead>
                  <TableHead className="w-64" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {policies.data.map((policy) => (
                  <TableRow key={policy.id}>
                    <TableCell className="font-medium whitespace-nowrap">{policy.name}</TableCell>
                    <TableCell
                      className="max-w-64 truncate text-xs text-muted-foreground"
                      title={policy.description ?? ""}
                    >
                      {policy.description ?? "—"}
                    </TableCell>
                    <TableCell className="tabular text-right">{policy.statementCount}</TableCell>
                    <TableCell className="whitespace-nowrap">
                      {policy.systemManaged ? (
                        <Badge variant="secondary" className="border-border">
                          시스템
                        </Badge>
                      ) : (
                        <span className="text-xs text-muted-foreground">사용자</span>
                      )}
                    </TableCell>
                    <TableCell className="tabular whitespace-nowrap text-muted-foreground">
                      {formatDateTime(policy.updatedAt)}
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center justify-end gap-2">
                        <Button variant="ghost" size="sm" onClick={() => setViewing(policy)}>
                          문서
                        </Button>
                        <Button variant="outline" size="sm" onClick={() => setAttaching(policy)}>
                          부착
                        </Button>
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={policy.systemManaged}
                          title={policy.systemManaged ? "시스템 정책은 편집할 수 없습니다" : undefined}
                          onClick={() => setEditing(policy)}
                        >
                          수정
                        </Button>
                        <Button
                          variant="destructive"
                          size="sm"
                          disabled={policy.systemManaged}
                          title={policy.systemManaged ? "시스템 정책은 삭제할 수 없습니다" : undefined}
                          onClick={() => setDeleting(policy)}
                        >
                          삭제
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableScroll>
        )}

        {policies.data?.some((policy) => policy.systemManaged) && (
          <p className="mt-3 text-xs text-muted-foreground">
            <Badge variant="secondary" className="mr-1.5 border-border">
              시스템
            </Badge>
            시스템 정책은 편집할 수 없습니다(서버가 409 IAM_POLICY_IMMUTABLE 로 거부). 동작을 바꾸려면 별도
            정책을 만들어 부착하세요.
          </p>
        )}
      </Section>

      {editing && (
        <PolicyEditor
          key={editing === "new" ? "new" : editing.id}
          policy={editing === "new" ? null : editing}
          scopeOrgId={scopeOrgId}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            policies.reload();
          }}
        />
      )}

      <Modal
        open={viewing !== null}
        onOpenChange={(open) => !open && setViewing(null)}
        wide
        title={`정책 문서 — ${viewing?.name ?? ""}`}
        description={
          viewing?.systemManaged ? "시스템 정책은 편집할 수 없습니다 — 읽기 전용입니다." : undefined
        }
        footer={<Button onClick={() => setViewing(null)}>닫기</Button>}
      >
        {viewing && <DocumentBlock document={viewing.document} />}
      </Modal>

      {attaching && (
        <AttachModal
          key={attaching.id}
          policy={attaching}
          groups={groups}
          onClose={() => setAttaching(null)}
        />
      )}

      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title="정책을 삭제할까요?"
        message={`'${deleting?.name ?? ""}' 을(를) 삭제하면 이 정책의 부착도 전부 함께 사라집니다. 이 정책으로만 권한을 받던 사용자는 즉시 거부됩니다.`}
        confirmLabel="삭제"
        busy={busy}
        onConfirm={remove}
      />
    </div>
  );
}

function PolicyEditor({
  policy,
  scopeOrgId,
  onClose,
  onSaved,
}: {
  policy: PolicyView | null;
  scopeOrgId: string;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [name, setName] = useState(policy?.name ?? "");
  const [description, setDescription] = useState(policy?.description ?? "");
  const draft = useJsonDraft(policy?.document ?? DEFAULT_DOCUMENT);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  async function submit() {
    setBusy(true);
    setError(undefined);
    try {
      if (policy) {
        await api.put<PolicyView>(`/api/admin/iam/policies/${policy.id}`, {
          description: description.trim() || null,
          document: draft.text,
        });
        toast.success("정책을 저장했습니다");
      } else {
        await api.post<PolicyView>("/api/admin/iam/policies", {
          name: name.trim(),
          description: description.trim() || null,
          orgId: scopeOrgId || null,
          document: draft.text,
        });
        toast.success("정책을 만들었습니다");
      }
      onSaved();
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return;
      // 서버가 문서를 최종 검증한다 — 지원하지 않는 Statement 요소·중복 키 등 실제 거절 사유를 그대로 옮긴다.
      setError(adminErrorText(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      onOpenChange={(open) => !open && onClose()}
      wide
      title={policy ? `정책 수정 — ${policy.name}` : "정책 만들기"}
      description={
        policy
          ? "이름과 스코프는 만든 뒤 변경할 수 없습니다. 문서를 바꾸면 이 정책이 부착된 모든 principal 의 권한이 즉시 달라집니다."
          : `스코프: ${scopeOrgId ? scopeOrgId : "플랫폼 전역"}. 스코프는 만든 뒤 바꿀 수 없습니다.`
      }
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={busy}>
            취소
          </Button>
          <Button
            onClick={submit}
            disabled={busy || Boolean(draft.error) || (!policy && name.trim().length === 0)}
          >
            {busy ? "저장 중" : "저장"}
          </Button>
        </>
      }
    >
      {error && <ErrorNotice message={error} />}
      {!policy && (
        <TextField
          label="이름"
          value={name}
          onChange={setName}
          placeholder="OrgMemberReadOnly"
          hint="스코프 안에서 유일해야 합니다(중복이면 서버가 409 IAM_CONFLICT). 변경 불가."
        />
      )}
      <TextField
        label="설명"
        value={description}
        onChange={setDescription}
        placeholder="구성원 목록 조회만 허용"
      />
      <TextAreaField
        label="정책 문서 (JSON)"
        value={draft.text}
        onChange={draft.setText}
        rows={16}
        mono
        error={draft.error}
        hint={DOCUMENT_HINT}
      />
      <div className="flex items-center gap-2">
        <Button variant="outline" size="sm" onClick={draft.pretty} disabled={Boolean(draft.error)}>
          정렬
        </Button>
        <p className="text-xs text-muted-foreground">
          Resource 에는 정책 변수 {"${taspa:OrgId}"} 를 쓸 수 있습니다. 조건키: taspa:OrgId ·
          taspa:ResourceOrg · taspa:StepUpPresent · taspa:PrincipalType · taspa:CurrentTime.
        </p>
      </div>
    </Modal>
  );
}

function AttachModal({
  policy,
  groups,
  onClose,
}: {
  policy: PolicyView;
  groups: GroupView[] | undefined;
  onClose: () => void;
}) {
  const [principalType, setPrincipalType] = useState<IamPrincipalType>("USER");
  const [principalId, setPrincipalId] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  const groupOptions = [
    { value: "", label: "그룹 선택" },
    ...(groups ?? []).map((group) => ({
      value: group.id,
      label: `${group.name} (${group.memberCount}명)`,
    })),
  ];

  async function submit(attach: boolean) {
    setBusy(true);
    setError(undefined);
    const body = {
      policyId: policy.id,
      principalType,
      principalId: principalId.trim(),
    };
    try {
      if (attach) await api.post("/api/admin/iam/attachments", body);
      else await api.delete("/api/admin/iam/attachments", { body });
      toast.success(attach ? "정책을 부착했습니다" : "부착을 해제했습니다");
      onClose();
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return;
      // 조직 정책을 다른 조직 principal 에 붙이면 서버가 409 로 막는다 — 그 문구를 그대로 보여준다.
      setError(adminErrorText(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      onOpenChange={(open) => !open && onClose()}
      title={`정책 부착 — ${policy.name}`}
      description={
        policy.orgId
          ? "조직 스코프 정책입니다. 같은 조직의 활성 멤버 또는 같은 조직 그룹에만 붙일 수 있습니다."
          : "플랫폼 전역 정책입니다. 부착은 플랫폼 권한을 나눠주는 행위이니 대상을 다시 확인하세요."
      }
      footer={
        <>
          <Button
            variant="outline"
            onClick={() => submit(false)}
            disabled={busy || principalId.trim().length === 0}
          >
            해제
          </Button>
          <Button onClick={() => submit(true)} disabled={busy || principalId.trim().length === 0}>
            {busy ? "처리 중" : "부착"}
          </Button>
        </>
      }
    >
      {error && <ErrorNotice message={error} />}
      <SelectField
        label="principal 종류"
        value={principalType}
        onChange={(value) => {
          setPrincipalType(value as IamPrincipalType);
          setPrincipalId("");
        }}
        options={PRINCIPAL_TYPES}
      />
      {principalType === "GROUP" ? (
        <SelectField
          label="그룹"
          value={principalId}
          onChange={setPrincipalId}
          options={groupOptions}
          hint="현재 스코프의 그룹만 나옵니다. 다른 스코프 그룹이면 UUID 를 직접 다루는 대신 스코프를 바꾸세요."
        />
      ) : (
        <TextField
          label="사용자 UUID"
          value={principalId}
          onChange={setPrincipalId}
          placeholder="00000000-0000-0000-0000-000000000000"
          hint="사용자 화면에서 계정을 찾아 UUID 를 복사하세요."
        />
      )}
      <p className="text-xs text-muted-foreground">
        부착·해제 모두 멱등입니다(중복 부착·없는 해제는 조용히 성공).
      </p>
    </Modal>
  );
}

/* ── 그룹 ─────────────────────────────────────────────────────────────── */

function GroupsTab({ scopeOrgId, groups }: { scopeOrgId: string; groups: Query<GroupView[]> }) {
  const [createOpen, setCreateOpen] = useState(false);
  const [selected, setSelected] = useState<GroupView | null>(null);
  const [deleting, setDeleting] = useState<GroupView | null>(null);
  const [busy, setBusy] = useState(false);

  async function remove() {
    if (!deleting) return;
    setBusy(true);
    try {
      await api.delete(`/api/admin/iam/groups/${deleting.id}`);
      toast.success(`그룹 '${deleting.name}' 을(를) 삭제했습니다`);
      if (selected?.id === deleting.id) setSelected(null);
      setDeleting(null);
      groups.reload();
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return;
      toast.error(adminErrorText(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col gap-5">
      <Section
        title="그룹"
        description={groups.data ? `${groups.data.length}개` : undefined}
        actions={
          <>
            <Button variant="outline" size="sm" onClick={groups.reload}>
              새로고침
            </Button>
            <Button size="sm" onClick={() => setCreateOpen(true)}>
              그룹 만들기
            </Button>
          </>
        }
      >
        {groups.loading ? (
          <RowsSkeleton rows={4} />
        ) : groups.error ? (
          <ErrorNotice message={groups.error} onRetry={groups.reload} />
        ) : !groups.data || groups.data.length === 0 ? (
          <EmptyState
            title="이 스코프에 그룹이 없습니다"
            description="같은 권한을 여러 사람에게 줄 때 그룹을 만들어 정책을 한 번만 부착하세요."
          />
        ) : (
          <TableScroll>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>이름</TableHead>
                  <TableHead>설명</TableHead>
                  <TableHead className="text-right">멤버</TableHead>
                  <TableHead>유형</TableHead>
                  <TableHead className="w-40">생성일</TableHead>
                  <TableHead className="w-44" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {groups.data.map((group) => (
                  <TableRow key={group.id} data-state={selected?.id === group.id ? "selected" : undefined}>
                    <TableCell className="font-medium whitespace-nowrap">{group.name}</TableCell>
                    <TableCell
                      className="max-w-64 truncate text-xs text-muted-foreground"
                      title={group.description ?? ""}
                    >
                      {group.description ?? "—"}
                    </TableCell>
                    <TableCell className="tabular text-right">{group.memberCount}</TableCell>
                    <TableCell className="whitespace-nowrap">
                      {group.systemManaged ? (
                        <Badge variant="secondary" className="border-border">
                          시스템
                        </Badge>
                      ) : (
                        <span className="text-xs text-muted-foreground">사용자</span>
                      )}
                    </TableCell>
                    <TableCell className="tabular whitespace-nowrap text-muted-foreground">
                      {formatDateTime(group.createdAt)}
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center justify-end gap-2">
                        <Button variant="outline" size="sm" onClick={() => setSelected(group)}>
                          멤버
                        </Button>
                        <Button
                          variant="destructive"
                          size="sm"
                          disabled={group.systemManaged}
                          title={group.systemManaged ? "시스템 그룹은 삭제할 수 없습니다" : undefined}
                          onClick={() => setDeleting(group)}
                        >
                          삭제
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableScroll>
        )}
      </Section>

      {selected && (
        <GroupMembersPanel
          key={selected.id}
          group={selected}
          onClose={() => setSelected(null)}
          onChanged={groups.reload}
        />
      )}

      <CreateGroupModal
        open={createOpen}
        scopeOrgId={scopeOrgId}
        onOpenChange={setCreateOpen}
        onCreated={() => {
          setCreateOpen(false);
          groups.reload();
        }}
      />

      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title="그룹을 삭제할까요?"
        message={`'${deleting?.name ?? ""}' 을(를) 삭제하면 멤버십과 이 그룹에 부착된 정책 연결이 함께 사라집니다. 그룹으로만 권한을 받던 멤버는 즉시 거부됩니다.`}
        confirmLabel="삭제"
        busy={busy}
        onConfirm={remove}
      />
    </div>
  );
}

function CreateGroupModal({
  open,
  scopeOrgId,
  onOpenChange,
  onCreated,
}: {
  open: boolean;
  scopeOrgId: string;
  onOpenChange: (open: boolean) => void;
  onCreated: () => void;
}) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  async function submit() {
    setBusy(true);
    setError(undefined);
    try {
      await api.post<GroupView>("/api/admin/iam/groups", {
        name: name.trim(),
        description: description.trim() || null,
        orgId: scopeOrgId || null,
      });
      toast.success("그룹을 만들었습니다");
      setName("");
      setDescription("");
      onCreated();
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
      title="그룹 만들기"
      description={`스코프: ${scopeOrgId ? scopeOrgId : "플랫폼 전역"}. 스코프는 만든 뒤 바꿀 수 없습니다.`}
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
      <TextField
        label="이름"
        value={name}
        onChange={setName}
        placeholder="OrgAdmins"
        hint="스코프 안에서 유일해야 합니다."
      />
      <TextField
        label="설명"
        value={description}
        onChange={setDescription}
        placeholder="조직 관리 업무 담당"
      />
    </Modal>
  );
}

function GroupMembersPanel({
  group,
  onClose,
  onChanged,
}: {
  group: GroupView;
  onClose: () => void;
  onChanged: () => void;
}) {
  const members = useApi<GroupMemberView[]>(`/api/admin/iam/groups/${group.id}/members`);
  const [userId, setUserId] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  async function run(label: string, call: () => Promise<unknown>) {
    setBusy(true);
    setError(undefined);
    try {
      await call();
      toast.success(`${label} 완료`);
      members.reload();
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
    <Section
      title={`그룹 멤버 — ${group.name}`}
      description={group.id}
      actions={
        <Button variant="ghost" size="sm" onClick={onClose}>
          닫기
        </Button>
      }
    >
      <div className="flex flex-col gap-3">
        {error && <ErrorNotice message={error} />}

        <div className="flex flex-wrap items-end gap-2">
          <TextField
            label="사용자 UUID"
            value={userId}
            onChange={setUserId}
            placeholder="00000000-0000-0000-0000-000000000000"
            className="min-w-72 flex-1"
            hint="사용자 화면에서 계정을 찾아 UUID 를 복사하세요. 추가는 멱등입니다."
          />
          <Button
            size="sm"
            disabled={busy || userId.trim().length === 0}
            onClick={() =>
              run("멤버 추가", async () => {
                await api.post(`/api/admin/iam/groups/${group.id}/members`, {
                  userId: userId.trim(),
                });
                setUserId("");
              })
            }
          >
            추가
          </Button>
        </div>

        {members.loading ? (
          <RowsSkeleton rows={3} />
        ) : members.error ? (
          <ErrorNotice message={members.error} onRetry={members.reload} />
        ) : !members.data || members.data.length === 0 ? (
          <EmptyState
            title="멤버가 없습니다"
            description="사용자 UUID 를 추가하면 이 그룹에 부착된 정책이 그 사용자에게 적용됩니다."
          />
        ) : (
          <TableScroll>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>이메일</TableHead>
                  <TableHead>사용자 UUID</TableHead>
                  <TableHead className="w-40">추가일</TableHead>
                  <TableHead className="w-24" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {members.data.map((member) => (
                  <TableRow key={member.userId}>
                    <TableCell className="font-medium whitespace-nowrap">{member.email ?? "—"}</TableCell>
                    <TableCell className="font-mono text-xs whitespace-nowrap text-muted-foreground">
                      {member.userId}
                    </TableCell>
                    <TableCell className="tabular whitespace-nowrap text-muted-foreground">
                      {formatDateTime(member.createdAt)}
                    </TableCell>
                    <TableCell>
                      <div className="flex justify-end">
                        <Button
                          variant="destructive"
                          size="sm"
                          disabled={busy}
                          onClick={() =>
                            run("멤버 제거", () =>
                              api.delete(`/api/admin/iam/groups/${group.id}/members/${member.userId}`),
                            )
                          }
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
      </div>
    </Section>
  );
}

/* ── 유효 정책(principal) ─────────────────────────────────────────────── */

const SOURCE_LABEL: Record<string, string> = {
  inline: "inline — 이 principal 에 직접 임베드",
  attached: "attached — 이 principal 에 부착된 managed 정책",
  "group-inline": "group-inline — 소속 그룹의 inline",
  "group-attached": "group-attached — 소속 그룹에 부착된 managed 정책",
};

function PrincipalsTab() {
  const [type, setType] = useState<IamPrincipalType>("USER");
  const [id, setId] = useState("");
  const [target, setTarget] = useState<{
    type: IamPrincipalType;
    id: string;
  } | null>(null);
  const [viewing, setViewing] = useState<PrincipalPolicyView | null>(null);
  const [inlineOpen, setInlineOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  const policies = useApi<PrincipalPolicyView[]>(
    target ? `/api/admin/iam/principals/${target.type}/${target.id}/policies` : null,
  );

  async function run(label: string, call: () => Promise<unknown>) {
    setBusy(true);
    setError(undefined);
    try {
      await call();
      toast.success(`${label} 완료`);
      policies.reload();
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
    <div className="flex flex-col gap-5">
      <Section
        title="principal 조회"
        description="이 principal 에 실제로 적용되는 정책 목록입니다. 사용자는 소속 그룹의 정책까지 합산됩니다."
      >
        <form
          className="flex flex-wrap items-end gap-2"
          onSubmit={(event) => {
            event.preventDefault();
            setError(undefined);
            setTarget(id.trim() ? { type, id: id.trim() } : null);
          }}
        >
          <SelectField
            label="종류"
            value={type}
            onChange={(value) => setType(value as IamPrincipalType)}
            options={PRINCIPAL_TYPES}
            className="w-44"
          />
          <TextField
            label="UUID"
            value={id}
            onChange={setId}
            placeholder="00000000-0000-0000-0000-000000000000"
            className="min-w-72 flex-1"
          />
          <Button type="submit" disabled={id.trim().length === 0}>
            조회
          </Button>
        </form>
      </Section>

      {target && (
        <Section
          title="유효 정책"
          description={`${target.type} ${target.id}${policies.data ? ` · ${policies.data.length}건` : ""}`}
          actions={
            <>
              <Button variant="outline" size="sm" onClick={policies.reload}>
                새로고침
              </Button>
              <Button size="sm" onClick={() => setInlineOpen(true)}>
                inline 정책 추가
              </Button>
            </>
          }
        >
          <div className="flex flex-col gap-3">
            {error && <ErrorNotice message={error} />}

            {policies.loading ? (
              <RowsSkeleton rows={3} />
            ) : policies.error ? (
              <ErrorNotice message={policies.error} onRetry={policies.reload} />
            ) : !policies.data || policies.data.length === 0 ? (
              <EmptyState
                title="적용되는 정책이 없습니다"
                description="이 principal 은 IAM 정책을 하나도 받지 않습니다(암묵적 거부). 정책을 부착하거나 inline 으로 추가하세요."
              />
            ) : (
              <TableScroll>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>이름</TableHead>
                      <TableHead>출처</TableHead>
                      <TableHead className="text-right">Statement</TableHead>
                      <TableHead className="w-52" />
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {policies.data.map((item) => (
                      <TableRow key={`${item.source}:${item.policyId ?? item.name}`}>
                        <TableCell className="font-medium whitespace-nowrap">{item.name}</TableCell>
                        <TableCell
                          className="text-xs whitespace-nowrap text-muted-foreground"
                          title={SOURCE_LABEL[item.source]}
                        >
                          {item.source}
                        </TableCell>
                        <TableCell className="tabular text-right">{item.statementCount}</TableCell>
                        <TableCell>
                          <div className="flex items-center justify-end gap-2">
                            <Button variant="ghost" size="sm" onClick={() => setViewing(item)}>
                              문서
                            </Button>
                            {item.source === "inline" && (
                              <Button
                                variant="destructive"
                                size="sm"
                                disabled={busy}
                                onClick={() =>
                                  run("inline 정책 삭제", () =>
                                    api.delete(
                                      `/api/admin/iam/principals/${target.type}/${target.id}/inline/${encodeURIComponent(item.name)}`,
                                    ),
                                  )
                                }
                              >
                                삭제
                              </Button>
                            )}
                            {item.source === "attached" && item.policyId && (
                              <Button
                                variant="destructive"
                                size="sm"
                                disabled={busy}
                                onClick={() =>
                                  run("부착 해제", () =>
                                    api.delete("/api/admin/iam/attachments", {
                                      body: {
                                        policyId: item.policyId,
                                        principalType: target.type,
                                        principalId: target.id,
                                      },
                                    }),
                                  )
                                }
                              >
                                해제
                              </Button>
                            )}
                          </div>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableScroll>
            )}

            <p className="text-xs text-muted-foreground">
              그룹에서 온 정책(group-*)은 여기서 떼지 못합니다 — 그룹 탭에서 멤버를 빼거나 그룹의 부착을
              해제하세요.
            </p>
          </div>
        </Section>
      )}

      <Modal
        open={viewing !== null}
        onOpenChange={(open) => !open && setViewing(null)}
        wide
        title={`정책 문서 — ${viewing?.name ?? ""}`}
        description={viewing ? SOURCE_LABEL[viewing.source] : undefined}
        footer={<Button onClick={() => setViewing(null)}>닫기</Button>}
      >
        {viewing && <DocumentBlock document={viewing.document} />}
      </Modal>

      {target && inlineOpen && (
        <InlinePolicyModal
          target={target}
          onClose={() => setInlineOpen(false)}
          onSaved={() => {
            setInlineOpen(false);
            policies.reload();
          }}
        />
      )}
    </div>
  );
}

function InlinePolicyModal({
  target,
  onClose,
  onSaved,
}: {
  target: { type: IamPrincipalType; id: string };
  onClose: () => void;
  onSaved: () => void;
}) {
  const [name, setName] = useState("");
  const draft = useJsonDraft(DEFAULT_DOCUMENT);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  async function submit() {
    setBusy(true);
    setError(undefined);
    try {
      await api.put(
        `/api/admin/iam/principals/${target.type}/${target.id}/inline/${encodeURIComponent(name.trim())}`,
        { document: draft.text },
      );
      toast.success("inline 정책을 저장했습니다");
      onSaved();
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return;
      setError(adminErrorText(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      onOpenChange={(open) => !open && onClose()}
      wide
      title="inline 정책 추가"
      description={`${target.type} ${target.id} 에 직접 임베드합니다. 같은 이름이 있으면 덮어씁니다. 재사용할 권한이라면 managed 정책을 만들어 부착하는 편이 관리하기 쉽습니다.`}
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={busy}>
            취소
          </Button>
          <Button onClick={submit} disabled={busy || Boolean(draft.error) || name.trim().length === 0}>
            {busy ? "저장 중" : "저장"}
          </Button>
        </>
      }
    >
      {error && <ErrorNotice message={error} />}
      <TextField label="이름" value={name} onChange={setName} placeholder="TempInvoiceAccess" />
      <TextAreaField
        label="정책 문서 (JSON)"
        value={draft.text}
        onChange={draft.setText}
        rows={14}
        mono
        error={draft.error}
        hint={DOCUMENT_HINT}
      />
      <div>
        <Button variant="outline" size="sm" onClick={draft.pretty} disabled={Boolean(draft.error)}>
          정렬
        </Button>
      </div>
    </Modal>
  );
}

/* ── 시뮬레이터 ───────────────────────────────────────────────────────── */

const SUBJECT_TYPES = [
  { value: "SESSION", label: "SESSION — 로그인 세션 사용자" },
  { value: "M2M", label: "M2M — client_credentials 서비스 토큰" },
  {
    value: "DELEGATED",
    label: "DELEGATED — 사용자를 대신하는 베어러(제3자 앱)",
  },
];

function SimulatorTab({
  defaultOrgId,
  orgOptions,
}: {
  defaultOrgId: string;
  orgOptions: { value: string; label: string }[];
}) {
  const [subjectType, setSubjectType] = useState<SimulateSubjectType>("SESSION");
  const [userId, setUserId] = useState("");
  const [orgId, setOrgId] = useState(defaultOrgId);
  const [stepUp, setStepUp] = useState(false);
  const [scopes, setScopes] = useState("");
  const [boundOrgs, setBoundOrgs] = useState("");
  const [merchantId, setMerchantId] = useState("");
  const [scimOrg, setScimOrg] = useState("");
  const [action, setAction] = useState("org:ListMembers");
  const [resource, setResource] = useState("");
  const [result, setResult] = useState<SimulateResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  const isMachine = subjectType === "M2M";
  const needsUser = !isMachine;

  async function submit() {
    setBusy(true);
    setError(undefined);
    setResult(null);
    try {
      const response = await api.post<SimulateResponse>("/api/admin/iam/simulate", {
        subjectType,
        userId: needsUser ? userId.trim() || null : null,
        orgId: orgId.trim() || null,
        stepUp,
        scopes: isMachine ? tokens(scopes) : [],
        boundOrgs: isMachine ? tokens(boundOrgs) : [],
        merchantId: isMachine ? merchantId.trim() || null : null,
        scimOrg: isMachine ? scimOrg.trim() || null : null,
        action: action.trim(),
        resource: resource.trim(),
      });
      setResult(response);
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return;
      setError(adminErrorText(cause));
    } finally {
      setBusy(false);
    }
  }

  const invalid =
    action.trim().length === 0 || resource.trim().length === 0 || (needsUser && userId.trim().length === 0);

  return (
    <div className="flex flex-col gap-5">
      <Section
        title="주체"
        description="실제 집행과 같은 정책 집합으로 평가합니다. DELEGATED 는 SESSION 과 같은 정책을 보되 taspa:PrincipalType 만 다릅니다 — 위임 토큰이 서비스 전용 능력에 닿는지(confused-deputy) 여기서 재현합니다."
      >
        <div className="flex flex-col gap-3">
          <SelectField
            label="주체 종류"
            value={subjectType}
            onChange={(value) => {
              setSubjectType(value as SimulateSubjectType);
              setResult(null);
            }}
            options={SUBJECT_TYPES}
            className="max-w-md"
          />

          {needsUser ? (
            <div className="grid gap-3 sm:grid-cols-2">
              <TextField
                label="사용자 UUID"
                value={userId}
                onChange={setUserId}
                placeholder="00000000-0000-0000-0000-000000000000"
                hint="필수. role=ADMIN 이면 플랫폼 관리자로, orgId 를 함께 주면 그 조직의 ORG_ADMIN·활성 멤버 여부까지 반영합니다."
              />
              <SelectField
                label="대상 조직"
                value={orgId}
                onChange={setOrgId}
                options={orgOptions}
                hint="taspa:OrgId 조건키로 들어갑니다. 비우면 조직 결속 없이 평가합니다."
              />
            </div>
          ) : (
            <div className="grid gap-3 sm:grid-cols-2">
              <TextField
                label="scope"
                value={scopes}
                onChange={setScopes}
                placeholder="meal.consumption.write org.read"
                hint="공백·쉼표로 구분. 토큰에 실린 scope 그대로."
              />
              <TextField
                label="결속 조직 UUID (org_id 클레임)"
                value={boundOrgs}
                onChange={setBoundOrgs}
                placeholder="00000000-0000-0000-0000-000000000000"
                hint="공백·쉼표로 구분. 여러 개면 전부."
              />
              <TextField
                label="결속 가맹 UUID (merchant_id 클레임)"
                value={merchantId}
                onChange={setMerchantId}
                hint="POS 단말 시뮬레이션. meal:Redeem 계열은 이 결속이 있어야 도달합니다."
              />
              <TextField
                label="SCIM org 앵커"
                value={scimOrg}
                onChange={setScimOrg}
                hint="org.scim 시뮬레이션은 이 값이 없으면 실제 집행과 달리 항상 DENY 로 보입니다."
              />
            </div>
          )}

          <CheckboxField
            label="최근 재인증(step-up) 통과 상태"
            checked={stepUp}
            onChange={setStepUp}
            hint="taspa:StepUpPresent 조건키. 민감 작업이 step-up 조건으로 막히는지 확인할 때 켜고/끄고 비교하세요."
          />
        </div>
      </Section>

      <Section
        title="질의"
        description="action 은 service:Action, resource 는 TRN(trn:taspa:{service}:{org}:{type}[/{id}]) 입니다."
      >
        <div className="flex flex-col gap-3">
          <div className="grid gap-3 sm:grid-cols-2">
            <SuggestField
              label="action"
              listId="iam-action-hints"
              value={action}
              onChange={setAction}
              options={ACTION_HINTS}
              placeholder="org:ListMembers"
              hint="자유 입력입니다. 목록에 없는 action 도 그대로 평가됩니다."
            />
            <SuggestField
              label="resource"
              listId="iam-resource-hints"
              value={resource}
              onChange={setResource}
              options={resourceHints(isMachine ? (tokens(boundOrgs)[0] ?? scimOrg) : orgId)}
              placeholder="trn:taspa:org:{org}:member/*"
              hint="제안 값의 org 세그먼트는 위에서 고른 조직으로 채워집니다."
            />
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <Button onClick={submit} disabled={busy || invalid}>
              {busy ? "판정 중" : "판정"}
            </Button>
            {invalid && (
              <p className="text-xs text-muted-foreground">
                {needsUser && userId.trim().length === 0 ? "사용자 UUID · " : ""}
                action · resource 를 채우세요.
              </p>
            )}
          </div>

          {error && <ErrorNotice message={error} />}
          {result && <SimulationResult result={result} />}
        </div>
      </Section>
    </div>
  );
}

function SimulationResult({ result }: { result: SimulateResponse }) {
  const allowed = result.effect === "ALLOW";
  return (
    <div
      className={cn(
        "flex flex-col gap-2 rounded-lg border px-4 py-3",
        allowed
          ? "border-[color:var(--taspa-success)]/40 bg-[color:var(--taspa-success-soft)]"
          : "border-[color:var(--taspa-danger)]/40 bg-[color:var(--taspa-danger-soft)]",
      )}
      role="status"
    >
      <p
        className={cn(
          "text-lg font-semibold",
          allowed ? "text-[color:var(--taspa-success)]" : "text-[color:var(--taspa-danger)]",
        )}
      >
        {allowed ? "허용 — ALLOW" : "거부 — DENY"}
      </p>
      <p className="text-sm text-foreground">{result.reason}</p>
      <p className="text-xs text-muted-foreground">
        매치된 Sid:{" "}
        {result.matchedSid ? (
          <span className="font-mono">{result.matchedSid}</span>
        ) : (
          "없음 (암묵적 거부 — 어떤 Statement 도 적용되지 않았습니다)"
        )}
      </p>
    </div>
  );
}
