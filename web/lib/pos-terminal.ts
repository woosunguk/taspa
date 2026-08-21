/**
 * POS 단말 BFF 의 서버 측 코어 — **이 모듈은 Next 서버에서만 실행된다.**
 *
 * 왜 BFF 인가: taspa 의 승인 API(`/api/merchant/redeem`)는 M2M 베어러 전용이고, 그 토큰은 가맹
 * client secret 으로만 받을 수 있다. 계산대 브라우저에 secret 을 두면 매장 단말 한 대만 열려도
 * **그 가맹의 모든 승인·취소 권한**이 통째로 유출된다(폐쇄루프 장부에 임의 거래를 쓸 수 있다).
 * 그래서 secret 은 Next 서버 프로세스 안에만 있고, 브라우저는 `/api/pos/*` 만 호출한다.
 *
 * 이 파일이 지키는 것:
 *  - secret·access token 은 **어떤 응답에도, 어떤 로그에도 실리지 않는다**(에러 문구 포함).
 *  - 토큰은 메모리에만, 만료 여유를 두고 재사용한다(요청마다 토큰을 새로 받으면 계산대 응답이 느려지고
 *    taspa 토큰 엔드포인트가 불필요한 부하를 받는다).
 *  - 자격증명이 없으면 **조용히 실패하지 않는다** — 호출부가 "단말 미등록"을 화면에 띄울 수 있게
 *    구분 가능한 errorCode 로 되돌린다.
 */

/**
 * 클라이언트 번들에 섞여 들어가면 secret 이 브라우저로 나간다. import 그래프가 잘못 얽히는 사고를
 * 침묵으로 넘기지 않는다(`server-only` 패키지가 의존성에 없어 직접 막는다).
 */
if (typeof window !== "undefined") {
  throw new Error("lib/pos-terminal 는 서버 전용 모듈입니다");
}

/**
 * taspa 오리진 — `next.config.ts` 의 프록시와 **같은 환경변수**를 쓴다(단말과 프록시가 갈라지지 않게).
 *
 * ★그런데 두 소비자의 해석 시점이 다르다. `next.config.ts` 의 `rewrites()` 는 **빌드 중 한 번** 실행되고
 * 결과가 `.next/routes-manifest.json` 에 직렬화되므로, standalone 서버는 런타임 env 를 다시 보지 않는다.
 * 반면 이 파일은 요청마다 `process.env` 를 읽는다. 그래서 운영자가 런타임 env 로만 오리진을 덮으면
 * **프록시는 이미지에 박힌 A 를, 결제 중계는 B 를 보는 분기**가 조용히 생긴다 — 로그인은 A 에서 되는데
 * 승인은 B 로 나가는, 진단하기 가장 나쁜 종류의 어긋남이다.
 *
 * 오리진을 바꾸려면 **재빌드**해야 한다(web/Dockerfile·README 참고). 아래 가드는 그 규칙을 문서가 아니라
 * 코드로 집행한다 — 빌드 시각 값과 런타임 값이 다르면 **첫 요청에서 크게 실패**시킨다. 조용한 분기보다
 * 시끄러운 정지가 낫다(결제 경로다).
 */
const TASPA_ORIGIN = process.env.TASPA_ORIGIN ?? "http://localhost:9100";

/**
 * 빌드 시각에 굳은 오리진. `next.config.ts` 가 rewrites 를 만들 때 본 값과 같은 값이 여기 박힌다
 * (둘 다 빌드 중 평가되는 표현식이다).
 */
const BUILD_TIME_ORIGIN = process.env.NEXT_PUBLIC_TASPA_BUILD_ORIGIN ?? TASPA_ORIGIN;

if (BUILD_TIME_ORIGIN !== TASPA_ORIGIN) {
  throw new Error(
    `TASPA_ORIGIN 이 빌드 시각(${BUILD_TIME_ORIGIN})과 런타임(${TASPA_ORIGIN})에 다릅니다. ` +
      "프록시 목적지는 빌드에 굳으므로 런타임 변경만으로는 갈라집니다 — 오리진을 바꾸려면 이미지를 다시 빌드하세요.",
  );
}

/**
 * 승인에 필요한 유일한 scope. 등록 클라이언트가 더 많은 scope 를 가졌더라도 여기서 좁혀 요청한다 —
 * 단말이 탈취돼도 발급되는 토큰의 권한이 승인/취소를 넘지 않는다.
 */
