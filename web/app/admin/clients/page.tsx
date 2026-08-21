"use client";

import { useState } from "react";
import { toast } from "sonner";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api } from "@/lib/api";
import { useApi } from "@/lib/useApi";
import {
  CheckboxField,
  ConfirmDialog,
  CopyBox,
  Modal,
  PageHeader,
  Section,
  TableScroll,
  TextAreaField,
  TextField,
  formatDateTime,
  linesToList,
  listToLines,
} from "../_components/kit";
import { adminErrorText } from "../_lib/errors";
import type { AdminClientView, ClientSecretResponse } from "../_lib/types";

/**
 * OAuth2 클라이언트 관리.
 *
 * ★시크릿은 등록·재발급 응답에서 **단 한 번만** 내려온다(저장은 bcrypt 해시라 서버도 원문을 모른다).
 * 그래서 발급 직후 화면을 닫으면 복구 경로는 재발급뿐이다 — 대화상자에서 그 사실을 분명히 말한다.
 */
const GRANT_TYPES = [
  {
    value: "authorization_code",
    label: "authorization_code — 사용자 로그인(RP)",
  },
  { value: "refresh_token", label: "refresh_token — 액세스 토큰 갱신" },
  { value: "client_credentials", label: "client_credentials — 서버 간(M2M)" },
  {
    value: "urn:ietf:params:oauth:grant-type:device_code",
    label: "device_code — TV·CLI 등 입력 제한 기기",
  },
];

const ROLE_NAMES_HINT =
  "이 서비스가 인가에 쓸 조직 커스텀 역할 이름을 한 줄에 하나씩. 실제로 실리는 값은 여기 적은 이름과 사용자가 실제로 가진 역할의 교집합입니다. 비워 두면 org.roles scope 가 있어도 roles 클레임을 보내지 않습니다.";

const SCOPE_HINT =
  "서버 화이트리스트(taspa.oauth.allowed-scopes)에 있는 값만 저장됩니다. 예: openid, profile, email, org.read, org.scim, meal.redeem, meal.consumption.read, meal.consumption.write, meal.forecast.read";

