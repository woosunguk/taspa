"use client";

import { useState } from "react";
import { toast } from "sonner";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api, apiRequest } from "@/lib/api";
import { useApi } from "@/lib/useApi";
import {
  ConfirmDialog,
  Modal,
  PageHeader,
  Section,
  SelectField,
  StatusBadge,
  TableScroll,
  TextAreaField,
  TextField,
  formatDateTime,
} from "../_components/kit";
import { adminErrorText } from "../_lib/errors";
import type { CalendarEventPage, FeedView, OrgView, SyncResultView } from "../_lib/types";

/**
 * 조직 캘린더(iCalendar) 피드 관리.
 *
 * ★수동 동기화는 **HTTP 200 으로도 실패할 수 있다** — 서버의 doSync 는 fetch·업서트 예외를 흡수하고
 * `status: "ERROR"` 를 돌려준다(스케줄 잡이 한 피드 때문에 멈추지 않게 한 설계). 그래서 이 화면은
 * 응답 본문의 status 를 반드시 확인한다. 성공으로 단정하면 관리자는 반영되지 않은 휴일로 식수를
 * 예측하게 된다. 실패 사유는 서버가 본문에 담지 않으므로(로그에만 남는다) 아는 것까지만 말한다.
 */
const FEED_TYPES = [
  { value: "HOLIDAY", label: "HOLIDAY — 휴일(식수 0 근거)" },
  { value: "WORK", label: "WORK — 근무일" },
  { value: "EVENT", label: "EVENT — 행사" },
];

const MODES = [
  { value: "subscription", label: "구독 URL — 서버가 주기적으로 가져옴" },
  { value: "upload", label: ".ics 업로드 — 본문을 직접 붙여넣음" },
];

