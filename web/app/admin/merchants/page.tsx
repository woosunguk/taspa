"use client";

import { useState } from "react";
import { toast } from "sonner";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api } from "@/lib/api";
import { useApi } from "@/lib/useApi";
import {
  ConfirmDialog,
  Modal,
  PageHeader,
  Section,
  SelectField,
  StatusBadge,
  TableScroll,
  TextField,
  formatDateTime,
} from "../_components/kit";
import { merchantCategoryLabel } from "@/app/merchant/_lib/format";
import { adminErrorText } from "../_lib/errors";
import type { MerchantMemberView, MerchantView } from "../_lib/types";
import { SitePicker } from "./SitePicker";

/**
 * 가맹점(식권 사용처) 관리.
 *
 * 상태는 fail-closed 다 — `ACTIVE` 인 가맹만 식권 승인을 받을 수 있고 `PENDING`·`SUSPENDED` 는 거절된다.
 * 사업장(site) 연결은 소비 이벤트의 site 축을 결정하므로, 조직 콘솔에서 만든 사업장의 UUID 를 넣는다.
 */
const CATEGORIES = [
  { value: "RESTAURANT", label: "RESTAURANT — 식당" },
  { value: "CONVENIENCE", label: "CONVENIENCE — 편의점" },
  { value: "CAFE", label: "CAFE — 카페" },
];

const STATUSES = [
  { value: "PENDING", label: "PENDING — 승인 대기(결제 불가)" },
  { value: "ACTIVE", label: "ACTIVE — 정상(결제 가능)" },
  { value: "SUSPENDED", label: "SUSPENDED — 정지(결제 불가)" },
];

/** 자주 쓰는 존 + 직접 입력. 목록은 소개용일 뿐 서버(organizationService.requireValidTimezone)가 최종 검증한다. */
const TIMEZONE_PRESETS = [
  { value: "Asia/Seoul", label: "Asia/Seoul — 서울(KST)" },
  { value: "UTC", label: "UTC" },
  { value: "Asia/Tokyo", label: "Asia/Tokyo — 도쿄(JST)" },
];
const CUSTOM_TIMEZONE = "__custom__";

interface Draft {
  id: string | null;
  name: string;
  category: string;
  status: string;
  siteId: string;
  timezone: string;
}

/*
 * ★등록 기본 상태는 **ACTIVE** 다(서버 엔티티 기본값과 같다).
 *
 * 예전 기본값 PENDING 은 "신중한 기본값"처럼 보였지만 실제로는 신규 가맹 온보딩을 **기본 경로에서**
 * 끊었다: PENDING 매장은 결제도 담당자 콘솔도 열리지 않는데, 등록·담당자 지정 두 작업 모두 성공을
 * 보고하고 어느 화면도 사유를 말하지 않았다(사장은 "권한을 못 받았다"고 문의하고, 관리자 화면에는
 * 담당자가 ACTIVE 로 보인다). 승인 절차를 두려면 상태 하나가 아니라 그 절차 자체가 있어야 한다.
 */
const EMPTY: Draft = {
  id: null,
  name: "",
  category: "RESTAURANT",
  status: "ACTIVE",
  siteId: "",
  timezone: "Asia/Seoul",
};

