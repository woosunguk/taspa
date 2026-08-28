"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError, api } from "@/lib/api";
import { useApi } from "@/lib/useApi";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Loading } from "@/components/feedback";
import { AmountPad, amountFromDigits } from "./AmountPad";
import { ApprovalPanel } from "./ApprovalPanel";
import { QrScanner } from "./QrScanner";
import { RecentApprovals } from "./RecentApprovals";
import {
  declineOf,
  formatWon,
  type ApiErrorBody,
  type Approval,
  type Decline,
  type PosMenusResponse,
  type RedeemResponse,
} from "./types";

/**
 * 매장 계산대 단말.
 *
 * **자격증명은 이 화면에 없다.** 승인은 taspa 의 M2M 전용 API 가 받는데, 그 토큰을 발급받는
 * client secret 은 Next 서버(`app/api/pos/*`)에만 있다. 브라우저는 같은 오리진의 `/api/pos/*`
 * 만 호출하고, 응답에는 토큰도 secret 도 실리지 않는다.
 *
 * 대신 **이 브라우저가 단말임을 증명**해야 한다(`lib/pos-session.ts`). `/pos` 와 `/meal` 은 같은
 * 앱이므로, 이 관문이 없으면 손님이 자기 QR 을 `/api/pos/redeem` 에 직접 보내 매장에 가지도 않고
 * 회사 예산으로 결제를 성립시킬 수 있다. 등록은 매장 직원이 한 번만 하고, 자격은 httpOnly 쿠키로
 * 남는다 — 화면 스크립트는 그 값을 읽지 못한다.
 *
 * 화면은 한 번에 **하나의 일**만 시킨다: 스캔 → 금액 → 결과. 계산대에서 선택지를 늘리면 줄이 선다.
 */

/** 화면에 쌓아 두는 승인 개수. 계산원이 "방금 그 결제"를 확인하는 용도라 길 필요가 없다. */
const RECENT_LIMIT = 10;

interface TerminalStatus {
  configured: boolean;
  missing: string[];
  enrollmentAvailable: boolean;
  /** 등록이 막힌 사유. "weak-key" 는 **키가 있는데 약해서** 잠긴 경우다(미설정과 증상이 같다). */
  enrollmentProblem: "missing" | "weak-key" | null;
  enrolled: boolean;
  /** 이 단말이 결속된 가맹점. 조회 실패 시 null(상태 응답 자체는 살린다). */
  merchant: {
    merchantId: string;
    name: string;
    category: string;
    timezone: string;
    /** 정액 단가(원). null 이면 계산원이 금액을 직접 입력한다. */
    defaultPriceMinor: number | null;
  } | null;
}

type Stage =
  | { kind: "scan" }
  | { kind: "amount"; token: string }
  /**
   * 정액 단가로 **금액 입력 없이** 승인을 요청하는 중. 이 단계가 없으면 스캔 직후 요청이 나가는 동안
   * 화면이 여전히 스캐너라, 계산원은 아무 일도 일어나지 않은 것으로 보고 QR 을 다시 댄다.
   */
  | { kind: "charging"; token: string; amountMinor: number }
  | { kind: "approved"; approval: Approval }
  | {
      kind: "declined";
      token: string;
      amountMinor: number;
      decline: Decline;
      detail: string;
    };

