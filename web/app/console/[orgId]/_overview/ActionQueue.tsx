"use client";

import { AlertTriangleIcon, CheckCircle2Icon, InfoIcon } from "lucide-react";
import { ButtonLink } from "@/components/ui/button";
import { ErrorNotice, RowsSkeleton } from "@/components/feedback";
import type { Query } from "@/lib/useApi";
import { Section, StatusLine } from "../../_components/console-ui";
import { formatCount } from "../../_lib/labels";
import type { Invoice, OrgDashboard, OrgDomainSettings } from "../../_lib/types";
import { monthLabel, monthOfInstantIn } from "./org-calendar";
import type { AdministeredOrg } from "../../_lib/types";

/**
 * 처리 대기 — 이 화면의 차별점은 숫자가 아니라 **행동 유도**다.
 *
 * 각 항목은 "무엇이 걸려 있는지 + 왜 문제인지 + 어디로 가면 되는지"를 함께 말한다. 판단 근거는 전부
 * 이미 받은 응답이라 추가 호출이 없다. 세 응답 중 하나라도 아직 도착하지 않았으면 "없음"으로 단정하지
 * 않는다 — 빈 목록과 미도착은 다른 상태다.
 */

type Tone = "attention" | "info";

interface ActionItem {
  key: string;
  tone: Tone;
  title: string;
  description: string;
  href: string;
  cta: string;
}

