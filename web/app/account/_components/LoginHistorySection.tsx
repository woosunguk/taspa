"use client";

import { useApi } from "@/lib/useApi";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { LOGIN_HISTORY, type LoginHistoryEntry } from "../_lib/endpoints";
import { formatDateTime, loginMethodLabel, relativeFromNow } from "../_lib/format";
import { FieldHint, Section } from "./chrome";

/**
 * 최근 로그인 활동(읽기 전용, 서버 기본 10건).
 * 활성 세션 목록과 다르다 — 여기 있는 항목은 이미 끝난 로그인이라 종료할 대상이 없다.
 */
export function LoginHistorySection() {
  const list = useApi<LoginHistoryEntry[]>(LOGIN_HISTORY);

  return (
    <Section title="로그인 기록" description="이 계정에 마지막으로 로그인한 기록입니다.">
      {list.loading ? (
        <RowsSkeleton rows={4} />
      ) : list.error ? (
        <ErrorNotice message={list.error} onRetry={list.reload} />
      ) : (list.data?.length ?? 0) === 0 ? (
        <EmptyState title="아직 로그인 기록이 없습니다" />
      ) : (
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>시각</TableHead>
                <TableHead>방법</TableHead>
                <TableHead>기기</TableHead>
                <TableHead>IP</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {list.data?.map((entry, index) => (
                <TableRow key={`${entry.occurredAt}-${index}`}>
                  <TableCell className="text-foreground" title={formatDateTime(entry.occurredAt)}>
                    {relativeFromNow(entry.occurredAt)}
                  </TableCell>
                  <TableCell className="text-muted-foreground">{loginMethodLabel(entry.method)}</TableCell>
                  <TableCell className="text-muted-foreground">{entry.device ?? "-"}</TableCell>
                  <TableCell className="tabular text-muted-foreground">{entry.ip ?? "-"}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      <FieldHint>기억나지 않는 로그인이 있다면 비밀번호를 바꾸고 활성 세션을 모두 종료하세요.</FieldHint>
    </Section>
  );
}
