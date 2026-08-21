"use client";

import type { ReactNode } from "react";
import { CheckCircle2Icon, ClockIcon } from "lucide-react";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { ProgressMeter, Stat, StatusLine } from "@/components/data-display";
import { ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { formatTimeInZone, formatWon, windowRangeLabel, type MealEntitlement } from "./types";

/**
 * "이번 달 얼마 썼나 / 오늘 몇 번 남았나 / 한 끼에 회사가 얼마까지 내주나" — QR 다음으로 큰 질문들.
 *
 * ★모든 숫자의 출처는 `GET /api/meal/entitlement` 하나다. 예전에는 거래 목록을 화면에서 합산했는데,
 * 서버는 (사용자 × 조직) × **조직 타임존** 달력으로 자르고 화면은 기기 달력으로 잘라 두 숫자가
 * 구조적으로 달랐다. 한도 잔여는 아예 낼 수도 없었다(정책 조회 API 부재). 지금은 승인 경로가 쓰는
 * 계산을 그대로 받아 표시만 한다 — 여기에 재계산 코드를 넣지 말 것.
 */
/**
 * 값의 출처를 안내 문구에 덧붙인다 — **부서·사업장 기준일 때만**.
 *
 * 조직 기준(`ORG`)이나 taspa 기본값(`CODE_DEFAULT`)은 직원이 알아야 할 사실이 아니라 운영 내부 사정이고,
 * 모든 줄에 "조직 기준"이 붙으면 그냥 소음이다. 반대로 부서별 기준이 적용된 순간은 직원에게 의미가
 * 있다 — "왜 내 한도가 옆자리와 다른가"의 답이 여기 있다.
 */
function withSource(base: string, source: string | null | undefined): string {
  if (source === "DEPARTMENT") return `${base} · 부서 기준이 적용됩니다`;
  if (source === "SITE") return `${base} · 사업장 기준이 적용됩니다`;
  return base;
}

export function SpendSummary({
  entitlement,
  loading,
  error,
  onRetry,
}: {
  entitlement: MealEntitlement | undefined;
  loading: boolean;
  error: string | undefined;
  onRetry: () => void;
}) {
  if (error) {
    return (
      <Shell>
        <ErrorNotice message={error} onRetry={onRetry} />
      </Shell>
    );
  }

  if (loading && !entitlement) {
    return (
      <Shell>
        <RowsSkeleton rows={3} />
      </Shell>
    );
  }

  if (!entitlement) return null;

  const capExhausted = entitlement.monthRemainingMinor === 0;

  return (
    <Card>
      <CardHeader>
        <CardTitle>이번 달 사용</CardTitle>
        <CardDescription>
          {periodLabel(entitlement)} · {entitlement.orgName} 기준
          {entitlement.timezone && ` (${entitlement.timezone} 달력)`}
        </CardDescription>
      </CardHeader>

      <CardContent className="@container flex flex-col gap-4">
        {/*
          ★"지금 쓸 수 있나"를 **맨 위**에 둔다. 예전에는 이 사실이 카드 맨 아래 회색 본문 한 줄이었다 —
          계산대 앞에서 가장 먼저 확인해야 하는 것이 가장 눈에 안 띄는 자리에 있었다.
        */}
        <MealTimeNotice entitlement={entitlement} />

        {/*
          ★390px 에서 3열이면 칸이 68px 라 "200,000원" 이 **숫자 중간에서** 끊긴다. 폭이 확보될 때만
          3열로 간다 — 금액 토큰이 중간에서 갈라지지 않는 것이 이 그리드의 수용 조건이다.
        */}
        <div className="grid grid-cols-1 gap-2 min-[380px]:grid-cols-3">
          <Stat label="회사 부담" value={formatWon(entitlement.monthOrgPaidMinor)} emphasis />
          <Stat label="내 부담" value={formatWon(entitlement.monthSelfPaidMinor)} />
          <Stat label="이용 횟수" value={`${entitlement.monthApprovedCount.toLocaleString("ko-KR")}회`} />
        </div>

        {/*
          한도는 "남은 양"이 핵심이다 — 총액만 보여 주면 사용자가 직접 빼야 한다.
          ★월 한도는 **막대**로 보여 준다. "200,000원 / 200,000원" 처럼 숫자쌍만 두면 두 수를 읽고
          나눠야 상태를 알 수 있어서, 한눈에 보라고 만든 요약이 오히려 계산을 요구했다.
        */}
        <ProgressMeter
          name="이번 달 회사 지원 사용률"
          value={entitlement.monthlyCapMinor - entitlement.monthRemainingMinor}
          max={entitlement.monthlyCapMinor}
          tone={capExhausted ? "warning" : "default"}
          label={
            <>
              <span className="text-muted-foreground">이번 달 회사 지원</span>
              <span className="tabular font-medium text-foreground">
                {formatWon(entitlement.monthRemainingMinor)} 남음
              </span>
            </>
          }
          caption={
            capExhausted
              ? "월 지원 한도를 모두 썼습니다 — 결제는 되지만 전액 내 부담입니다"
              : `월 한도 ${formatWon(entitlement.monthlyCapMinor)}`
          }
        />

        <dl className="surface-sunken flex flex-col gap-2 rounded-lg px-3 py-2.5 text-sm">
          <Row
            term="한 끼 회사 지원"
            detail={`최대 ${formatWon(entitlement.perMealLimitMinor)}`}
            note={withSource("초과분은 결제 시 내 부담으로 나뉩니다", entitlement.perMealLimitSource)}
          />
          <Row
            term="오늘 남은 횟수"
            detail={`${entitlement.dailyRemaining}회 / 하루 ${entitlement.dailyMealCount}회`}
            note={
              entitlement.todayApprovedCount > 0
                ? `오늘 ${entitlement.todayApprovedCount}회 이용했습니다`
                : "오늘은 아직 이용하지 않았습니다"
            }
          />
        </dl>

        <p className="text-xs leading-relaxed text-muted-foreground">
          한도와 식사 시간대는 조직 정책이며, 위 숫자는 결제 승인에 쓰이는 서버 기준과 같습니다.
        </p>
      </CardContent>
    </Card>
  );
}

/**
 * 지금이 식사 시간인지 / 아니면 언제부터인지. 조직 타임존으로 읽는다(기기 시계로 재계산하지 않는다).
 *
 * ★세 경우가 **서로 다른 색**을 갖는다. 예전에는 셋 다 회색 본문이라, "지금 쓸 수 있다"와 "지금은 안
 * 된다"가 같은 무게로 보였다 — 문장을 끝까지 읽어야 구분되는 상태 표시는 상태 표시가 아니다.
 */
function MealTimeNotice({ entitlement }: { entitlement: MealEntitlement }) {
  if (entitlement.currentWindow) {
    return (
      <StatusLine tone="ok" icon={<CheckCircle2Icon />}>
        지금 이용할 수 있습니다 ·{" "}
        <span className="font-medium">{windowRangeLabel(entitlement.currentWindow)}</span>
      </StatusLine>
    );
  }
  if (entitlement.nextWindow) {
    return (
      <StatusLine tone="warning" icon={<ClockIcon />}>
        지금은 식사 시간이 아닙니다 · 다음{" "}
        <span className="font-medium">{windowRangeLabel(entitlement.nextWindow)}</span>{" "}
        {formatTimeInZone(entitlement.nextWindow.startsAt, entitlement.timezone)}부터
      </StatusLine>
    );
  }
  return (
    <StatusLine tone="neutral" icon={<ClockIcon />}>
      이 조직에는 이용 가능한 식사 시간대가 설정되어 있지 않습니다. 조직 관리자에게 문의하세요.
    </StatusLine>
  );
}

function Shell({ children }: { children: ReactNode }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>이번 달 사용</CardTitle>
      </CardHeader>
      <CardContent>{children}</CardContent>
    </Card>
  );
}

/**
 * 집계 기간 라벨. 서버가 준 `periodStart` 를 **조직 타임존으로** 읽는다 — 월 경계가 조직 달력으로
 * 잘렸으므로 기기 달력으로 읽으면 월 이름이 하루 차이로 어긋날 수 있다.
 */
function periodLabel(entitlement: MealEntitlement): string {
  const ms = Date.parse(entitlement.periodStart);
  if (!Number.isFinite(ms)) return "이번 달";
  try {
    return new Intl.DateTimeFormat("ko-KR", {
      timeZone: entitlement.timezone,
      year: "numeric",
      month: "long",
    }).format(new Date(ms));
  } catch {
    return "이번 달";
  }
}

function Row({ term, detail, note, warn }: { term: string; detail: string; note?: string; warn?: boolean }) {
  return (
    <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5">
      <dt className="text-muted-foreground">{term}</dt>
      <dd className="tabular font-medium text-foreground">{detail}</dd>
      {note && (
        <p
          className={`w-full text-xs ${warn ? "text-[color:var(--taspa-warning)]" : "text-muted-foreground"}`}
        >
          {note}
        </p>
      )}
    </div>
  );
}