export default function AdminCalendarPage() {
  const orgs = useApi<OrgView[]>("/api/admin/orgs");
  const [orgId, setOrgId] = useState("");
  const feeds = useApi<FeedView[]>(orgId ? `/api/admin/orgs/${orgId}/calendar/feeds` : null);

  const [createOpen, setCreateOpen] = useState(false);
  const [uploading, setUploading] = useState<FeedView | null>(null);
  const [deleting, setDeleting] = useState<FeedView | null>(null);
  const [preview, setPreview] = useState<FeedView | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  const orgOptions = [
    { value: "", label: "조직을 선택하세요" },
    ...(orgs.data ?? []).map((org) => ({
      value: org.id,
      label: `${org.name} (${org.slug})`,
    })),
  ];

  async function sync(feed: FeedView) {
    setBusy(true);
    setError(undefined);
    try {
      const result = await api.post<SyncResultView>(
        `/api/admin/orgs/${orgId}/calendar/feeds/${feed.id}/sync`,
      );
      if (result.status === "OK") {
        toast.success(`'${feed.name}' 동기화 완료 — ${result.imported}건 반영`);
      } else {
        // 서버가 상태만 남기고 사유는 로그로 보낸다 — 있지도 않은 이유를 지어내지 않는다.
        const text = `'${feed.name}' 동기화 실패(${result.status}). 구독 URL 을 가져오지 못했거나 저장에 실패했습니다 — URL 접근 가능 여부와 서버 로그를 확인하세요.`;
        setError(text);
        toast.error(text);
      }
      feeds.reload();
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return;
      // 파싱 실패는 예외로 올라온다(흡수되지 않는 유일한 단계) — 서버 메시지를 그대로 보여준다.
      const text = adminErrorText(cause);
      setError(text);
      toast.error(text);
    } finally {
      setBusy(false);
    }
  }

  async function toggle(feed: FeedView) {
    setBusy(true);
    setError(undefined);
    try {
      // 활성 토글은 서버가 PATCH 다(부분 수정) — `api` 헬퍼에 없는 메서드라 apiRequest 를 직접 쓴다.
      await apiRequest<FeedView>(`/api/admin/orgs/${orgId}/calendar/feeds/${feed.id}`, {
        method: "PATCH",
        body: { enabled: !feed.enabled },
      });
      toast.success(
        feed.enabled ? `'${feed.name}' 을(를) 비활성화했습니다` : `'${feed.name}' 을(를) 활성화했습니다`,
      );
      feeds.reload();
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return;
      const text = adminErrorText(cause);
      setError(text);
      toast.error(text);
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!deleting) return;
    setBusy(true);
    try {
      await api.delete(`/api/admin/orgs/${orgId}/calendar/feeds/${deleting.id}`);
      toast.success(`'${deleting.name}' 피드를 삭제했습니다`);
      if (preview?.id === deleting.id) setPreview(null);
      setDeleting(null);
      feeds.reload();
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return;
      toast.error(adminErrorText(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="캘린더"
        description="조직의 휴일·근무일 달력을 iCalendar 로 들여옵니다. 여기서 들어온 이벤트가 식수 예측의 휴일 판단 근거가 됩니다."
        actions={
          <Button disabled={!orgId} onClick={() => setCreateOpen(true)}>
            피드 등록
          </Button>
        }
      />

      <Section title="조직" description="피드는 조직 단위로 격리됩니다.">
        <SelectField
          label="조직"
          value={orgId}
          onChange={(value) => {
            setOrgId(value);
            setPreview(null);
            setError(undefined);
          }}
          options={orgOptions}
          disabled={orgs.loading}
          className="max-w-md"
        />
        {orgs.error && <ErrorNotice message={orgs.error} onRetry={orgs.reload} />}
      </Section>

      {!orgId ? (
        <Section>
          <EmptyState
            title="조직을 선택하세요"
            description="조직을 고르면 등록된 캘린더 피드와 동기화 상태를 볼 수 있습니다."
          />
        </Section>
      ) : (
        <Section
          title="피드"
          description={feeds.data ? `${feeds.data.length}개` : undefined}
          actions={
            <Button variant="outline" size="sm" onClick={feeds.reload}>
              새로고침
            </Button>
          }
        >
          <div className="flex flex-col gap-3">
            {error && <ErrorNotice message={error} />}

            {feeds.loading ? (
              <RowsSkeleton rows={4} />
            ) : feeds.error ? (
              <ErrorNotice message={feeds.error} onRetry={feeds.reload} />
            ) : !feeds.data || feeds.data.length === 0 ? (
              <EmptyState
                title="등록된 피드가 없습니다"
                description="공휴일 .ics 구독 URL 을 등록하거나, 사내 달력 파일을 직접 붙여넣어 만드세요."
                action={<Button onClick={() => setCreateOpen(true)}>피드 등록</Button>}
              />
            ) : (
              <TableScroll>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>이름</TableHead>
                      <TableHead>종류</TableHead>
                      <TableHead>출처</TableHead>
                      <TableHead>활성</TableHead>
                      <TableHead className="w-40">마지막 동기화</TableHead>
                      <TableHead>상태</TableHead>
                      <TableHead className="w-72" />
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {feeds.data.map((feed) => (
                      <TableRow key={feed.id} data-state={preview?.id === feed.id ? "selected" : undefined}>
                        <TableCell className="font-medium whitespace-nowrap">{feed.name}</TableCell>
                        <TableCell className="whitespace-nowrap text-muted-foreground">{feed.type}</TableCell>
                        <TableCell
                          className="max-w-64 truncate font-mono text-xs text-muted-foreground"
                          title={feed.sourceUrl ?? ""}
                        >
                          {feed.subscription ? feed.sourceUrl : "업로드"}
                        </TableCell>
                        <TableCell>
                          {feed.enabled ? (
                            <Badge variant="secondary" className="border-border">
                              활성
                            </Badge>
                          ) : (
                            <Badge variant="outline">비활성</Badge>
                          )}
                        </TableCell>
                        <TableCell className="tabular whitespace-nowrap text-muted-foreground">
                          {formatDateTime(feed.lastSyncedAt)}
                        </TableCell>
                        <TableCell className="whitespace-nowrap">
                          {feed.lastSyncStatus ? (
                            <StatusBadge status={feed.lastSyncStatus} />
                          ) : (
                            <span className="text-xs text-muted-foreground">미실행</span>
                          )}
                        </TableCell>
                        <TableCell>
                          <div className="flex items-center justify-end gap-2">
                            {feed.subscription ? (
                              <Button variant="outline" size="sm" disabled={busy} onClick={() => sync(feed)}>
                                동기화
                              </Button>
                            ) : (
                              <Button
                                variant="outline"
                                size="sm"
                                disabled={busy}
                                onClick={() => setUploading(feed)}
                              >
                                .ics 업로드
                              </Button>
                            )}
                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={() => setPreview(preview?.id === feed.id ? null : feed)}
                            >
                              {preview?.id === feed.id ? "접기" : "미리보기"}
                            </Button>
                            <Button variant="outline" size="sm" disabled={busy} onClick={() => toggle(feed)}>
                              {feed.enabled ? "비활성화" : "활성화"}
                            </Button>
                            <Button variant="destructive" size="sm" onClick={() => setDeleting(feed)}>
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

            {feeds.data?.some((feed) => feed.lastSyncStatus === "ERROR") && (
              <p className="text-xs text-muted-foreground">
                <StatusBadge status="ERROR" /> 상태인 피드는 마지막 시도가 실패한 것입니다. 서버는 사유를
                응답에 담지 않고 로그에만 남깁니다 — 흔한 원인은 구독 URL 접근 불가(사설망·http 는 SSRF 가드가
                차단), 인증이 필요한 URL, .ics 형식 오류입니다. 이전에 성공한 이벤트는 그대로 남아 있습니다.
              </p>
            )}
          </div>
        </Section>
      )}

      {preview && orgId && (
        <EventsPreview key={preview.id} orgId={orgId} feed={preview} onClose={() => setPreview(null)} />
      )}

      {orgId && (
        <CreateFeedModal
          open={createOpen}
          orgId={orgId}
          onOpenChange={setCreateOpen}
          onCreated={() => {
            setCreateOpen(false);
            feeds.reload();
          }}
        />
      )}

      {uploading && orgId && (
        <UploadModal
          key={uploading.id}
          orgId={orgId}
          feed={uploading}
          onClose={() => setUploading(null)}
          onImported={() => {
            setUploading(null);
            feeds.reload();
          }}
        />
      )}

      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title="피드를 삭제할까요?"
        message={`'${deleting?.name ?? ""}' 을(를) 삭제하면 이 피드로 들여온 이벤트도 전부 함께 삭제됩니다. 휴일 정보가 사라지면 이후 식수 예측이 달라집니다.`}
        confirmLabel="삭제"
        busy={busy}
        onConfirm={remove}
      />
    </div>
  );
}

/* ── 등록 ─────────────────────────────────────────────────────────────── */

function CreateFeedModal({
  open,
  orgId,
  onOpenChange,
  onCreated,
}: {
  open: boolean;
  orgId: string;
  onOpenChange: (open: boolean) => void;
  onCreated: () => void;
}) {
  const [name, setName] = useState("");
  const [type, setType] = useState("HOLIDAY");
  const [mode, setMode] = useState("subscription");
  const [sourceUrl, setSourceUrl] = useState("");
  const [ics, setIcs] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  const subscription = mode === "subscription";

  async function submit() {
    setBusy(true);
    setError(undefined);
    try {
      const feed = await api.post<FeedView>(`/api/admin/orgs/${orgId}/calendar/feeds`, {
        name: name.trim(),
        type,
        sourceUrl: subscription ? sourceUrl.trim() : null,
      });
      // 업로드형이면 방금 만든 피드에 .ics 본문을 곧바로 넣는다(서버는 text/calendar 원문을 받는다).
      if (!subscription && ics.trim()) {
        const result = await importIcs(orgId, feed.id, ics);
        toast.success(`피드를 만들고 ${result.imported}건을 들여왔습니다`);
      } else {
        toast.success("피드를 등록했습니다");
      }
      setName("");
      setSourceUrl("");
      setIcs("");
      onCreated();
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return;
      setError(adminErrorText(cause));
    } finally {
      setBusy(false);
    }
  }

  const invalid = name.trim().length === 0 || (subscription && sourceUrl.trim().length === 0);

  return (
    <Modal
      open={open}
      onOpenChange={onOpenChange}
      wide
      title="피드 등록"
      description="구독형은 등록 시점에 URL 을 검증합니다(https 만 허용, 사설망·메타데이터 주소는 SSRF 가드가 거부). 출처 방식은 등록 후 바꿀 수 없습니다."
      footer={
        <>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>
            취소
          </Button>
          <Button onClick={submit} disabled={busy || invalid}>
            {busy ? "등록 중" : "등록"}
          </Button>
        </>
      }
    >
      {error && <ErrorNotice message={error} />}
      <div className="grid gap-3 sm:grid-cols-2">
        <TextField label="이름" value={name} onChange={setName} placeholder="대한민국 공휴일" />
        <SelectField label="종류" value={type} onChange={setType} options={FEED_TYPES} />
      </div>
      <SelectField label="수집 방식" value={mode} onChange={setMode} options={MODES} />
      {subscription ? (
        <TextField
          label="구독 URL"
          value={sourceUrl}
          onChange={setSourceUrl}
          placeholder="https://example.com/holidays.ics"
          hint="https 만 허용됩니다. 서버가 주기적으로 가져오며, 여기서 수동 동기화도 할 수 있습니다."
        />
      ) : (
        <TextAreaField
          label=".ics 본문"
          value={ics}
          onChange={setIcs}
          rows={10}
          mono
          hint="선택 사항 — 지금 비워 두고 등록한 뒤 나중에 목록의 '.ics 업로드' 로 넣어도 됩니다. 업로드는 이 피드의 이벤트를 대체합니다."
          placeholder={"BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\n..."}
        />
      )}
    </Modal>
  );
}

/* ── .ics 업로드 ──────────────────────────────────────────────────────── */

/** 서버는 `consumes = text/calendar` 라 JSON 직렬화하면 415 다 — 원문 그대로 보낸다. */
function importIcs(orgId: string, feedId: string, ics: string): Promise<SyncResultView> {
  return apiRequest<SyncResultView>(`/api/admin/orgs/${orgId}/calendar/feeds/${feedId}/import`, {
    method: "POST",
    raw: { contentType: "text/calendar", content: ics },
  });
}

function UploadModal({
  orgId,
  feed,
  onClose,
  onImported,
}: {
  orgId: string;
  feed: FeedView;
  onClose: () => void;
  onImported: () => void;
}) {
  const [ics, setIcs] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  async function submit() {
    setBusy(true);
    setError(undefined);
    try {
      const result = await importIcs(orgId, feed.id, ics);
      toast.success(`'${feed.name}' 에 ${result.imported}건을 들여왔습니다`);
      onImported();
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return;
      setError(adminErrorText(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      onOpenChange={(open) => !open && onClose()}
      wide
      title={`.ics 업로드 — ${feed.name}`}
      description="이 피드의 이벤트를 업로드 본문으로 대체(업서트)합니다. 본문에 없는 기존 이벤트는 남습니다."
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={busy}>
            취소
          </Button>
          <Button onClick={submit} disabled={busy || ics.trim().length === 0}>
            {busy ? "들여오는 중" : "들여오기"}
          </Button>
        </>
      }
    >
      {error && <ErrorNotice message={error} />}
      <TextAreaField
        label=".ics 본문"
        value={ics}
        onChange={setIcs}
        rows={14}
        mono
        placeholder={"BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\n..."}
      />
    </Modal>
  );
}

/* ── 이벤트 미리보기 ──────────────────────────────────────────────────── */

function EventsPreview({ orgId, feed, onClose }: { orgId: string; feed: FeedView; onClose: () => void }) {
  const [page, setPage] = useState(0);
  const events = useApi<CalendarEventPage>(
    `/api/admin/orgs/${orgId}/calendar/feeds/${feed.id}/events?page=${page}`,
  );

  return (
    <Section
      title={`최근 이벤트 — ${feed.name}`}
      description="시작 시각 내림차순입니다. 동기화가 실제로 무엇을 넣었는지 확인하세요."
      actions={
        <Button variant="ghost" size="sm" onClick={onClose}>
          닫기
        </Button>
      }
    >
      <div className="flex flex-col gap-3">
        {events.loading ? (
          <RowsSkeleton rows={5} />
        ) : events.error ? (
          <ErrorNotice message={events.error} onRetry={events.reload} />
        ) : !events.data || events.data.items.length === 0 ? (
          <EmptyState
            title="이벤트가 없습니다"
            description="아직 동기화하지 않았거나, 가져온 .ics 에 VEVENT 가 없습니다."
          />
        ) : (
          <>
            <TableScroll>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>요약</TableHead>
                    <TableHead>분류</TableHead>
                    <TableHead className="w-40">시작</TableHead>
                    <TableHead className="w-40">종료</TableHead>
                    <TableHead>종일</TableHead>
                    <TableHead>출처</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {events.data.items.map((event) => (
                    <TableRow key={event.id}>
                      <TableCell className="font-medium whitespace-nowrap">{event.summary ?? "—"}</TableCell>
                      <TableCell className="whitespace-nowrap text-muted-foreground">
                        {event.category ?? "—"}
                      </TableCell>
                      <TableCell className="tabular whitespace-nowrap text-muted-foreground">
                        {formatDateTime(event.startsAt)}
                      </TableCell>
                      <TableCell className="tabular whitespace-nowrap text-muted-foreground">
                        {formatDateTime(event.endsAt)}
                      </TableCell>
                      <TableCell className="text-xs text-muted-foreground">
                        {event.allDay ? "예" : "—"}
                      </TableCell>
                      <TableCell className="text-xs text-muted-foreground">{event.source}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableScroll>

            <div className="flex flex-wrap items-center justify-between gap-2">
              <p className="text-xs text-muted-foreground">
                전체 {events.data.total}건 · {events.data.page + 1} 페이지 ({events.data.size}건씩)
              </p>
              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page === 0}
                  onClick={() => setPage((current) => Math.max(0, current - 1))}
                >
                  이전
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={!events.data.hasNext}
                  onClick={() => setPage((current) => current + 1)}
                >
                  다음
                </Button>
              </div>
            </div>
          </>
        )}
      </div>
    </Section>
  );
}