export default function PosPage() {
  const status = useApi<TerminalStatus>("/api/pos/status");

  if (status.loading)
    return (
      <Screen>
        <Loading label="단말을 확인하는 중" />
      </Screen>
    );

  // 상태 조회 자체가 실패하면 승인도 실패한다 — 미설정과 같은 화면으로 수렴시킨다(조용한 실패 금지).
  const data = status.error ? null : status.data;
  if (!data?.configured || !data.enrollmentAvailable) {
    // 등록 키가 없으면 어떤 기기도 단말이 될 수 없다 — 누락 목록에 그 사실을 그대로 싣는다.
    // 단 **약한 키**는 값이 이미 있는 경우라, "설정하세요"만 보여 주면 운영자가 원인을 찾지 못한다.
    const weakKey = data?.enrollmentProblem === "weak-key";
    const missing = weakKey
      ? (data?.missing ?? [])
      : [
          ...(data?.missing ?? []),
          ...(data && !data.enrollmentAvailable ? ["POS_TERMINAL_KEY", "POS_SESSION_SECRET"] : []),
        ];
    return (
      <Screen>
        <NotConfigured
          missing={missing}
          reason={
            status.error ??
            (weakKey
              ? "POS_TERMINAL_KEY 가 너무 단순해 등록이 잠겼습니다. `openssl rand -base64 32` 로 새로 만들어 주세요."
              : undefined)
          }
          onRetry={status.reload}
        />
      </Screen>
    );
  }

  if (!data.enrolled) {
    return (
      <Screen>
        <EnrollmentGate onEnrolled={status.reload} />
      </Screen>
    );
  }

  return (
    <Screen merchant={data.merchant}>
      <Terminal fixedPriceMinor={data.merchant?.defaultPriceMinor ?? null} />
    </Screen>
  );
}

function Screen({
  children,
  merchant,
}: {
  children: React.ReactNode;
  merchant?: TerminalStatus["merchant"];
}) {
  return (
    <div className="mx-auto flex w-full max-w-xl flex-col gap-6 px-4 py-6">
      <header>
        <h1 className="text-display text-foreground">식권 결제</h1>
        {/*
          ★**어느 매장으로 승인되는지**를 제목 바로 아래 둔다. 이 줄이 없던 동안 계산원은 눈앞의 화면이
          자기 가게 것인지 확인할 방법이 없었다 — 결속은 환경변수 안에만 있었다. 매장이 여럿인 사업자나
          단말을 옮겨 설치하는 현장에서는 곧 "옆 가게 이름으로 승인"이고, 발견은 월말 정산 때다.
          이름을 못 읽었을 때도 그 사실을 말한다(조용히 비워 두면 "확인했다"와 구별되지 않는다).
        */}
        {merchant !== undefined && (
          <p className="mt-1 text-base font-medium text-foreground">
            {merchant ? (
              <>
                {merchant.name}
                <span className="ml-2 text-sm font-normal text-muted-foreground">{merchant.timezone}</span>
              </>
            ) : (
              <span className="text-warning">매장 정보를 불러오지 못했습니다 — 결제는 가능합니다</span>
            )}
          </p>
        )}
        <p className="mt-1 text-base text-muted-foreground">손님의 QR 을 스캔하고 금액을 입력하세요.</p>
      </header>
      {children}
    </div>
  );
}

