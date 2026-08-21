"use client";

import { useState } from "react";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useApi } from "@/lib/useApi";
import { PageHeader, Section, SelectField, TableScroll, formatDateTime } from "../_components/kit";
import type { AdminAuditEventView } from "../_lib/types";

/**
 * 감사 로그.
 *
 * 서버는 총 건수를 돌려주지 않는다(limit/offset 만). 그래서 "다음"은 **이번 페이지가 꽉 찼는지**로만
 * 판단한다 — 마지막 페이지가 정확히 limit 건이면 빈 다음 페이지가 한 번 나올 수 있고, 그때는 그 사실을
 * 화면에서 말해 준다(총 페이지 수를 지어내지 않는다).
 */
const LIMITS = [
  { value: "25", label: "25건씩" },
  { value: "50", label: "50건씩" },
  { value: "100", label: "100건씩" },
  { value: "200", label: "200건씩 (서버 상한)" },
];

export default function AdminAuditPage() {
  const [typeInput, setTypeInput] = useState("");
  const [emailInput, setEmailInput] = useState("");
  const [type, setType] = useState("");
  const [email, setEmail] = useState("");
  const [limit, setLimit] = useState("50");
  const [offset, setOffset] = useState(0);

  const size = Number(limit);
  const params = new URLSearchParams({ limit, offset: String(offset) });
  if (type) params.set("type", type);
  if (email) params.set("email", email);

  const events = useApi<AdminAuditEventView[]>(`/api/admin/audit?${params.toString()}`);
  const rows = events.data ?? [];
  const hasNext = rows.length === size;

  function applyFilters() {
    setType(typeInput.trim());
    setEmail(emailInput.trim());
    setOffset(0);
  }

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="감사 로그"
        description="플랫폼 전체의 상태 변경 기록입니다. 유형은 정확히 일치해야 하고, 이메일은 해당 계정과 연결된 이벤트만 걸러냅니다."
      />

      <Section title="필터">
        <form
          className="flex flex-wrap items-end gap-3"
          onSubmit={(event) => {
            event.preventDefault();
            applyFilters();
          }}
        >
          <div className="flex flex-col gap-1.5">
            <label htmlFor="audit-type" className="text-sm font-medium">
              이벤트 유형
            </label>
            <Input
              id="audit-type"
              value={typeInput}
              onChange={(event) => setTypeInput(event.target.value)}
              placeholder="ADMIN_USER_SUSPENDED"
              className="w-64"
              autoComplete="off"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="audit-email" className="text-sm font-medium">
              계정 이메일
            </label>
            <Input
              id="audit-email"
              value={emailInput}
              onChange={(event) => setEmailInput(event.target.value)}
              placeholder="user@example.com"
              className="w-64"
              autoComplete="off"
            />
          </div>
          <SelectField
            label="표시 개수"
            value={limit}
            onChange={(value) => {
              setLimit(value);
              setOffset(0);
            }}
            options={LIMITS}
            className="w-44"
          />
          <div className="flex items-center gap-2 pb-0.5">
            <Button type="submit">적용</Button>
            {(type || email) && (
              <Button
                type="button"
                variant="ghost"
                onClick={() => {
                  setTypeInput("");
                  setEmailInput("");
                  setType("");
                  setEmail("");
                  setOffset(0);
                }}
              >
                초기화
              </Button>
            )}
          </div>
        </form>
        <p className="mt-2 text-xs text-muted-foreground">
          유형은 부분 일치가 아니라 정확 일치입니다. 예: ADMIN_CLIENT_REGISTERED, ADMIN_IAM_POLICY_UPDATED,
          ORG_DOMAIN_VERIFIED, RISK_DETECTED
        </p>
      </Section>

      <Section
        title="이벤트"
        description={`${offset + 1}–${offset + rows.length}번째 표시 중`}
        actions={
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={offset === 0 || events.loading}
              onClick={() => setOffset(Math.max(0, offset - size))}
            >
              이전
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={!hasNext || events.loading}
              onClick={() => setOffset(offset + size)}
            >
              다음
            </Button>
            <Button variant="outline" size="sm" onClick={events.reload}>
              새로고침
            </Button>
          </div>
        }
      >
        {events.loading ? (
          <RowsSkeleton rows={8} />
        ) : events.error ? (
          <ErrorNotice message={events.error} onRetry={events.reload} />
        ) : rows.length === 0 ? (
          <EmptyState
            title={offset > 0 ? "이 페이지에는 더 이상 기록이 없습니다" : "조건에 맞는 기록이 없습니다"}
            description={
              offset > 0
                ? "이전 페이지로 돌아가세요."
                : "유형 철자나 이메일을 확인하고, 필터를 지운 뒤 다시 시도해 보세요."
            }
            action={
              offset > 0 ? (
                <Button variant="outline" size="sm" onClick={() => setOffset(Math.max(0, offset - size))}>
                  이전 페이지
                </Button>
              ) : undefined
            }
          />
        ) : (
          <TableScroll>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-40">시각</TableHead>
                  <TableHead>유형</TableHead>
                  <TableHead>대상 계정</TableHead>
                  <TableHead>상세</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((event) => (
                  <TableRow key={event.id}>
                    <TableCell className="tabular whitespace-nowrap text-muted-foreground">
                      {formatDateTime(event.createdAt)}
                    </TableCell>
                    <TableCell className="font-medium whitespace-nowrap">{event.type}</TableCell>
                    <TableCell className="whitespace-nowrap">
                      {event.email ?? (event.userId ? "(탈퇴 계정)" : "—")}
                    </TableCell>
                    <TableCell>
                      <code className="block max-w-xl overflow-x-auto font-mono text-xs whitespace-pre-wrap text-muted-foreground">
                        {event.detail ?? "—"}
                      </code>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableScroll>
        )}
      </Section>
    </div>
  );
}
