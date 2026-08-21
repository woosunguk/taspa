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
  SelectField,
  TableScroll,
  TextAreaField,
  TextField,
  linesToList,
  listToLines,
} from "../_components/kit";
import { adminErrorText } from "../_lib/errors";
import type { SsoConnectionView } from "../_lib/types";

/**
 * 기업 SSO 커넥션(조직 IdP).
 *
 * `registrationId` 와 프로토콜은 등록 후 바꿀 수 없다 — 콜백 경로(`/login/oauth2/code/{id}`,
 * `/login/saml2/sso/{id}`)가 그 값에 묶여 있어서, 바꾸면 상대 IdP 에 등록한 URL 이 전부 깨진다.
 * OIDC 시크릿은 서버가 암호문으로만 보관하므로 조회되지 않는다 — 빈 값이면 기존 값이 유지된다.
 */
interface Draft {
  id: string | null;
  registrationId: string;
  displayName: string;
  protocol: string;
  enabled: boolean;
  enforced: boolean;
  trustIdpMfa: boolean;
  domains: string;
  oidcIssuer: string;
  oidcAuthorizationUri: string;
  oidcTokenUri: string;
  oidcJwksUri: string;
  oidcUserInfoUri: string;
  oidcUserNameAttr: string;
  oidcClientId: string;
  oidcClientSecret: string;
  oidcScopes: string;
  samlIdpEntityId: string;
  samlSsoUrl: string;
  samlVerificationCert: string;
  samlWantAuthnSigned: boolean;
  samlEmailAttr: string;
  samlNameAttr: string;
}

const EMPTY: Draft = {
  id: null,
  registrationId: "",
  displayName: "",
  protocol: "OIDC",
  enabled: true,
  enforced: true,
  trustIdpMfa: false,
  domains: "",
  oidcIssuer: "",
  oidcAuthorizationUri: "",
  oidcTokenUri: "",
  oidcJwksUri: "",
  oidcUserInfoUri: "",
  oidcUserNameAttr: "",
  oidcClientId: "",
  oidcClientSecret: "",
  oidcScopes: "",
  samlIdpEntityId: "",
  samlSsoUrl: "",
  samlVerificationCert: "",
  samlWantAuthnSigned: false,
  samlEmailAttr: "",
  samlNameAttr: "",
};

function toDraft(connection: SsoConnectionView): Draft {
  return {
    id: connection.id,
    registrationId: connection.registrationId,
    displayName: connection.displayName,
    protocol: connection.protocol,
    enabled: connection.enabled,
    enforced: connection.enforced,
    trustIdpMfa: connection.trustIdpMfa,
    domains: listToLines(connection.domains.map((domain) => domain.domain)),
    oidcIssuer: connection.oidcIssuer ?? "",
    oidcAuthorizationUri: connection.oidcAuthorizationUri ?? "",
    oidcTokenUri: connection.oidcTokenUri ?? "",
    oidcJwksUri: connection.oidcJwksUri ?? "",
    oidcUserInfoUri: connection.oidcUserInfoUri ?? "",
    oidcUserNameAttr: connection.oidcUserNameAttr ?? "",
    oidcClientId: connection.oidcClientId ?? "",
    oidcClientSecret: "",
    oidcScopes: connection.oidcScopes ?? "",
    samlIdpEntityId: connection.samlIdpEntityId ?? "",
    samlSsoUrl: connection.samlSsoUrl ?? "",
    samlVerificationCert: connection.samlVerificationCert ?? "",
    samlWantAuthnSigned: connection.samlWantAuthnSigned,
    samlEmailAttr: connection.samlEmailAttr ?? "",
    samlNameAttr: connection.samlNameAttr ?? "",
  };
}