function Terminal({ fixedPriceMinor }: { fixedPriceMinor: number | null }) {
  const [stage, setStage] = useState<Stage>({ kind: "scan" });
  /**
   * 정액 단가가 있어도 **금액 입력으로 되돌릴 수 있어야 한다** — 행사가·부분 결제처럼 정액을 벗어나는
   * 일이 현장에 있고, 그때 단말을 바꾸거나 관리자에게 설정 변경을 요청하게 만들면 손님을 세워 둔다.
   */
  const [manualOverride, setManualOverride] = useState(false);
  const [digits, setDigits] = useState("");
  const [busy, setBusy] = useState(false);
  const [voiding, setVoiding] = useState(false);
  const [voidError, setVoidError] = useState<string | null>(null);
  const [recent, setRecent] = useState<Approval[]>([]);
  /**
   * 배식 코너 선택 — **끈끈하다(sticky)**. 계산원은 보통 한 코너에 앉아 연속으로 찍으므로 손님마다
   * 다시 고르게 하면 계산이 느려지고, 그러면 현장은 선택을 생략한다(축이 다시 빈다). 코너를 옮길 때만
   * 바꾸면 된다. 메뉴가 하나뿐이거나 없으면 이 UI 자체가 나타나지 않는다(서버가 단일 메뉴를 자동 귀속).
   */
  const [menuId, setMenuId] = useState<string | null>(null);
  // Terminal 은 등록·설정 게이트를 통과한 뒤에만 마운트된다(PosPage 상단) — 조건 없이 조회해도 안전하다.
  const menus = useApi<PosMenusResponse>("/api/pos/menus");
  const menuChoices = menus.data?.menus ?? [];

  /*
   * ★멱등키(posTxnId)의 수명이 이 화면의 유일한 안전 장치다.
   *
   * taspa 는 (merchant, posTxnId) 가 같은 재전송을 "같은 결제"로 보고 **이전 결과를 그대로 재반환**한다.
   * 그래서 통신이 끊긴 뒤 재시도는 같은 키여야 이중 승인이 되지 않는다. 반대로 금액을 고쳐 다시
   * 승인할 때 같은 키를 쓰면 **바뀐 금액이 무시되고 옛 승인이 되돌아온다** — 계산원은 새 금액이
   * 승인된 줄 안다. 그래서 키는 (QR, 금액) 쌍에 묶고, 그 쌍이 달라지면 새로 만든다.
   */
  const attempt = useRef<{
    token: string;
    amountMinor: number;
    posTxnId: string;
  } | null>(null);

  const posTxnIdFor = useCallback((token: string, amountMinor: number): string => {
    const previous = attempt.current;
    if (previous && previous.token === token && previous.amountMinor === amountMinor) {
      return previous.posTxnId;
    }
    const next = { token, amountMinor, posTxnId: newPosTxnId() };
    attempt.current = next;
    return next.posTxnId;
  }, []);

  const reset = useCallback(() => {
    attempt.current = null;
    setDigits("");
    setVoidError(null);
    setStage({ kind: "scan" });
  }, []);

  const approve = useCallback(
    async (token: string, amountMinor: number) => {
      setBusy(true);
      setVoidError(null);
      try {
        const response = await api.post<RedeemResponse>(
          "/api/pos/redeem",
          { token, amountMinor, posTxnId: posTxnIdFor(token, amountMinor), menuId: menuId ?? undefined },
          // 세션 화면이 아니다 — 401 을 로그인으로 해석해 계산대를 로그인 페이지로 보내면 안 되고,
          // CSRF 토큰(taspa 세션용)을 받으러 가느라 승인이 늦어질 이유도 없다.
          { noRedirect: true, noCsrf: true },
        );
        const approval: Approval = {
          authId: response.authId,
          // 총액은 서버가 돌려주지 않는다(조직부담+개인부담으로 분리해 준다). 입력값이 유일한 원천이다.
          totalMinor: amountMinor,
          orgPaidMinor: response.approvedAmountMinor,
          selfPaidMinor: response.selfPaidMinor,
          refundedMinor: 0,
          cashBackMinor: null,
          mealWindow: response.mealWindow,
          status: response.status,
          at: Date.now(),
        };
        attempt.current = null; // 결제가 끝났다 — 다음 손님은 새 멱등키로 시작한다.
        setRecent((list) => [approval, ...list].slice(0, RECENT_LIMIT));
        setStage({ kind: "approved", approval });
      } catch (cause) {
        const error = errorBodyOf(cause);
        setStage({
          kind: "declined",
          token,
          amountMinor,
          decline: declineOf(error),
          detail: `${error.errorCode} · ${error.message}`,
        });
      } finally {
        setBusy(false);
      }
    },
    [posTxnIdFor, menuId],
  );

  const voidApproval = useCallback(async (authId: string) => {
    setVoiding(true);
    setVoidError(null);
    try {
      const response = await api.post<RedeemResponse>(
        "/api/pos/void",
        { authId },
        { noRedirect: true, noCsrf: true },
      );
      setStage((current) =>
        current.kind === "approved" && current.approval.authId === authId
          ? {
              kind: "approved",
              approval: { ...current.approval, status: response.status },
            }
          : current,
      );
      setRecent((list) =>
        list.map((item) => (item.authId === authId ? { ...item, status: response.status } : item)),
      );
    } catch (cause) {
      const error = errorBodyOf(cause);
      const decline = declineOf(error);
      setVoidError(`${decline.title} — ${decline.guidance}`);
    } finally {
      setVoiding(false);
    }
  }, []);

  /**
   * 부분 환불 — 승인은 유지하고 금액만 줄인다.
   *
   * ★멱등키는 (거래, 금액) 쌍에 묶는다. 통신이 끊긴 뒤 재시도는 같은 키여야 이중 환불이 되지 않고,
   * 금액을 고쳐 다시 시도할 때 같은 키를 쓰면 **옛 환불이 그대로 재반환**돼 계산원이 새 금액이
   * 처리된 줄 안다(승인의 posTxnId 와 같은 함정·같은 해법).
   */
  const refundAttempt = useRef<{
    authId: string;
    amountMinor: number;
    posRefundId: string;
  } | null>(null);

  const refundApproval = useCallback(async (authId: string, amountMinor: number) => {
    setVoiding(true);
    setVoidError(null);
    try {
      const previous = refundAttempt.current;
      const posRefundId =
        previous && previous.authId === authId && previous.amountMinor === amountMinor
          ? previous.posRefundId
          : newPosTxnId();
      refundAttempt.current = { authId, amountMinor, posRefundId };

      const response = await api.post<RedeemResponse>(
        "/api/pos/refund",
        { authId, amountMinor, posRefundId },
        { noRedirect: true, noCsrf: true },
      );
      // 서버가 돌려준 분담이 유일한 진실이다 — 화면에서 계산하면 장부와 갈라진다.
      const apply = (item: Approval): Approval => ({
        ...item,
        totalMinor: item.totalMinor - amountMinor,
        refundedMinor: item.refundedMinor + amountMinor,
        orgPaidMinor: response.approvedAmountMinor,
        selfPaidMinor: response.selfPaidMinor,
        // 서버가 정한 분담 — 화면이 두 값의 차로 유추하지 않는다(유추가 틀리면 현금이 틀린다).
        cashBackMinor: response.selfRefundedMinor ?? 0,
        status: response.status,
      });
      refundAttempt.current = null;
      setStage((current) =>
        current.kind === "approved" && current.approval.authId === authId
          ? { kind: "approved", approval: apply(current.approval) }
          : current,
      );
      setRecent((list) => list.map((item) => (item.authId === authId ? apply(item) : item)));
    } catch (cause) {
      const error = errorBodyOf(cause);
      const decline = declineOf(error);
      setVoidError(`${decline.title} — ${decline.guidance}`);
    } finally {
      setVoiding(false);
    }
  }, []);

  /**
   * 정액 단가가 설정돼 있으면 **금액 입력을 건너뛰고 즉시 승인**한다(계산원의 타이핑이 사라진다).
   * 없거나 수동 전환 상태면 지금까지처럼 금액 입력 단계로 간다.
   *
   * 스캐너에 넘기는 콜백은 안정적이어야 한다 — 리렌더마다 새 함수를 주면 카메라가 껐다 켜진다.
   * 그래서 의존성은 값(price)과 안정 콜백(approve)뿐이다.
   */
  const autoPrice = manualOverride ? null : fixedPriceMinor;
  const handleScan = useCallback(
    (token: string) => {
      if (autoPrice !== null && autoPrice > 0) {
        setStage({ kind: "charging", token, amountMinor: autoPrice });
        void approve(token, autoPrice);
        return;
      }
      setStage({ kind: "amount", token });
    },
    [autoPrice, approve],
  );

  const amount = amountFromDigits(digits);

  return (
    <>
      {stage.kind === "scan" && (
        <div className="flex flex-col gap-4">
          {autoPrice !== null && (
            <div className="rounded-xl border border-border bg-[color:var(--taspa-brand-soft)] px-4 py-3">
              <p className="text-base text-foreground">
                정액 <strong className="text-brand">{formatWon(autoPrice)}</strong> — QR 을 읽으면 바로
                승인됩니다.
              </p>
              <button
                type="button"
                className="mt-1 text-sm text-muted-foreground underline"
                onClick={() => setManualOverride(true)}
              >
                이번 손님은 금액을 직접 입력
              </button>
            </div>
          )}
          {autoPrice === null && fixedPriceMinor !== null && (
            <div className="flex items-center justify-between rounded-xl border border-border px-4 py-3">
              <p className="text-base text-foreground">금액 직접 입력 모드</p>
              <button
                type="button"
                className="text-sm text-brand underline"
                onClick={() => setManualOverride(false)}
              >
                정액({formatWon(fixedPriceMinor)})으로 되돌리기
              </button>
            </div>
          )}
          {menuChoices.length > 1 && (
            <div className="rounded-xl border border-border px-4 py-3">
              <p className="mb-2 text-sm text-muted-foreground">
                배식 코너 — 손님이 받은 메뉴를 선택하면 코너별 식수가 집계됩니다 (결제와는 무관)
              </p>
              <div className="flex flex-wrap gap-2">
                {menuChoices.map((menu) => (
                  <button
                    key={menu.menuId}
                    type="button"
                    className={
                      menuId === menu.menuId
                        ? "rounded-lg border border-brand bg-[color:var(--taspa-brand-soft)] px-4 py-2 text-base font-medium text-brand"
                        : "rounded-lg border border-border px-4 py-2 text-base text-foreground"
                    }
                    onClick={() => setMenuId((current) => (current === menu.menuId ? null : menu.menuId))}
                  >
                    {menu.corner ? `${menu.corner} · ` : ""}
                    {menu.name}
                  </button>
                ))}
              </div>
            </div>
          )}
          <QrScanner onScan={handleScan} />
        </div>
      )}

      {stage.kind === "charging" && (
        <div className="flex flex-col items-center gap-3 rounded-xl border border-border px-4 py-10">
          <p className="text-2xl font-semibold text-brand">{formatWon(stage.amountMinor)}</p>
          <p className="text-base text-muted-foreground">승인 요청 중…</p>
        </div>
      )}

      {stage.kind === "amount" && (
        <div className="flex flex-col gap-4">
          <p className="rounded-xl bg-[color:var(--taspa-success-soft)] px-4 py-3 text-base text-foreground">
            QR 을 읽었습니다. 결제 금액을 입력하세요.
          </p>
          <AmountPad digits={digits} onChange={setDigits} disabled={busy} />
          <Button
            type="button"
            size="lg"
            className="h-20 rounded-xl text-2xl"
            onClick={() => approve(stage.token, amount)}
            disabled={busy || amount <= 0}
          >
            {busy ? "승인 요청 중" : `${formatWon(amount)} 승인`}
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="lg"
            className="h-12 rounded-xl text-base"
            onClick={reset}
            disabled={busy}
          >
            취소하고 처음으로
          </Button>
        </div>
      )}

      {stage.kind === "approved" && (
        <ApprovalPanel
          approval={stage.approval}
          onVoid={() => voidApproval(stage.approval.authId)}
          onRefund={(amountMinor) => refundApproval(stage.approval.authId, amountMinor)}
          voiding={voiding}
          voidError={voidError}
          onNext={reset}
        />
      )}

      {stage.kind === "declined" && (
        <DeclinedPanel
          stage={stage}
          busy={busy}
          onRetry={() => approve(stage.token, stage.amountMinor)}
          onReset={reset}
        />
      )}

      <section>
        <h2 className="mb-2 text-lg font-medium text-foreground">최근 승인</h2>
        <RecentApprovals
          approvals={recent}
          onReopen={(approval) => {
            // 취소·환불 패널을 다시 연다. 새 승인이 아니므로 멱등키(attempt)는 건드리지 않는다.
            setVoidError(null);
            setStage({ kind: "approved", approval });
          }}
        />
      </section>
    </>
  );
}

