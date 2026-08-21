"use client";

import { useState } from "react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { DownloadLink } from "@/components/DownloadLink";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { EmptyState, ErrorNotice, Loading, RowsSkeleton } from "@/components/feedback";
import { api } from "@/lib/api";
import { useApi, useMutation } from "@/lib/useApi";
import { ConfirmButton, Field, Section, Stat, TableScroll } from "../../_components/console-ui";
import { orgPath, useOrg } from "../../_lib/org-context";
import { ReconciliationSection } from "./ReconciliationSection";
import { formatCount, formatDateTime, formatMinor, invoiceStatusLabel, monthOffset } from "../../_lib/labels";
import type { Invoice, InvoiceDetail } from "../../_lib/types";

/**
 * 청구서 탭 — 월 단위 조직 부담금 집계.
 *
 * 실제 수납·세금계산서 발행은 이 시스템 범위 밖이다. 여기서 하는 일은 "확정(불변화)"까지다.
 * 확정 시 서버가 스냅샷 기간으로 다시 계산해 합계가 달라졌으면 거절한다(거래 취소·환불이 끼어든 경우) —
 * 그때는 재생성 후 다시 확정해야 한다. 그 사유를 서버 문구 그대로 보여준다.
 */
export default function InvoicesPage() {
  const { orgId } = useOrg();
  const invoices = useApi<Invoice[]>(orgPath(orgId, "/invoices"), [orgId]);
  const [period, setPeriod] = useState(monthOffset(-1));
  const [openId, setOpenId] = useState<string | null>(null);
  // 확정·재생성처럼 같은 id 의 내용을 바꾸는 작업 뒤에 올린다 — 열려 있는 상세를 다시 읽게 하는 신호.
  const [version, setVersion] = useState(0);

  const generate = useMutation(async () =>
    api.post<InvoiceDetail>(orgPath(orgId, "/invoices/generate"), { period }),
  );

  const finalize = useMutation(async (invoice: Invoice) =>
    api.post<Invoice>(orgPath(orgId, `/invoices/${encodeURIComponent(invoice.id)}/finalize`)),
  );

  const rows = invoices.data ?? [];

  return (
    <div className="flex flex-col gap-5">
      <Section
        title="청구서 생성"
        description="조직 타임존 기준 달력 월의 승인 거래를 모아 조직 부담금을 집계합니다. 초안은 같은 달로 다시 생성하면 덮어씁니다. 지난달 초안은 매달 자동으로 만들어지므로, 여기서는 다시 계산하거나 다른 달을 만들 때만 사용하세요."
      >
        {generate.error && <ErrorNotice message={generate.error} onDismiss={generate.clearError} />}

        {/*
          ★기간 입력을 `type="month"` 로 두면 브라우저 로케일대로 **"June 2026"** 이 뜬다 —
          같은 화면 아래 "정합성 대사"의 기간은 `2026-06` 이라, 한 페이지가 같은 개념을 두 표기로
          말하게 된다. 돈 문서에서 기간은 곧 문서의 정체성이라 대조가 안 되면 곤란하다.
          서버 계약이 양쪽 다 `YYYY-MM` 이므로 표기를 그쪽에 맞춘다.
        */}
        <div className="flex flex-wrap items-end gap-3">
          <Field label="청구 월" htmlFor="invoice-period" hint="YYYY-MM">
            <Input
              id="invoice-period"
              className="w-40"
              value={period}
              placeholder="YYYY-MM"
              onChange={(event) => setPeriod(event.target.value.trim())}
            />
          </Field>
          {/*
            초안 생성은 **기존 초안을 덮어쓴다.** 규약대로 ConfirmButton 을 쓰고, 강조도 낮춘다 —
            설명이 "여기서는 다시 계산할 때만 쓰세요"라고 말하는데 화면에서 가장 강한 파란 버튼이면
            시각 위계가 문구와 정면으로 어긋난다(자동 생성이 기본 경로다).
          */}
          <ConfirmButton
            variant="outline"
            confirmLabel="덮어쓰고 생성"
            disabled={generate.busy || !/^\d{4}-\d{2}$/.test(period)}
            onConfirm={async () => {
              const created = await generate.mutate();
              if (created) {
                toast.success(`${created.period} 청구서를 생성했습니다`);
                setOpenId(created.id);
                invoices.reload();
                setVersion((v) => v + 1);
              }
            }}
          >
            {generate.busy ? "생성 중" : "초안 생성"}
          </ConfirmButton>
        </div>
      </Section>

      <Section title="청구서 목록" description="확정된 청구서는 더 이상 바뀌지 않습니다.">
        {invoices.error && <ErrorNotice message={invoices.error} onRetry={invoices.reload} />}
        {finalize.error && <ErrorNotice message={finalize.error} onDismiss={finalize.clearError} />}
        {invoices.loading && <RowsSkeleton rows={3} />}

        {!invoices.loading && !invoices.error && rows.length === 0 && (
          <EmptyState
            title="청구서가 없습니다"
            description="지난달 초안은 매달 자동으로 생성됩니다(거래가 있는 달만). 다른 달이 필요하면 위에서 월을 고르세요."
          />
        )}

        {rows.length > 0 && (
          <TableScroll>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>기간</TableHead>
                  <TableHead>상태</TableHead>
                  <TableHead className="text-right">거래 수</TableHead>
                  <TableHead className="text-right">조직 부담 합계</TableHead>
                  <TableHead>생성</TableHead>
                  <TableHead>확정</TableHead>
                  <TableHead className="text-right">관리</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((invoice) => (
                  <TableRow key={invoice.id}>
                    <TableCell className="tabular font-medium">{invoice.period}</TableCell>
                    <TableCell>
                      <Badge variant={invoice.status === "FINALIZED" ? "default" : "secondary"}>
                        {invoiceStatusLabel(invoice.status)}
                      </Badge>
                    </TableCell>
                    <TableCell className="tabular text-right">{formatCount(invoice.txnCount)}</TableCell>
                    <TableCell className="tabular text-right">{formatMinor(invoice.subtotalMinor)}</TableCell>
                    <TableCell className="tabular whitespace-nowrap">
                      {formatDateTime(invoice.generatedAt)}
                    </TableCell>
                    <TableCell className="tabular whitespace-nowrap">
                      {formatDateTime(invoice.finalizedAt)}
                    </TableCell>
                    <TableCell>
                      <div className="flex justify-end gap-1">
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => setOpenId(openId === invoice.id ? null : invoice.id)}
                        >
                          {openId === invoice.id ? "닫기" : "상세"}
                        </Button>
                        <DownloadLink
                          href={orgPath(orgId, `/invoices/${encodeURIComponent(invoice.id)}/csv`)}
                        >
                          CSV
                        </DownloadLink>
                        {invoice.status !== "FINALIZED" && (
                          <ConfirmButton
                            variant="outline"
                            disabled={finalize.busy}
                            confirmLabel="확정 실행"
                            onConfirm={async () => {
                              const done = await finalize.mutate(invoice);
                              if (done) {
                                toast.success(`${done.period} 청구서를 확정했습니다`);
                                invoices.reload();
                                setVersion((v) => v + 1);
                              }
                            }}
                          >
                            확정
                          </ConfirmButton>
                        )}
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableScroll>
        )}
      </Section>

      {/* version 이 바뀌면 상세도 다시 읽는다. 확정·재생성은 같은 id 의 **내용**을 바꾸므로
          목록만 갱신하면 열려 있는 상세가 "초안"이나 옛 합계로 남아 목록과 어긋난다. */}
      {openId && <InvoiceDetailSection orgId={orgId} invoiceId={openId} version={version} />}
      <ReconciliationSection orgId={orgId} />
    </div>
  );
}

function InvoiceDetailSection({
  orgId,
  invoiceId,
  version,
}: {
  orgId: string;
  invoiceId: string;
  version: number;
}) {
  const detail = useApi<InvoiceDetail>(orgPath(orgId, `/invoices/${encodeURIComponent(invoiceId)}`), [
    invoiceId,
    version,
  ]);

  return (
    <Section
      title="청구서 상세"
      description="이메일·부서명은 생성 시점의 값으로 고정됩니다(이후 조직 변경에 영향받지 않습니다)."
    >
      {detail.error && <ErrorNotice message={detail.error} onRetry={detail.reload} />}
      {detail.loading && <Loading />}

      {detail.data && (
        <div className="flex flex-col gap-5">
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Stat label="기간" value={detail.data.period} />
            <Stat label="상태" value={invoiceStatusLabel(detail.data.status)} />
            <Stat label="거래 수" value={formatCount(detail.data.txnCount)} />
            <Stat label="조직 부담 합계" value={formatMinor(detail.data.subtotalMinor)} />
          </div>

          <div>
            <h3 className="mb-2 text-sm font-medium text-foreground">부서별 소계</h3>
            {detail.data.departmentSubtotals.length === 0 ? (
              <p className="text-sm text-muted-foreground">집계된 거래가 없습니다.</p>
            ) : (
              <TableScroll>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>부서</TableHead>
                      <TableHead className="text-right">거래 수</TableHead>
                      <TableHead className="text-right">금액</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {detail.data.departmentSubtotals.map((row) => (
                      <TableRow key={row.departmentId ?? "unassigned"}>
                        <TableCell>{row.departmentName ?? "부서 미배정"}</TableCell>
                        <TableCell className="tabular text-right">{formatCount(row.txnCount)}</TableCell>
                        <TableCell className="tabular text-right">{formatMinor(row.amountMinor)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableScroll>
            )}
          </div>

          <div>
            <h3 className="mb-2 text-sm font-medium text-foreground">구성원별 내역</h3>
            {detail.data.lines.length === 0 ? (
              <p className="text-sm text-muted-foreground">집계된 거래가 없습니다.</p>
            ) : (
              <TableScroll>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>구성원</TableHead>
                      <TableHead>부서(스냅샷)</TableHead>
                      <TableHead className="text-right">거래 수</TableHead>
                      <TableHead className="text-right">금액</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {detail.data.lines.map((line) => (
                      <TableRow key={line.userId}>
                        <TableCell className="font-medium">{line.userEmail}</TableCell>
                        <TableCell>{line.departmentName ?? "—"}</TableCell>
                        <TableCell className="tabular text-right">{formatCount(line.txnCount)}</TableCell>
                        <TableCell className="tabular text-right">{formatMinor(line.amountMinor)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableScroll>
            )}
          </div>
        </div>
      )}
    </Section>
  );
}
