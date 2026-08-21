"use client";

import Link from "next/link";
import { useState } from "react";
import { ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useApi } from "@/lib/useApi";
import { PageHeader, Section, StatCard, TableScroll } from "../_components/kit";
import type { PlatformReconciliationView } from "../_lib/types";

/**
 * 전역 정합성 대사 — **조기 경보 화면**.
 *
 * 조직별 대사는 그 조직 관리자가 자기 청구서를 의심할 때 연다. 이 화면은 반대 방향이다: 시스템이
 * 어딘가에서 깨졌는지를 조직 수와 무관하게 한 번에 본다.
 *
 * ★그래서 이 화면의 정직함은 "불일치 목록"이 아니라 **`scanned`·`skipped`** 에 걸려 있다.
 * "불일치 0건"은 두 가지 뜻일 수 있다 — 다 봤는데 정상이었거나, **아무것도 안 봤거나**. 둘을 구분해
 * 보여주지 않으면 이 화면은 안심시키는 역할만 하고 경보 역할을 못 한다.
 */
/**
 * placeholder 용 지난달 문자열. **고정 문자열을 쓰면 다음 달부터 화면이 거짓말을 한다** —
 * 서버 기본값은 지난달인데 안내는 예전 달을 가리키게 된다.
 */
function lastMonthPeriod(): string {
  const now = new Date();
  const d = new Date(now.getFullYear(), now.getMonth() - 1, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
}

export default function AdminReconciliationPage() {
  const [periodInput, setPeriodInput] = useState("");
  const [period, setPeriod] = useState("");

  const query = period ? `?period=${encodeURIComponent(period)}` : "";
  const recon = useApi<PlatformReconciliationView>(`/api/admin/reconciliation${query}`);
  const data = recon.data;
  const rows = data?.unbalanced ?? [];
  // ★대사에 **성공한** 조직 수. `scanned` 는 시도한 수라, 실패분까지 "일치"로 세면 같은 화면의
  //   아래 문단(failed 경고)과 카드가 서로 다른 말을 하게 된다.
  const reconciled = (data?.scanned ?? 0) - (data?.failed ?? 0);

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="정합성 대사"
        description="원장·장부·소비이벤트 세 기록이 같은 사실을 말하는지 전 조직에 대해 확인합니다. 세 기록은 같은 트랜잭션에서 쓰이므로 정상 동작에서는 갈라질 수 없습니다 — 차이가 나면 그건 오차가 아니라 버그의 직접 증거입니다."
      />

      <Section
        title="기간"
        description="비워 두면 지난달을 봅니다. 이번 달은 아직 쌓이는 중이라 기본값으로 부적절합니다."
      >
        <form
          className="flex flex-wrap items-end gap-3"
          onSubmit={(event) => {
            event.preventDefault();
            setPeriod(periodInput.trim());
          }}
        >
          <div className="flex flex-col gap-1.5">
            <label htmlFor="recon-period" className="text-sm font-medium text-foreground">
              기간 (YYYY-MM)
            </label>
            <Input
              id="recon-period"
              value={periodInput}
              onChange={(event) => setPeriodInput(event.target.value)}
              placeholder={lastMonthPeriod()}
              className="w-40 tabular"
            />
          </div>
          <Button type="submit">조회</Button>
          {period && (
            <Button
              type="button"
              variant="ghost"
              onClick={() => {
                setPeriodInput("");
                setPeriod("");
              }}
            >
              지난달로
            </Button>
          )}
        </form>
      </Section>

      {recon.error ? (
        <ErrorNotice message={recon.error} onRetry={recon.reload} />
      ) : recon.loading || !data ? (
        <RowsSkeleton rows={4} />
      ) : (
        <>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard label="대상 기간" value={data.period} hint="조직 타임존으로 각각 계산" />
            <StatCard
              label="대사한 조직"
              value={data.scanned}
              hint={
                data.scanned === 0
                  ? "이 기간에 원장 활동이 있는 조직이 없습니다"
                  : "이 기간에 원장 활동이 있던 조직"
              }
            />
            {/*
              ★"불일치 0"에는 뜻이 둘이다 — **다 봤는데 정상**과 **아무것도 안 봤다**.
              둘을 같은 문장으로 말하면 이 화면은 안심시키는 역할만 하고 경보 역할을 못 한다
              (이 표면이 막으려던 실패 형태 그 자체다). scanned 로 갈라 말한다.
            */}
            <StatCard
              label="불일치 조직"
              value={rows.length}
              hint={
                rows.length > 0
                  ? "즉시 확인이 필요합니다"
                  : reconciled === 0
                    ? "대사에 성공한 조직이 없어 판단할 수 없습니다"
                    : data.failed > 0
                      ? `${reconciled}개 조직 일치 · ${data.failed}개는 검사 실패`
                      : `${reconciled}개 조직에서 세 기록이 모두 일치합니다`
              }
            />
            <StatCard
              label="검사 못 한 조직"
              value={data.skipped}
              hint={
                data.skipped === 0
                  ? "상한에 걸린 조직 없음"
                  : "상한 초과 — 결과를 '이상 없음'으로 읽지 마세요"
              }
            />
          </div>

          {/* ★실패를 말하지 않으면 "모두 일치"가 거짓이 된다 — 하필 불일치가 있는 조직일수록 사라진다. */}
          {data.failed > 0 && (
            <p className="rounded-lg border border-[color:var(--taspa-warning)]/40 bg-[color:var(--taspa-warning-soft)] px-3 py-2.5 text-sm text-[color:var(--taspa-warning)]">
              {data.failed}개 조직은 대사에 <strong>실패</strong>했습니다(일시적 오류). 아래 결과는
              <strong> 전부가 아닙니다</strong> — 잠시 후 다시 조회하세요.
            </p>
          )}

          {data.skipped > 0 && (
            <p className="rounded-lg border border-[color:var(--taspa-warning)]/40 bg-[color:var(--taspa-warning-soft)] px-3 py-2.5 text-sm text-[color:var(--taspa-warning)]">
              활동 조직이 서버 상한(500)을 넘어 {data.skipped}개를 검사하지 못했습니다. 기간을 좁혀 다시
              확인하세요 — 지금 목록은 <strong>전부가 아닙니다</strong>.
            </p>
          )}

          <Section
            title="불일치 조직"
            description="정상 조직은 싣지 않습니다. 경보 화면은 이상만 보여야 눈에 띕니다."
          >
            {data.scanned === 0 ? (
              <p className="py-6 text-center text-sm text-muted-foreground">
                이 기간에 원장 활동이 있는 조직이 없어 <strong>아무것도 대사하지 않았습니다</strong>.
                &quot;이상 없음&quot;과는 다른 상태입니다.
              </p>
            ) : rows.length === 0 ? (
              <p className="py-6 text-center text-sm text-muted-foreground">
                {data.failed > 0
                  ? `${data.scanned - data.failed}개 조직에서 불일치를 찾지 못했습니다(${data.failed}개는 검사 실패).`
                  : `${data.scanned}개 조직을 대사했고 모두 일치합니다.`}
              </p>
            ) : (
              <TableScroll>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>조직</TableHead>
                      <TableHead className="text-right">금액 차이</TableHead>
                      <TableHead className="text-right">건수 차이</TableHead>
                      <TableHead className="text-right">대차 위반</TableHead>
                      <TableHead className="text-right">통과 잔여</TableHead>
                      <TableHead className="w-24" />
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {rows.map((row) => (
                      <TableRow key={row.orgId}>
                        <TableCell>
                          <p className="font-medium">{row.orgName}</p>
                          <p className="text-xs text-muted-foreground">{row.timezone}</p>
                        </TableCell>
                        <Drift value={row.amountDrift} unit="원" hint="원장 미수금 − 장부 조직부담" />
                        <Drift value={row.countDrift} unit="건" hint="장부 승인 − 소비 이벤트" />
                        <Drift value={row.unbalancedEntryCount} unit="건" hint="분개 합이 0 이 아닌 사건" />
                        <Drift value={row.passThroughDrift} unit="원" hint="미수금 + 미지급금" />
                        <TableCell className="text-right">
                          <Link
                            href={`/console/${row.orgId}`}
                            className="text-sm font-medium text-primary hover:underline"
                          >
                            조직 열기
                          </Link>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableScroll>
            )}
          </Section>

          <Section title="지표가 뜻하는 것">
            <dl className="grid gap-3 text-sm sm:grid-cols-2">
              <Explain term="금액 차이">
                원장의 조직 미수금 합과 장부(meal_transactions)의 조직 부담 합이 다릅니다. 환불이 장부를 소급
                변경하는데 원장에 반대 분개가 남지 않았을 때 나타납니다.
              </Explain>
              <Explain term="건수 차이">
                승인 건수와 소비 이벤트 건수가 다릅니다. 소비 적재가 빠졌거나 취소가 한쪽에만 반영된
                경우입니다 — 예측의 정답데이터가 틀어집니다.
              </Explain>
              <Explain term="대차 위반">
                한 사건의 분개 합이 0 이 아닙니다. 원장 자체가 깨진 상태라, 이 값이 0 이 아니면 잔액이 우연히
                맞아도 믿을 수 없습니다.
              </Explain>
              <Explain term="통과 잔여">
                조직에게 받을 돈과 가맹에 줄 돈이 정확히 상쇄되지 않습니다. 플랫폼은 통과 지점이므로 0 이어야
                합니다.
              </Explain>
            </dl>
          </Section>
        </>
      )}
    </div>
  );
}

/** 0 은 조용히, 0 이 아니면 눈에 띄게 — 이 화면에서 0 이 아닌 값은 전부 이상이다. */
function Drift({ value, unit, hint }: { value: number; unit: string; hint: string }) {
  const ok = value === 0;
  return (
    <TableCell className="text-right" title={hint}>
      <span className={`tabular text-sm ${ok ? "text-muted-foreground" : "font-semibold text-destructive"}`}>
        {value > 0 ? "+" : ""}
        {value.toLocaleString("ko-KR")}
        <span className="ml-0.5 text-xs font-normal text-muted-foreground">{unit}</span>
      </span>
    </TableCell>
  );
}

function Explain({ term, children }: { term: string; children: React.ReactNode }) {
  return (
    <div className="rounded-lg border border-border px-3 py-2.5">
      <dt className="text-sm font-medium text-foreground">{term}</dt>
      <dd className="mt-0.5 text-xs leading-relaxed text-muted-foreground">{children}</dd>
    </div>
  );
}