function DeclinedPanel({
  stage,
  busy,
  onRetry,
  onReset,
}: {
  stage: Extract<Stage, { kind: "declined" }>;
  busy: boolean;
  onRetry: () => void;
  onReset: () => void;
}) {
  return (
    <div className="flex flex-col gap-5">
      <div role="alert" className="rounded-2xl bg-[color:var(--taspa-danger-soft)] px-6 py-6">
        <p className="text-2xl font-semibold text-[color:var(--taspa-danger)]">{stage.decline.title}</p>
        <p className="mt-3 text-lg text-foreground">{stage.decline.guidance}</p>
        <p className="mt-4 text-sm text-muted-foreground">결제 금액 {formatWon(stage.amountMinor)}</p>
        {/* 서버가 보낸 사유 원문. 계산원이 본사에 문의할 때 그대로 읽어 주면 되는 값이다. */}
        <p className="mt-1 font-mono text-xs break-all text-muted-foreground">{stage.detail}</p>
      </div>

      <div className="flex flex-col gap-3">
        {stage.decline.retryable && (
          <Button
            type="button"
            size="lg"
            className="h-16 rounded-xl text-xl"
            onClick={onRetry}
            disabled={busy}
          >
            {busy ? "승인 요청 중" : "같은 금액으로 다시 시도"}
          </Button>
        )}
        <Button
          type="button"
          variant={stage.decline.retryable ? "outline" : "default"}
          size="lg"
          className="h-16 rounded-xl text-xl"
          onClick={onReset}
          disabled={busy}
        >
          {stage.decline.needsNewQr ? "새 QR 스캔하기" : "처음으로"}
        </Button>
      </div>
    </div>
  );
}

