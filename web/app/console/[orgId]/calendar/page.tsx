"use client";

import { useRef, useState } from "react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { api } from "@/lib/api";
import { useApi, useMutation } from "@/lib/useApi";
import { Choice, Field, FieldAction, Section, TableScroll, type Option } from "../../_components/console-ui";
import { orgPath, useOrg } from "../../_lib/org-context";
import { formatDateTime } from "../../_lib/labels";

/**
 * 조직 캘린더 — 휴일·사내 행사를 **iCalendar(RFC 5545) 표준**으로 선언하는 화면.
 *
 * 자체 이벤트 입력 폼이 없는 것은 의도다: 조직 일정은 이미 Google Workspace·Outlook·그룹웨어에 있고,
 * 여기 다시 입력하게 하면 두 곳이 갈라진다 — 갈라진 순간 예측은 틀린 쪽을 믿는다. 입력은 표준 두 경로뿐:
 * 구독 URL(.ics 공개 주소) 또는 .ics 파일 업로드.
 *
 * 피드 **유형이 곧 예측 신호**다: HOLIDAY 피드의 종일 이벤트는 휴일로, EVENT 피드의 종일 이벤트는
 * 사내 행사로 판정된다(요약 텍스트로 추측하지 않는다). 시각이 붙은 회의·교육은 신호가 아니다.
 */
const FEED_TYPES: Option[] = [
  { value: "HOLIDAY", label: "휴일 (전사 휴무)" },
  { value: "EVENT", label: "사내 행사 (워크숍·체육대회)" },
  { value: "WORK", label: "일반 일정 (신호 아님)" },
];

interface FeedView {
  id: string;
  name: string;
  type: string;
  sourceUrl: string | null;
  subscription: boolean;
  enabled: boolean;
  lastSyncedAt: string | null;
  lastSyncStatus: string | null;
}

interface FeedEventPage {
  items: Array<{
    id: string;
    uid: string;
    summary: string | null;
    category: string | null;
    startsAt: string;
    endsAt: string | null;
    allDay: boolean;
  }>;
  total: number;
}

function typeLabel(raw: string): string {
  return FEED_TYPES.find((option) => option.value === raw)?.label ?? raw;
}

