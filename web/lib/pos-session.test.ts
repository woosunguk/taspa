import { createHmac, randomBytes } from "node:crypto";
import type { NextRequest } from "next/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * `lib/pos-session.ts` — POS 단말 인증의 순수 로직.
 *
 * 이 파일이 결제 승인의 **유일한 관문**이라, 여기서 조용히 약해지면 손님이 자기 QR 을 BFF 로 직접
 * 보내 매장에 가지도 않고 회사 예산으로 결제를 성립시킬 수 있다. 그래서 테스트는 "동작한다"가 아니라
 * **거절해야 하는 것들**과 **문서가 약속한 회수 경로**를 잠근다.
 *
 * 모듈 전역 상태(설정 캐시·시도 카운터)가 있으므로 테스트마다 `vi.resetModules()` 로 새로 적재한다.
 */

type PosSession = typeof import("@/lib/pos-session");

/** 강도 검증을 통과하는 등록 키(22자, 문자 종류 다수 → 추정 엔트로피 90bit 이상). */
const STRONG_KEY = "Zx7Qp2Lm9Rt4Vb8N-cD3fG";
const OTHER_STRONG_KEY = "Kw5Th1Yu6Ie0Op3As-dF7g";
const SECRET = "signing-material-0123456789abcdef";
const OTHER_SECRET = "signing-material-fedcba9876543210";

const COOKIE_NAME = "pos_terminal";
const DAY_MS = 24 * 60 * 60 * 1000;
const IDLE_TTL_MS = 7 * DAY_MS;
const ABSOLUTE_TTL_MS = 90 * DAY_MS;

const ENV_KEYS = [
  "POS_TERMINAL_KEY",
  "POS_SESSION_SECRET",
  "POS_CLIENT_SECRET",
  "POS_TRUSTED_PROXY",
] as const;

let savedEnv: Record<string, string | undefined> = {};

beforeEach(() => {
  savedEnv = Object.fromEntries(ENV_KEYS.map((name) => [name, process.env[name]]));
  for (const name of ENV_KEYS) delete process.env[name];
  // 약한 키 경고는 설계상 stderr 로 나간다 — 테스트 출력만 조용히 시킨다.
  vi.spyOn(console, "error").mockImplementation(() => {});
  vi.spyOn(console, "info").mockImplementation(() => {});
});

