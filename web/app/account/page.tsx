"use client";

import { useEffect } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { toast } from "sonner";
import { AppShell, RequireAuth } from "@/components/AppShell";
import { ErrorNotice, Loading } from "@/components/feedback";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useApi } from "@/lib/useApi";
import type { CurrentUser } from "@/lib/session";
import { ConnectedAppsSection } from "./_components/ConnectedAppsSection";
import { DangerZone } from "./_components/DangerZone";
import { EmailSection } from "./_components/EmailSection";
import { FederationsSection } from "./_components/FederationsSection";
import { LoginHistorySection } from "./_components/LoginHistorySection";
import { MfaSection } from "./_components/MfaSection";
import { PasskeySection } from "./_components/PasskeySection";
import { PasswordSection } from "./_components/PasswordSection";
import { ProfileSection } from "./_components/ProfileSection";
import { SessionsSection } from "./_components/SessionsSection";
import { TrustedDevicesSection } from "./_components/TrustedDevicesSection";

/**
 * 계정 설정(본인).
 *
 * 인증: 다른 보호 화면과 같이 `RequireAuth` 로 감싼다. `lib/api` 의 자동 이동에만 기대면 미인증
 * 사용자에게 "계정 정보를 불러오지 못했습니다" 가 먼저 깜빡이고, 그 화면 안의 다른 조회들이 각자
 * 실패하며 오류를 뿌린다 — 진입 시점에 한 번 판정하는 편이 사용자에게도 정직하다.
 * (로그인 UI 자체는 서버가 소유 — MFA·패스키·소셜·리스크 게이트가 서버 플로우에 얽혀 있다.)
 *
 * 화면 구성이 탭인 이유: 관리 대상이 아홉 가지라 한 줄로 늘어놓으면 사용자가 원하는 항목을 스크롤로
 * 찾아야 한다. 탭 경계는 "무엇을 바꾸려고 왔는가"(내 정보 / 보안 / 기기 / 연결)로 나눴다.
 */
export default function AccountPage() {
  // 소셜 연결은 서버 리다이렉트로 돌아오며 결과를 쿼리스트링에 담는다(/account?linked=1 · ?linkError=inuse).
  // useSearchParams 대신 location 을 읽는다 — 이 값은 서버 왕복의 산물이라 클라이언트에서만 의미가 있고,
  // 그래야 이 페이지가 Suspense 경계 없이도 프로덕션 빌드를 통과한다.
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const linked = params.get("linked");
    const linkError = params.get("linkError");
    if (!linked && !linkError) return;

    if (linked === "1") {
      toast.success("소셜 계정을 연결했습니다");
    } else if (linkError === "inuse") {
      toast.error("이미 다른 taspa 계정에 연결된 소셜 계정입니다");
    } else if (linkError) {
      toast.error("소셜 계정을 연결하지 못했습니다. 잠시 후 다시 시도해 주세요");
    }

    // 새로고침할 때마다 같은 토스트가 다시 뜨지 않도록 흔적을 지운다(히스토리는 남기지 않는다).
    params.delete("linked");
    params.delete("linkError");
    const query = params.toString();
    window.history.replaceState(null, "", `${window.location.pathname}${query ? `?${query}` : ""}`);
  }, []);

  return (
    <AppShell>
      <RequireAuth>{() => <AccountContent />}</RequireAuth>
    </AppShell>
  );
}

/**
 * 로그인이 확인된 뒤의 본문.
 *
 * 세션의 사용자(RequireAuth 가 넘겨주는 값) 대신 `useApi` 로 한 번 더 조회하는 이유는 **reload** 다 —
 * 표시 이름·이메일·MFA 상태는 이 화면 안에서 바뀌고, 변경 직후 화면이 그 결과를 보여줘야 한다.
 */
function AccountContent() {
  const me = useApi<CurrentUser>("/api/account/me");

  if (me.loading) return <Loading label="계정 정보를 불러오는 중" />;
  if (me.error || !me.data) {
    return <ErrorNotice message={me.error ?? "계정 정보를 불러오지 못했습니다"} onRetry={me.reload} />;
  }
  return <AccountTabs user={me.data} onChanged={me.reload} />;
}

const TAB_IDS = ["profile", "security", "devices", "connections"] as const;

function AccountTabs({ user, onChanged }: { user: CurrentUser; onChanged: () => void }) {
  /*
   * ★탭을 **URL 에 둔다**. 그전에는 컴포넌트 상태뿐이라, 민감 조작이 step-up 으로 튕겼다가 돌아오면
   * 언제나 '내 정보' 탭이었다 — 사용자는 하던 일이 있던 자리를 스스로 다시 찾아가야 했고, 그게
   * 재인증을 요구하는 모든 조작(MFA·패스키·세션 폐기·연결 해제)에서 매번 반복됐다.
   * 링크 공유·뒤로가기도 함께 살아난다.
   */
  const router = useRouter();
  const params = useSearchParams();
  const requested = params.get("tab");
  const tab = (TAB_IDS as readonly string[]).includes(requested ?? "") ? requested! : "profile";
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-display text-foreground">계정 설정</h1>
        <p className="mt-1 text-sm text-muted-foreground">{user.email}</p>
      </div>

      <Tabs
        value={tab}
        onValueChange={(next) => {
          // replace — 탭 전환이 뒤로가기 스택을 채우면 '뒤로'가 화면을 벗어나지 못한다.
          router.replace(`/account?tab=${next}`, { scroll: false });
        }}
      >
        <TabsList className="max-w-full overflow-x-auto">
          <TabsTrigger value="profile">내 정보</TabsTrigger>
          <TabsTrigger value="security">보안</TabsTrigger>
          <TabsTrigger value="devices">기기와 세션</TabsTrigger>
          <TabsTrigger value="connections">연결</TabsTrigger>
        </TabsList>

        <TabsContent value="profile" className="flex flex-col gap-4">
          <ProfileSection user={user} onChanged={onChanged} />
          <EmailSection user={user} onChanged={onChanged} />
          <DangerZone user={user} />
        </TabsContent>

        <TabsContent value="security" className="flex flex-col gap-4">
          <PasswordSection user={user} />
          <MfaSection user={user} onChanged={onChanged} />
          <PasskeySection />
        </TabsContent>

        <TabsContent value="devices" className="flex flex-col gap-4">
          <SessionsSection />
          <TrustedDevicesSection />
          <LoginHistorySection />
        </TabsContent>

        <TabsContent value="connections" className="flex flex-col gap-4">
          <FederationsSection />
          <ConnectedAppsSection />
        </TabsContent>
      </Tabs>
    </div>
  );
}