const REDEEM_SCOPE = "meal.redeem";

/**
 * 만료 여유. 토큰을 만료 직전까지 쓰면 "발급 시점엔 유효했는데 taspa 에 도착하니 만료"인 경합이
 * 생기고, 그게 하필 계산대에서 터진다. 서버 access TTL 은 15분이라 60초를 떼도 재발급이 잦지 않다.
 */
const EXPIRY_SKEW_MS = 60_000;

/** taspa 가 응답에 expires_in 을 주지 않는 이례적 상황의 하한(짧게 잡아 자주 갱신되게 한다). */
const FALLBACK_TTL_SEC = 60;

/** 계산대가 무한정 기다리지 않게 한다 — 응답이 없으면 재시도가 낫다. */
const UPSTREAM_TIMEOUT_MS = 10_000;

/** 브라우저에 그대로 전달되는 단말 측 오류 코드(서버 ErrorCode 와 겹치지 않는 이름을 쓴다). */
export const TERMINAL_NOT_CONFIGURED = "TERMINAL_NOT_CONFIGURED";
export const TERMINAL_UPSTREAM_ERROR = "TERMINAL_UPSTREAM_ERROR";

export interface TerminalConfig {
  clientId: string;
  clientSecret: string;
  origin: string;
}

/** 설정 조회 결과 — 누락된 환경변수 **이름만** 노출한다(값은 절대 아니다). */
export type ConfigResult = { ok: true; config: TerminalConfig } | { ok: false; missing: string[] };

export function terminalConfig(): ConfigResult {
  const clientId = process.env.POS_CLIENT_ID?.trim();
  const clientSecret = process.env.POS_CLIENT_SECRET?.trim();

  const missing: string[] = [];
  if (!clientId) missing.push("POS_CLIENT_ID");
  if (!clientSecret) missing.push("POS_CLIENT_SECRET");
  if (missing.length > 0) return { ok: false, missing };

  return {
    ok: true,
    config: {
      clientId: clientId!,
      clientSecret: clientSecret!,
      origin: TASPA_ORIGIN,
    },
  };
}

/* ------------------------------------------------------------------ 토큰 캐시 */

interface CachedToken {
  accessToken: string;
  /** 이 프로세스 시계 기준 사용 만료 시각(ms) — 이미 여유(skew)를 뺀 값이다. */
  usableUntilMs: number;
}

let cached: CachedToken | null = null;

/**
 * 진행 중인 발급 요청. 계산대에서 승인이 연달아 들어오면 캐시가 빈 순간에 요청 수만큼 토큰 발급이
 * 동시에 나간다(stampede). 하나만 나가게 묶고 나머지는 같은 약속을 기다린다.
 */
let inFlight: Promise<string> | null = null;

/** 401 을 받았을 때 등 — 다음 호출이 새 토큰을 받게 한다. */
function invalidateToken(): void {
  cached = null;
}

async function accessToken(config: TerminalConfig): Promise<string> {
  const now = Date.now();
  if (cached && cached.usableUntilMs > now) return cached.accessToken;

  inFlight ??= issueToken(config).finally(() => {
    inFlight = null;
  });
  return inFlight;
}

/**
 * client_credentials 토큰 발급. 인증은 client_secret_basic — taspa 의 기밀 클라이언트 등록
 * (AdminClientService)이 이 방식으로 고정돼 있다.
 *
 * Basic 자격증명은 base64 **전에** 퍼센트 인코딩한다: SAS 의 ClientSecretBasicAuthenticationConverter
 * 가 디코드 시 URL 디코딩을 하므로, secret 에 `+` 가 있으면 인코딩 없이는 공백으로 해석돼
 * 인증이 조용히 실패한다(RFC 6749 §2.3.1).
 */