afterEach(() => {
  for (const name of ENV_KEYS) {
    const value = savedEnv[name];
    if (value === undefined) delete process.env[name];
    else process.env[name] = value;
  }
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

interface Env {
  key?: string;
  secret?: string;
  clientSecret?: string;
  trustedProxy?: string;
}

function applyEnv(env: Env): void {
  if (env.key !== undefined) process.env.POS_TERMINAL_KEY = env.key;
  else delete process.env.POS_TERMINAL_KEY;
  if (env.secret !== undefined) process.env.POS_SESSION_SECRET = env.secret;
  else delete process.env.POS_SESSION_SECRET;
  if (env.clientSecret !== undefined) process.env.POS_CLIENT_SECRET = env.clientSecret;
  else delete process.env.POS_CLIENT_SECRET;
  if (env.trustedProxy !== undefined) process.env.POS_TRUSTED_PROXY = env.trustedProxy;
  else delete process.env.POS_TRUSTED_PROXY;
}

/** 새 모듈 인스턴스를 적재한다(전역 카운터·설정 캐시 초기화). */
async function load(env: Env = { key: STRONG_KEY, secret: SECRET }): Promise<PosSession> {
  vi.resetModules();
  applyEnv(env);
  return import("@/lib/pos-session");
}

/** 쿠키만 담은 최소 요청. 프로덕션 코드는 `cookies.get`·`headers.get` 만 쓴다. */
function requestWith(cookie?: string, headers: Record<string, string> = {}): NextRequest {
  return {
    cookies: {
      get: (name: string) =>
        name === COOKIE_NAME && cookie !== undefined ? { name, value: cookie } : undefined,
    },
    headers: new Headers(headers),
  } as unknown as NextRequest;
}

/** `Set-Cookie` 헤더에서 쿠키 값만 뽑는다. */
function cookieValue(setCookie: string): string {
  const first = setCookie.split(";")[0] ?? "";
  return first.slice(first.indexOf("=") + 1);
}

/*
 * 아래 두 함수는 프로덕션의 서명 규약을 의도적으로 복제한다. 그래야 "서명은 올바른데 다른 이유로
 * 거절돼야 하는" 쿠키(버전 불일치·형식 위반·만료)를 만들 수 있다 — 서명 실패로 통과해 버리면
 * 그 검사들이 실제로 도는지 알 수 없다.
 */
function signingKeyFor(key: string, secret: string): Buffer {
  return createHmac("sha256", secret).update(`taspa-pos-terminal/v1\n${key}`).digest();
}

function craft(payload: string, key = STRONG_KEY, secret = SECRET): string {
  const signature = createHmac("sha256", signingKeyFor(key, secret)).update(payload).digest("base64url");
  return `${payload}.${signature}`;
}

function terminalId(): string {
  return randomBytes(16).toString("base64url");
}

describe("등록 키 강도 (fail-closed)", () => {
  it("키·시크릿이 없으면 등록 기능이 꺼진다", async () => {
    const mod = await load({});
    expect(mod.enrollmentConfigured()).toBe(false);
    expect(mod.enrollmentProblem()).toBe("missing");
  });

  it("시크릿만 없어도 꺼진다", async () => {
    const mod = await load({ key: STRONG_KEY });
    expect(mod.enrollmentConfigured()).toBe(false);
    expect(mod.enrollmentProblem()).toBe("missing");
  });

  it("POS_CLIENT_SECRET 이 시크릿의 폴백으로 쓰인다", async () => {
    const mod = await load({ key: STRONG_KEY, clientSecret: SECRET });
    expect(mod.enrollmentConfigured()).toBe(true);
    expect(mod.enrollmentProblem()).toBeNull();
  });

  it("짧은 키는 등록 자체를 비활성화한다 — 스로틀이 아니라 이게 첫 방어선이다", async () => {
    const mod = await load({ key: "short", secret: SECRET });
    expect(mod.enrollmentConfigured()).toBe(false);
    expect(mod.enrollmentProblem()).toBe("weak-key");
    // 기능이 꺼졌으므로 그 키로도 등록할 수 없다.
    expect(mod.enroll("short")).toBeNull();
  });

  it("문자 종류가 적은 키(aaaa…)는 길어도 거절한다", async () => {
    const mod = await load({ key: "a".repeat(32), secret: SECRET });
    expect(mod.enrollmentProblem()).toBe("weak-key");
  });

  it("길이·종류는 통과해도 추정 엔트로피가 모자라면 거절한다", async () => {
    const mod = await load({ key: "abcdefghabcdefgh", secret: SECRET });
    expect(mod.enrollmentProblem()).toBe("weak-key");
  });

  it("강한 키는 통과한다 (대조군)", async () => {
    const mod = await load();
    expect(mod.enrollmentConfigured()).toBe(true);
    expect(mod.enrollmentProblem()).toBeNull();
  });
});

describe("등록과 쿠키 속성", () => {
  it("맞는 키는 httpOnly·SameSite=Strict 쿠키를 발급한다", async () => {
    const mod = await load();
    const result = mod.enroll(STRONG_KEY);
    expect(result).not.toBeNull();
    const setCookie = result!.setCookie;
    expect(setCookie.startsWith(`${COOKIE_NAME}=`)).toBe(true);
    expect(setCookie).toContain("HttpOnly");
    expect(setCookie).toContain("SameSite=Strict");
    expect(setCookie).toContain("Path=/");
    expect(setCookie).toContain(`Max-Age=${Math.floor(IDLE_TTL_MS / 1000)}`);
    expect(result!.terminalId).toMatch(/^[A-Za-z0-9_-]{16,64}$/);
    expect(mod.TERMINAL_COOKIE_NAME).toBe(COOKIE_NAME);
  });

  it("운영에서만 Secure 를 붙인다 (개발은 http://localhost 라 붙이면 쿠키가 저장되지 않는다)", async () => {
    const dev = await load();
    expect(dev.enroll(STRONG_KEY)!.setCookie.split("; ")).not.toContain("Secure");

    vi.stubEnv("NODE_ENV", "production");
    const prod = await load();
    expect(prod.enroll(STRONG_KEY)!.setCookie.split("; ")).toContain("Secure");
  });

  it("틀린 키는 거절한다", async () => {
    const mod = await load();
    expect(mod.enroll(OTHER_STRONG_KEY)).toBeNull();
  });

  it("길이가 다른 키도 예외 없이 거절한다 (상수시간 비교의 길이 가드)", async () => {
    const mod = await load();
    expect(mod.enroll("")).toBeNull();
    expect(mod.enroll(`${STRONG_KEY}x`)).toBeNull();
    expect(mod.enroll(STRONG_KEY.slice(0, -1))).toBeNull();
  });

  it("발급한 쿠키는 같은 설정에서 그대로 인정된다", async () => {
    const mod = await load();
    const result = mod.enroll(STRONG_KEY)!;
    const session = mod.enrolledTerminal(requestWith(cookieValue(result.setCookie)));
    expect(session).not.toBeNull();
    expect(session!.terminalId).toBe(result.terminalId);
    expect(mod.isEnrolledTerminal(requestWith(cookieValue(result.setCookie)))).toBe(true);
  });

  it("쿠키가 아예 없으면 미등록이다", async () => {
    const mod = await load();
    expect(mod.enrolledTerminal(requestWith())).toBeNull();
  });

  it("등록 기능이 꺼져 있으면 유효한 쿠키가 있어도 미등록이다", async () => {
    const issuer = await load();
    const cookie = cookieValue(issuer.enroll(STRONG_KEY)!.setCookie);
    const disabled = await load({});
    expect(disabled.enrolledTerminal(requestWith(cookie))).toBeNull();
  });
});

describe("위조·형식 거절", () => {
  it("서명을 바꾼 쿠키를 거절한다", async () => {
    const mod = await load();
    const cookie = cookieValue(mod.enroll(STRONG_KEY)!.setCookie);
    const separator = cookie.lastIndexOf(".");
    const forged = `${cookie.slice(0, separator)}.${"A".repeat(cookie.length - separator - 1)}`;
    expect(forged).not.toBe(cookie);
    expect(mod.enrolledTerminal(requestWith(forged))).toBeNull();
  });

  it("만료를 늘린 페이로드를 거절한다 (서명이 페이로드 전체를 덮는다)", async () => {
    const mod = await load();
    const cookie = cookieValue(mod.enroll(STRONG_KEY)!.setCookie);
    const parts = cookie.split(".");
    parts[3] = String(Date.now() + ABSOLUTE_TTL_MS);
    expect(mod.enrolledTerminal(requestWith(parts.join(".")))).toBeNull();
  });

  it("버전이 다른 쿠키는 서명이 맞아도 거절한다", async () => {
    const mod = await load();
    const now = Date.now();
    const cookie = craft(`v0.${terminalId()}.${now}.${now + IDLE_TTL_MS}`);
    expect(mod.enrolledTerminal(requestWith(cookie))).toBeNull();
  });

  it("terminalId 패턴을 어긴 쿠키는 서명이 맞아도 거절한다", async () => {
    const mod = await load();
    const now = Date.now();
    // 너무 짧음 / 허용되지 않는 문자 — 둘 다 거절돼야 한다.
    expect(mod.enrolledTerminal(requestWith(craft(`v1.short.${now}.${now + IDLE_TTL_MS}`)))).toBeNull();
    expect(
      mod.enrolledTerminal(requestWith(craft(`v1.${"a".repeat(15)}!.${now}.${now + IDLE_TTL_MS}`))),
    ).toBeNull();
  });

  it("필드 수가 다르거나 시각이 숫자가 아니면 거절한다", async () => {
    const mod = await load();
    const now = Date.now();
    expect(mod.enrolledTerminal(requestWith(craft(`v1.${terminalId()}.${now}`)))).toBeNull();
    expect(
      mod.enrolledTerminal(requestWith(craft(`v1.${terminalId()}.notanumber.${now + IDLE_TTL_MS}`))),
    ).toBeNull();
  });

  it("구분자가 없는 값·빈 값을 거절한다", async () => {
    const mod = await load();
    expect(mod.enrolledTerminal(requestWith(""))).toBeNull();
    expect(mod.enrolledTerminal(requestWith("garbage"))).toBeNull();
    expect(mod.enrolledTerminal(requestWith(".onlyseparator"))).toBeNull();
  });
});

describe("키 회전 = 즉시 회수", () => {
  /*
   * 문서가 약속하는 유일한 사고 대응이다("유출되면 키를 교체해 전 단말을 즉시 무효화").
   * 예전 구현은 등록 키를 비교에만 쓰고 서명에는 넣지 않아 이 약속이 **실제로는 아무 일도 하지
   * 않았다** — 교체해도 이미 나간 쿠키가 그대로 살아 있었다.
   */
  it("대조군: 설정이 그대로면 쿠키는 유효하다", async () => {
    const issuer = await load();
    const cookie = cookieValue(issuer.enroll(STRONG_KEY)!.setCookie);
    const reloaded = await load({ key: STRONG_KEY, secret: SECRET });
    expect(reloaded.enrolledTerminal(requestWith(cookie))).not.toBeNull();
  });

  it("POS_TERMINAL_KEY 를 바꾸면 기존 쿠키가 전부 무효가 된다", async () => {
    const issuer = await load();
    const cookie = cookieValue(issuer.enroll(STRONG_KEY)!.setCookie);
    const rotated = await load({ key: OTHER_STRONG_KEY, secret: SECRET });
    expect(rotated.enrolledTerminal(requestWith(cookie))).toBeNull();
  });

  it("POS_SESSION_SECRET 을 바꿔도 기존 쿠키가 전부 무효가 된다", async () => {
    const issuer = await load();
    const cookie = cookieValue(issuer.enroll(STRONG_KEY)!.setCookie);
    const rotated = await load({ key: STRONG_KEY, secret: OTHER_SECRET });
    expect(rotated.enrolledTerminal(requestWith(cookie))).toBeNull();
  });

  it("키 교체는 프로세스 재시작 없이도(설정 캐시가 갱신되어) 적용된다", async () => {
    const mod = await load();
    const cookie = cookieValue(mod.enroll(STRONG_KEY)!.setCookie);
    expect(mod.enrolledTerminal(requestWith(cookie))).not.toBeNull();
    // 같은 모듈 인스턴스에서 환경변수만 교체 — 캐시가 (키, 시크릿) 쌍으로 무효화돼야 한다.
    process.env.POS_TERMINAL_KEY = OTHER_STRONG_KEY;
    expect(mod.enrolledTerminal(requestWith(cookie))).toBeNull();
    expect(mod.enroll(STRONG_KEY)).toBeNull();
    expect(mod.enroll(OTHER_STRONG_KEY)).not.toBeNull();
  });
});

describe("만료 — 유휴와 절대는 각각 독립적으로 막는다", () => {
  it("유휴 만료: 발급은 최근이어도 만료 시각이 지났으면 거절", async () => {
    const mod = await load();
    const now = Date.now();
    const cookie = craft(`v1.${terminalId()}.${now - DAY_MS}.${now - 1_000}`);
    expect(mod.enrolledTerminal(requestWith(cookie))).toBeNull();
  });

  it("절대 만료: 유휴 만료가 한참 남았어도 최초 등록이 90일을 넘겼으면 거절", async () => {
    const mod = await load();
    const now = Date.now();
    // 갱신을 계속 받아 온 단말을 흉내 낸다 — expiresAt 은 미래다.
    const cookie = craft(`v1.${terminalId()}.${now - ABSOLUTE_TTL_MS - 1_000}.${now + IDLE_TTL_MS}`);
    expect(mod.enrolledTerminal(requestWith(cookie))).toBeNull();
  });

  it("대조군: 둘 다 남아 있으면 유효", async () => {
    const mod = await load();
    const now = Date.now();
    const cookie = craft(`v1.${terminalId()}.${now - 10 * DAY_MS}.${now + DAY_MS}`);
    expect(mod.enrolledTerminal(requestWith(cookie))).not.toBeNull();
  });

  it("미래에 발급된 쿠키(시계 되감김)를 거절한다", async () => {
    const mod = await load();
    const now = Date.now();
    const cookie = craft(`v1.${terminalId()}.${now + 10 * 60_000}.${now + IDLE_TTL_MS}`);
    expect(mod.enrolledTerminal(requestWith(cookie))).toBeNull();
  });

  it("허용 오차(60초) 안의 미래 발급은 통과한다 — 정상 시계 편차로 단말이 죽지 않는다", async () => {
    const mod = await load();
    const now = Date.now();
    const cookie = craft(`v1.${terminalId()}.${now + 30_000}.${now + IDLE_TTL_MS}`);
    expect(mod.enrolledTerminal(requestWith(cookie))).not.toBeNull();
  });
});

describe("슬라이딩 갱신", () => {
  it("유휴 기간이 절반 넘게 남았으면 갱신하지 않는다", async () => {
    const mod = await load();
    const now = Date.now();
    const session = {
      terminalId: terminalId(),
      issuedAt: now,
      expiresAt: now + IDLE_TTL_MS,
    };
    expect(mod.renewTerminalCookie(session)).toBeNull();
  });

  it("절반 아래로 떨어지면 유휴 만료만 연장하고 발급 시각은 보존한다", async () => {
    const mod = await load();
    const now = Date.now();
    const issuedAt = now - 6 * DAY_MS;
    const renewed = mod.renewTerminalCookie({
      terminalId: terminalId(),
      issuedAt,
      expiresAt: now + DAY_MS,
    });
    expect(renewed).not.toBeNull();
    const decoded = mod.enrolledTerminal(requestWith(cookieValue(renewed!)));
    expect(decoded).not.toBeNull();
    // issuedAt 이 갱신마다 밀리면 절대 만료가 영원히 오지 않는다.
    expect(decoded!.issuedAt).toBe(issuedAt);
    expect(decoded!.expiresAt).toBeGreaterThan(now + 6 * DAY_MS);
  });

  it("절대 만료를 넘겨서는 연장하지 않는다", async () => {
    const mod = await load();
    const now = Date.now();
    const issuedAt = now - (ABSOLUTE_TTL_MS - 2 * 60 * 60 * 1000); // 절대 만료까지 2시간
    const renewed = mod.renewTerminalCookie({
      terminalId: terminalId(),
      issuedAt,
      expiresAt: now + 60 * 60 * 1000,
    });
    expect(renewed).not.toBeNull();
    const decoded = mod.enrolledTerminal(requestWith(cookieValue(renewed!)));
    expect(decoded!.expiresAt).toBeLessThanOrEqual(issuedAt + ABSOLUTE_TTL_MS);
  });

  it("연장할 것이 없으면 Set-Cookie 를 생략한다", async () => {
    const mod = await load();
    const now = Date.now();
    const issuedAt = now - ABSOLUTE_TTL_MS + 60_000;
    // 이미 절대 만료 상한까지 늘어난 쿠키 — 더 줄 것이 없다.
    expect(
      mod.renewTerminalCookie({
        terminalId: terminalId(),
        issuedAt,
        expiresAt: issuedAt + ABSOLUTE_TTL_MS,
      }),
    ).toBeNull();
  });

  it("등록 기능이 꺼지면 갱신도 하지 않는다", async () => {
    const mod = await load({});
    const now = Date.now();
    expect(
      mod.renewTerminalCookie({
        terminalId: terminalId(),
        issuedAt: now - 6 * DAY_MS,
        expiresAt: now + DAY_MS,
      }),
    ).toBeNull();
  });
});

describe("발신지 식별 — 신뢰를 선언했을 때만 XFF 를 본다", () => {
  it("기본값은 헤더를 무시하고 단일 버킷으로 접는다", async () => {
    const mod = await load();
    const key = mod.enrollClientKey(requestWith(undefined, { "x-forwarded-for": "1.2.3.4" }));
    expect(key).toBe("direct");
    // 헤더를 아무리 돌려도 같은 버킷이라 우회가 성립하지 않는다.
    expect(mod.enrollClientKey(requestWith(undefined, { "x-forwarded-for": "9.9.9.9" }))).toBe(key);
  });

  it("hops=1 이면 오른쪽 첫 값(프록시가 본 주소)을 읽는다", async () => {
    const mod = await load({
      key: STRONG_KEY,
      secret: SECRET,
      trustedProxy: "true",
    });
    // 왼쪽 값은 클라이언트가 미리 심을 수 있는 위조 값이다.
    const request = requestWith(undefined, {
      "x-forwarded-for": "6.6.6.6, 203.0.113.9",
    });
    expect(mod.enrollClientKey(request)).toBe("ip:203.0.113.9");
  });

  it("hops=2 면 오른쪽에서 두 번째 값을 읽는다", async () => {
    const mod = await load({
      key: STRONG_KEY,
      secret: SECRET,
      trustedProxy: "2",
    });
    const request = requestWith(undefined, {
      "x-forwarded-for": "6.6.6.6, 203.0.113.9, 10.0.0.1",
    });
    expect(mod.enrollClientKey(request)).toBe("ip:203.0.113.9");
  });

  it("사슬이 선언한 홉 수보다 짧으면 위조로 보고 단일 버킷에 넣는다", async () => {
    const mod = await load({
      key: STRONG_KEY,
      secret: SECRET,
      trustedProxy: "3",
    });
    expect(mod.enrollClientKey(requestWith(undefined, { "x-forwarded-for": "6.6.6.6" }))).toBe("direct");
  });

  it("헤더가 없거나 값이 잘못된 신뢰 설정은 단일 버킷으로 접는다", async () => {
    const trusted = await load({
      key: STRONG_KEY,
      secret: SECRET,
      trustedProxy: "true",
    });
    expect(trusted.enrollClientKey(requestWith())).toBe("direct");
    const bogus = await load({
      key: STRONG_KEY,
      secret: SECRET,
      trustedProxy: "-1",
    });
    expect(bogus.enrollClientKey(requestWith(undefined, { "x-forwarded-for": "1.2.3.4" }))).toBe("direct");
  });
});

describe("시도 제한 — 감속은 하되 봉쇄는 하지 않는다", () => {
  it("첫 시도는 지연도 스로틀도 없다", async () => {
    const mod = await load();
    const gate = mod.registerEnrollAttempt("direct");
    expect(gate.delayMs).toBe(0);
    expect(gate.throttled).toBe(false);
    expect(gate.overloaded).toBe(false);
  });

  it("압력이 쌓이면 스로틀 상태가 되고 Retry-After 를 준다", async () => {
    const mod = await load();
    let gate = mod.registerEnrollAttempt("direct");
    for (let i = 0; i < 12; i += 1) gate = mod.registerEnrollAttempt("direct");
    expect(gate.throttled).toBe(true);
    expect(gate.retryAfterSeconds).toBeGreaterThan(0);
    expect(gate.retryAfterSeconds).toBeLessThanOrEqual(60);
  });

  it("대기열이 넘치면 기다리지 않고 즉시 거절한다 (지연 자체가 DoS 가 되지 않게)", async () => {
    const mod = await load();
    let gate = mod.registerEnrollAttempt("direct");
    for (let i = 0; i < 60; i += 1) gate = mod.registerEnrollAttempt("direct");
    expect(gate.overloaded).toBe(true);
    expect(gate.delayMs).toBe(0);
    expect(gate.throttled).toBe(true);
  });

  it("성공한 등록은 시도 카운터를 비운다 — 다음 실패의 감속이 기본 간격으로 돌아간다", async () => {
    const mod = await load();
    for (let i = 0; i < 6; i += 1) mod.registerEnrollAttempt("direct");

    // 압력이 높으면 시도 사이 간격이 상한(2s)까지 벌어진다.
    const loadedFirst = mod.registerEnrollAttempt("direct").delayMs;
    const loadedSecond = mod.registerEnrollAttempt("direct").delayMs;
    expect(loadedSecond - loadedFirst).toBeGreaterThan(1_900);

    mod.clearEnrollAttempts("direct");

    // 카운터가 비었으므로 간격이 기본값(100ms)으로 복귀한다.
    const clearedFirst = mod.registerEnrollAttempt("direct").delayMs;
    const clearedSecond = mod.registerEnrollAttempt("direct").delayMs;
    expect(clearedSecond - clearedFirst).toBeLessThan(200);
  });

  it("이미 쌓인 대기열(nextSlotAt)은 비워지지 않는다 — 감속은 전역 직렬화라 리셋 대상이 아니다", async () => {
    /*
     * 발견 사항의 고정: `clearEnrollAttempts` 는 카운터(발신지별·전역)만 비우고 전역 직렬화 시각은
     * 그대로 둔다. 그래서 KDoc 의 "공격 중에 등록한 정상 단말이 뒤이어 벌을 받지도 않는다"는
     * **맞는 키 경로에 한해서만** 참이다(그 경로는 지연을 아예 통과하지 않는다).
     * 보안상 안전한 방향(느슨해지지 않음)이라 그대로 두고, 실제 동작을 명시적으로 잠근다.
     */
    const mod = await load();
    for (let i = 0; i < 20; i += 1) mod.registerEnrollAttempt("direct");
    mod.clearEnrollAttempts("direct");

    const gate = mod.registerEnrollAttempt("direct");
    expect(gate.overloaded).toBe(true);

    // 그럼에도 맞는 키는 통과한다 — 이것이 실제로 지켜야 할 불변식이다.
    expect(mod.enroll(STRONG_KEY)).not.toBeNull();
  });

  it("지연은 gate 가 지시한 만큼만 든다", async () => {
    const mod = await load();
    const started = Date.now();
    await mod.applyEnrollPenalty({
      delayMs: 0,
      throttled: false,
      overloaded: false,
      retryAfterSeconds: 0,
    });
    expect(Date.now() - started).toBeLessThan(50);
  });
});

describe("등록 처리 순서 — 계수 → 검증 → (틀렸을 때만) 지연·429", () => {
  /*
   * ★이 순서가 뒤바뀌면(압력을 이유로 검증 전에 429 를 돌려주면) 공격자가 틀린 키를 초당 한 번만
   * 보내도 **맞는 키를 가진 매장의 등록을 무기한 봉쇄**할 수 있다. 제한이 지키려던 정상 영업을
   * 제한이 깨뜨리는 것이라, 라우트 단위로 잠근다.
   */
  type EnrollRoute = typeof import("@/app/api/pos/enroll/route");

  async function loadRoute(env: Env = { key: STRONG_KEY, secret: SECRET }): Promise<{
    route: EnrollRoute;
    session: PosSession;
  }> {
    vi.resetModules();
    applyEnv(env);
    const [route, session] = await Promise.all([
      import("@/app/api/pos/enroll/route"),
      import("@/lib/pos-session"),
    ]);
    return { route, session };
  }

  function postWith(key: unknown): NextRequest {
    return {
      json: async () => ({ key }),
      cookies: { get: () => undefined },
      headers: new Headers(),
    } as unknown as NextRequest;
  }

  it("등록 미설정이면 503 이고 어떤 키도 통과하지 못한다", async () => {
    const { route } = await loadRoute({});
    const response = await route.POST(postWith(STRONG_KEY));
    expect(response.status).toBe(503);
    expect(response.headers.get("set-cookie")).toBeNull();
  });

  it("압력이 없을 때 틀린 키는 401 이며 쿠키를 심지 않는다", async () => {
    const { route } = await loadRoute();
    const response = await route.POST(postWith("wrong-key-attempt-000"));
    expect(response.status).toBe(401);
    expect(response.headers.get("set-cookie")).toBeNull();
    expect(await response.json()).toMatchObject({
      errorCode: "TERMINAL_UNAUTHORIZED",
    });
  });

  it("압력이 최대여도 **맞는 키는 지체 없이 통과**한다", async () => {
    const { route, session } = await loadRoute();
    // 틀린 키를 퍼부어 대기열을 포화시킨 상태를 만든다(라우트를 거치지 않고 카운터만 밀어 올린다).
    for (let i = 0; i < 80; i += 1) session.registerEnrollAttempt("direct");
    expect(session.registerEnrollAttempt("direct").overloaded).toBe(true);

    const started = Date.now();
    const response = await route.POST(postWith(STRONG_KEY));
    const elapsed = Date.now() - started;

    expect(response.status).toBe(200);
    expect(response.headers.get("set-cookie")).toContain(`${COOKIE_NAME}=`);
    // 공격 압력이 정상 등록을 지연시키지 않는다.
    expect(elapsed).toBeLessThan(1_000);
  });

  it("같은 압력에서 틀린 키는 429 + Retry-After 를 받는다", async () => {
    const { route, session } = await loadRoute();
    for (let i = 0; i < 80; i += 1) session.registerEnrollAttempt("direct");

    const response = await route.POST(postWith("wrong-key-attempt-000"));
    expect(response.status).toBe(429);
    expect(Number(response.headers.get("retry-after"))).toBeGreaterThan(0);
    expect(await response.json()).toMatchObject({
      errorCode: "TERMINAL_RATE_LIMITED",
    });
  });

  it("본문이 없거나 key 가 문자열이 아니면 401 로 수렴한다 (형식 오류를 구분해 알려주지 않는다)", async () => {
    const { route } = await loadRoute();
    const broken = {
      json: async () => {
        throw new SyntaxError("not json");
      },
      cookies: { get: () => undefined },
      headers: new Headers(),
    } as unknown as NextRequest;
    expect((await route.POST(broken)).status).toBe(401);
    expect((await route.POST(postWith(12345))).status).toBe(401);
  });

  it("성공한 등록으로 발급된 쿠키는 곧바로 단말로 인정된다", async () => {
    const { route, session } = await loadRoute();
    const response = await route.POST(postWith(STRONG_KEY));
    const setCookie = response.headers.get("set-cookie")!;
    expect(session.isEnrolledTerminal(requestWith(cookieValue(setCookie)))).toBe(true);
  });
});
