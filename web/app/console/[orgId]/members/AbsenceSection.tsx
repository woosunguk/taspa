"use client";

import { useMemo, useState } from "react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { api } from "@/lib/api";
import { useApi, useMutation } from "@/lib/useApi";
import { Choice, Field, FieldAction, Section, TableScroll, type Option } from "../../_components/console-ui";
import { orgPath } from "../../_lib/org-context";
import { formatDate, isoDateOffset } from "../../_lib/labels";
import type { Membership } from "../../_lib/types";

/**
 * 부재(연차·반차·출장·병가) 관리.
 *
 * **이 화면의 목적은 예측 모수 교정**이다 — 12명이 연차인 날 40명분을 준비하면 그만큼 버린다.
 * 그래서 부서·직함 같은 인사 편집과 다른 섹션에 둔다(성격이 "그날의 사실"이지 "그 사람의 속성"이 아니다).
 *
 * 반차가 0.5명인 것은 서버가 유형에서 파생한다 — 화면이 가중치를 보내지 않는다. 보내게 하면
 * "출장인데 0.1" 같은 값이 들어와 재실 모수가 조용히 왜곡되고, 그 왜곡은 예측에만 나타난다.
 */
const ABSENCE_TYPES: Option[] = [
  { value: "ANNUAL_LEAVE", label: "연차" },
  { value: "HALF_DAY", label: "반차 (0.5명)" },
  { value: "BUSINESS_TRIP", label: "출장" },
  { value: "SICK", label: "병가" },
  { value: "OTHER", label: "기타" },
];

interface Absence {
  id: string;
  userId: string;
  email: string | null;
  displayName: string | null;
  absenceDate: string;
  type: string;
  source: string;
  weight: number;
}

interface DaySummary {
  date: string;
  headcount: number;
  weightedAbsent: number;
}

function typeLabel(raw: string): string {
  return ABSENCE_TYPES.find((option) => option.value === raw)?.label ?? raw;
}

