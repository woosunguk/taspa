"use client";

import { useState } from "react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { api } from "@/lib/api";
import { useApi, useMutation } from "@/lib/useApi";
import { ConfirmButton, CopyValue, Field, Section } from "../../_components/console-ui";
import { orgPath, useOrg } from "../../_lib/org-context";
import { formatDateTime } from "../../_lib/labels";
import type { OrgDomain, OrgDomainSettings } from "../../_lib/types";

/**
 * 도메인 탭 — 회사 이메일 도메인을 등록·검증하고 자동 가입을 켠다.
 *
 * 자동 가입은 **검증된 도메인 + 조직별 opt-in** 둘 다 켜져야 동작한다(서버 정책). 화면도 그 순서를 그대로
 * 안내한다 — 토글만 켜고 도메인이 미검증이면 아무 일도 일어나지 않는데, 그 이유를 화면에서 알 수 있어야 한다.
 */
export default function DomainsPage() {
  const { orgId } = useOrg();
  const settings = useApi<OrgDomainSettings>(orgPath(orgId, "/domains"), [orgId]);
  const [domain, setDomain] = useState("");

  const toggle = useMutation(async (enabled: boolean) =>
    api.put<{ autoJoinEnabled: boolean }>(orgPath(orgId, "/auto-join"), {
      enabled,
    }),
  );

  const register = useMutation(async () =>
    api.post<OrgDomain>(orgPath(orgId, "/domains"), { domain: domain.trim() }),
  );

  const verify = useMutation(async (target: OrgDomain) =>
    api.post<OrgDomain>(orgPath(orgId, `/domains/${encodeURIComponent(target.id)}/verify`)),
  );

  const remove = useMutation(async (target: OrgDomain) => {
    await api.delete<void>(orgPath(orgId, `/domains/${encodeURIComponent(target.id)}`));
    return true;
  });

  const domains = settings.data?.domains ?? [];
  const verifiedCount = domains.filter((item) => item.verified).length;

  return (
    <div className="flex flex-col gap-5">
      <Section
        title="이메일 도메인 자동 가입"
        description="회사 이메일로 가입·인증한 사용자를 이 조직에 자동으로 소속시킵니다. 자동 소속되는 역할은 항상 ‘구성원’입니다."
      >
        {settings.error && <ErrorNotice message={settings.error} onRetry={settings.reload} />}
        {toggle.error && <ErrorNotice message={toggle.error} onDismiss={toggle.clearError} />}
        {settings.loading && <RowsSkeleton rows={2} />}

        {settings.data && (
          <div className="flex flex-wrap items-center gap-3">
            <Switch
              id="auto-join"
              checked={settings.data.autoJoinEnabled}
              disabled={toggle.busy}
              onCheckedChange={async (next) => {
                const done = await toggle.mutate(next);
                if (done) {
                  toast.success(next ? "자동 가입을 켰습니다" : "자동 가입을 껐습니다");
                  settings.reload();
                }
              }}
            />
            <Label htmlFor="auto-join">자동 가입 사용</Label>
            <span className="text-sm text-muted-foreground">
              {settings.data.autoJoinEnabled
                ? verifiedCount > 0
                  ? `검증된 도메인 ${verifiedCount}개에 적용 중입니다.`
                  : "켜져 있지만 검증된 도메인이 없어 아직 아무도 자동 가입되지 않습니다."
                : "꺼져 있습니다. 도메인을 검증해도 자동 가입은 일어나지 않습니다."}
            </span>
          </div>
        )}
      </Section>

      <Section
        title="도메인"
        description="등록 후 DNS 에 TXT 레코드를 추가하고 ‘검증’을 누르면 소유가 확인됩니다. 공용 메일 도메인(gmail.com 등)은 등록할 수 없습니다."
      >
        {register.error && <ErrorNotice message={register.error} onDismiss={register.clearError} />}
        {verify.error && <ErrorNotice message={verify.error} onDismiss={verify.clearError} />}
        {remove.error && <ErrorNotice message={remove.error} onDismiss={remove.clearError} />}

        <div className="grid items-end gap-3 sm:grid-cols-[1fr_auto]">
          <Field label="도메인" htmlFor="domain-input">
            <Input
              id="domain-input"
              value={domain}
              onChange={(event) => setDomain(event.target.value)}
              placeholder="example.com"
            />
          </Field>
          <Button
            disabled={register.busy || domain.trim().length === 0}
            onClick={async () => {
              const created = await register.mutate();
              if (created) {
                toast.success(`${created.domain} 을(를) 등록했습니다. DNS TXT 레코드를 추가하세요.`);
                setDomain("");
                settings.reload();
              }
            }}
          >
            도메인 등록
          </Button>
        </div>

        {!settings.loading && domains.length === 0 && (
          <EmptyState
            title="등록된 도메인이 없습니다"
            description="회사 메일 도메인을 등록하면 신규 입사자가 스스로 조직에 소속될 수 있습니다."
          />
        )}

        <div className="flex flex-col gap-3">
          {domains.map((item) => (
            <div key={item.id} className="rounded-lg border border-border p-4">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-medium text-foreground">{item.domain}</span>
                  {item.verified ? (
                    <Badge variant="secondary" className="text-[color:var(--taspa-success)]">
                      검증됨
                    </Badge>
                  ) : (
                    <Badge variant="outline">검증 대기</Badge>
                  )}
                  <span className="text-xs text-muted-foreground">
                    {item.verified
                      ? `검증 ${formatDateTime(item.verifiedAt)}`
                      : `등록 ${formatDateTime(item.createdAt)}`}
                  </span>
                </div>
                <div className="flex gap-1">
                  {!item.verified && (
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={verify.busy}
                      onClick={async () => {
                        const result = await verify.mutate(item);
                        if (result) {
                          if (result.verified) toast.success(`${result.domain} 소유를 확인했습니다`);
                          else
                            toast.error("아직 TXT 레코드를 찾지 못했습니다. 전파에 시간이 걸릴 수 있습니다.");
                          settings.reload();
                        }
                      }}
                    >
                      {verify.busy ? "확인 중" : "검증"}
                    </Button>
                  )}
                  <ConfirmButton
                    variant="ghost"
                    disabled={remove.busy}
                    confirmLabel="삭제 확정"
                    onConfirm={async () => {
                      const done = await remove.mutate(item);
                      if (done) {
                        toast.success("도메인을 삭제했습니다");
                        settings.reload();
                      }
                    }}
                  >
                    삭제
                  </ConfirmButton>
                </div>
              </div>

              {!item.verified && (
                <div className="mt-3 flex flex-col gap-2">
                  <p className="text-sm text-muted-foreground">
                    DNS 에 아래 TXT 레코드를 추가한 뒤 ‘검증’을 누르세요. 전파에 수 분~수 시간이 걸릴 수
                    있습니다.
                  </p>
                  <div className="grid gap-2 sm:grid-cols-2">
                    <div>
                      <p className="mb-1 text-xs text-muted-foreground">레코드 이름</p>
                      <CopyValue value={item.txtRecordName} label="레코드 이름" />
                    </div>
                    <div>
                      <p className="mb-1 text-xs text-muted-foreground">레코드 값</p>
                      <CopyValue value={item.txtRecordValue} label="레코드 값" />
                    </div>
                  </div>
                </div>
              )}
            </div>
          ))}
        </div>
      </Section>
    </div>
  );
}
