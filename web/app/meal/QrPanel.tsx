"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import QRCode from "react-qr-code";
import { ApiError, api } from "@/lib/api";
import { messageOf } from "@/lib/useApi";
import { Button } from "@/components/ui/button";
import { useTicker } from "./useTicker";
import { formatTimeInZone, windowRangeLabel, type MealEntitlement, type MealQrIssue } from "./types";

/**
 * 기기 시계가 어긋났을 때의 상한/대체값.
 *
 * 만료 시각의 출처는 **언제나 서버 응답(expiresAt)** 이다. 다만 `expiresAt - Date.now()` 는 기기 시계가
 * 몇 분씩 틀어져 있으면 음수(즉시 만료)나 터무니없이 큰 값이 된다. 그래서 남은 시간이 상식적인 범위일
 * 때만 그대로 쓰고, 벗어나면 서버 기본 TTL(taspa.meal.qr-ttl = 60초)로 되돌린다. 어느 쪽이든 화면의
 * 카운트다운은 **경과 시간**으로만 줄어들고, 실제 유효성은 서버(redeem)가 최종 판정한다.
 */
const FALLBACK_TTL_MS = 60 * 1000;

/**
 * 429(QR_RATE_LIMITED) 이후 버튼을 잠가 둘 시간. 서버는 재시도 가능 시각을 응답에 담지 않으므로
 * (Retry-After 헤더도 없다) 서버 기본 쿨다운(taspa.meal.qr-issue-cooldown = 10초)을 UI 대기값으로 쓴다.
 * 판정은 어차피 서버가 한다 — 이 값이 짧으면 429 를 한 번 더 받을 뿐이고, 길면 조금 더 기다릴 뿐이다.
 */
const COOLDOWN_HINT_MS = 10 * 1000;

/**
 * 서버는 QR_RATE_LIMITED 에 ErrorCode 기본 문구(영문)를 실어 보낸다. 다른 오류는 서버 문구가 한국어라
 * 그대로 노출하지만, 이 하나는 화면에 영문을 띄울 수 없어 서버 i18n 의 ko 문구(meal.rateLimited)와
 * 같은 문장으로 대체한다.
 */
const RATE_LIMITED_MESSAGE = "발급 요청이 너무 잦습니다.";

interface ActiveQr {
  token: string;
  /** 이 기기 시계 기준 만료 시각(ms). 표시는 항상 지금과의 차이로만 계산한다. */
  deadlineMs: number;
}

/**
 * 발급 결과는 **어느 조직으로 발급했는지와 함께** 들고 있는다. 조직을 바꾸면 이전 결과(QR·오류)는
 * 자동으로 화면에서 사라진다 — 초기화 effect 없이 렌더에서 그대로 판별된다.
 */
interface IssueResult {
  orgId: string;
  qr: ActiveQr | null;
  error?: string;
}

/**
 * 표시용 만료 시각. **오차는 항상 짧은 쪽으로만** 낸다.
 *
 * `expiresAt - Date.now()` 는 기기 시계가 틀어지면 그만큼 왜곡된다. 길게 왜곡되는 쪽이 위험하다 —
 * 시계가 3분 느리면 이미 죽은 QR 을 "아직 240초 남음"으로 표시해 계산대에서 실패하게 만든다.
 * 반대로 짧게 잡히면 사용자가 한 번 더 발급할 뿐이다. 그래서 서버 TTL(60초)을 **상한**으로 두고,
 * 계산값이 그보다 크거나 비정상이면 상한을 쓴다. 유효성의 최종 판정은 언제나 서버(redeem)다.
 */
function deadlineFrom(expiresAt: string): number {
  const expiresAtMs = Date.parse(expiresAt);
  const remaining = expiresAtMs - Date.now();
  const ttl =
    Number.isFinite(expiresAtMs) && remaining > 0 ? Math.min(remaining, FALLBACK_TTL_MS) : FALLBACK_TTL_MS;
  return Date.now() + ttl;
}

/**
 * 지금 발급이 막혀 있다면 그 이유를 사람 말로. **판정은 서버(entitlement)가 하고 여기선 문구만 고른다** —
 * `canIssueNow` 는 redeem 이 거절하는 두 조건(끼니창 밖·일 횟수 소진)과 같은 계산이라, 이 안내를
 * 화면에서 다시 계산하면 서버와 갈라진다.
 *
 * 자격을 아직 모르는 동안(로딩·조회 실패)에는 막지 않는다: 확신 없이 버튼을 잠그면 실제로는 결제할 수
 * 있는 사람이 계산대 앞에서 QR 을 못 만드는 쪽이 더 나쁘다. 최종 판정은 어차피 발급·승인 시 서버가 한다.
 */
