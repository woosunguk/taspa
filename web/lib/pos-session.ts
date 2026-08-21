import { createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import { setTimeout as delay } from "node:timers/promises";
import type { NextRequest } from "next/server";

/**
 * POS **단말 인증**.
 *
 * ★이것이 없으면 BFF 는 인증 없는 공개 결제 오라클이 된다. `/pos` 와 `/meal` 은 같은 앱이므로,
 * 손님은 자기 QR 을 `/meal` 에서 발급받아 `/api/pos/redeem` 에 직접 보낼 수 있다 — 매장에 가지도 않고
 * 회사 예산으로 결제가 성립하고, 응답의 authId 로 자기 결제를 void 해 매장 매출을 지울 수도 있다.
 * "매장 네트워크로 제한" 같은 배포 완화책은 성립하지 않는다: 손님도 QR 을 받으려면 같은 앱에 닿아야 한다.
 *
 * 그래서 **브라우저가 단말임을 증명**하게 한다. 매장 직원이 등록 키를 한 번 입력하면 서명된
 * httpOnly 쿠키가 심기고, 그 쿠키가 있는 요청만 승인·취소를 중계한다.
 *  - httpOnly: 페이지 스크립트(XSS 포함)가 값을 읽지 못한다.
 *  - SameSite=Strict: 외부 사이트가 단말 브라우저를 시켜 결제를 만들지 못한다.
 *  - 서명(HMAC): 쿠키를 위조할 수 없다. 서버는 상태를 저장하지 않는다(단말은 여러 대일 수 있다).
 *
 * 한계는 정직하게 적어 둔다: 등록 키를 본 사람은 자기 기기를 단말로 만들 수 있다. 키는 매장 직원만
 * 알아야 하고, 유출되면 **키를 교체(POS_TERMINAL_KEY 변경)해 전 단말을 즉시 무효화**한다 —
 * 그 회수 경로가 실제로 동작하도록 서명 키를 등록 키에서 파생시킨다(아래 signingKey 참조).
 */

/**
 * 서버 전용 모듈. 클라이언트 번들에 섞이면 등록 키가 브라우저로 나간다.
 * (`lib/pos-terminal.ts` 와 같은 이유·같은 방식으로 직접 막는다 — `server-only` 패키지가 없다.)
 */
if (typeof window !== "undefined") {
  throw new Error("lib/pos-session 는 서버 전용 모듈입니다");
}

const COOKIE_NAME = "pos_terminal";

/** 쿠키 형식 버전. 형식을 바꾸면 올려서 옛 쿠키를 파싱 단계에서 거절한다. */
const COOKIE_VERSION = "v1";

/**
 * **유휴** 만료 — 이 기간 동안 단말이 `/pos` 를 한 번도 열지 않으면 재등록해야 한다.
 *
 * 기존 30일 고정은 "분실 단말이 최대 30일 결제 가능"을 뜻했다. 매장 단말은 영업일마다 화면을 여니
 * 유휴 기준으로 잘라도 현장 마찰이 없고(아래 슬라이딩 갱신), 서랍에 들어간 기기는 일주일이면 죽는다.
 */
const IDLE_TTL_MS = 7 * 24 * 60 * 60 * 1000;

/**
 * **절대** 만료 — 갱신을 아무리 반복해도 최초 등록으로부터 이 시점엔 반드시 재등록한다.
 * 슬라이딩만 두면 매일 켜지는 단말의 쿠키는 영원히 살아 있어서, 회수 수단이 키 교체 하나로 줄어든다.
 */
const ABSOLUTE_TTL_MS = 90 * 24 * 60 * 60 * 1000;

/**
 * 갱신 임계 — 남은 유휴 기간이 절반 아래로 떨어졌을 때만 새 쿠키를 내린다.
 * 매 요청마다 Set-Cookie 를 붙이면 응답이 커지고 프록시 캐시 규칙과 얽힌다.
 */
const RENEW_AFTER_RATIO = 0.5;

/** 미래 시각으로 서명된 쿠키(시계 되감김)를 거절할 때 허용할 오차. */
const CLOCK_SKEW_MS = 60_000;

export const TERMINAL_UNAUTHORIZED = "TERMINAL_UNAUTHORIZED";
export const TERMINAL_COOKIE_NAME = COOKIE_NAME;

/* -------------------------------------------------------------- 등록 키 강도 */

/**
 * 등록 엔드포인트는 인증 없이 열려 있어야 한다(그게 등록의 정의다) — 즉 **키 추측 오라클**이다.
 * 아래 시도 제한은 추측 속도를 떨어뜨릴 뿐, 키가 약하면 결국 뚫린다. 그래서 강도 검증이
 * 스로틀보다 먼저 오는 방어선이다: 기준 미달이면 등록 기능 자체를 끈다(fail-closed) — 화면은
 * "단말이 등록되지 않았습니다"로 수렴하고, 아무도 단말이 되지 못하므로 결제도 열리지 않는다.
 *
 * 기준은 하한선이지 증명이 아니다(엔트로피 추정은 문자 다양성 기반의 근사다). 권장값은 그대로
 * `openssl rand -base64 32`.
 */
const MIN_KEY_LENGTH = 16;
const MIN_KEY_DISTINCT_CHARS = 8;
const MIN_KEY_ENTROPY_BITS = 80;

function keyStrengthProblem(key: string): string | null {
  if (key.length < MIN_KEY_LENGTH) return `길이가 ${MIN_KEY_LENGTH}자 미만입니다`;
  const distinct = new Set(key).size;
  if (distinct < MIN_KEY_DISTINCT_CHARS) return "사용된 문자 종류가 너무 적습니다";
  if (key.length * Math.log2(distinct) < MIN_KEY_ENTROPY_BITS) return "무작위성이 부족합니다";
  return null;
}

/** 기동 후 한 번만 경고한다 — 요청마다 찍으면 로그가 무의미해진다. */
let weakKeyWarned = false;

/* ------------------------------------------------------------------ 설정 로딩 */

interface EnrollmentConfig {
  /** 직원이 입력하는 등록 키. */
  key: string;
  /**
   * 쿠키 서명 키 — `POS_SESSION_SECRET`(없으면 client secret)으로 **등록 키를 HMAC** 한 값이다.
   *
   * ★이 파생이 "키 교체 = 전 단말 재등록"을 **구조적으로** 보장한다. 예전 구현은 등록 키를 비교에만
   * 쓰고 서명에는 넣지 않아, 키를 바꿔도 이미 발급된 쿠키가 그대로 살아 있었다 — 분실·유출 단말에
   * 대한 문서상의 사고 대응이 실제로는 아무 일도 하지 않았다.
   */
  signingKey: Buffer;
}

let configCache: {
  key: string;
  secret: string;
  config: EnrollmentConfig;
} | null = null;

function enrollmentConfig(): EnrollmentConfig | null {
  const key = process.env.POS_TERMINAL_KEY?.trim();
  if (!key) return null;
  const secret = process.env.POS_SESSION_SECRET?.trim() || process.env.POS_CLIENT_SECRET?.trim();
  if (!secret) return null;

  const problem = keyStrengthProblem(key);
  if (problem) {
    if (!weakKeyWarned) {
      weakKeyWarned = true;
      // 값은 절대 찍지 않는다. 운영자가 고칠 수 있을 만큼의 사유만 남긴다.
      console.error(`[pos] POS_TERMINAL_KEY 가 약합니다(${problem}). 단말 등록을 비활성화합니다.`);
    }
    return null;
  }

  if (configCache && configCache.key === key && configCache.secret === secret) return configCache.config;
  const config: EnrollmentConfig = {
    key,
    signingKey: createHmac("sha256", secret).update(`taspa-pos-terminal/${COOKIE_VERSION}\n${key}`).digest(),
  };
  configCache = { key, secret, config };
  return config;
}

/** 등록 기능이 설정돼 있는가. 미설정이면 단말을 등록할 수 없고, 따라서 승인도 불가능하다(fail-closed). */
export function enrollmentConfigured(): boolean {
  return enrollmentConfig() !== null;
}

/**
 * 등록이 막힌 **사유**. 화면이 "환경변수를 설정하세요"만 말하면, 이미 설정해 둔 운영자는 원인을
 * 찾지 못하고 서버 로그를 뒤져야 한다 — 약한 키는 "설정 안 됨"과 증상이 같기 때문이다.
 * 값은 절대 싣지 않는다(사유 문구만).
 */
export function enrollmentProblem(): "missing" | "weak-key" | null {
  const key = process.env.POS_TERMINAL_KEY?.trim();
  const secret = process.env.POS_SESSION_SECRET?.trim() || process.env.POS_CLIENT_SECRET?.trim();
  if (!key || !secret) return "missing";
  return keyStrengthProblem(key) ? "weak-key" : null;
}

/* ------------------------------------------------------------------ 쿠키 서명 */

function sign(payload: string, signingKey: Buffer): string {
  return createHmac("sha256", signingKey).update(payload).digest("base64url");
}

function constantTimeEquals(a: string, b: string): boolean {
  const left = Buffer.from(a);
  const right = Buffer.from(b);
  // 길이가 다르면 timingSafeEqual 이 던지므로 먼저 거른다(길이 노출은 비밀 노출이 아니다).
  if (left.length !== right.length) return false;
  return timingSafeEqual(left, right);
}

/** 등록된 단말 1대의 신원. terminalId 는 비밀이 아니라 **감사 추적용 식별자**다. */
export interface TerminalSession {
  terminalId: string;
  /** 최초 등록 시각(ms) — 갱신해도 바뀌지 않는다. 절대 만료의 기준이다. */
  issuedAt: number;
  /** 현재 유휴 만료 시각(ms). */
  expiresAt: number;
}

function encode(session: TerminalSession, signingKey: Buffer): string {
  const payload = `${COOKIE_VERSION}.${session.terminalId}.${session.issuedAt}.${session.expiresAt}`;
  return `${payload}.${sign(payload, signingKey)}`;
}

const TERMINAL_ID_PATTERN = /^[A-Za-z0-9_-]{16,64}$/;

function decode(raw: string, signingKey: Buffer, now: number): TerminalSession | null {
  const separator = raw.lastIndexOf(".");
  if (separator <= 0) return null;
  const payload = raw.slice(0, separator);
  if (!constantTimeEquals(raw.slice(separator + 1), sign(payload, signingKey))) return null;

  const parts = payload.split(".");
  if (parts.length !== 4 || parts[0] !== COOKIE_VERSION) return null;
  const [, terminalId, issuedRaw, expiresRaw] = parts as [string, string, string, string];
  if (!TERMINAL_ID_PATTERN.test(terminalId)) return null;

  const issuedAt = Number(issuedRaw);
  const expiresAt = Number(expiresRaw);
  if (!Number.isFinite(issuedAt) || !Number.isFinite(expiresAt)) return null;
  // 미래에 발급된 쿠키는 우리가 서명한 것이라도 신뢰하지 않는다(시계 되감김으로 수명이 늘어난다).
  if (issuedAt > now + CLOCK_SKEW_MS) return null;
  // 유휴 만료와 절대 만료를 **둘 다** 본다 — TTL 을 줄이면 이미 나간 쿠키에도 즉시 적용된다.
  if (now >= expiresAt || now >= issuedAt + ABSOLUTE_TTL_MS) return null;

  return { terminalId, issuedAt, expiresAt };
}

/**
 * 쿠키 속성. 등록과 갱신이 같은 속성을 쓰도록 한 곳에 둔다 — 갱신에서 SameSite 하나만 빠져도
 * 그 순간부터 단말 쿠키가 크로스사이트 요청에 실린다.
 */
function terminalCookieHeader(value: string, maxAgeSeconds: number): string {
  return [
    `${COOKIE_NAME}=${value}`,
    "Path=/",
    "HttpOnly",
    "SameSite=Strict",
    `Max-Age=${maxAgeSeconds}`,
    // 개발(http://localhost)에서는 Secure 를 붙이면 쿠키가 저장되지 않는다.
    process.env.NODE_ENV === "production" ? "Secure" : "",
  ]
    .filter(Boolean)
    .join("; ");
}

/** 등록 키 검증 → 심을 Set-Cookie 헤더. 키가 틀리면 null. */
export function enroll(submittedKey: string): { setCookie: string; terminalId: string } | null {
  const config = enrollmentConfig();
  if (!config) return null;
  if (!constantTimeEquals(submittedKey, config.key)) return null;

  const now = Date.now();
  const session: TerminalSession = {
    // 단말별 식별자. 어떤 기기가 언제 등록됐는지 로그로 되짚을 수 있게 한다(쿠키 값 자체는 비교 불가).
    terminalId: randomBytes(16).toString("base64url"),
    issuedAt: now,
    expiresAt: now + IDLE_TTL_MS,
  };
  return {
    setCookie: terminalCookieHeader(encode(session, config.signingKey), Math.floor(IDLE_TTL_MS / 1000)),
    terminalId: session.terminalId,
  };
}

/** 이 요청을 보낸 등록 단말. 등록되지 않았거나 만료·위조면 null. */
export function enrolledTerminal(request: NextRequest): TerminalSession | null {
  const config = enrollmentConfig();
  if (!config) return null;
  const raw = request.cookies.get(COOKIE_NAME)?.value;
  if (!raw) return null;
  return decode(raw, config.signingKey, Date.now());
}

/** 이 요청이 등록된 단말에서 왔는가. */
export function isEnrolledTerminal(request: NextRequest): boolean {
  return enrolledTerminal(request) !== null;
}

/**
 * 슬라이딩 갱신 — 사용 중인 단말은 유휴 만료로 죽지 않게, 방치된 단말은 그대로 죽게.
 *
 * 절대 만료를 넘겨 연장하지 않는다. 연장할 것이 없으면 null 을 돌려 Set-Cookie 를 생략한다.
 */
export function renewTerminalCookie(session: TerminalSession): string | null {
  const config = enrollmentConfig();
  if (!config) return null;

  const now = Date.now();
  if (session.expiresAt - now > IDLE_TTL_MS * RENEW_AFTER_RATIO) return null;

  const expiresAt = Math.min(now + IDLE_TTL_MS, session.issuedAt + ABSOLUTE_TTL_MS);
  if (expiresAt <= session.expiresAt) return null;

  const renewed: TerminalSession = { ...session, expiresAt };
  return terminalCookieHeader(
    encode(renewed, config.signingKey),
    Math.max(1, Math.floor((expiresAt - now) / 1000)),
  );
}

/* --------------------------------------------------------------- 등록 시도 제한 */

/*
 * 등록 시도 제한의 설계 원칙: **제한은 "추측"을 막고, "키 소지"는 절대 막지 않는다.**
 *
 * 예전 구현은 발신지별 하드 잠금이었고, 발신지는 `X-Forwarded-For` 를 그대로 믿어 결정했다.
 * 그 결과가 정확히 거꾸로였다 — 공격자는 헤더만 돌리면 무제한 추측이 가능했고(제한 0),
 * 정상 매장은 프록시 뒤에서 한 버킷을 공유해 오타 몇 번에 10분 잠겼으며, 외부인이 피해 매장의
 * IP 를 XFF 에 적어 그 매장의 등록을 봉쇄할 수도 있었다.
 *
 * 그래서 층을 이렇게 나눈다:
 *  1. **발신지 식별은 신뢰를 선언했을 때만**(POS_TRUSTED_PROXY). 기본은 위조 불가능한 단일 버킷.
 *  2. **전역 실패 압력** — 발신지를 위조해도 우회할 수 없는 유일한 층. 지연의 크기를 정한다.
 *  3. **지연(발신지 무관·전역 직렬화)** — 실제 감속 장치. 사람은 한 번 기다리면 그만이지만
 *     자동화는 초당 수백 회가 초당 한 회 이하로 떨어진다.
 *  4. **429 는 틀린 키에만** 준다. 맞는 키는 압력이 아무리 높아도(=공격 중에도) 통과한다 —
 *     그래서 발신지 위조로 매장의 등록을 봉쇄하는 표적 공격이 성립하지 않는다.
 *
 * 한계: 프로세스 메모리라 인스턴스별로 센다(POS 배포는 매장 1대 전제라 충분하다). 재시작하면
 * 카운터가 사라진다 — 이건 지연 장치이지 인증이 아니다. 실제 방어선은 키의 엔트로피이고,
 * 그 전제를 위에서 강도 검증으로 세운다.
 */

const ATTEMPT_WINDOW_MS = 10 * 60 * 1000;
/** 이 횟수를 넘긴 발신지는 틀린 키에 429 를 받는다(맞는 키는 계속 통과). */
const MAX_ATTEMPTS_PER_SOURCE = 10;
/** 발신지를 위조해도 합산되는 전역 상한. */
const MAX_ATTEMPTS_GLOBAL = 30;

/**
 * 개별 추적할 발신지 수의 상한.
 *
 * 키가 공격자 제어(헤더 값)라 상한이 없으면 요청 하나당 항목 하나가 무한히 쌓이고, 만료 순회가
 * 매 요청 O(n) 이라 **제한 장치 자체가 DoS 수단**이 된다. 가득 차면 개별 추적을 포기하고 전역
 * 카운터에 맡긴다 — 잃는 것은 해상도뿐이고, 감속은 전역 층이 그대로 유지한다.
 */
const MAX_TRACKED_SOURCES = 1024;
/** 만료 항목 청소 주기. 매 요청 순회는 위와 같은 이유로 하지 않는다. */
const SWEEP_INTERVAL_MS = 30_000;

/** 지연은 압력에 따라 배로 늘어난다. 첫 오타는 사실상 즉시, 자동화는 곧 벽에 부딪힌다. */
const PENALTY_BASE_MS = 100;
const PENALTY_MAX_MS = 2_000;
/**
 * 대기열 상한. 병렬 요청이 몰리면 지연 중인 요청이 메모리에 쌓이므로, 이 이상 밀린 요청은
 * 기다리지 않고 즉시 거절한다(`overloaded`). **키 검증은 그래도 먼저 이뤄진다** — 압력을 이유로
 * 검증을 건너뛰면 공격자가 대기열을 포화시키는 것만으로 맞는 키까지 봉쇄할 수 있다(라우트 KDoc 참조).
 */
const PENALTY_QUEUE_MAX_MS = 15_000;

interface Window {
  count: number;
  resetAt: number;
}

const attempts = new Map<string, Window>();
const globalWindow: Window = { count: 0, resetAt: 0 };
/** 다음 시도를 처리해도 되는 시각 — 지연을 전역으로 **직렬화**한다(동시 요청도 줄을 선다). */
let nextSlotAt = 0;
let lastSweepAt = 0;

function bump(window: Window, now: number): number {
  if (window.resetAt <= now) {
    window.count = 1;
    window.resetAt = now + ATTEMPT_WINDOW_MS;
    return 1;
  }
  window.count += 1;
  return window.count;
}

function sweep(now: number): void {
  if (now - lastSweepAt < SWEEP_INTERVAL_MS) return;
  lastSweepAt = now;
  for (const [key, entry] of attempts) {
    if (entry.resetAt <= now) attempts.delete(key);
  }
}

function bumpSource(source: string, now: number): number {
  const existing = attempts.get(source);
  if (existing) return bump(existing, now);
  if (attempts.size >= MAX_TRACKED_SOURCES) return 0; // 전역 층이 대신 센다(위 주석 참조).
  attempts.set(source, { count: 1, resetAt: now + ATTEMPT_WINDOW_MS });
  return 1;
}

export interface EnrollGate {
  /** 키를 검증하기 전에 기다려야 하는 시간(ms). */
  delayMs: number;
  /** 키가 **틀렸을 때** 401 대신 429 로 답해야 하는가. 맞는 키는 이 값과 무관하게 통과한다. */
  throttled: boolean;
  /** 대기열이 한계를 넘어 기다리지 않고 거절해야 하는가(틀린 키 경로에서만 의미가 있다). */
  overloaded: boolean;
  /** 429 응답의 Retry-After(초). */
  retryAfterSeconds: number;
}

/**
 * 시도 1회를 기록하고 이 요청을 어떻게 다룰지 답한다.
 *
 * 계수는 키 검증보다 **먼저**다 — 나중에 세면 실패 응답 자체가 무료 추측 기회가 된다.
 */
export function registerEnrollAttempt(source: string): EnrollGate {
  const now = Date.now();
  sweep(now);

  const global = bump(globalWindow, now);
  const perSource = bumpSource(source, now);
  const pressure = Math.max(global, perSource);

  // 2^8 이면 이미 상한에 닿는다. 지수를 묶어 두지 않으면 큰 수의 거듭제곱이 Infinity 로 튄다.
  const spacing = Math.min(PENALTY_BASE_MS * 2 ** Math.min(pressure - 1, 8), PENALTY_MAX_MS);
  const startAt = Math.max(now, nextSlotAt);
  const delayMs = startAt - now;
  const overloaded = delayMs > PENALTY_QUEUE_MAX_MS;
  // 밀려서 거절할 요청은 줄을 더 늘리지 않는다(거절이 대기열을 키우면 회복이 늦어진다).
  if (!overloaded) nextSlotAt = startAt + spacing;

  const throttled = overloaded || perSource > MAX_ATTEMPTS_PER_SOURCE || global > MAX_ATTEMPTS_GLOBAL;
  // 창이 리셋될 때까지가 정확한 답이지만(최대 10분), 그대로 안내하면 "맞는 키는 즉시 통과"라는
  // 사실과 어긋나 사용자를 불필요하게 기다리게 한다. 60초로 잘라 재시도를 유도한다.
  const retryAfterSeconds = throttled
    ? Math.max(1, Math.min(60, Math.ceil((globalWindow.resetAt - now) / 1000)))
    : 0;

  return {
    delayMs: overloaded ? 0 : delayMs,
    throttled,
    overloaded,
    retryAfterSeconds,
  };
}

/** 감속. 발신지와 무관하게 걸리며, 전역 직렬화라 병렬 요청은 서로를 밀어낸다. */
export async function applyEnrollPenalty(gate: EnrollGate): Promise<void> {
  if (gate.delayMs > 0) await delay(gate.delayMs);
}

/**
 * 성공한 등록은 압력을 비운다 — 이 리셋을 유발할 수 있는 것은 **키를 아는 사람뿐**이므로
 * 공격자가 악용할 수 없고, 공격 중에 등록한 정상 단말이 뒤이어 벌을 받지도 않는다.
 */
export function clearEnrollAttempts(source: string): void {
  attempts.delete(source);
  globalWindow.count = 0;
  globalWindow.resetAt = 0;
}

/**
 * 시도 계수의 기준이 되는 발신지.
 *
 * ★`X-Forwarded-For` 는 **명시적으로 신뢰를 선언했을 때만** 본다(`POS_TRUSTED_PROXY`).
 * Next 는 클라이언트가 XFF 를 보내지 않은 경우에만 그 헤더를 소켓 주소로 채우므로
 * (`next/dist/server/base-server.js`), 헤더가 오면 소켓 주소는 이미 소실된 뒤다 — 즉 선언 없이는
 * "위조인지 진짜인지"를 구분할 방법이 라우트 핸들러에 없다. 구분할 수 없으면 구분하는 척하지 않고
 * **단일 버킷으로 접는다**: 헤더를 아무리 돌려도 카운터가 하나라 우회가 성립하지 않는다.
 *
 * 신뢰를 선언할 때는 **오른쪽에서 hops 번째** 값을 읽는다. 프록시는 자기가 본 주소를 뒤에 덧붙이므로
 * 클라이언트가 미리 넣어 둔 값은 왼쪽으로 밀려난다 — 왼쪽 첫 값을 읽던 예전 구현이 정확히 그
 * 위조 값을 읽고 있었다. 프록시가 실제로 없는데 이 값을 켜면 다시 클라이언트를 믿게 되므로,
 * 실제 홉 수와 정확히 일치시켜야 한다(.env.example 참조).
 */
const DIRECT_SOURCE = "direct";

function trustedProxyHops(): number {
  const raw = process.env.POS_TRUSTED_PROXY?.trim();
  if (!raw) return 0;
  if (raw === "true") return 1;
  const hops = Number(raw);
  return Number.isSafeInteger(hops) && hops > 0 ? hops : 0;
}

export function enrollClientKey(request: NextRequest): string {
  const hops = trustedProxyHops();
  if (hops <= 0) return DIRECT_SOURCE;

  const forwarded = request.headers.get("x-forwarded-for");
  if (!forwarded) return DIRECT_SOURCE;
  const chain = forwarded
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
  // 선언한 홉 수보다 사슬이 짧으면 프록시를 거치지 않은 요청이다 — 위조로 보고 단일 버킷에 넣는다.
  const client = chain[chain.length - hops];
  return client ? `ip:${client}` : DIRECT_SOURCE;
}