export function AbsenceSection({ orgId, members }: { orgId: string; members: Membership[] }) {
  // 기본 창은 **오늘부터 4주** — 부재는 과거 기록이 아니라 다가오는 날을 위한 입력이다.
  const [from, setFrom] = useState(isoDateOffset(0));
  const [to, setTo] = useState(isoDateOffset(28));

  const [userId, setUserId] = useState<string | null>(null);
  const [type, setType] = useState<string | null>("ANNUAL_LEAVE");
  const [leaveFrom, setLeaveFrom] = useState(isoDateOffset(1));
  const [leaveTo, setLeaveTo] = useState(isoDateOffset(1));

  const query = `?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`;
  const absences = useApi<Absence[]>(from && to ? orgPath(orgId, `/absences${query}`) : null, [from, to]);
  const summary = useApi<DaySummary[]>(from && to ? orgPath(orgId, `/absences/summary${query}`) : null, [
    from,
    to,
  ]);

  const memberOptions: Option[] = useMemo(
    () =>
      members
        .filter((member) => member.status === "ACTIVE")
        .map((member) => ({
          value: member.userId,
          label: member.displayName
            ? `${member.displayName} (${member.email})`
            : (member.email ?? member.userId),
        })),
    [members],
  );

  const reload = () => {
    absences.reload();
    summary.reload();
  };

  const save = useMutation(async (input: { userId: string; type: string; from: string; to: string }) => {
    const result = await api.post<{ created: number; updated: number }>(orgPath(orgId, "/absences"), {
      userId: input.userId,
      type: input.type,
      from: input.from,
      to: input.to,
    });
    return result;
  });

  const remove = useMutation(async (absence: Absence) => {
    await api.delete<void>(
      orgPath(
        orgId,
        `/absences/${encodeURIComponent(absence.userId)}/${encodeURIComponent(absence.absenceDate)}`,
      ),
    );
    return true;
  });

  // 가장 많이 빠지는 날 — 발주 담당자가 먼저 봐야 하는 정보다.
  const peak = useMemo(() => {
    const rows = summary.data ?? [];
    return rows.reduce<DaySummary | null>(
      (best, row) => (best === null || row.weightedAbsent > best.weightedAbsent ? row : best),
      null,
    );
  }, [summary.data]);

  return (
    <Section
      title="부재 (연차·휴가)"
      description="등록된 부재만큼 그날 재실 인원이 줄어 식수 예측이 낮아집니다. 반차는 0.5명으로 셉니다."
      action={
        peak ? (
          <Badge variant="secondary">
            최다 결식 {formatDate(peak.date)} · {peak.weightedAbsent.toFixed(1)}명
          </Badge>
        ) : null
      }
    >
      <div className="flex flex-wrap items-start gap-3">
        <Field label="구성원" htmlFor="absence-user" className="min-w-56">
          <Choice
            id="absence-user"
            value={userId}
            onChange={setUserId}
            options={memberOptions}
            placeholder="선택"
          />
        </Field>
        <Field label="유형" htmlFor="absence-type" className="min-w-40">
          <Choice id="absence-type" value={type} onChange={setType} options={ABSENCE_TYPES} />
        </Field>
        <Field label="시작일" htmlFor="absence-from">
          <Input
            id="absence-from"
            type="date"
            value={leaveFrom}
            onChange={(event) => setLeaveFrom(event.target.value)}
          />
        </Field>
        <Field label="종료일" htmlFor="absence-to" hint="하루면 시작일과 같게 두세요">
          <Input
            id="absence-to"
            type="date"
            value={leaveTo}
            onChange={(event) => setLeaveTo(event.target.value)}
          />
        </Field>
        <FieldAction>
          <Button
            disabled={!userId || !type || save.busy}
            onClick={async () => {
              if (!userId || !type) return;
              const result = await save.mutate({ userId, type, from: leaveFrom, to: leaveTo });
              if (!result) return;
              toast.success(`부재 ${result.created + result.updated}일 등록 (신규 ${result.created}일)`);
              reload();
            }}
          >
            {save.busy ? "등록 중…" : "부재 등록"}
          </Button>
        </FieldAction>
      </div>
      {save.error && <ErrorNotice message={save.error} onDismiss={save.clearError} />}

      <div className="flex flex-wrap items-start gap-3 border-t border-border pt-3">
        <Field label="조회 시작" htmlFor="absence-window-from">
          <Input
            id="absence-window-from"
            type="date"
            value={from}
            onChange={(event) => setFrom(event.target.value)}
          />
        </Field>
        <Field label="조회 종료" htmlFor="absence-window-to">
          <Input
            id="absence-window-to"
            type="date"
            value={to}
            onChange={(event) => setTo(event.target.value)}
          />
        </Field>
      </div>

      {absences.error && <ErrorNotice message={absences.error} onRetry={absences.reload} />}
      {absences.loading && <RowsSkeleton rows={3} />}
      {!absences.loading && (absences.data?.length ?? 0) === 0 && (
        <EmptyState
          title="등록된 부재가 없습니다"
          description="연차·출장을 등록하면 그날 예측이 그만큼 낮아집니다. 조회 구간을 바꿔 다른 기간도 확인할 수 있습니다."
        />
      )}
      {(absences.data?.length ?? 0) > 0 && (
        <TableScroll>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>날짜</TableHead>
                <TableHead>구성원</TableHead>
                <TableHead>유형</TableHead>
                <TableHead className="text-right">결식 인원</TableHead>
                <TableHead>등록 경로</TableHead>
                <TableHead className="text-right">작업</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(absences.data ?? []).map((absence) => (
                <TableRow key={absence.id}>
                  <TableCell className="whitespace-nowrap">{formatDate(absence.absenceDate)}</TableCell>
                  <TableCell>{absence.displayName ?? absence.email ?? absence.userId}</TableCell>
                  <TableCell>{typeLabel(absence.type)}</TableCell>
                  <TableCell className="text-right tabular-nums">{absence.weight.toFixed(1)}</TableCell>
                  <TableCell className="text-muted-foreground">
                    {absence.source === "MANUAL" ? "직접 입력" : absence.source}
                  </TableCell>
                  <TableCell className="text-right">
                    <Button
                      variant="ghost"
                      size="sm"
                      disabled={remove.busy}
                      onClick={async () => {
                        const done = await remove.mutate(absence);
                        if (!done) return;
                        toast.success("부재를 취소했습니다");
                        reload();
                      }}
                    >
                      취소
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableScroll>
      )}
      {remove.error && <ErrorNotice message={remove.error} onDismiss={remove.clearError} />}
    </Section>
  );
}
