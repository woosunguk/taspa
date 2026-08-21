"use client";

import { useApi } from "@/lib/useApi";
import { Section } from "../_components/console-ui";
import { orgPath, useOrg } from "../_lib/org-context";
import type { Invoice, OrgDashboard, OrgDomainSettings } from "../_lib/types";
import { ActionQueue } from "./_overview/ActionQueue";
import { ConsumptionSection } from "./_overview/ConsumptionSection";
import { ForecastSummary } from "./_overview/ForecastSummary";
import { HeadcountSection } from "./_overview/HeadcountSection";
import { ProfileSection } from "./_overview/ProfileSection";
import { RecentActivity } from "./_overview/RecentActivity";
import { SpendSection } from "./_overview/SpendSection";
import { currentMonthIn, monthStartInstant, previousMonth } from "./_overview/org-calendar";

/**
 * 개요 탭 = 조직관리자 대시보드.
 *
 * 답해야 하는 질문은 둘이다 — **"이번 달 식대가 얼마 나가고 있나"**, **"내가 지금 뭘 처리해야 하나"**.
 * 그래서 처리 대기가 맨 위에 오고(이 화면의 차별점은 숫자가 아니라 행동 유도다), 그다음이 금액,
 * 그다음이 실적·예측, 마지막이 모수(인원)와 설정이다. 각 항목은 기존 8탭의 진입점이며 새 최상위
 * 라우트를 만들지 않는다.
 *
 * ★모든 기간 경계는 **조직 타임존**으로 계산한다(V18). 조직 정보가 아직 없거나(비동기 도착) 플랫폼
 * 권한으로 들어와 `/api/orgs/mine` 에 잡히지 않으면 타임존을 모르는데, 그때는 브라우저 시간대로
 * 대신 계산하지 않고 해당 구획을 정직하게 비운다 — 하루 어긋난 숫자는 없는 숫자보다 나쁘다.
 */
export default function OrgOverviewPage() {
  const { orgId, org, reload: reloadOrg } = useOrg();
  const base = `/console/${orgId}`;
  const timezone = org?.timezone ?? null;

  const currentMonthKey = currentMonthIn(timezone);
  const previousMonthKey = currentMonthKey ? previousMonth(currentMonthKey) : null;
  const monthStart = currentMonthKey ? monthStartInstant(timezone, currentMonthKey) : null;

  // 처리 대기와 금액 요약이 같은 응답을 본다 — 한 번만 받아 아래로 내린다(중복 호출 방지).
  const dashboard = useApi<OrgDashboard>(orgPath(orgId, "/dashboard"), [orgId]);
  const domains = useApi<OrgDomainSettings>(orgPath(orgId, "/domains"), [orgId]);
  const invoices = useApi<Invoice[]>(orgPath(orgId, "/invoices"), [orgId]);

  return (
    <div className="flex flex-col gap-5">
      <ActionQueue
        base={base}
        org={org}
        dashboard={dashboard}
        domains={domains}
        invoices={invoices}
        previousMonthKey={previousMonthKey}
      />

      {/* 금액 구획은 서버가 org 타임존으로 기간을 정해 내려주므로(GET /spend) 달력 인자를 받지 않는다 —
          조직 정보가 아직 없거나 플랫폼 권한으로 들어와 타임존을 모를 때도 정상 동작한다. */}
      <SpendSection orgId={orgId} base={base} />

      <ConsumptionSection
        orgId={orgId}
        monthKey={currentMonthKey}
        monthStart={monthStart}
        timezone={timezone}
      />

      <ForecastSummary orgId={orgId} base={base} />

      <HeadcountSection dashboard={dashboard} />

      <RecentActivity orgId={orgId} base={base} timezone={timezone} />

      {/* 조직 정보는 레이아웃에서 비동기로 도착한다. key 를 값에 묶어 도착·저장 시점에 폼이 새 값으로 다시
          마운트되게 한다(effect 로 setState 하면 불필요한 연쇄 렌더가 생긴다).

          ★현재 값을 모르는 동안에는 편집 폼을 아예 렌더하지 않는다. 빈 문자열을 초기값으로 두면
          사용자가 한 글자만 입력해도 "변경됨"이 되어 저장이 열리고, 서버의 updateProfile 은 넘어온
          이름을 그대로 반영하므로 **조직명이 통째로 교체**된다(플랫폼 관리자가 /api/orgs/mine 에
          잡히지 않는 조직으로 URL 진입한 경우가 실제 경로다). */}
      {org ? (
        <ProfileSection
          key={`${org.id}:${org.name}:${org.timezone}`}
          orgId={orgId}
          initialName={org.name}
          initialTimezone={org.timezone}
          onSaved={reloadOrg}
        />
      ) : (
        <Section title="조직 정보" description="이름과 타임존을 변경합니다.">
          <p className="text-sm text-muted-foreground">
            조직 정보를 불러오지 못해 편집할 수 없습니다. 타임존을 모르면 이번 달 집계도 계산하지 않습니다.
            새로고침해도 계속되면 접근 권한을 확인해 주세요.
          </p>
        </Section>
      )}
    </div>
  );
}