function blockedReason(entitlement: MealEntitlement | undefined): string | null {
  if (!entitlement || entitlement.canIssueNow) return null;
  if (!entitlement.currentWindow) {
    if (!entitlement.nextWindow) return "이 조직에는 이용 가능한 식사 시간대가 설정되어 있지 않습니다.";
    const next = entitlement.nextWindow;
    return `지금은 식사 시간이 아닙니다. 다음 ${windowRangeLabel(next)} (${formatTimeInZone(
      next.startsAt,
      entitlement.timezone,
    )}) 부터 발급할 수 있습니다.`;
  }
  return `오늘 이용 가능한 횟수(${entitlement.dailyMealCount}회)를 모두 썼습니다. 내일 다시 이용할 수 있습니다.`;
}

export function QrPanel({
  orgId,
  orgName,
  entitlement,
  onIssued,
  onExpired,
}: {
  orgId: string | null;
  orgName: string | null;
  /** 서버가 판정한 현재 자격. undefined 면 아직 모른다(버튼을 잠그지 않는다 — blockedReason 주석 참고). */
  entitlement: MealEntitlement | undefined;
  /** 발급 직후 — 직전 결제가 이력에 반영됐을 수 있다. */
  onIssued: () => void;
  /** 만료 — QR 이 살아있던 사이 결제됐을 수 있으니 이력을 갱신한다. */
  onExpired: () => void;
}) {
  const [result, setResult] = useState<IssueResult | null>(null);
  const [busy, setBusy] = useState(false);
  // 쿨다운은 사용자 단위(서버가 userId 로 판정)라 조직을 바꿔도 유지한다.
  const [cooldownUntilMs, setCooldownUntilMs] = useState<number | null>(null);

  const current = result && result.orgId === orgId ? result : null;
  const qr = current?.qr ?? null;
  const error = current?.error;

  const now = useTicker();
  const remainingSec = qr ? Math.max(0, Math.ceil((qr.deadlineMs - now) / 1000)) : 0;
  const expired = qr !== null && remainingSec === 0;
  const cooldownSec = cooldownUntilMs ? Math.max(0, Math.ceil((cooldownUntilMs - now) / 1000)) : 0;

  // 만료는 QR 하나당 한 번만 알린다(매 초 이력을 다시 부르지 않게).
  const expiryNotifiedFor = useRef<string | null>(null);
  useEffect(() => {
    if (!expired || !qr) return;
    if (expiryNotifiedFor.current === qr.token) return;
    expiryNotifiedFor.current = qr.token;
    onExpired();
  }, [expired, qr, onExpired]);

  const issue = useCallback(async () => {
    if (!orgId) return;
    setBusy(true);
    try {
      const issued = await api.post<MealQrIssue>("/api/meal/qr", { orgId });
      setResult({
        orgId,
        qr: { token: issued.token, deadlineMs: deadlineFrom(issued.expiresAt) },
      });
      // 발급 성공 시점부터 서버 쿨다운이 시작된다 — 여기서 해제하면 버튼이 열린 채로 남아
      // 10초 안의 재클릭이 **확정 429** 가 된다. 성공 경로에서도 동일하게 잠근다.
      setCooldownUntilMs(Date.now() + COOLDOWN_HINT_MS);
      onIssued();
    } catch (cause) {
      // 세션 만료·step-up 은 api 계층이 이미 이동을 시작했다 — 화면을 오류로 바꾸지 않는다.
      if (cause instanceof Error && cause.message === "navigating") return;
      if (cause instanceof ApiError && cause.status === 429) {
        // 쿨다운은 발급 실패일 뿐이다. 아직 유효한 QR 이 떠 있으면 그대로 둔다.
        setCooldownUntilMs(Date.now() + COOLDOWN_HINT_MS);
        setResult((previous) => ({
          orgId,
          qr: previous && previous.orgId === orgId ? previous.qr : null,
          error: RATE_LIMITED_MESSAGE,
        }));
        return;
      }
      // 403(비멤버) 등 서버 판정은 문구를 그대로 전한다 — 빈 화면으로 숨기지 않는다.
      setResult({ orgId, qr: null, error: messageOf(cause) });
    } finally {
      setBusy(false);
    }
  }, [orgId, onIssued]);

  const waiting = cooldownSec > 0;
  // 서버가 "지금은 안 된다"고 말했으면 누를 수 없게 한다 — 눌러서 QR 을 받고 계산대에서 거절당하는
  // 것보다, 왜 안 되는지와 언제 되는지를 미리 말해 주는 편이 낫다.
  const blocked = blockedReason(entitlement);
  const label = busy
    ? "발급 중"
    : blocked
      ? "지금은 발급할 수 없습니다"
      : waiting
        ? `다시 시도 (${cooldownSec}초)`
        : qr
          ? "다시 발급"
          : "식권 QR 발급";

  return (
    <div className="flex flex-col items-center gap-4">
      {/*
        ★대기 상태에서는 자리를 **잡아 두지 않는다**(`min-h` 는 QR 이 있을 때만).

        예전에는 두 상태 모두 19rem 을 예약했다. 이유는 "발급 순간 버튼이 아래로 밀리면 계산대 앞에서
        손가락이 빗나간다" 였는데, 그 대가로 **정작 눌러야 할 버튼이 첫 화면 밖으로** 나갔다(390px 폭에서
        버튼 상단이 613px — 스크롤해야 보인다). 계산대에서 3초 안에 해야 하는 일이 스크롤 뒤에 있으면
        안 된다.

        밀림이 실제로 문제가 되려면 발급 직후에 같은 자리를 다시 눌러야 하는데, 그때 사용자의 다음 행동은
        "화면을 점원에게 보여주기"다. 재발급(만료 후)은 이미 QR 영역에 내용이 있는 상태라 자리가 그대로다.
      */}
      <div className={`flex w-full flex-col items-center justify-center gap-3 ${qr ? "min-h-[19rem]" : ""}`}>
        {qr ? (
          <>
            <div className="relative">
              {/*
                QR 은 스캐너 대비를 위해 테마와 무관하게 항상 흰 배경 + 검은 모듈이다.
                다크 모드에서 반전시키면 읽지 못하는 매장 스캐너가 생긴다.
              */}
              <div
                className={`rounded-2xl bg-white p-4 shadow-sm ring-1 ring-border transition ${
                  expired ? "opacity-30 blur-[3px]" : ""
                }`}
              >
                <QRCode
                  value={qr.token}
                  size={256}
                  level="M"
                  bgColor="#FFFFFF"
                  fgColor="#000000"
                  title="식권 QR 코드"
                  viewBox="0 0 256 256"
                  style={{ width: "min(62vw, 15rem)", height: "auto" }}
                />
              </div>
              {expired && (
                <div className="absolute inset-0 flex items-center justify-center">
                  <span className="rounded-lg bg-card/95 px-3 py-1.5 text-sm font-medium text-[color:var(--taspa-danger)] shadow-sm">
                    만료됨
                  </span>
                </div>
              )}
            </div>

            <p aria-live="polite" className="text-center text-sm">
              {expired ? (
                <span className="text-muted-foreground">
                  이 QR 은 더 이상 쓸 수 없습니다. 다시 발급하세요.
                </span>
              ) : (
                <span className="text-muted-foreground">
                  <span className="tabular font-medium text-foreground">{remainingSec}초</span> 뒤 만료 ·
                  카운터에서 스캔하세요
                </span>
              )}
            </p>
          </>
        ) : (
          /*
            대기 상태는 **작은 안내 한 줄**이면 충분하다. 큰 점선 틀은 "여기에 QR 이 뜬다"를 알려 주지만
            그 정보의 값어치는 한 번 써 본 뒤로는 0 이 되고, 매번 화면의 절반을 먹는다.
          */
          <div className="surface-sunken flex w-full max-w-xs flex-col items-center gap-2 rounded-2xl px-4 py-5 text-center">
            <svg
              aria-hidden
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.5"
              className="size-7 text-muted-foreground"
            >
              <rect x="3" y="3" width="7" height="7" rx="1.5" />
              <rect x="14" y="3" width="7" height="7" rx="1.5" />
              <rect x="3" y="14" width="7" height="7" rx="1.5" />
              <path d="M14 14h3v3h-3zM20 14h1M14 20h3M20 20h1" strokeLinecap="round" />
            </svg>
            <p className="text-sm leading-relaxed text-muted-foreground">
              결제 직전에 발급하세요. QR 은 짧은 시간만 유효하고, 한 번 사용하면 즉시 소멸합니다.
            </p>
          </div>
        )}
      </div>

      <Button
        type="button"
        size="lg"
        className="h-12 w-full max-w-xs text-base"
        onClick={issue}
        disabled={!orgId || busy || waiting || blocked !== null}
      >
        {label}
      </Button>

      {!orgId && <p className="text-xs text-muted-foreground">결제할 조직을 먼저 선택하세요</p>}

      {blocked && <p className="max-w-xs text-center text-sm text-muted-foreground">{blocked}</p>}

      {error && (
        <p role="alert" className="max-w-xs text-center text-sm text-[color:var(--taspa-danger)]">
          {error}
          {waiting && <> 약 {cooldownSec}초 후 다시 시도할 수 있습니다.</>}
        </p>
      )}

      {qr && !expired && (
        // 스캐너가 고장난 매장을 위한 수동 입력 경로. 기본은 접어 둔다(어깨너머 노출을 줄인다).
        <details className="w-full max-w-xs text-center">
          <summary className="cursor-pointer text-xs text-muted-foreground">
            스캔이 안 되나요? 코드 직접 입력
          </summary>
          <p className="mt-2 rounded-lg bg-muted px-3 py-2 font-mono text-[11px] break-all text-muted-foreground">
            {qr.token}
          </p>
        </details>
      )}

      {orgName && qr && !expired && (
        <p className="text-xs text-muted-foreground">{orgName} 식대로 결제됩니다</p>
      )}
    </div>
  );
}