function toRequest(draft: Draft) {
  const blank = (value: string) => (value.trim() ? value.trim() : null);
  return {
    registrationId: draft.registrationId.trim(),
    displayName: draft.displayName.trim(),
    protocol: draft.protocol,
    enabled: draft.enabled,
    enforced: draft.enforced,
    trustIdpMfa: draft.trustIdpMfa,
    domains: linesToList(draft.domains),
    oidcIssuer: blank(draft.oidcIssuer),
    oidcAuthorizationUri: blank(draft.oidcAuthorizationUri),
    oidcTokenUri: blank(draft.oidcTokenUri),
    oidcJwksUri: blank(draft.oidcJwksUri),
    oidcUserInfoUri: blank(draft.oidcUserInfoUri),
    oidcUserNameAttr: blank(draft.oidcUserNameAttr),
    oidcClientId: blank(draft.oidcClientId),
    // 빈 값이면 서버가 기존 시크릿을 유지한다(수정 화면에서 시크릿을 다시 입력하지 않아도 되게).
    oidcClientSecret: blank(draft.oidcClientSecret),
    oidcScopes: blank(draft.oidcScopes),
    samlIdpEntityId: blank(draft.samlIdpEntityId),
    samlSsoUrl: blank(draft.samlSsoUrl),
    samlVerificationCert: blank(draft.samlVerificationCert),
    samlWantAuthnSigned: draft.samlWantAuthnSigned,
    samlEmailAttr: blank(draft.samlEmailAttr),
    samlNameAttr: blank(draft.samlNameAttr),
  };
}

