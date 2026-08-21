"use client";

import { useState } from "react";
import { toast } from "sonner";
import { ApiError } from "@/lib/api";
import { useApi, useMutation } from "@/lib/useApi";
import { Button, ButtonLink } from "@/components/ui/button";
import { ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { FEDERATIONS, federationLinkUrl, unlinkFederation, type Federation } from "../_lib/endpoints";
import { formatDate, socialLabel } from "../_lib/format";
import { ConfirmDialog, FieldHint, InlineError, Section } from "./chrome";

/** 서버가 지원하는 소셜 공급자. 실제 사용 가능 여부는 서버 설정(환경변수)이 정한다 — 아래 주석 참고. */
const SOCIAL_PROVIDERS = ["google", "kakao", "naver"] as const;

/**
 * 소셜 계정 연결/해제.
 *
 * **연결 시작은 fetch 가 아니라 전체 이동**이어야 한다. `GET /account/federations/link/{provider}` 가
 * 세션에 연결 의도(SocialLinkIntent)를 심고 공급자 인가로 리다이렉트하며, 돌아올 때
 * `/account?linked=1` 또는 `/account?linkError=...` 로 이 화면에 결과를 전달한다(page.tsx 가 읽는다).
 *
 * **해제는 서버가 막을 수 있다**: 비밀번호도 패스키도 다른 소셜 연결도 없으면 계정이 잠기므로 409
 * (LAST_LOGIN_METHOD)로 거절된다. 그 문구는 서버가 로케일에 맞춰 주므로 그대로 보여준다.
 */
export function FederationsSection() {
  const list = useApi<Federation[]>(FEDERATIONS);
  const [unlinking, setUnlinking] = useState<Federation | null>(null);
  const [blocked, setBlocked] = useState<string | undefined>(undefined);

  const unlink = useMutation(async (provider: string) => {
    try {
      await unlinkFederation(provider);
      return true;
    } catch (cause) {
      // 409 = 마지막 로그인 수단. 대화상자를 닫고 섹션 본문에 남겨 사용자가 대안을 먼저 만들게 한다.
      if (cause instanceof ApiError && cause.status === 409) {
        setBlocked(cause.message);
        setUnlinking(null);
        return false;
      }
      throw cause;
    }
  });

  const linked = new Set((list.data ?? []).map((federation) => federation.provider));
  const linkable = SOCIAL_PROVIDERS.filter((provider) => !linked.has(provider));

  return (
    <>
      <Section title="소셜 계정 연결" description="연결한 계정으로도 taspa 에 로그인할 수 있습니다.">
        {list.loading ? (
          <RowsSkeleton rows={2} />
        ) : list.error ? (
          <ErrorNotice message={list.error} onRetry={list.reload} />
        ) : (
          <>
            {(list.data?.length ?? 0) > 0 && (
              <ul className="flex flex-col divide-y divide-border">
                {list.data?.map((federation) => (
                  <li key={federation.provider} className="flex flex-wrap items-center gap-3 py-3">
                    <div className="min-w-0 flex-1">
                      <p className="text-sm font-medium text-foreground">
                        {federation.providerLabel || socialLabel(federation.provider)}
                      </p>
                      <p className="truncate text-xs text-muted-foreground">
                        {federation.emailAtLink ?? "이메일 정보 없음"} · 연결{" "}
                        {formatDate(federation.createdAt)}
                      </p>
                    </div>
                    <Button variant="ghost" size="sm" onClick={() => setUnlinking(federation)}>
                      연결 해제
                    </Button>
                  </li>
                ))}
              </ul>
            )}

            <InlineError message={blocked} />

            {linkable.length > 0 && (
              <div className="flex flex-col gap-2 border-t border-border pt-4">
                <p className="text-sm text-muted-foreground">새 계정 연결</p>
                <div className="flex flex-wrap gap-2">
                  {linkable.map((provider) => (
                    // 세션 마커를 서버가 심어야 하므로 SPA 라우팅이 아닌 실제 이동이다.
                    <ButtonLink key={provider} variant="outline" href={federationLinkUrl(provider)} external>
                      {socialLabel(provider)} 연결
                    </ButtonLink>
                  ))}
                </div>
                <FieldHint>
                  서버에 설정되지 않은 공급자를 선택하면 연결이 취소되고 이 화면으로 되돌아옵니다.
                </FieldHint>
              </div>
            )}
          </>
        )}
      </Section>

      <ConfirmDialog
        open={unlinking !== null}
        onOpenChange={(open) => {
          if (!open) {
            setUnlinking(null);
            unlink.clearError();
          }
        }}
        title="소셜 계정 연결을 해제할까요?"
        description={
          unlinking
            ? `${unlinking.providerLabel || socialLabel(unlinking.provider)} 계정으로는 더 이상 로그인할 수 없습니다.`
            : ""
        }
        confirmLabel="연결 해제"
        busy={unlink.busy}
        error={unlink.error}
        onConfirm={async () => {
          if (!unlinking) return;
          setBlocked(undefined);
          if (await unlink.mutate(unlinking.provider)) {
            setUnlinking(null);
            toast.success("연결을 해제했습니다");
            list.reload();
          }
        }}
      />
    </>
  );
}
