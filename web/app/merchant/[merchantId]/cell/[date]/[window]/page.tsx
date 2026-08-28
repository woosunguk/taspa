"use client";

import Link from "next/link";
import { use } from "react";
import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { useApi } from "@/lib/useApi";
import { Notice, Section, Stat, TableScroll } from "../../../../_components/kit";
import { merchantPath, useMerchant } from "../../../../_lib/merchant-context";
import { forecastMethodLabel, formatCount, formatDate, mealWindowLabel } from "../../../../_lib/format";
import type { MerchantCellDetail } from "../../../../_lib/types";

/**
 * 셀 근거 상세 — 예측 숫자를 클릭했을 때 "왜 이 숫자인가"에 답하는 페이지.
 *
 * 세 층으로 답한다: ① 어떤 메뉴가 몇 인분인가(메뉴 분해 — 발주 담당자의 실제 질문),
 * ② 어느 조직 몫이 얼마인가(조직 분해 + 휴일·행사·연차 신호), ③ 어느 날짜의 어떤 실적을
 * 근거로 썼는가(basis — 여기까지 내려가면 숫자를 검증할 수 있다).
 *
 * ★셀 값은 목록 API 와 **같은 계산**을 서버가 재사용한다 — 이 페이지가 목록과 다른 숫자를
 * 말하면 근거가 아니라 두 번째 의견이 된다(서버 cellDetail KDoc).
 */