/**
 * 단말 등록 관문. 매장 직원이 등록 키를 한 번 넣으면 이 브라우저가 단말이 되고, 이후 사용하는 동안
 * 다시 묻지 않는다(쿠키는 유휴 7일·절대 90일로 만료하고, 화면을 열 때마다 갱신된다).
 * 키는 서버로만 가고 화면에 남기지 않는다(입력값은 성공하면 즉시 버린다).
 *
 * 실패 메시지는 "키가 틀렸다" 하나로 통일한다 — 형식이 틀렸는지 값이 틀렸는지 알려 주면
 * 추측 공격에 힌트가 된다(서버도 같은 응답을 준다). 유일한 예외가 429 인데, 그때는 서버가 남은
 * 대기 시간을 문구에 담아 주므로 그대로 보여주면 된다.
 */
function EnrollmentGate({ onEnrolled }: { onEnrolled: () => void }) {
  const [key, setKey] = useState("");
  // URL 에 키가 실려 왔으면 첫 렌더부터 "등록 중"이다 — effect 안의 동기 setState 는 CI 게이트
  // (react-hooks/set-state-in-effect)가 막고, 실제로도 한 프레임 동안 입력 폼이 번쩍인다.
  const [busy, setBusy] = useState(
    () => typeof window !== "undefined" && new URL(window.location.href).searchParams.has("key"),
  );
  const [error, setError] = useState<string | null>(null);

  /*
   * URL 자동 등록 — `/pos?key=<등록 키>` 로 열면 입력 없이 곧바로 등록을 시도한다.
   * 매장 운영자가 단말 여러 대를 세팅할 때 링크(또는 그 링크의 QR) 하나로 끝내기 위한 경로다.
   *
   * ★키는 읽는 **즉시 URL 에서 지운다**(history.replaceState). 남겨 두면 브라우저 히스토리·
   *   즐겨찾기·화면 공유에 등록 키가 그대로 노출된다 — 자동 등록의 편의가 유출 경로가 되면 안 된다.
   * ★검증·지연·429 는 전부 기존 `/api/pos/enroll` 이 담당한다 — 이 경로는 입력 방법이 다를 뿐
   *   새 검증 경로가 아니다(관문이 둘이 되는 순간 어느 쪽이 열려 있는지 아무도 추적하지 못한다).
   * ★실패하면 수동 입력 폼으로 그대로 낙하하고 사유를 보여 준다(무한 재시도하지 않는다 —
   *   틀린 키가 URL 에 박힌 채 재시도가 돌면 스로틀이 정상 등록까지 막는다).
   */
  const autoTried = useRef(false);
  useEffect(() => {
    if (autoTried.current) return;
    const url = new URL(window.location.href);
    const urlKey = url.searchParams.get("key");
    if (!urlKey) return;
    autoTried.current = true;
    url.searchParams.delete("key");
    window.history.replaceState(null, "", url.pathname + (url.search || "") + url.hash);
    api
      .post("/api/pos/enroll", { key: urlKey }, { noRedirect: true, noCsrf: true })
      .then(() => onEnrolled())
      .catch((cause) => setError(errorBodyOf(cause).message))
      .finally(() => setBusy(false));
  }, [onEnrolled]);

  const submit = useCallback(
    async (event: React.FormEvent) => {
      event.preventDefault();
      if (!key.trim() || busy) return;
      setBusy(true);
      setError(null);
      try {
        await api.post("/api/pos/enroll", { key }, { noRedirect: true, noCsrf: true });
        setKey("");
        onEnrolled();
      } catch (cause) {
        setError(errorBodyOf(cause).message);
      } finally {
        setBusy(false);
      }
    },
    [busy, key, onEnrolled],
  );

  return (
    <form onSubmit={submit} className="flex flex-col gap-4 rounded-2xl bg-muted px-6 py-6">
      <div>
        <h2 className="text-2xl font-semibold text-foreground">이 기기를 단말로 등록하세요</h2>
        <p className="mt-2 text-base text-muted-foreground">
          매장 관리자에게 받은 등록 키를 한 번만 입력하면 됩니다. 이후에는 이 기기에서 바로 결제를 승인할 수
          있습니다.
        </p>
      </div>

      <div className="flex flex-col gap-2">
        <label htmlFor="pos-enroll-key" className="text-base font-medium text-foreground">
          등록 키
        </label>
        <Input
          id="pos-enroll-key"
          type="password"
          autoComplete="off"
          inputMode="text"
          className="h-14 rounded-xl text-lg"
          value={key}
          onChange={(event) => setKey(event.target.value)}
          disabled={busy}
        />
      </div>

      {error && (
        <p
          role="alert"
          className="rounded-xl bg-[color:var(--taspa-danger-soft)] px-4 py-3 text-base text-[color:var(--taspa-danger)]"
        >
          {error}
        </p>
      )}

      <Button type="submit" size="lg" className="h-16 rounded-xl text-xl" disabled={busy || !key.trim()}>
        {busy ? "등록하는 중" : "단말 등록"}
      </Button>
    </form>
  );
}