export default function CalendarPage() {
  const { orgId } = useOrg();
  const feeds = useApi<FeedView[]>(orgPath(orgId, "/calendar/feeds"), [orgId]);

  const [name, setName] = useState("");
  const [type, setType] = useState<string | null>("HOLIDAY");
  const [sourceUrl, setSourceUrl] = useState("");
  const [previewOf, setPreviewOf] = useState<FeedView | null>(null);
  const fileInput = useRef<HTMLInputElement | null>(null);
  const [uploadTarget, setUploadTarget] = useState<FeedView | null>(null);

  const create = useMutation(async () => {
    const feed = await api.post<FeedView>(orgPath(orgId, "/calendar/feeds"), {
      name: name.trim(),
      type,
      sourceUrl: sourceUrl.trim() || null,
    });
    return feed;
  });

  const sync = useMutation(async (feed: FeedView) => {
    const result = await api.post<{ status: string; imported: number }>(
      orgPath(orgId, `/calendar/feeds/${feed.id}/sync`),
      {},
    );
    return result;
  });

  const toggle = useMutation(async (feed: FeedView) => {
    await api.patch<FeedView>(orgPath(orgId, `/calendar/feeds/${feed.id}`), { enabled: !feed.enabled });
    return true;
  });

  const removeFeed = useMutation(async (feed: FeedView) => {
    await api.delete<void>(orgPath(orgId, `/calendar/feeds/${feed.id}`));
    return true;
  });

  const upload = useMutation(async (input: { feed: FeedView; body: string }) => {
    // .ics 본문을 raw 로 보낸다(JSON 직렬화 금지 — 따옴표가 섞이면 415). 해석은 전부 서버 몫.
    const result = await api.post<{ status: string; imported: number }>(
      orgPath(orgId, `/calendar/feeds/${input.feed.id}/import`),
      undefined,
      { raw: { contentType: "text/calendar", content: input.body } },
    );
    return result;
  });

  return (
    <div className="flex flex-col gap-5">
      <Section
        title="캘린더 피드"
        description="휴일·사내 행사를 iCalendar(.ics) 표준으로 등록합니다. 종일 이벤트만 예측 신호가 되며, 휴일과 행사는 예측에서 다르게 반영됩니다."
      >
        <div className="flex flex-wrap items-start gap-3">
          <Field label="이름" htmlFor="feed-name" className="min-w-48">
            <Input
              id="feed-name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="예: 전사 휴무일"
            />
          </Field>
          <Field label="유형" htmlFor="feed-type" className="min-w-56">
            <Choice id="feed-type" value={type} onChange={setType} options={FEED_TYPES} />
          </Field>
          <Field
            label="구독 URL (선택)"
            htmlFor="feed-url"
            className="min-w-72 flex-1"
            hint="비우면 .ics 업로드형 피드가 됩니다. Google 캘린더의 비공개 iCal 주소를 붙여넣으세요."
          >
            <Input
              id="feed-url"
              value={sourceUrl}
              onChange={(event) => setSourceUrl(event.target.value)}
              placeholder="https://calendar.google.com/calendar/ical/…/basic.ics"
            />
          </Field>
          <FieldAction>
            <Button
              disabled={!name.trim() || !type || create.busy}
              onClick={async () => {
                const feed = await create.mutate();
                if (!feed) return;
                toast.success(`피드 "${feed.name}" 등록`);
                setName("");
                setSourceUrl("");
                feeds.reload();
              }}
            >
              {create.busy ? "등록 중…" : "피드 등록"}
            </Button>
          </FieldAction>
        </div>
        {create.error && <ErrorNotice message={create.error} onDismiss={create.clearError} />}

        {feeds.error && <ErrorNotice message={feeds.error} onRetry={feeds.reload} />}
        {feeds.loading && <RowsSkeleton rows={3} />}
        {!feeds.loading && (feeds.data?.length ?? 0) === 0 && (
          <EmptyState
            title="등록된 캘린더가 없습니다"
            description="휴일 피드를 등록하면 예측이 휴일을 인지하고, 행사 피드를 등록하면 전사 행사일의 급식 감소를 반영합니다."
          />
        )}
        {(feeds.data?.length ?? 0) > 0 && (
          <TableScroll>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>이름</TableHead>
                  <TableHead>유형</TableHead>
                  <TableHead>입력 경로</TableHead>
                  <TableHead>마지막 동기화</TableHead>
                  <TableHead>상태</TableHead>
                  <TableHead className="text-right">작업</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {(feeds.data ?? []).map((feed) => (
                  <TableRow key={feed.id}>
                    <TableCell className="font-medium">{feed.name}</TableCell>
                    <TableCell>
                      <Badge variant={feed.type === "WORK" ? "outline" : "secondary"}>
                        {typeLabel(feed.type)}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-muted-foreground">
                      {feed.subscription ? "구독 (자동 동기화)" : ".ics 업로드"}
                    </TableCell>
                    <TableCell className="text-muted-foreground">
                      {feed.lastSyncedAt ? formatDateTime(feed.lastSyncedAt) : "—"}
                      {feed.lastSyncStatus === "ERROR" && (
                        <Badge variant="destructive" className="ml-2">
                          실패
                        </Badge>
                      )}
                    </TableCell>
                    <TableCell>
                      <Badge variant={feed.enabled ? "secondary" : "outline"}>
                        {feed.enabled ? "사용 중" : "중지됨"}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end gap-1">
                        <Button variant="ghost" size="sm" onClick={() => setPreviewOf(feed)}>
                          일정 보기
                        </Button>
                        {feed.subscription ? (
                          <Button
                            variant="ghost"
                            size="sm"
                            disabled={sync.busy}
                            onClick={async () => {
                              const result = await sync.mutate(feed);
                              if (!result) return;
                              toast.success(`동기화 완료 — ${result.imported}건`);
                              feeds.reload();
                            }}
                          >
                            지금 동기화
                          </Button>
                        ) : (
                          <Button
                            variant="ghost"
                            size="sm"
                            disabled={upload.busy}
                            onClick={() => {
                              setUploadTarget(feed);
                              fileInput.current?.click();
                            }}
                          >
                            .ics 업로드
                          </Button>
                        )}
                        <Button
                          variant="ghost"
                          size="sm"
                          disabled={toggle.busy}
                          onClick={async () => {
                            const done = await toggle.mutate(feed);
                            if (done) feeds.reload();
                          }}
                        >
                          {feed.enabled ? "중지" : "재개"}
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-destructive"
                          disabled={removeFeed.busy}
                          onClick={async () => {
                            const done = await removeFeed.mutate(feed);
                            if (!done) return;
                            toast.success("피드를 삭제했습니다");
                            feeds.reload();
                          }}
                        >
                          삭제
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableScroll>
        )}
        {(sync.error || toggle.error || removeFeed.error || upload.error) && (
          <ErrorNotice
            message={sync.error ?? toggle.error ?? removeFeed.error ?? upload.error ?? ""}
            onDismiss={() => {
              sync.clearError();
              toggle.clearError();
              removeFeed.clearError();
              upload.clearError();
            }}
          />
        )}
        {/* 숨은 파일 입력 — 표에서 "업로드"를 누른 피드로 보낸다. */}
        <input
          ref={fileInput}
          type="file"
          accept=".ics,text/calendar"
          className="hidden"
          onChange={async (event) => {
            const file = event.target.files?.[0];
            event.target.value = "";
            if (!file || !uploadTarget) return;
            const body = await file.text();
            const result = await upload.mutate({ feed: uploadTarget, body });
            if (!result) return;
            toast.success(`가져오기 완료 — ${result.imported}건`);
            feeds.reload();
          }}
        />
      </Section>

      {previewOf && <FeedEventsSection orgId={orgId} feed={previewOf} onClose={() => setPreviewOf(null)} />}
    </div>
  );
}

function FeedEventsSection({ orgId, feed, onClose }: { orgId: string; feed: FeedView; onClose: () => void }) {
  const events = useApi<FeedEventPage>(orgPath(orgId, `/calendar/feeds/${feed.id}/events?size=50`), [
    feed.id,
  ]);
  return (
    <Section
      title={`일정 미리보기 — ${feed.name}`}
      description="종일(하루 단위) 일정만 예측 신호가 됩니다. 시각이 붙은 일정은 참고용입니다."
      action={
        <Button variant="ghost" size="sm" onClick={onClose}>
          닫기
        </Button>
      }
    >
      {events.error && <ErrorNotice message={events.error} onRetry={events.reload} />}
      {events.loading && <RowsSkeleton rows={3} />}
      {!events.loading && (events.data?.items.length ?? 0) === 0 && (
        <EmptyState
          title="일정이 없습니다"
          description="구독이면 동기화를, 업로드형이면 .ics 를 올려 주세요."
        />
      )}
      {(events.data?.items.length ?? 0) > 0 && (
        <TableScroll>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>날짜</TableHead>
                <TableHead>제목</TableHead>
                <TableHead>구분</TableHead>
                <TableHead>예측 신호</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(events.data?.items ?? []).map((event) => (
                <TableRow key={event.id}>
                  <TableCell className="whitespace-nowrap">
                    {event.allDay ? event.startsAt.slice(0, 10) : formatDateTime(event.startsAt)}
                  </TableCell>
                  <TableCell>{event.summary ?? "(제목 없음)"}</TableCell>
                  <TableCell className="text-muted-foreground">{event.category ?? "—"}</TableCell>
                  <TableCell>
                    {event.allDay ? (
                      <Badge variant="secondary">신호</Badge>
                    ) : (
                      <Badge variant="outline">참고용</Badge>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableScroll>
      )}
    </Section>
  );
}