export default function CellDetailPage({ params }: { params: Promise<{ date: string; window: string }> }) {
  const { date, window } = use(params);
  const { merchantId } = useMerchant();
  const detail = useApi<MerchantCellDetail>(
    merchantPath(
      merchantId,
      `/forecast/cell?date=${encodeURIComponent(date)}&mealWindow=${encodeURIComponent(window)}`,
    ),
    [date, window],
  );
  const data = detail.data;
  const cell = data?.cell;

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <Link
            href={`/merchant/${merchantId}`}
            className="text-sm text-muted-foreground hover:text-foreground"
          >
            ← 식수예측
          </Link>
          <h2 className="mt-1 text-xl font-semibold">
            {formatDate(date)} {mealWindowLabel(window)} — 예측 근거
          </h2>
        </div>
        {cell && (
          <div className="flex items-baseline gap-2">
            <span className="tabular text-4xl font-semibold text-brand">
              {cell.predicted !== null ? formatCount(cell.predicted) : "—"}
            </span>
            <span className="text-sm text-muted-foreground">인분 · {forecastMethodLabel(cell.method)}</span>
          </div>
        )}
      </div>

      {detail.error && <ErrorNotice message={detail.error} onRetry={detail.reload} />}
      {detail.loading && <RowsSkeleton rows={6} />}

      {data && cell && (
        <>
          {cell.soFar != null && (
            <Notice>
              오늘 지금까지 <b>{formatCount(cell.soFar)}인분</b>이 이미 나갔습니다
              {cell.predicted !== null && cell.soFar >= cell.predicted
                ? " — 예측이 이 값을 하한으로 올렸습니다."
                : "."}
            </Notice>
          )}

          {/* ① 어떤 메뉴가 몇 인분인가 — 발주 담당자의 실제 질문이라 맨 위. */}
          <Section
            title="메뉴별 예상"
            description={
              data.menuLearnFrom
                ? `비율은 이 매장의 같은 요일 실적(${formatDate(data.menuLearnFrom)} ~ ${formatDate(data.menuLearnTo!)}, 코너 기록 기준)에서 배웠습니다. 근거가 없는 메뉴에는 숫자를 지어내지 않습니다.`
                : "이 끼니에 등록된 식단이 없습니다. 조직 콘솔 > 식사정책, 또는 식단 등록 후 코너 기록이 쌓이면 메뉴별 분해가 나타납니다."
            }
          >
            {data.menus.length === 0 && (
              <EmptyState
                title="등록된 식단이 없습니다"
                description="사업장이 연결된 조직이 식단을 등록하면 메뉴별 분해가 나타납니다."
              />
            )}
            {data.menus.length > 0 && (
              <TableScroll>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>코너</TableHead>
                      <TableHead>메뉴</TableHead>
                      <TableHead className="text-right">예상 인분</TableHead>
                      <TableHead className="text-right">실측 선택 비율</TableHead>
                      <TableHead className="text-right">계획 인분</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {data.menus.map((menu) => (
                      <TableRow key={menu.name}>
                        <TableCell className="whitespace-nowrap">{menu.corner ?? "—"}</TableCell>
                        <TableCell className="font-medium">{menu.name}</TableCell>
                        <TableCell className="text-right">
                          {menu.predicted !== null ? (
                            <span className="tabular text-lg font-semibold text-brand">
                              {formatCount(menu.predicted)}
                            </span>
                          ) : (
                            <Badge
                              variant="outline"
                              title="이 메뉴로 기록된 실적이 아직 없어 비율을 지어내지 않습니다"
                            >
                              근거 없음
                            </Badge>
                          )}
                        </TableCell>
                        <TableCell className="text-right tabular-nums">
                          {menu.share !== null
                            ? `${Math.round(menu.share * 100)}% (표본 ${formatCount(menu.sampleQuantity)}인분)`
                            : "—"}
                        </TableCell>
                        <TableCell className="text-right tabular-nums text-muted-foreground">
                          {menu.plannedPortions !== null ? formatCount(menu.plannedPortions) : "—"}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableScroll>
            )}
          </Section>

          {/* ② 어느 조직 몫이 얼마인가 — 신호가 적용된 단위. */}
          <Section
            title="조직별 분해"
            description="총 예측은 이용 조직별 예측의 합입니다. 휴일·행사·연차는 그 조직 몫에만 반영됩니다."
          >
            <TableScroll>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>조직</TableHead>
                    <TableHead className="text-right">예측</TableHead>
                    <TableHead>산출 방법</TableHead>
                    <TableHead>신호</TableHead>
                    <TableHead>근거 실적 (같은 요일·같은 성격의 날)</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {data.orgs
                    .filter(
                      (org) =>
                        org.slice.predicted !== null ||
                        org.basis.length > 0 ||
                        org.slice.holiday ||
                        org.slice.event,
                    )
                    .map((org) => (
                      <TableRow key={org.slice.orgId}>
                        <TableCell className="font-medium">{org.slice.orgName}</TableCell>
                        <TableCell className="text-right">
                          {org.slice.predicted !== null ? (
                            <span className="tabular font-semibold">{formatCount(org.slice.predicted)}</span>
                          ) : (
                            "—"
                          )}
                        </TableCell>
                        <TableCell className="text-muted-foreground">
                          {forecastMethodLabel(org.slice.method)}
                        </TableCell>
                        <TableCell>
                          <div className="flex flex-wrap gap-1">
                            {org.slice.holiday && (
                              <Badge variant="outline">🏮 {org.slice.holidayName ?? "휴일"}</Badge>
                            )}
                            {org.slice.event && (
                              <Badge variant="secondary">🎪 {org.slice.eventName ?? "행사"}</Badge>
                            )}
                            {org.slice.absentWeight > 0 && (
                              <Badge
                                variant="outline"
                                title={
                                  org.headcount
                                    ? `재직 ${org.headcount}명 중 ${org.slice.absentWeight}명 부재 — 그 비율만큼 낮췄습니다`
                                    : undefined
                                }
                              >
                                🌴 연차 {org.slice.absentWeight}명
                                {org.headcount ? ` / 재직 ${org.headcount}명` : ""}
                              </Badge>
                            )}
                          </div>
                        </TableCell>
                        <TableCell className="text-sm text-muted-foreground">
                          {org.basis.length === 0
                            ? "비교 가능한 과거 실적 없음"
                            : org.basis.map((b) => `${formatDate(b.date)} ${b.actual}인분`).join(" · ")}
                        </TableCell>
                      </TableRow>
                    ))}
                </TableBody>
              </Table>
            </TableScroll>
            {cell.partial && (
              <p className="text-xs text-muted-foreground">
                일부 조직은 비교할 과거 실적이 없어 합계에서 빠져 있습니다 — 총 예측은 하한입니다.
              </p>
            )}
          </Section>

          {/* ③ 읽는 법 — 숫자를 검증하는 손잡이. */}
          <Section title="이 숫자를 읽는 법">
            <div className="grid gap-3 sm:grid-cols-3">
              <Stat
                label="총 예측"
                value={`${cell.predicted !== null ? formatCount(cell.predicted) : "—"}인분`}
                hint="조직별 예측의 합"
              />
              <Stat
                label="전주 같은 끼니 실적"
                value={
                  cell.basis.lastWeekActual !== null ? `${formatCount(cell.basis.lastWeekActual)}인분` : "—"
                }
                hint="매장 전체 기준"
              />
              <Stat
                label="산출 조직 수"
                value={`${cell.basis.sampleWeeks}곳`}
                hint="근거가 있어 합산에 든 조직"
              />
            </div>
            <p className="text-xs leading-relaxed text-muted-foreground">
              예측은 조직마다 같은 요일·같은 성격(평일/휴일/행사)의 과거 실적을 근거로 하고, 등록된 연차만큼
              재실 비율을 곱합니다. 근거가 없으면 어느 단계에서도 숫자를 지어내지 않습니다 — “근거 없음”이
              그대로 표시되는 것이 이 화면의 신뢰 근거입니다.
            </p>
          </Section>
        </>
      )}
    </div>
  );
}
