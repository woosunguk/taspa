"use client";

import { Badge } from "@/components/ui/badge";
import { ButtonLink } from "@/components/ui/button";
import { ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { useApi } from "@/lib/useApi";
import { Section } from "../../_components/console-ui";
import { orgPath } from "../../_lib/org-context";
import { auditTypeLabel } from "../../_lib/labels";
import type { OrgAuditEvent } from "../../_lib/types";
import { formatInZone } from "./org-calendar";

const LIMIT = 5;

/**
 * 최근 활동 — 이 조직에서 일어난 관리 행위 5건.
 *
 * ★플랫폼 운영자가 한 행위는 **서버가 신원(userId·email)을 마스킹해서** 내려준다. 화면은 역할 라벨만
 * 표시하며, 그 마스킹을 우회해 신원을 복원하려 들지 않는다.
 */
export function RecentActivity({
  orgId,
  base,
  timezone,
}: {
  orgId: string;
  base: string;
  timezone: string | null;
}) {
  const events = useApi<OrgAuditEvent[]>(orgPath(orgId, `/audit?limit=${LIMIT}&offset=0`), [orgId]);
  const rows = events.data ?? [];

  return (
    <Section
      title="최근 활동"
      description={
        timezone
          ? `이 조직에서 일어난 관리 행위 최근 ${LIMIT}건입니다. 시각은 조직 타임존 ${timezone} 기준입니다.`
          : `이 조직에서 일어난 관리 행위 최근 ${LIMIT}건입니다. 조직 타임존을 몰라 시각은 브라우저 시간대로 표시합니다.`
      }
      action={
        <ButtonLink variant="outline" size="sm" href={`${base}/audit`}>
          전체 로그
        </ButtonLink>
      }
    >
      {events.error && <ErrorNotice message={events.error} onRetry={events.reload} />}
      {events.loading && <RowsSkeleton rows={3} />}

      {!events.loading && !events.error && rows.length === 0 && (
        <p className="text-sm text-muted-foreground">
          아직 기록이 없습니다. 구성원·초대·조직구조·청구서를 다루면 여기에 남습니다.
        </p>
      )}

      {rows.length > 0 && (
        <ul className="flex flex-col divide-y divide-border">
          {rows.map((event) => (
            <li
              key={event.id}
              className="flex flex-wrap items-baseline gap-x-3 gap-y-1 py-2 first:pt-0 last:pb-0"
            >
              <span className="tabular w-40 shrink-0 text-sm text-muted-foreground">
                {formatInZone(event.createdAt, timezone)}
              </span>
              <span className="text-sm font-medium text-foreground">{auditTypeLabel(event.type)}</span>
              {event.platformActor ? (
                <Badge variant="outline">플랫폼 운영자</Badge>
              ) : (
                <span className="text-sm text-muted-foreground">{event.email ?? "시스템"}</span>
              )}
            </li>
          ))}
        </ul>
      )}
    </Section>
  );
}