export default function AdminMerchantsPage() {
  const merchants = useApi<MerchantView[]>("/api/admin/merchants");
  const [draft, setDraft] = useState<Draft | null>(null);
  const [deleting, setDeleting] = useState<MerchantView | null>(null);
  const [managingMembers, setManagingMembers] = useState<MerchantView | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);
  const [filter, setFilter] = useState("");
  /** 편집을 연 시점의 사업장 id — SitePicker 가 "원래 값"과 "지금 값"을 구분하는 근거. */
  const [editingSiteId, setEditingSiteId] = useState<string | undefined>(undefined);

  /* 최근 등록이 위로 오게 한다 — 운영자가 방금 만든 것을 찾는 일이 가장 흔하다. */
  const term = filter.trim().toLowerCase();
  const visible = (merchants.data ?? [])
    .filter(
      (merchant) =>
        term.length === 0 ||
        merchant.name.toLowerCase().includes(term) ||
        merchant.category.toLowerCase().includes(term),
    )
    .slice()
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt));

  async function save() {
    if (!draft) return;
    setBusy(true);
    setError(undefined);
    const body = {
      name: draft.name.trim(),
      category: draft.category,
      status: draft.status,
      siteId: draft.siteId.trim() || null,
      // 비우면(직접 입력을 지우면) 생성은 UTC, 수정은 기존 값 유지 — 서버가 full-replace 하지 않는다.
      timezone: draft.timezone.trim() || null,
    };
    try {
      if (draft.id) await api.put<MerchantView>(`/api/admin/merchants/${draft.id}`, body);
      else await api.post<MerchantView>("/api/admin/merchants", body);
      toast.success(draft.id ? "가맹점을 저장했습니다" : "가맹점을 등록했습니다");
      setDraft(null);
      merchants.reload();
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return;
      setError(adminErrorText(cause));
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!deleting) return;
    setBusy(true);
    try {
      await api.delete(`/api/admin/merchants/${deleting.id}`);
      toast.success(`'${deleting.name}' 을(를) 삭제했습니다`);
      setDeleting(null);
      merchants.reload();
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
        title="가맹점"
        description="식권 QR 을 받을 수 있는 매장입니다. POS 단말은 여기서 만든 가맹 UUID 에 결속된 M2M 클라이언트로 승인 요청을 보냅니다."
        actions={
          <Button
            onClick={() => {
              setEditingSiteId(undefined);
              setDraft(EMPTY);
            }}
          >
            가맹점 등록
          </Button>
        }
      />

      <Section
        title="가맹점 목록"
        description={
          merchants.data
            ? filter.trim()
              ? `${visible.length}곳 / 전체 ${merchants.data.length}곳`
              : `${merchants.data.length}곳`
            : undefined
        }
        actions={
          <>
            {/*
              ★검색이 없던 동안 방금 등록한 매장이 **화면 밖**에 생겼다(목록은 등록 순이고 수십 곳이다).
              등록 직후 "성공했습니다" 토스트는 뜨는데 눈으로는 확인할 수 없어, 운영자는 같은 매장을
              한 번 더 등록하거나 실패했다고 판단한다.
            */}
            <input
              type="search"
              value={filter}
              onChange={(event) => setFilter(event.target.value)}
              placeholder="이름·업종 검색"
              aria-label="가맹점 검색"
              className="h-8 w-44 rounded-md border border-input bg-background px-2 text-sm"
            />
            <Button variant="outline" size="sm" onClick={merchants.reload}>
              새로고침
            </Button>
          </>
        }
      >
        {merchants.loading ? (
          <RowsSkeleton rows={4} />
        ) : merchants.error ? (
          <ErrorNotice message={merchants.error} onRetry={merchants.reload} />
        ) : visible.length === 0 ? (
          <EmptyState
            title={term ? "검색 결과가 없습니다" : "등록된 가맹점이 없습니다"}
            description={
              term
                ? "다른 이름이나 업종으로 검색해 보세요."
                : "구내식당이나 제휴 매장을 등록하면 식권 승인을 받을 수 있습니다."
            }
          />
        ) : (
          <TableScroll>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>이름</TableHead>
                  <TableHead>업종</TableHead>
                  <TableHead>상태</TableHead>
                  <TableHead>사업장</TableHead>
                  <TableHead>타임존</TableHead>
                  <TableHead className="w-40">등록</TableHead>
                  <TableHead className="w-64" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {visible.map((merchant) => (
                  <TableRow key={merchant.id}>
                    <TableCell className="font-medium whitespace-nowrap">
                      {merchant.name}
                      <span className="ml-2 font-mono text-xs text-muted-foreground">
                        {merchant.id.slice(0, 8)}…
                      </span>
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">
                      {merchantCategoryLabel(merchant.category)}
                    </TableCell>
                    <TableCell>
                      <StatusBadge status={merchant.status} />
                    </TableCell>
                    <TableCell className="text-xs whitespace-nowrap text-muted-foreground">
                      {merchant.siteId ? (
                        // font-mono 는 UUID 조각에만 — 셀에 걸면 한글 '연결 없음'까지 등폭으로 벌어진다.
                        <span className="font-mono" title={merchant.siteId}>
                          {merchant.siteId.slice(0, 8)}…
                        </span>
                      ) : (
                        "연결 없음"
                      )}
                    </TableCell>
                    <TableCell className="whitespace-nowrap">
                      <span
                        className={
                          merchant.timezone === "UTC"
                            ? "font-medium text-[color:var(--taspa-warning)]"
                            : "text-muted-foreground"
                        }
                        title={
                          merchant.timezone === "UTC"
                            ? "타임존이 설정되지 않아 UTC 로 남아 있습니다. 소비 집계 날짜가 실제 매장 시간과 어긋날 수 있습니다."
                            : undefined
                        }
                      >
                        {merchant.timezone}
                        {merchant.timezone === "UTC" && (
                          <span className="ml-1 text-xs font-normal">· 확인 필요</span>
                        )}
                      </span>
                    </TableCell>
                    <TableCell className="tabular whitespace-nowrap text-muted-foreground">
                      {formatDateTime(merchant.createdAt)}
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center justify-end gap-2">
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => {
                            setEditingSiteId(merchant.siteId ?? "");
                            setDraft({
                              id: merchant.id,
                              name: merchant.name,
                              category: merchant.category,
                              status: merchant.status,
                              siteId: merchant.siteId ?? "",
                              timezone: merchant.timezone,
                            });
                          }}
                        >
                          수정
                        </Button>
                        <Button variant="outline" size="sm" onClick={() => setManagingMembers(merchant)}>
                          담당자
                        </Button>
                        <Button variant="destructive" size="sm" onClick={() => setDeleting(merchant)}>
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
      </Section>

      <Modal
        open={draft !== null}
        onOpenChange={(open) => {
          if (!open) {
            setDraft(null);
            setError(undefined);
          }
        }}
        title={draft?.id ? "가맹점 수정" : "가맹점 등록"}
        footer={
          <>
            <Button variant="outline" onClick={() => setDraft(null)} disabled={busy}>
              취소
            </Button>
            <Button onClick={save} disabled={busy || !draft || draft.name.trim().length === 0}>
              {busy ? "저장 중" : "저장"}
            </Button>
          </>
        }
      >
        {error && <ErrorNotice message={error} />}
        {draft && (
          <>
            <TextField
              label="이름"
              value={draft.name}
              onChange={(value) => setDraft({ ...draft, name: value })}
              placeholder="본사 구내식당"
            />
            <SelectField
              label="업종"
              value={draft.category}
              onChange={(value) => setDraft({ ...draft, category: value })}
              options={CATEGORIES}
            />
            <SelectField
              label="상태"
              value={draft.status}
              onChange={(value) => setDraft({ ...draft, status: value })}
              options={STATUSES}
              hint="ACTIVE 인 가맹만 식권 승인을 받고, 담당자 콘솔도 그때 열립니다. PENDING·SUSPENDED 로 두면 지정된 담당자에게 매장이 잠긴 상태로 보입니다."
            />
            {/*
              ★`initialSiteId` 는 **편집을 시작한 시점의 값**이어야 한다. 현재 편집값(`draft.siteId`)을
              넘기면 둘이 항상 같아져, SitePicker 의 "조직을 바꾸면 이전 조직의 사업장을 들고 있지
              않는다"(교차 테넌트 오귀속 방지) 가드가 영구히 거짓이 된다.
            */}
            <SitePicker
              value={draft.siteId}
              onChange={(siteId) => setDraft({ ...draft, siteId })}
              initialSiteId={editingSiteId}
            />
            <SelectField
              label="타임존"
              value={
                TIMEZONE_PRESETS.some((zone) => zone.value === draft.timezone)
                  ? draft.timezone
                  : CUSTOM_TIMEZONE
              }
              onChange={(value) =>
                setDraft({
                  ...draft,
                  timezone: value === CUSTOM_TIMEZONE ? "" : value,
                })
              }
              options={[...TIMEZONE_PRESETS, { value: CUSTOM_TIMEZONE, label: "직접 입력" }]}
              hint="가맹 그레인 집계·예측의 하루 경계 앵커입니다. 수정 시 비우면 지워지는 게 아니라 기존 값이 그대로 유지됩니다(생성 시 비우면 UTC)."
            />
            {!TIMEZONE_PRESETS.some((zone) => zone.value === draft.timezone) && (
              <TextField
                label="타임존 직접 입력"
                value={draft.timezone}
                onChange={(value) => setDraft({ ...draft, timezone: value })}
                placeholder="예: Asia/Ho_Chi_Minh"
                hint="IANA 존 이름. 비워두면 위 안내대로 처리됩니다."
              />
            )}
          </>
        )}
      </Modal>

      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title="가맹점을 삭제할까요?"
        message={`'${deleting?.name ?? ""}' 을(를) 삭제하면 이 매장에서의 신규 승인이 불가능해집니다. 결제를 잠시 막으려는 것이라면 삭제 대신 상태를 SUSPENDED 로 바꾸는 편이 안전합니다.`}
        confirmLabel="삭제"
        busy={busy}
        onConfirm={remove}
      />

      {managingMembers && (
        <MerchantMembersModal merchant={managingMembers} onClose={() => setManagingMembers(null)} />
      )}
    </div>
  );
}

/* ── 가맹 담당자(사람 신원) 관리 ──────────────────────────────────────────── */

function MerchantMembersModal({ merchant, onClose }: { merchant: MerchantView; onClose: () => void }) {
  const members = useApi<MerchantMemberView[]>(`/api/admin/merchants/${merchant.id}/members`);
  const [email, setEmail] = useState("");
  const [removing, setRemoving] = useState<MerchantMemberView | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  async function add() {
    setBusy(true);
    setError(undefined);
    try {
      await api.post<MerchantMemberView>(`/api/admin/merchants/${merchant.id}/members`, {
        email: email.trim(),
      });
      toast.success(`'${email.trim()}' 을(를) 담당자로 추가했습니다`);
      setEmail("");
      members.reload();
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return;
      setError(adminErrorText(cause));
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!removing) return;
    setBusy(true);
    try {
      await api.delete(`/api/admin/merchants/${merchant.id}/members/${removing.userId}`);
      toast.success(`'${removing.email ?? removing.userId}' 담당자를 해제했습니다`);
      setRemoving(null);
      members.reload();
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return;
      toast.error(adminErrorText(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Modal
        open
        onOpenChange={(open) => !open && onClose()}
        title={`담당자 — ${merchant.name}`}
        description="이 가맹의 관리 콘솔(/merchant, 매출·식수예측)에 들어갈 수 있는 사람입니다. 역할은 항상 MERCHANT_ADMIN 이며 부여는 플랫폼 관리자만 할 수 있습니다."
        footer={
          <Button variant="outline" onClick={onClose}>
            닫기
          </Button>
        }
      >
        {error && <ErrorNotice message={error} />}

        {/*
          ★매장이 ACTIVE 가 아니면 **부여 전에** 말한다. 그렇지 않으면 이 모달은 "담당자로 추가했습니다"
          라고 성공을 보고하고 표에도 ACTIVE 로 뜨는데, 정작 그 사람 화면에서는 매장이 잠겨 있다.
          두 화면이 서로 다른 사실을 말하는 상태라 양쪽 모두 원인을 짐작할 수 없다.
        */}
        {merchant.status !== "ACTIVE" && (
          <div className="rounded-lg bg-warning-soft px-3 py-2.5 text-sm text-warning">
            이 매장은 <strong>{merchant.status}</strong> 상태라 담당자를 지정해도 지금은 콘솔이 열리지
            않습니다. 담당자 화면에는 &lsquo;아직 열 수 없는 매장&rsquo;으로 사유와 함께 표시됩니다 — 바로
            쓰게 하려면 매장 상태를 ACTIVE 로 바꿔 주세요.
          </div>
        )}

        {members.loading ? (
          <RowsSkeleton rows={2} />
        ) : members.error ? (
          <ErrorNotice message={members.error} onRetry={members.reload} />
        ) : !members.data || members.data.length === 0 ? (
          <EmptyState
            title="등록된 담당자가 없습니다"
            description="아래에서 기존 사용자의 이메일로 담당자를 추가하세요."
          />
        ) : (
          <TableScroll>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>이메일</TableHead>
                  <TableHead>이름</TableHead>
                  <TableHead>상태</TableHead>
                  <TableHead className="w-20" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {members.data.map((member) => (
                  <TableRow key={member.userId}>
                    <TableCell className="font-medium whitespace-nowrap">
                      {member.email ?? member.userId}
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">
                      {member.displayName ?? "—"}
                    </TableCell>
                    <TableCell>
                      <StatusBadge status={member.status} />
                    </TableCell>
                    <TableCell>
                      <Button
                        variant="destructive"
                        size="sm"
                        disabled={busy}
                        onClick={() => setRemoving(member)}
                      >
                        해제
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableScroll>
        )}

        <div className="flex items-end gap-2 border-t border-border pt-3">
          <TextField
            label="이메일로 추가"
            value={email}
            onChange={setEmail}
            placeholder="staff@example.com"
            hint="이미 가입된 사용자만 담당자로 지정할 수 있습니다."
            className="flex-1"
          />
          <Button size="sm" onClick={add} disabled={busy || email.trim().length === 0}>
            추가
          </Button>
        </div>
      </Modal>

      <ConfirmDialog
        open={removing !== null}
        onOpenChange={(open) => !open && setRemoving(null)}
        title="담당자를 해제할까요?"
        message={`'${removing?.email ?? removing?.userId ?? ""}' 은(는) 더 이상 이 가맹의 관리 콘솔에 들어갈 수 없습니다.`}
        confirmLabel="해제"
        busy={busy}
        onConfirm={remove}
      />
    </>
  );
}
