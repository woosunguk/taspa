"use client";

import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import type { Query } from "@/lib/useApi";
import { Notice, Portions, Section, Stat, TableScroll } from "../../_components/kit";
import {
  MEAL_WINDOWS,
  formatCount,
  formatDate,
  formatDelta,
  formatWon,
  mealWindowLabel,
  weekdayOf,
} from "../../_lib/format";
import type { MerchantBacktestResponse, MerchantTransactionsResponse } from "../../_lib/types";
import { cellsOfDate, summarizeLedger } from "./shared";

/**
 * 어제 마감 — 오늘 준비량을 얼마나 믿을지 판단하는 가장 가까운 재료.
 *
 * 인분(실적)과 금액(장부)의 원천이 다르다: 인분은 확정 소비 이벤트, 금액은 식권 결제 내역이다. 그래서 두
 * 숫자는 서로 나누거나 곱해서 파생시키지 않고 각각 그대로 보여준다(1 결제 = 1 인분을 가정하지 않는다).
 *
 * 어제는 **완결된 하루**라 진행 중 표기가 없다 — 오늘 구획과 다른 점이며, 그래서 예측 오차를 여기서만
 * 확정적으로 말할 수 있다.
 */
export function YesterdaySection({
  backtest,
  transactions,
  yesterday,
  timezone,
}: {
  backtest: Query<MerchantBacktestResponse>;
  transactions: Query<MerchantTransactionsResponse>;
  yesterday: string | null;
  timezone: string | null;
}) {
  const data = backtest.data;
  const covered = yesterday !== null && !!data && data.from <= yesterday && data.to >= yesterday;
  const cells = covered ? cellsOfDate(data?.cells, yesterday) : [];
  const hasCells = cells.some((cell) => cell !== undefined);

  const ledger = transactions.data;
  const totals = summarizeLedger(ledger?.rows ?? []);
  const actualTotal = cells.reduce((sum, cell) => sum + (cell?.actual ?? 0), 0);

  const loading = (backtest.loading && !data) || (transactions.loading && !ledger);

  return (
    <Section
      title="어제 마감"
      description={
        timezone
          ? `매장 시간(${timezone}) 기준 어제 하루의 확정 실적입니다. 취소(void)된 결제는 인분 집계에서 자동으로 빠집니다.`
          : "매장 시간 기준 어제 하루의 확정 실적입니다. 취소(void)된 결제는 인분 집계에서 자동으로 빠집니다."
      }
      action={
        yesterday && (
          <p className="text-sm text-muted-foreground">
            {formatDate(yesterday)} ({weekdayOf(yesterday)})
          </p>
        )
      }
    >
      {yesterday === null && (
        <Notice>매장 타임존을 확인하기 전에는 &lsquo;어제&rsquo;를 정할 수 없어 조회하지 않았습니다.</Notice>
      )}

      {backtest.error && <ErrorNotice message={backtest.error} onRetry={backtest.reload} />}
      {transactions.error && <ErrorNotice message={transactions.error} onRetry={transactions.reload} />}
      {loading && <RowsSkeleton rows={3} />}

      {!loading && (data || ledger) && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Stat
            label="어제 실적 (인분)"
            value={hasCells ? formatCount(actualTotal) : "—"}
            hint={hasCells ? "확정 소비 이벤트 기준" : "평가 구간에 어제가 없습니다"}
          />
          <Stat
            label="어제 결제 (건)"
            value={ledger ? formatCount(totals.approvedCount) : "—"}
            hint={totals.voidedCount > 0 ? `취소 ${totals.voidedCount}건 별도` : "식권 결제 기준"}
          />
          <Stat label="어제 결제액" value={ledger ? formatWon(totals.amount) : "—"} hint="취소 건 제외" />
          <Stat
            label="조직 부담"
            value={ledger ? formatWon(totals.orgPaid) : "—"}
            hint={`개인 부담 ${ledger ? formatWon(totals.selfPaid) : "—"}`}
          />
        </div>
      )}

      {ledger?.rowsTruncated && (
        <Notice>
          어제 거래가 표시 건수 상한({formatCount(ledger.limit)}건)에 도달했습니다 — 금액·건수 합계는{" "}
          <b className="font-medium">불러온 {formatCount(ledger.limit)}건 기준</b>
          입니다. 인분 실적은 별도 집계라 이 상한의 영향을 받지 않습니다.
        </Notice>
      )}

      {!loading && !covered && !hasCells && (
        <EmptyState
          title="어제 실적을 아직 불러오지 못했습니다"
          description="매장 타임존이 확인된 뒤 조회합니다. 계속 비어 있으면 새로고침해 주세요."
        />
      )}

      {hasCells && (
        // ★"0"의 뜻이 한 가지가 아니다. 백테스트 응답은 조회 구간의 모든 (날짜 × 끼니) 셀을 채우고
        //   실적이 없는 칸도 actual=0 으로 내려보내므로, 화면만으로는 "정말 0인분"과 "그 끼니를
        //   운영하지 않았다"를 구분할 수 없다. 예측 열의 "데이터 없음"(predicted=null)과 달리
        //   실적 열에는 그 구분이 계약에 없다 — 매장이 표를 오해하지 않도록 사실대로 적어 둔다.
        <Notice>
          실적 <b className="font-medium">0</b>은 &ldquo;그 끼니에 식수가 없었다&rdquo;와 &ldquo;그 끼니를
          운영하지 않았다&rdquo;를 함께 나타냅니다 — 둘을 구분해 표시하지는 못합니다.
        </Notice>
      )}

      {hasCells && (
        <TableScroll>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>끼니</TableHead>
                <TableHead className="text-right">실적 (인분)</TableHead>
                <TableHead className="text-right">그날 예측 (인분)</TableHead>
                <TableHead className="text-right">오차 (예측−실적)</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {MEAL_WINDOWS.map((window, index) => {
                const cell = cells[index];
                const diff = cell && cell.predicted !== null ? cell.predicted - cell.actual : null;
                return (
                  <TableRow key={window}>
                    <TableCell className="whitespace-nowrap">{mealWindowLabel(window)}</TableCell>
                    <TableCell className="tabular text-right font-medium">
                      {cell ? formatCount(cell.actual) : "—"}
                    </TableCell>
                    <TableCell className="text-right">
                      <Portions value={cell?.predicted ?? null} emptyLabel={cell ? "데이터 없음" : "—"} />
                    </TableCell>
                    <TableCell className="tabular text-right text-muted-foreground">
                      {formatDelta(diff)}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </TableScroll>
      )}
    </Section>
  );
}