function NotConfigured({
  missing,
  reason,
  onRetry,
}: {
  missing: string[];
  reason: string | undefined;
  onRetry: () => void;
}) {
  return (
    <div className="flex flex-col gap-4 rounded-2xl bg-[color:var(--taspa-danger-soft)] px-6 py-6">
      <p className="text-2xl font-semibold text-[color:var(--taspa-danger)]">단말이 등록되지 않았습니다</p>
      <p className="text-lg text-foreground">
        이 단말에는 매장 자격증명이 설정되어 있지 않아 결제를 승인할 수 없습니다. 손님에게는 다른 결제 수단을
        안내하고, 매장 관리자에게 단말 설정을 요청하세요.
      </p>
      {missing.length > 0 && (
        <p className="text-base text-muted-foreground">
          서버에 설정해야 할 환경변수: <span className="font-mono text-foreground">{missing.join(", ")}</span>
        </p>
      )}
      {reason && <p className="text-sm text-muted-foreground">{reason}</p>}
      <div>
        <Button
          type="button"
          variant="outline"
          size="lg"
          className="h-12 rounded-xl text-base"
          onClick={onRetry}
        >
          다시 확인
        </Button>
      </div>
    </div>
  );
}

/**
 * 실패 원인을 `{errorCode, message}` 로 정규화한다. `api` 계층은 서버 본문의 errorCode 를 그대로
 * 실어 주므로, 네트워크 단절처럼 본문이 없는 경우만 단말 코드로 메운다.
 */
function errorBodyOf(cause: unknown): ApiErrorBody {
  if (cause instanceof ApiError) return { errorCode: cause.errorCode, message: cause.message };
  return {
    errorCode: "TERMINAL_UPSTREAM_ERROR",
    message: "서버에 연결하지 못했습니다.",
  };
}

/**
 * 멱등키 생성. `crypto.randomUUID` 는 보안 컨텍스트(https·localhost)에서만 있고, 매장 단말이
 * 사설 IP 로 http 접속하는 구성이 있어 폴백을 둔다. 유일성은 **한 가맹 안에서만** 필요하므로
 * 시각 + 난수로 충분하다(taspa 의 (merchant, posTxnId) UNIQUE 가 최종 방어선이다).
 */
function newPosTxnId(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `pos-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}