export function ActionQueue({
  base,
  org,
  dashboard,
  domains,
  invoices,
  previousMonthKey,
}: {
  /** 조직 콘솔 기준 경로(`/console/{orgId}`). */
  base: string;
  /** 이 조직. `createdAt` 으로 "존재하지도 않던 달"의 재촉을 막는다. */
  org: AdministeredOrg | undefined;
  dashboard: Query<OrgDashboard>;
  domains: Query<OrgDomainSettings>;
  invoices: Query<Invoice[]>;
  /** 조직 타임존 기준 지난달 'YYYY-MM'. 타임존을 모르면 null — 청구서 항목을 만들지 않는다. */
  previousMonthKey: string | null;
}) {
  const items: ActionItem[] = [];

  const board = dashboard.data;
  if (board) {
    if (board.pendingInvitations > 0) {
      items.push({
        key: "invitations",
        tone: "attention",
        title: `수락 대기 중인 초대 ${formatCount(board.pendingInvitations)}건`,
        description: "만료되면 다시 보내야 합니다. 재발송하거나 필요 없는 초대는 취소하세요.",
        href: `${base}/invitations`,
        cta: "초대 관리",
      });
    }

    const terminated = board.byEmploymentStatus.TERMINATED ?? 0;
    if (terminated > 0) {
      items.push({
        key: "terminated",
        tone: "attention",
        title: `재직 상태가 '퇴직'인 활성 구성원 ${formatCount(terminated)}명`,
        // 서버 식권 발급은 멤버십 상태만 본다(재직 상태는 보지 않는다) — 방치하면 실지출이 계속된다.
        description:
          "식권 발급은 멤버십 상태로만 판정합니다. 멤버십이 활성인 한 퇴직자도 조직 식대를 계속 쓸 수 있습니다.",
        href: `${base}/members`,
        cta: "구성원 확인",
      });
    }

    if (board.departmentUnassignedCount > 0) {
      /*
       * ★**보낼 곳이 다음에 할 일과 맞아야 한다.**
       *
       * 이 항목은 늘 조직구조 탭으로 보냈는데, 그 탭에는 부서·사업장 CRUD 와 위임만 있고 **구성원을
       * 부서에 배정하는 UI 가 없다**(배정은 구성원 탭의 행 편집 모달이다). 그래서 안내를 따라간
       * 관리자는 도착지에서 할 일을 찾지 못하고, 항목은 계속 남아 다시 재촉한다.
       *
       * 부서가 하나도 없으면 먼저 만들어야 하므로 조직구조가 맞다 — 그때만 그리로 보낸다.
       */
      // 부서 존재 여부는 롤업 목록으로 안다(별도 필드가 없다 — 서버 계약을 넓히지 않는다).
      const noDepartments = board.byDepartment.length === 0;
      items.push({
        key: "dept-unassigned",
        tone: "info",
        title: `부서 미배정 ${formatCount(board.departmentUnassignedCount)}명`,
        description: noDepartments
          ? "부서가 아직 없습니다. 조직구조에서 부서를 만든 뒤 구성원 탭에서 배정할 수 있습니다."
          : "청구서의 부서별 소계에서 '부서 미배정'으로 묶여 비용 귀속이 흐려집니다. 배정은 구성원 탭의 '편집'에서 합니다.",
        href: noDepartments ? `${base}/structure` : `${base}/members`,
        cta: noDepartments ? "부서 만들기" : "구성원 배정",
      });
    }

    if (board.siteCount > 0 && board.siteUnassignedCount > 0) {
      items.push({
        key: "site-unassigned",
        tone: "info",
        title: `사업장 미배정 ${formatCount(board.siteUnassignedCount)}명`,
        description: "사업장별 인원 집계에 포함되지 않아 사업장 단위 식수 예측의 모수가 실제보다 적어집니다.",
        href: `${base}/structure`,
        cta: "조직구조",
      });
    }
  }

  const settings = domains.data;
  if (settings) {
    const unverified = settings.domains.filter((item) => !item.verified);
    const verified = settings.domains.length - unverified.length;

    if (unverified.length > 0) {
      items.push({
        key: "domains-unverified",
        tone: "attention",
        title: `미검증 도메인 ${formatCount(unverified.length)}개`,
        description: `DNS TXT 검증을 마쳐야 자동 가입이 동작합니다. (${unverified
          .slice(0, 3)
          .map((item) => item.domain)
          .join(", ")}${unverified.length > 3 ? " 외" : ""})`,
        href: `${base}/domains`,
        cta: "도메인 검증",
      });
    }

    if (verified > 0 && !settings.autoJoinEnabled) {
      items.push({
        key: "auto-join-off",
        tone: "info",
        title: "자동 가입이 꺼져 있습니다",
        description: `검증된 도메인 ${formatCount(verified)}개가 있지만, 회사 이메일로 가입한 사람이 조직에 자동 소속되지 않습니다.`,
        href: `${base}/domains`,
        cta: "설정 열기",
      });
    }
  }

  // 지난달 청구서는 달이 끝난 뒤에야 확정할 수 있다 — 조직 타임존을 모르면 "지난달"을 특정할 수 없으므로
  // 항목 자체를 만들지 않는다(틀린 달을 재촉하지 않는다).
  /*
   * ★조직이 **존재하지도 않던 달**은 재촉하지 않는다. 이 가드가 없던 동안 어제 만든 조직에도
   * "지난달 청구서가 없습니다"가 적색으로 떴고, 따라가 누르면 0원 초안이 생기고, 그러면 항목이
   * '확정하기'로 바뀌어 다시 재촉해 결국 **되돌릴 수 없는 0원 확정 청구서**가 남았다.
   * (서버도 이제 거래 0건이면 생성을 거절한다 — 화면과 서버 양쪽에서 막는다.)
   *
   * ★비교는 **조직 달력**으로 한다. UTC 문자열을 그대로 자르면(`createdAt.slice(0, 7)`) 월 경계 근처에
   * 만들어진 조직에서 한 달이 어긋나 — Asia/Seoul 기준 7월 1일 새벽에 만든 조직이 UTC 로는 6월이라 —
   * 있지도 않던 달의 청구서를 재촉하거나, 반대로 정당한 재촉이 사라진다.
   */
  const createdMonth = monthOfInstantIn(org?.timezone, org?.createdAt);
  const createdBeforePeriodEnd =
    previousMonthKey !== null && createdMonth !== null && createdMonth <= previousMonthKey;

  if (previousMonthKey && invoices.data && createdBeforePeriodEnd) {
    const previous = invoices.data.find((invoice) => invoice.period === previousMonthKey);
    if (!previous) {
      /*
       * ★**적색(attention)이 아니다.** 화면은 "지난달에 청구할 거래가 있었는가"를 모른다 — 조용한 달을
       * 보낸 조직에 적색으로 재촉하면 **지울 수 없는 경보**가 되고, 따라간 관리자는 서버의 거절
       * ("청구할 거래가 없어 만들지 않았습니다")을 만난다. 재촉이 스스로 해소될 수 없으면 경보가 아니라
       * 소음이고, 소음 옆의 진짜 경보는 읽히지 않는다.
       */
      items.push({
        key: "invoice-missing",
        tone: "info",
        title: `${monthLabel(previousMonthKey)} 청구서가 아직 없습니다`,
        description:
          "그 달에 결제가 있었다면 초안을 만들어 조직 부담 합계를 확인하고 확정할 수 있습니다. 거래가 없었다면 만들지 않는 것이 정상입니다.",
        href: `${base}/invoices`,
        cta: "청구서 확인",
      });
    } else if (previous.status !== "FINALIZED") {
      items.push({
        key: "invoice-draft",
        tone: "attention",
        title: `${monthLabel(previousMonthKey)} 청구서가 초안 상태입니다`,
        description: "확정 전에는 금액이 고정되지 않습니다. 취소 거래가 끼면 합계가 계속 바뀝니다.",
        href: `${base}/invoices`,
        cta: "확정하기",
      });
    }
  }

  const loading = dashboard.loading || domains.loading || invoices.loading;
  const errors = [dashboard.error, domains.error, invoices.error].filter(Boolean) as string[];
  const attention = items.filter((item) => item.tone === "attention").length;

  return (
    <Section
      title="처리 대기"
      description="지금 조직관리자가 손대야 하는 일만 모았습니다. 각 항목은 해당 탭으로 바로 이동합니다."
      /*
        ★이 화면에서 **강조는 여기 하나뿐**이다.
        개요 탭은 섹션이 8개인데 전부 같은 흰 카드였다. 그중 행동을 요구하는 것은 이 구획뿐인데
        나머지 7개와 시각적으로 동급이라, 세로 2,289px 안에서 그냥 '첫 번째 상자'였다.
        ★단 **처리할 일이 있을 때만** 띄운다. 대기 0건인데 강조하면 "볼 것 없음"을 강조하는 셈이라
        매일 여는 사람에게는 그게 곧 소음이 되고, 진짜 대기가 생겼을 때 구별되지 않는다.
      */
      tone={!loading && errors.length === 0 && items.length > 0 ? "attention" : "default"}
      action={
        !loading && errors.length === 0 ? (
          <span className="text-sm text-muted-foreground">
            {items.length === 0
              ? "대기 0건"
              : `대기 ${formatCount(items.length)}건${attention > 0 ? ` · 확인 필요 ${formatCount(attention)}건` : ""}`}
          </span>
        ) : undefined
      }
    >
      {errors.map((message, index) => (
        <ErrorNotice
          key={message + index}
          message={`처리 대기 판단에 필요한 정보를 일부 불러오지 못했습니다: ${message}`}
        />
      ))}

      {loading && <RowsSkeleton rows={3} />}

      {/* 아직 도착하지 않은 응답이 있으면 "없음"이라고 말하지 않는다 — 빈 목록과 미확인은 다르다. */}
      {!loading && items.length === 0 && errors.length === 0 && (
        <StatusLine tone="ok" icon={<CheckCircle2Icon />}>
          지금 처리할 일이 없습니다. 초대·도메인·청구서·구성원 배정 모두 정리되어 있습니다.
        </StatusLine>
      )}

      {!loading && items.length > 0 && (
        <ul className="flex flex-col gap-2">
          {items.map((item) => (
            <li
              key={item.key}
              /*
                info 항목은 **파인 면**으로 둔다. `bg-card` 로 두면 강조된 섹션(bg-raised) 안에서
                배경이 같아져(라이트에서 둘 다 흰색) 항목이 카드에 녹아 버린다 — 중첩은 배경 차이로
                읽혀야 한다.
              */
              className={`flex flex-wrap items-start justify-between gap-3 rounded-lg px-4 py-3 ${
                item.tone === "attention" ? "border border-destructive/40 bg-destructive/5" : "surface-sunken"
              }`}
            >
              <div className="flex min-w-0 items-start gap-2">
                {item.tone === "attention" ? (
                  <AlertTriangleIcon className="mt-0.5 size-4 shrink-0 text-destructive" aria-hidden />
                ) : (
                  <InfoIcon className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden />
                )}
                <div className="min-w-0">
                  <p className="text-sm font-medium text-foreground">{item.title}</p>
                  <p className="mt-0.5 text-sm text-muted-foreground">{item.description}</p>
                </div>
              </div>
              <ButtonLink variant="outline" size="sm" href={item.href}>
                {item.cta}
              </ButtonLink>
            </li>
          ))}
        </ul>
      )}
    </Section>
  );
}
