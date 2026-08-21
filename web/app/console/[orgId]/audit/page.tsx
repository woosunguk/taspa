"use client";

import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { useApi } from "@/lib/useApi";
import { Section, TableScroll } from "../../_components/console-ui";
import { orgPath, useOrg } from "../../_lib/org-context";
import { auditTypeLabel, formatDateTime } from "../../_lib/labels";
import type { OrgAuditEvent } from "../../_lib/types";

const PAGE_SIZE = 50;

/**
 * 활동로그 탭 — 이 조직에서 일어난 관리 행위만 보여준다(다른 조직·전역 이벤트는 서버가 격리한다).
 * 플랫폼 운영자가 한 행위는 서버가 신원을 가린 채 내려주므로, 화면은 역할 라벨만 표시한다.
 */
export default function AuditPage() {
  const { orgId } = useOrg();
  const [offset, setOffset] = useState(0);
  const events = useApi<OrgAuditEvent[]>(orgPath(orgId, `/audit?limit=${PAGE_SIZE}&offset=${offset}`), [
    orgId,
  ]);

  const rows = events.data ?? [];
  const hasNext = rows.length === PAGE_SIZE;

  return (
    <Section
      title="활동로그"
      description="구성원·초대·조직구조·도메인·청구서 등 이 조직의 관리 행위 기록입니다."
    >
      {events.error && <ErrorNotice message={events.error} onRetry={events.reload} />}
      {events.loading && <RowsSkeleton rows={6} />}

      {!events.loading && !events.error && rows.length === 0 && (
        <EmptyState
          title={offset === 0 ? "기록이 없습니다" : "더 이상 기록이 없습니다"}
          description={offset === 0 ? "관리 작업을 하면 여기에 남습니다." : undefined}
          action={
            offset > 0 ? (
              <Button variant="outline" onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}>
                이전 페이지
              </Button>
            ) : undefined
          }
        />
      )}

      {rows.length > 0 && (
        <>
          <TableScroll>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-44">시각</TableHead>
                  <TableHead>행위</TableHead>
                  <TableHead>행위자</TableHead>
                  <TableHead>상세</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((event) => (
                  <TableRow key={event.id}>
                    <TableCell className="tabular whitespace-nowrap">
                      {formatDateTime(event.createdAt)}
                    </TableCell>
                    <TableCell>
                      <span className="font-medium">{auditTypeLabel(event.type)}</span>
                      {auditTypeLabel(event.type) !== event.type && (
                        <span className="ml-2 font-mono text-xs text-muted-foreground">{event.type}</span>
                      )}
                    </TableCell>
                    <TableCell>
                      {event.platformActor ? (
                        <Badge variant="outline">플랫폼 운영자</Badge>
                      ) : (
                        (event.email ?? <span className="text-muted-foreground">시스템</span>)
                      )}
                    </TableCell>
                    <TableCell>
                      <Detail raw={event.detail} />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableScroll>

          <div className="flex items-center justify-between gap-3">
            <span className="text-sm text-muted-foreground">
              {offset + 1}–{offset + rows.length}번째 기록
            </span>
            <div className="flex gap-2">
              <Button
                variant="outline"
                size="sm"
                disabled={offset === 0}
                onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}
              >
                이전
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={!hasNext}
                onClick={() => setOffset(offset + PAGE_SIZE)}
              >
                다음
              </Button>
            </div>
          </div>
        </>
      )}
    </Section>
  );
}

/**
 * 감사 상세는 서버가 JSON 문자열로 준다. 키가 이벤트마다 달라 표로 고정할 수 없으므로 `키=값` 나열로
 * 편다. 파싱이 안 되면 원문을 그대로 보여준다(숨기면 무슨 일이 있었는지 알 길이 없어진다).
 */
function Detail({ raw }: { raw: string | null }) {
  const [open, setOpen] = useState(false);
  if (!raw) return <span className="text-muted-foreground">—</span>;

  let parsed: Record<string, unknown> | null = null;
  try {
    const value = JSON.parse(raw);
    if (value && typeof value === "object" && !Array.isArray(value)) {
      parsed = value as Record<string, unknown>;
    }
  } catch {
    parsed = null;
  }

  if (!parsed) {
    return <code className="font-mono text-xs break-all text-muted-foreground">{raw}</code>;
  }

  const entries = Object.entries(parsed).filter(([, value]) => value !== null && value !== "");
  const preview = entries.slice(0, 2);
  const rest = entries.length - preview.length;

  return (
    <div className="flex flex-col gap-1 text-xs">
      {(open ? entries : preview).map(([key, value]) => (
        <span key={key} className="font-mono break-all text-muted-foreground">
          {key}={String(value)}
        </span>
      ))}
      {rest > 0 && (
        <button
          type="button"
          className="w-fit text-xs text-primary hover:underline"
          onClick={() => setOpen(!open)}
        >
          {open ? "접기" : `${rest}개 더 보기`}
        </button>
      )}
    </div>
  );
}