export default function AdminClientsPage() {
  const clients = useApi<AdminClientView[]>("/api/admin/clients");
  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<AdminClientView | null>(null);
  const [deleting, setDeleting] = useState<AdminClientView | null>(null);
  const [regenerating, setRegenerating] = useState<AdminClientView | null>(null);
  const [secret, setSecret] = useState<ClientSecretResponse | null>(null);
  const [busy, setBusy] = useState(false);

  async function remove() {
    if (!deleting) return;
    setBusy(true);
    try {
      await api.delete(`/api/admin/clients/${deleting.id}`);
      toast.success(`클라이언트 '${deleting.clientId}' 를 삭제했습니다`);
      setDeleting(null);
      clients.reload();
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return;
      toast.error(adminErrorText(cause));
    } finally {
      setBusy(false);
    }
  }

  async function regenerate() {
    if (!regenerating) return;
    setBusy(true);
    try {
      const response = await api.post<ClientSecretResponse>(`/api/admin/clients/${regenerating.id}/secret`);
      setRegenerating(null);
      setSecret(response);
      clients.reload();
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return;
      toast.error(adminErrorText(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="OAuth 클라이언트"
        description="taspa 를 IdP 로 쓰는 앱과 서버 간 통신용 클라이언트를 등록합니다. PKCE 와 동의 화면은 항상 강제됩니다."
        actions={<Button onClick={() => setCreateOpen(true)}>클라이언트 등록</Button>}
      />

      <Section
        title="등록된 클라이언트"
        description={clients.data ? `${clients.data.length}개` : undefined}
        actions={
          <Button variant="outline" size="sm" onClick={clients.reload}>
            새로고침
          </Button>
        }
      >
        {clients.loading ? (
          <RowsSkeleton rows={5} />
        ) : clients.error ? (
          <ErrorNotice message={clients.error} onRetry={clients.reload} />
        ) : !clients.data || clients.data.length === 0 ? (
          <EmptyState
            title="등록된 클라이언트가 없습니다"
            description="로그인을 연동할 앱을 등록하면 client_id 와 시크릿이 발급됩니다."
          />
        ) : (
          <TableScroll>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>이름</TableHead>
                  <TableHead>client_id</TableHead>
                  <TableHead>유형</TableHead>
                  <TableHead>Grant</TableHead>
                  <TableHead>Scope</TableHead>
                  <TableHead className="w-40">등록</TableHead>
                  <TableHead className="w-56" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {clients.data.map((client) => (
                  <TableRow key={client.id}>
                    <TableCell className="font-medium whitespace-nowrap">{client.clientName}</TableCell>
                    <TableCell className="font-mono text-xs whitespace-nowrap">{client.clientId}</TableCell>
                    <TableCell>
                      {client.publicClient ? (
                        <Badge variant="outline">공개</Badge>
                      ) : (
                        <Badge variant="secondary" className="border-border">
                          기밀
                        </Badge>
                      )}
                    </TableCell>
                    <TableCell
                      className="max-w-48 truncate text-xs text-muted-foreground"
                      title={client.grantTypes.join(", ")}
                    >
                      {client.grantTypes.join(", ")}
                    </TableCell>
                    <TableCell
                      className="max-w-48 truncate text-xs text-muted-foreground"
                      title={client.scopes.join(", ")}
                    >
                      {client.scopes.join(", ") || "—"}
                    </TableCell>
                    <TableCell className="tabular whitespace-nowrap text-muted-foreground">
                      {formatDateTime(client.clientIdIssuedAt)}
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center justify-end gap-2">
                        <Button variant="outline" size="sm" onClick={() => setEditing(client)}>
                          수정
                        </Button>
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={client.publicClient}
                          onClick={() => setRegenerating(client)}
                        >
                          시크릿 재발급
                        </Button>
                        <Button variant="destructive" size="sm" onClick={() => setDeleting(client)}>
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

      <CreateClientModal
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreated={(response) => {
          clients.reload();
          setSecret(response);
        }}
      />

      {editing && (
        <EditClientModal
          key={editing.id}
          client={editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            clients.reload();
            setEditing(null);
          }}
        />
      )}

      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title="클라이언트를 삭제할까요?"
        message={`'${deleting?.clientName ?? ""}'(${deleting?.clientId ?? ""}) 를 삭제하면 이 클라이언트의 인가·동의 기록도 함께 정리되고, 연동된 앱의 로그인이 즉시 중단됩니다. 되돌릴 수 없습니다.`}
        confirmLabel="삭제"
        busy={busy}
        onConfirm={remove}
      />

      <ConfirmDialog
        open={regenerating !== null}
        onOpenChange={(open) => !open && setRegenerating(null)}
        title="시크릿을 재발급할까요?"
        message={`'${regenerating?.clientName ?? ""}' 의 기존 시크릿은 즉시 무효가 되어 연동이 끊깁니다. 새 시크릿은 이 화면에서 한 번만 볼 수 있으니 바로 배포처에 반영하세요.`}
        confirmLabel="재발급"
        busy={busy}
        onConfirm={regenerate}
      />

      <SecretModal response={secret} onClose={() => setSecret(null)} />
    </div>
  );
}

/* ── 시크릿 1회 노출 ──────────────────────────────────────────────────── */

function SecretModal({ response, onClose }: { response: ClientSecretResponse | null; onClose: () => void }) {
  const [acknowledged, setAcknowledged] = useState(false);

  return (
    <Modal
      open={response !== null}
      onOpenChange={(open) => {
        if (!open) {
          setAcknowledged(false);
          onClose();
        }
      }}
      title="클라이언트 시크릿"
      description="이 값은 지금 이 화면에서만 볼 수 있습니다. 서버는 해시만 저장하므로 다시 조회할 수 없습니다."
      // 시크릿이 실린 화면은 **저장 확인 전까지 닫히지 않는다**(ESC·바깥 클릭·X 포함).
      locked={Boolean(response?.clientSecret) && !acknowledged}
      footer={
        <Button
          disabled={Boolean(response?.clientSecret) && !acknowledged}
          onClick={() => {
            setAcknowledged(false);
            onClose();
          }}
        >
          닫기
        </Button>
      }
    >
      {response && (
        <>
          <CopyBox label="client_id" value={response.client.clientId} />
          {response.clientSecret ? (
            <>
              <CopyBox label="client_secret" value={response.clientSecret} />
              <p className="rounded-lg border border-[color:var(--taspa-warning)]/40 bg-[color:var(--taspa-warning-soft)] px-3 py-2 text-sm text-[color:var(--taspa-warning)]">
                지금 안전한 곳(비밀 관리 시스템)에 옮겨 두세요. 창을 닫으면 다시 볼 수 없고, 잃어버리면
                재발급만 가능합니다.
              </p>
              <CheckboxField
                label="시크릿을 안전한 곳에 저장했습니다"
                checked={acknowledged}
                onChange={setAcknowledged}
              />
            </>
          ) : (
            <p className="text-sm text-muted-foreground">
              공개 클라이언트라 시크릿이 없습니다. PKCE 로 인가 코드를 보호합니다.
            </p>
          )}
        </>
      )}
    </Modal>
  );
}

/* ── 등록 ─────────────────────────────────────────────────────────────── */

function CreateClientModal({
  open,
  onOpenChange,
  onCreated,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCreated: (response: ClientSecretResponse) => void;
}) {
  const [clientId, setClientId] = useState("");
  const [clientName, setClientName] = useState("");
  const [publicClient, setPublicClient] = useState(false);
  const [grants, setGrants] = useState<string[]>(["authorization_code", "refresh_token"]);
  const [redirectUris, setRedirectUris] = useState("");
  const [postLogoutUris, setPostLogoutUris] = useState("");
  const [scopes, setScopes] = useState("openid\nprofile\nemail");
  const [orgId, setOrgId] = useState("");
  const [merchantId, setMerchantId] = useState("");
  const [roleNames, setRoleNames] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  const needsRedirect = grants.includes("authorization_code");

  function toggleGrant(value: string) {
    setGrants((current) =>
      current.includes(value) ? current.filter((item) => item !== value) : [...current, value],
    );
  }

  async function submit() {
    setBusy(true);
    setError(undefined);
    try {
      const response = await api.post<ClientSecretResponse>("/api/admin/clients", {
        clientId: clientId.trim(),
        clientName: clientName.trim(),
        redirectUris: linesToList(redirectUris),
        postLogoutRedirectUris: linesToList(postLogoutUris),
        scopes: linesToList(scopes),
        grantTypes: grants,
        publicClient,
        orgId: orgId.trim() || null,
        merchantId: merchantId.trim() || null,
        roleNames: linesToList(roleNames),
      });
      onCreated(response);
      onOpenChange(false);
      setClientId("");
      setClientName("");
      setRedirectUris("");
      setPostLogoutUris("");
      setOrgId("");
      setMerchantId("");
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return;
      setError(adminErrorText(cause));
    } finally {
      setBusy(false);
    }
  }

  const invalid =
    clientId.trim().length === 0 ||
    clientName.trim().length === 0 ||
    grants.length === 0 ||
    (needsRedirect && linesToList(redirectUris).length === 0);

  return (
    <Modal
      open={open}
      onOpenChange={onOpenChange}
      wide
      title="클라이언트 등록"
      description="client_id 와 유형(공개/기밀), grant type 은 등록 후 변경할 수 없습니다."
      footer={
        <>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>
            취소
          </Button>
          <Button onClick={submit} disabled={busy || invalid}>
            {busy ? "등록 중" : "등록"}
          </Button>
        </>
      }
    >
      {error && <ErrorNotice message={error} />}
      <div className="grid gap-3 sm:grid-cols-2">
        <TextField
          label="client_id"
          value={clientId}
          onChange={setClientId}
          placeholder="my-app"
          hint="변경 불가"
        />
        <TextField label="표시 이름" value={clientName} onChange={setClientName} placeholder="사내 포털" />
      </div>

      <CheckboxField
        label="공개 클라이언트 (SPA·모바일)"
        checked={publicClient}
        onChange={setPublicClient}
        hint="공개 클라이언트는 시크릿이 없고 client_credentials 를 쓸 수 없습니다. 시크릿을 안전히 보관할 수 있는 서버 앱이면 끄세요."
      />

      <div className="flex flex-col gap-1.5">
        <p className="text-sm font-medium">Grant type</p>
        {GRANT_TYPES.map((grant) => (
          <CheckboxField
            key={grant.value}
            label={grant.label}
            checked={grants.includes(grant.value)}
            onChange={() => toggleGrant(grant.value)}
            disabled={publicClient && grant.value === "client_credentials"}
          />
        ))}
      </div>

      <TextAreaField
        label="Redirect URI"
        value={redirectUris}
        onChange={setRedirectUris}
        rows={3}
        mono
        hint={
          needsRedirect
            ? "한 줄에 하나씩. authorization_code 를 쓰면 최소 1개가 필요하며 http/https 만 허용됩니다."
            : "authorization_code 를 쓰지 않으면 비워도 됩니다."
        }
        placeholder={"http://localhost:8080/login/oauth2/code/taspa"}
      />
      <TextAreaField
        label="로그아웃 후 Redirect URI"
        value={postLogoutUris}
        onChange={setPostLogoutUris}
        rows={2}
        mono
        hint="선택 사항. 한 줄에 하나씩."
      />
      <TextAreaField label="Scope" value={scopes} onChange={setScopes} rows={3} mono hint={SCOPE_HINT} />
      <TextAreaField
        label="선언 역할 이름"
        value={roleNames}
        onChange={setRoleNames}
        rows={2}
        hint={ROLE_NAMES_HINT}
      />

      <div className="grid gap-3 sm:grid-cols-2">
        <TextField
          label="결속 조직 UUID (선택)"
          value={orgId}
          onChange={setOrgId}
          hint="M2M 토큰에 org_id 클레임을 실어 조직 결속 쓰기를 허용합니다."
        />
        <TextField
          label="결속 가맹 UUID (선택)"
          value={merchantId}
          onChange={setMerchantId}
          hint="POS 단말용. 토큰에 merchant_id 클레임이 실려 식권 승인에 도달합니다."
        />
      </div>
    </Modal>
  );
}

/* ── 수정 ─────────────────────────────────────────────────────────────── */

function EditClientModal({
  client,
  onClose,
  onSaved,
}: {
  client: AdminClientView;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [clientName, setClientName] = useState(client.clientName);
  const [redirectUris, setRedirectUris] = useState(listToLines(client.redirectUris));
  const [postLogoutUris, setPostLogoutUris] = useState(listToLines(client.postLogoutRedirectUris));
  const [scopes, setScopes] = useState(listToLines(client.scopes));
  const [roleNames, setRoleNames] = useState(listToLines(client.roleNames));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  async function submit() {
    setBusy(true);
    setError(undefined);
    try {
      await api.put<AdminClientView>(`/api/admin/clients/${client.id}`, {
        clientName: clientName.trim(),
        redirectUris: linesToList(redirectUris),
        postLogoutRedirectUris: linesToList(postLogoutUris),
        scopes: linesToList(scopes),
        roleNames: linesToList(roleNames),
      });
      toast.success("클라이언트를 저장했습니다");
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
      title={`클라이언트 수정 — ${client.clientId}`}
      description="client_id·유형·grant type 은 변경할 수 없습니다."
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={busy}>
            취소
          </Button>
          <Button onClick={submit} disabled={busy || clientName.trim().length === 0}>
            {busy ? "저장 중" : "저장"}
          </Button>
        </>
      }
    >
      {error && <ErrorNotice message={error} />}
      <TextField label="표시 이름" value={clientName} onChange={setClientName} />
      <TextAreaField
        label="Redirect URI"
        value={redirectUris}
        onChange={setRedirectUris}
        rows={3}
        mono
        hint={
          client.grantTypes.includes("authorization_code")
            ? "authorization_code 클라이언트라 최소 1개가 필요합니다."
            : "한 줄에 하나씩."
        }
      />
      <TextAreaField
        label="로그아웃 후 Redirect URI"
        value={postLogoutUris}
        onChange={setPostLogoutUris}
        rows={2}
        mono
      />
      <TextAreaField label="Scope" value={scopes} onChange={setScopes} rows={3} mono hint={SCOPE_HINT} />
      <TextAreaField
        label="선언 역할 이름"
        value={roleNames}
        onChange={setRoleNames}
        rows={2}
        hint={ROLE_NAMES_HINT}
      />
    </Modal>
  );
}