async function issueToken(config: TerminalConfig): Promise<string> {
  const basic = Buffer.from(
    `${encodeURIComponent(config.clientId)}:${encodeURIComponent(config.clientSecret)}`,
  ).toString("base64");

  const response = await fetchWithTimeout(`${config.origin}/oauth2/token`, {
    method: "POST",
    headers: {
      Authorization: `Basic ${basic}`,
      "Content-Type": "application/x-www-form-urlencoded",
      Accept: "application/json",
    },
    body: new URLSearchParams({
      grant_type: "client_credentials",
      scope: REDEEM_SCOPE,
    }).toString(),
  });

  if (!response.ok) {
    // ★본문을 그대로 싣지 않는다. 토큰 엔드포인트의 오류 본문은 자격증명 자체를 담지는 않지만,
    //   그대로 흘려보내면 브라우저가 클라이언트 식별자·설정 실수를 읽게 된다. 상태코드만 남긴다.
    throw new UpstreamError(`단말 인증에 실패했습니다 (taspa 토큰 발급 ${response.status})`, response.status);
  }

  const payload = (await response.json()) as {
    access_token?: string;
    expires_in?: number;
  };
  if (!payload.access_token) {
    throw new UpstreamError("단말 인증 응답에 토큰이 없습니다", 502);
  }

  const ttlSec =
    typeof payload.expires_in === "number" && payload.expires_in > 0 ? payload.expires_in : FALLBACK_TTL_SEC;
  // 여유를 뺀 값이 0 이하가 되는 초단명 토큰도 캐시는 하되 즉시 만료로 취급한다(재사용 금지).
  cached = {
    accessToken: payload.access_token,
    usableUntilMs: Date.now() + ttlSec * 1000 - EXPIRY_SKEW_MS,
  };
  return payload.access_token;
}

/** 상류(taspa) 통신 자체가 실패한 경우 — 업무 오류(거절)와 구분해야 안내 문구가 달라진다. */
export class UpstreamError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
    this.name = "UpstreamError";
  }
}

async function fetchWithTimeout(url: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(url, {
      ...init,
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
      cache: "no-store",
    });
  } catch (cause) {
    // 네트워크 실패·타임아웃. 원인 객체에는 내부 주소가 들어 있어 그대로 노출하지 않는다.
    const timedOut = cause instanceof Error && cause.name === "TimeoutError";
    throw new UpstreamError(
      timedOut ? "서버 응답이 없습니다. 잠시 후 다시 시도하세요." : "서버에 연결하지 못했습니다.",
      504,
    );
  }
}

/* ------------------------------------------------------- taspa 가맹 API 호출 */

export interface UpstreamResult {
  status: number;
  /** taspa 응답 본문(성공 RedeemResponse 또는 {errorCode, message}). 그대로 브라우저에 전달한다. */
  body: unknown;
}

/**
 * `/api/merchant/**` 호출. 결과는 **상태코드까지 그대로** 돌려준다 — 거절 사유(만료·이미 사용됨·
 * 끼니 시간 아님·횟수 초과)는 taspa 만 판정할 수 있고, 계산대는 그 사유별로 손님에게 다른 말을
 * 해야 한다. 여기서 뭉뚱그리면 그 정보가 사라진다.
 *
 * 401 은 한 번만 재시도한다: 캐시된 토큰이 서버 재시작·키 회전으로 무효가 된 경우가 유일하게
 * 자동 복구 가능한 케이스다. 두 번째 401 은 설정 문제이므로 그대로 올린다(무한 재시도 금지).
 */
export async function callMerchantApi(
  config: TerminalConfig,
  path: string,
  body: unknown,
  /** 조회 계열(`GET /api/merchant/me`)만 "GET". 기본은 승인·취소·환불의 POST 다. */
  method: "POST" | "GET" = "POST",
): Promise<UpstreamResult> {
  let response = await send(config, path, body, await accessToken(config), method);

  if (response.status === 401) {
    invalidateToken();
    response = await send(config, path, body, await accessToken(config), method);
  }

  return { status: response.status, body: await readJson(response) };
}

