"use client";

import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { useApi } from "@/lib/useApi";
import { Field, Section } from "../../_components/console-ui";
import { orgPath } from "../../_lib/org-context";
import { formatCount, formatMinor, monthOffset } from "../../_lib/labels";
import type { ReconciliationReport } from "../../_lib/types";

/**
 * 3-way 대사 — 원장·장부·소비이벤트가 같은 사실을 말하는지.
 *
 * ★화면이 이 값을 **참고 지표가 아니라 경보로** 다뤄야 한다. 세 기록은 같은 트랜잭션에서 쓰이므로
 * 정상 동작에서는 갈라질 수 없고, 갈라졌다면 그건 취향 차이가 아니라 버그다. 그래서 불일치일 때
 * "확인 필요" 같은 미지근한 문구를 쓰지 않고 무엇이 얼마나 어긋났는지 숫자로 말한다.
 *
 * 청구서 탭에 두는 이유: 조직관리자가 청구서를 의심하는 순간이 이 숫자를 보고 싶은 순간이다.
 */
export function ReconciliationSection({ orgId }: { orgId: string }) {
  const [period, setPeriod] = useState(monthOffset(-1));
  const valid = /^\d{4}-\d{2}$/.test(period);
  const report = useApi<ReconciliationReport>(
    valid ? orgPath(orgId, `/reconciliation?period=${encodeURIComponent(period)}`) : null,
    [orgId, period],
  );

  return (
    <Section
      title="정합성 대사"
      description="원장·거래 장부·소비 이벤트 세 기록을 맞춰 봅니다. 정상이라면 모든 차이가 0입니다 — 세 기록은 같은 순간에 함께 쓰이기 때문입니다."
      action={
        report.data ? (
          report.data.balanced ? (
            <Badge variant="secondary">이상 없음</Badge>
          ) : (
            <Badge variant="destructive">불일치</Badge>
          )
        ) : null
      }
    >
      <Field label="대사 월" htmlFor="recon-period">
        <Input
          id="recon-period"
          className="w-40"
          value={period}
          placeholder="YYYY-MM"
          onChange={(event) => setPeriod(event.target.value.trim())}
        />
      </Field>

      {report.error && <ErrorNotice message={report.error} onRetry={report.reload} />}
      {report.loading && <RowsSkeleton rows={3} />}

      {report.data && (
        <>
          <dl className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            {report.data.legs.map((leg) => (
              <div key={leg.name} className="rounded-lg border border-border px-3 py-2.5">
                <dt className="text-sm text-muted-foreground">{leg.name}</dt>
                <dd className="mt-0.5 text-lg font-medium tabular-nums text-foreground">
                  {leg.kind === "AMOUNT" ? formatMinor(leg.value) : `${formatCount(leg.value)}건`}
                </dd>
              </div>
            ))}
          </dl>

          <div className="flex flex-col gap-2 rounded-lg border border-border px-4 py-3 text-sm">
            <Drift
              label="금액 차이 (원장 − 장부)"
              value={formatMinor(report.data.amountDrift)}
              bad={report.data.amountDrift !== 0}
            />
            <Drift
              label="건수 차이 (장부 − 소비 이벤트)"
              value={`${formatCount(report.data.countDrift)}건`}
              bad={report.data.countDrift !== 0}
            />
            <Drift
              label="대차가 맞지 않는 기록"
              value={`${formatCount(report.data.unbalancedEntryCount)}건`}
              bad={report.data.unbalancedEntryCount !== 0}
            />
            <Drift
              label="미수금 + 미지급금"
              value={formatMinor(report.data.passThroughDrift)}
              bad={report.data.passThroughDrift !== 0}
            />
          </div>

          {!report.data.balanced && (
            <p role="alert" className="rounded-lg bg-destructive/10 px-4 py-3 text-sm text-destructive">
              세 기록이 서로 다릅니다. 이 기간의 청구서를 확정하기 전에 taspa 지원에 문의해 주세요 — 정상
              동작에서는 생길 수 없는 차이입니다.
            </p>
          )}
        </>
      )}
    </Section>
  );
}

function Drift({ label, value, bad }: { label: string; value: string; bad: boolean }) {
  return (
    <div className="flex items-center justify-between gap-4">
      <span className="text-muted-foreground">{label}</span>
      <span
        className={`tabular-nums font-medium ${bad ? "text-[color:var(--taspa-danger)]" : "text-foreground"}`}
      >
        {value}
      </span>
    </div>
  );
}