export default function AdminSsoPage() {
  const connections = useApi<SsoConnectionView[]>("/api/admin/sso");
  const [draft, setDraft] = useState<Draft | null>(null);
  const [deleting, setDeleting] = useState<SsoConnectionView | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  async function save() {
    if (!draft) return;
    setBusy(true);
    setError(undefined);
    try {
      const body = toRequest(draft);
      if (draft.id) await api.put<SsoConnectionView>(`/api/admin/sso/${draft.id}`, body);
      else await api.post<SsoConnectionView>("/api/admin/sso", body);
      toast.success(draft.id ? "커넥션을 저장했습니다" : "커넥션을 만들었습니다");
      setDraft(null);
      connections.reload();
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return;
      setError(adminErrorText(cause));
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!deleting) return;
    setBusy(true);
    try {
      await api.delete(`/api/admin/sso/${deleting.id}`);
      toast.success("커넥션을 삭제했습니다");
      setDeleting(null);
      connections.reload();
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return;
      toast.error(adminErrorText(cause));
    } finally {
      setBusy(false);
    }
  }

  async function setDomainVerified(connection: SsoConnectionView, domain: string, verified: boolean) {
    setBusy(true);
    try {
      await api.post(`/api/admin/sso/${connection.id}/domain/verify`, {
        domain,
        verified,
      });
      toast.success(
        verified ? `${domain} 도메인을 검증 처리했습니다` : `${domain} 도메인 검증을 해제했습니다`,
      );
      connections.reload();
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
        title="기업 SSO"
        description="조직의 IdP(OIDC·SAML)를 연결합니다. 커넥션을 조직에 결속해야 로그인 성공 시 JIT 멤버십이 생성됩니다 — 결속은 조직 화면에서 합니다."
        actions={<Button onClick={() => setDraft(EMPTY)}>커넥션 만들기</Button>}
      />

      <Section
        title="커넥션"
        description={connections.data ? `${connections.data.length}개` : undefined}
        actions={
          <Button variant="outline" size="sm" onClick={connections.reload}>
            새로고침
          </Button>
        }
      >
        {connections.loading ? (
          <RowsSkeleton rows={4} />
        ) : connections.error ? (
          <ErrorNotice message={connections.error} onRetry={connections.reload} />
        ) : !connections.data || connections.data.length === 0 ? (
          <EmptyState
            title="등록된 커넥션이 없습니다"
            description="고객사 IdP 정보를 받아 OIDC 또는 SAML 커넥션을 만드세요."
          />
        ) : (
          <TableScroll>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>이름</TableHead>
                  <TableHead>registrationId</TableHead>
                  <TableHead>프로토콜</TableHead>
                  <TableHead>상태</TableHead>
                  <TableHead>도메인</TableHead>
                  <TableHead>조직 결속</TableHead>
                  <TableHead className="w-52" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {connections.data.map((connection) => (
                  <TableRow key={connection.id}>
                    <TableCell className="font-medium whitespace-nowrap">{connection.displayName}</TableCell>
                    <TableCell className="font-mono text-xs whitespace-nowrap">
                      {connection.registrationId}
                    </TableCell>
                    <TableCell>{connection.protocol}</TableCell>
                    <TableCell className="whitespace-nowrap">
                      <div className="flex flex-wrap items-center gap-1">
                        {connection.enabled ? (
                          <Badge variant="secondary" className="border-border">
                            활성
                          </Badge>
                        ) : (
                          <Badge variant="outline">비활성</Badge>
                        )}
                        {connection.enforced && <Badge variant="outline">강제</Badge>}
                        {connection.trustIdpMfa && <Badge variant="outline">IdP MFA 신뢰</Badge>}
                      </div>
                    </TableCell>
                    <TableCell className="text-xs whitespace-nowrap text-muted-foreground">
                      {connection.domains.length === 0
                        ? "—"
                        : connection.domains
                            .map((domain) => `${domain.domain}${domain.verified ? "" : "(미검증)"}`)
                            .join(", ")}
                    </TableCell>
                    <TableCell className="text-xs whitespace-nowrap text-muted-foreground">
                      {connection.orgId ? `${connection.orgId.slice(0, 8)}…` : "결속 없음"}
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center justify-end gap-2">
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => setExpanded(expanded === connection.id ? null : connection.id)}
                        >
                          {expanded === connection.id ? "접기" : "SP 정보"}
                        </Button>
                        <Button variant="outline" size="sm" onClick={() => setDraft(toDraft(connection))}>
                          수정
                        </Button>
                        <Button variant="destructive" size="sm" onClick={() => setDeleting(connection)}>
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

      {expanded && connections.data && (
        <ConnectionDetail
          connection={connections.data.find((connection) => connection.id === expanded)!}
          busy={busy}
          onVerify={setDomainVerified}
          onClose={() => setExpanded(null)}
        />
      )}

      <Modal
        open={draft !== null}
        onOpenChange={(open) => {
          if (!open) {
            setDraft(null);
            setError(undefined);
          }
        }}
        wide
        title={draft?.id ? `커넥션 수정 — ${draft.registrationId}` : "커넥션 만들기"}
        description="registrationId 와 프로토콜은 만든 뒤 변경할 수 없습니다(콜백 URL 이 그 값에 묶입니다)."
        footer={
          <>
            <Button variant="outline" onClick={() => setDraft(null)} disabled={busy}>
              취소
            </Button>
            <Button
              onClick={save}
              disabled={
                busy ||
                !draft ||
                draft.registrationId.trim().length === 0 ||
                draft.displayName.trim().length === 0
              }
            >
              {busy ? "저장 중" : "저장"}
            </Button>
          </>
        }
      >
        {error && <ErrorNotice message={error} />}
        {draft && <ConnectionForm draft={draft} setDraft={setDraft} />}
      </Modal>

      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title="커넥션을 삭제할까요?"
        message={`'${deleting?.displayName ?? ""}' 커넥션을 삭제하면 이 IdP 를 통한 로그인이 즉시 중단됩니다. 이미 연결된 계정의 연합 신원은 남지만 커넥션 참조는 해제됩니다.`}
        confirmLabel="삭제"
        busy={busy}
        onConfirm={remove}
      />
    </div>
  );
}

function ConnectionForm({ draft, setDraft }: { draft: Draft; setDraft: (draft: Draft) => void }) {
  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => setDraft({ ...draft, [key]: value });

  return (
    <>
      <div className="grid gap-3 sm:grid-cols-2">
        <TextField
          label="registrationId"
          value={draft.registrationId}
          onChange={(value) => set("registrationId", value)}
          disabled={draft.id !== null}
          hint="콜백 URL 에 들어가는 식별자입니다. 소문자·하이픈 권장."
          placeholder="acme-okta"
        />
        <TextField
          label="표시 이름"
          value={draft.displayName}
          onChange={(value) => set("displayName", value)}
          placeholder="ACME Okta"
        />
      </div>

      <SelectField
        label="프로토콜"
        value={draft.protocol}
        onChange={(value) => set("protocol", value)}
        disabled={draft.id !== null}
        options={[
          { value: "OIDC", label: "OIDC" },
          { value: "SAML", label: "SAML 2.0" },
        ]}
      />

      <CheckboxField
        label="활성"
        checked={draft.enabled}
        onChange={(value) => set("enabled", value)}
        hint="끄면 이 IdP 로그인이 차단됩니다."
      />
      <CheckboxField
        label="강제(enforced)"
        checked={draft.enforced}
        onChange={(value) => set("enforced", value)}
        hint="이 도메인 사용자는 비밀번호 대신 반드시 이 IdP 로 로그인하게 합니다."
      />
      <CheckboxField
        label="IdP 의 MFA 를 신뢰"
        checked={draft.trustIdpMfa}
        onChange={(value) => set("trustIdpMfa", value)}
        hint="켜면 taspa 자체 MFA 게이트를 건너뜁니다. 상대 IdP 가 실제로 MFA 를 강제할 때만 켜세요."
      />

      <TextAreaField
        label="도메인"
        value={draft.domains}
        onChange={(value) => set("domains", value)}
        rows={3}
        hint="한 줄에 하나씩. 등록 직후에는 미검증 상태이며, 검증 처리는 목록의 SP 정보 패널에서 합니다."
        placeholder={"acme.com"}
      />

      {draft.protocol === "OIDC" ? (
        <div className="flex flex-col gap-3 border-t border-border pt-3">
          <p className="text-sm font-semibold">OIDC 설정</p>
          <div className="grid gap-3 sm:grid-cols-2">
            <TextField
              label="Issuer"
              value={draft.oidcIssuer}
              onChange={(value) => set("oidcIssuer", value)}
              placeholder="https://acme.okta.com"
            />
            <TextField
              label="Client ID"
              value={draft.oidcClientId}
              onChange={(value) => set("oidcClientId", value)}
            />
            <TextField
              label="Authorization URI"
              value={draft.oidcAuthorizationUri}
              onChange={(value) => set("oidcAuthorizationUri", value)}
            />
            <TextField
              label="Token URI"
              value={draft.oidcTokenUri}
              onChange={(value) => set("oidcTokenUri", value)}
            />
            <TextField
              label="JWKS URI"
              value={draft.oidcJwksUri}
              onChange={(value) => set("oidcJwksUri", value)}
            />
            <TextField
              label="UserInfo URI"
              value={draft.oidcUserInfoUri}
              onChange={(value) => set("oidcUserInfoUri", value)}
            />
            <TextField
              label="사용자 이름 속성"
              value={draft.oidcUserNameAttr}
              onChange={(value) => set("oidcUserNameAttr", value)}
              hint="보통 sub 또는 email"
            />
            <TextField
              label="Scope"
              value={draft.oidcScopes}
              onChange={(value) => set("oidcScopes", value)}
              hint="공백 구분. 보통 openid profile email"
            />
          </div>
          <TextField
            label="Client Secret"
            type="password"
            value={draft.oidcClientSecret}
            onChange={(value) => set("oidcClientSecret", value)}
            hint={
              draft.id
                ? "비워 두면 기존 시크릿이 유지됩니다(서버는 암호문만 보관해 조회할 수 없습니다)."
                : "IdP 콘솔에서 발급한 값을 입력하세요."
            }
          />
        </div>
      ) : (
        <div className="flex flex-col gap-3 border-t border-border pt-3">
          <p className="text-sm font-semibold">SAML 설정</p>
          <div className="grid gap-3 sm:grid-cols-2">
            <TextField
              label="IdP Entity ID"
              value={draft.samlIdpEntityId}
              onChange={(value) => set("samlIdpEntityId", value)}
            />
            <TextField
              label="SSO URL"
              value={draft.samlSsoUrl}
              onChange={(value) => set("samlSsoUrl", value)}
            />
            <TextField
              label="이메일 속성"
              value={draft.samlEmailAttr}
              onChange={(value) => set("samlEmailAttr", value)}
            />
            <TextField
              label="이름 속성"
              value={draft.samlNameAttr}
              onChange={(value) => set("samlNameAttr", value)}
            />
          </div>
          <TextAreaField
            label="서명 검증 인증서"
            value={draft.samlVerificationCert}
            onChange={(value) => set("samlVerificationCert", value)}
            rows={5}
            mono
            hint="IdP 의 공개 인증서(PEM). 이 값이 없으면 SAML 응답 서명을 검증할 수 없습니다."
          />
          <CheckboxField
            label="AuthnRequest 서명 요구"
            checked={draft.samlWantAuthnSigned}
            onChange={(value) => set("samlWantAuthnSigned", value)}
          />
        </div>
      )}
    </>
  );
}

function ConnectionDetail({
  connection,
  busy,
  onVerify,
  onClose,
}: {
  connection: SsoConnectionView;
  busy: boolean;
  onVerify: (connection: SsoConnectionView, domain: string, verified: boolean) => void;
  onClose: () => void;
}) {
  return (
    <Section
      title={`SP 정보 — ${connection.displayName}`}
      description="상대 IdP 콘솔에 등록해야 하는 우리 쪽 값입니다."
      actions={
        <Button variant="ghost" size="sm" onClick={onClose}>
          닫기
        </Button>
      }
    >
      <div className="flex flex-col gap-3">
        {connection.protocol === "SAML" ? (
          <>
            <CopyBox label="SP Entity ID" value={connection.spEntityId} />
            <CopyBox label="ACS URL" value={connection.spAcsUrl} />
            <CopyBox label="SP 메타데이터 URL" value={connection.spMetadataUrl} />
          </>
        ) : (
          <>
            <CopyBox label="Redirect URI" value={connection.oidcRedirectUri} />
            <p className="text-xs text-muted-foreground">
              클라이언트 시크릿 등록 여부: {connection.hasOidcSecret ? "등록됨" : "없음 — 저장 시 입력하세요"}
            </p>
          </>
        )}

        <div className="border-t border-border pt-3">
          <h3 className="mb-2 text-sm font-semibold">도메인 검증</h3>
          {connection.domains.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              등록된 도메인이 없습니다. 커넥션 수정에서 추가하세요.
            </p>
          ) : (
            <div className="flex flex-col gap-2">
              {connection.domains.map((domain) => (
                <div
                  key={domain.domain}
                  className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border px-3 py-2"
                >
                  <span className="text-sm">
                    {domain.domain}{" "}
                    {domain.verified ? (
                      <Badge variant="secondary" className="ml-1 border-border">
                        검증됨
                      </Badge>
                    ) : (
                      <Badge variant="outline" className="ml-1">
                        미검증
                      </Badge>
                    )}
                  </span>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={busy}
                    onClick={() => onVerify(connection, domain.domain, !domain.verified)}
                  >
                    {domain.verified ? "검증 해제" : "검증 처리"}
                  </Button>
                </div>
              ))}
            </div>
          )}
          <p className="mt-2 text-xs text-muted-foreground">
            검증된 도메인만 강제(enforced) 라우팅에 쓰입니다. 소유 확인은 오프라인으로 하고 여기서 결과만
            반영합니다.
          </p>
        </div>
      </div>
    </Section>
  );
}