async function send(
  config: TerminalConfig,
  path: string,
  body: unknown,
  token: string,
  method: "POST" | "GET",
): Promise<Response> {
  return fetchWithTimeout(`${config.origin}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
      Accept: "application/json",
    },
    // GET 에 본문을 실으면 fetch 가 TypeError 를 던진다.
    body: method === "GET" ? undefined : JSON.stringify(body ?? {}),
  });
}

/**
 * 본문 파싱. taspa 는 오류도 JSON({errorCode, message})으로 주지만, 프레임워크 기본 오류나 프록시가
 * 끼면 HTML 이 올 수 있다 — 그때 파싱 실패로 500 을 만들지 않고 상태코드로 표현한다.
 */
async function readJson(response: Response): Promise<unknown> {
  const text = await response.text();
  if (!text) {
    return response.ok
      ? {}
      : {
          errorCode: TERMINAL_UPSTREAM_ERROR,
          message: `요청이 실패했습니다 (${response.status})`,
        };
  }
  try {
    return JSON.parse(text);
  } catch {
    return {
      errorCode: TERMINAL_UPSTREAM_ERROR,
      message: `서버 응답을 해석하지 못했습니다 (${response.status})`,
    };
  }
}

/* ------------------------------------------------------------ 요청 본문 검증 */

/**
 * 단말이 보낸 값의 형식 검증. **인가가 아니라 형식 검사다** — 금액·한도·시간대 판정은 전부 taspa 가
 * 하고, 여기서는 명백히 깨진 요청이 왕복하지 않게만 거른다(계산대 응답을 빠르게).
 */
export function requireString(value: unknown, field: string, maxLength: number): string {
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new BadRequestError(`${field} 값이 필요합니다`);
  }
  const trimmed = value.trim();
  if (trimmed.length > maxLength) throw new BadRequestError(`${field} 값이 너무 깁니다`);
  return trimmed;
}

/** 금액은 원 단위 정수다. 소수·음수·NaN 은 taspa 가 아니라 여기서 끊는다. */
export function requireAmountMinor(value: unknown): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value <= 0) {
    throw new BadRequestError("금액은 1원 이상의 정수여야 합니다");
  }
  return value;
}

export class BadRequestError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "BadRequestError";
  }
}

/**
 * 요청 본문 파싱. 깨진 JSON 은 400 이지 502 가 아니다 — 이 구분이 없으면 계산원이 "서버 장애"로
 * 읽고 매장에 문의 전화를 건다(실제로는 단말 요청이 잘못된 것이다).
 */
export async function readJsonBody(request: Request): Promise<Record<string, unknown>> {
  try {
    const parsed: unknown = await request.json();
    if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
      throw new BadRequestError("요청 본문이 올바르지 않습니다");
    }
    return parsed as Record<string, unknown>;
  } catch (cause) {
    if (cause instanceof BadRequestError) throw cause;
    throw new BadRequestError("요청 본문이 올바르지 않습니다");
  }
}

/**
 * 라우트 핸들러 공통 마무리 — 오류 종류를 브라우저가 분기할 수 있는 형태({errorCode, message})로
 * 통일한다. 예상치 못한 예외는 **내용을 노출하지 않는다**(스택·내부 주소·토큰 조각이 섞일 수 있다).
 */
export function terminalErrorResponse(cause: unknown): Response {
  if (cause instanceof BadRequestError) {
    return Response.json({ errorCode: "VALIDATION_ERROR", message: cause.message }, { status: 400 });
  }
  if (cause instanceof UpstreamError) {
    return Response.json({ errorCode: TERMINAL_UPSTREAM_ERROR, message: cause.message }, { status: 502 });
  }
  console.error("[pos] 단말 요청 처리 실패", cause instanceof Error ? cause.name : typeof cause);
  return Response.json(
    {
      errorCode: TERMINAL_UPSTREAM_ERROR,
      message: "요청을 처리하지 못했습니다.",
    },
    { status: 502 },
  );
}

/** 자격증명 미설정 응답 — 화면이 "단말이 등록되지 않았습니다"로 분기하는 신호다. */
/**
 * 등록되지 않은 브라우저의 승인·취소 시도. 화면은 이 코드를 보고 등록 단계로 되돌린다.
 * 실패 사유를 자세히 알려주지 않는다 — 이 엔드포인트는 손님도 호출할 수 있는 자리다.
 */
export function terminalUnauthorizedResponse(): Response {
  return Response.json(
    {
      errorCode: "TERMINAL_UNAUTHORIZED",
      message: "이 기기는 등록된 단말이 아닙니다. 매장 관리자에게 단말 등록을 요청하세요.",
    },
    { status: 401, headers: { "Cache-Control": "no-store" } },
  );
}

export function notConfiguredResponse(missing: string[]): Response {
  return Response.json(
    {
      errorCode: TERMINAL_NOT_CONFIGURED,
      message: `단말이 등록되지 않았습니다. 서버 환경변수를 설정하세요: ${missing.join(", ")}`,
      missing,
    },
    { status: 503 },
  );
}
