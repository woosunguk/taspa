/**
 * taspa API 클라이언트.
 *
 * 서버가 기대하는 3가지 계약을 한 곳에 담는다:
 *  1. **세션 쿠키 인증** — 동일 오리진 프록시(next.config.ts)를 통해 브라우저가 taspa 세션 쿠키를 그대로
 *     보낸다. 토큰을 다루지 않으므로 XSS 로 유출될 자격증명이 프런트에 존재하지 않는다.
 *  2. **CSRF** — `/api/sessions/**`·`/api/admin/**`·`/api/orgs/**` 의 상태변경은 CSRF 헤더를 요구한다.
 *     서버 렌더링 화면은 meta 태그로 토큰을 받지만 SPA 는 그럴 수 없어 `/api/csrf` 로 조달한다.
 *  3. **step-up 재인증** — 민감 작업은 최근 재인증이 없으면 401 `REAUTH_REQUIRED` 로 거절된다.
 *     이때는 에러를 띄우는 게 아니라 `/reauth` 로 보내고 원래 화면으로 복귀시킨다.
 */

export interface ApiErrorBody {
  errorCode: string;
  message: string;
  timestamp?: string;
  /** true 면 본문이 JSON 이 아니어서 **프런트가 지어낸** 값 — 서버의 선언처럼 믿으면 안 된다. */
  synthesized?: boolean;
}

/** 서버가 돌려준 오류. `errorCode` 로 분기하고, `message` 는 그대로 사용자에게 보여도 되는 문구다. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly errorCode: string,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

/** 인증이 끊긴 상태 — 호출부가 로그인으로 보낼 수 있게 별도 타입으로 구분한다. */
export class UnauthenticatedError extends ApiError {}

interface CsrfToken {
  headerName: string;
  token: string;
}

let csrfCache: CsrfToken | null = null;

async function fetchCsrf(): Promise<CsrfToken | null> {
  try {
    const response = await fetch("/api/csrf", { credentials: "same-origin" });
    if (!response.ok) return null;
    return (await response.json()) as CsrfToken;
  } catch {
    return null;
  }
}

/** 상태변경 요청에 붙일 CSRF 헤더. 조달에 실패하면 빈 객체(서버가 403 으로 거절 — fail-closed). */
async function csrfHeaders(): Promise<Record<string, string>> {
  csrfCache ??= await fetchCsrf();
  if (!csrfCache) return {};
  return { [csrfCache.headerName]: csrfCache.token };
}

/** 세션이 바뀌면 토큰도 바뀐다(로그인·재인증 후). 캐시를 버려 다음 요청에서 다시 받는다. */
export function invalidateCsrf(): void {
  csrfCache = null;
}

const MUTATING = new Set(["POST", "PUT", "PATCH", "DELETE"]);

export interface RequestOptions {
  method?: string;
  body?: unknown;
  /**
   * JSON 직렬화하지 않고 원문 그대로 보낼 본문 + 그 Content-Type.
   *
   * 서버의 일부 엔드포인트는 JSON 을 받지 않는다 — 예: `.../calendar/feeds/{id}/import` 는
   * `consumes = text/calendar`. JSON.stringify 를 태우면 따옴표·이스케이프가 섞여 .ics 가 깨지고
   * 415 로 거절된다. `body` 와 함께 쓰면 이쪽이 이긴다.
   */
  raw?: { contentType: string; content: string };
  /** step-up(401 REAUTH_REQUIRED)·미인증(401/403) 시 자동 이동을 끄고 예외로 받는다. */
  noRedirect?: boolean;
  /**
   * CSRF 토큰 조달을 건너뛴다.
   *
   * CSRF 토큰은 **taspa 세션 API 를 위한 것**이다. 이 앱이 소유한 BFF 라우트(`/api/pos/**`)는
   * 세션 쿠키로 인가하지 않으므로 토큰이 필요 없는데, 조달을 시도하면 요청마다 taspa 로 왕복이
   * 하나 더 붙는다 — 계산대처럼 taspa 가 잠시 느려도 화면은 살아 있어야 하는 곳에서 그 왕복은
   * 순수한 손해다(실패해도 무시되지만, 기다리는 시간은 그대로 든다).
   */
  noCsrf?: boolean;
  signal?: AbortSignal;
}

/**
 * 현재 경로로 돌아오도록 재인증/로그인 페이지로 이동시킨다.
 * `continue` 는 서버가 로컬 경로만 허용한다(open redirect 방지) — 여기서도 경로만 넘긴다.
 */
/**
 * 재인증 때문에 **중단된 작업**의 표식(sessionStorage 키).
 *
 * ★step-up 리다이렉트는 화면을 통째로 갈아엎는다. 그래서 작성 중이던 초대·정책 편집이 사라지는데,
 * 돌아온 화면은 아무 말도 하지 않았다 — 사용자는 저장이 **끝났다고 믿고** 떠난다(실제로는 아무 일도
 * 일어나지 않았다). 조용한 실패 중에서도 나쁜 축이다: 실패했다는 사실 자체가 어디에도 없다.
 *
 * 값은 "무엇을 하려다 끊겼는가"(경로)이고, 복귀한 화면이 읽어서 한 번 알리고 지운다.
 * sessionStorage 인 이유: 탭을 닫으면 사라져야 하고, 다른 탭의 작업과 섞이면 안 된다.
 */
export const INTERRUPTED_KEY = "taspa:interrupted";

function redirectTo(path: "login" | "reauth"): never {
  const here = window.location.pathname + window.location.search;
  if (path === "reauth") {
    try {
      sessionStorage.setItem(INTERRUPTED_KEY, here);
    } catch {
      // 사생활 보호 모드 등에서 sessionStorage 가 막혀 있어도 리다이렉트 자체는 막지 않는다.
    }
  }
  window.location.href = `/${path}?continue=${encodeURIComponent(here)}`;
  // 이동이 시작되면 이후 코드는 의미가 없다. 호출부의 then 체인이 도는 것을 막는다.
  throw new Error("navigating");
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? "GET";
  const headers: Record<string, string> = { Accept: "application/json" };

  if (options.raw) headers["Content-Type"] = options.raw.contentType;
  else if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (MUTATING.has(method) && !options.noCsrf) Object.assign(headers, await csrfHeaders());

  const response = await fetch(path, {
    method,
    headers,
    credentials: "same-origin",
    body: options.raw
      ? options.raw.content
      : options.body === undefined
        ? undefined
        : JSON.stringify(options.body),
    signal: options.signal,
  });

  if (response.ok) {
    if (response.status === 204) return undefined as T;
    const contentType = response.headers.get("content-type") ?? "";
    const text = await response.text();
    // 본문 없는 성공(204 외에도 202 accepted 등)은 Content-Type 이 아예 없다 — 정상 경로다.
    if (!text) return undefined as T;
    // 200 인데 JSON 이 아니면 API 가 아니라 **인증 관문(로그인 페이지)에 착지**한 것이다.
    // 서버는 이제 /api 이하에 401 JSON 을 주지만, 앞단의 리버스 프록시·게이트웨이가 자체 로그인
    // HTML 을 200 으로 끼워 넣으면 여기까지 온다. 그대로 두면 JSON.parse 가 SyntaxError 를 던져
    // 화면은 "Unexpected token <" 같은 무의미한 문구를 띄우고 사용자는 재로그인 방법을 알 수 없다.
    if (!contentType.includes("json")) {
      invalidateCsrf();
      if (!options.noRedirect) redirectTo("login");
      throw new UnauthenticatedError(response.status, "UNAUTHENTICATED", "로그인이 필요합니다");
    }
    return JSON.parse(text) as T;
  }

  const error = await readError(response);

  // step-up 이 필요한 민감 작업 — 재인증 후 원래 화면으로 돌아온다.
  if (response.status === 401 && error.errorCode === "REAUTH_REQUIRED") {
    invalidateCsrf();
    if (!options.noRedirect) redirectTo("reauth");
    throw new ApiError(response.status, error.errorCode, error.message);
  }

  // 세션 만료·미인증. 프록시가 로그인 페이지 HTML 을 돌려주는 경우도 여기로 수렴한다.
  if (response.status === 401) {
    invalidateCsrf();
    if (!options.noRedirect) redirectTo("login");
    throw new UnauthenticatedError(response.status, error.errorCode, error.message);
  }

  // CSRF 토큰이 만료된 경우가 있어 한 번은 캐시를 버린다(다음 시도에서 새로 받는다).
  if (response.status === 403) invalidateCsrf();

  /*
   * ★**세션이 사라진 뒤의 상태변경은 401 이 아니라 403 으로 온다** — CSRF 토큰이 세션에 매여 있기
   * 때문이다. 이 분기가 없던 동안 그 요청은 로그인 이동을 타지 못하고 화면 오류가 됐고, 서버가 우리
   * 스키마로 답하기 전에는 그 문구가 영문 "Forbidden (403)" 이었다. 사용자는 무엇이 잘못됐는지도,
   * 로그인하면 된다는 것도 알 수 없었다.
   *
   * 서버(`ApiAccessDeniedHandler`)가 CSRF 토큰 부재를 `UNAUTHENTICATED` 로 표시해 주므로 그때만
   * 401 과 **같은 경로**로 보낸다. 진짜 권한 부족(FORBIDDEN)은 그대로 화면 오류로 남긴다 —
   * 그건 로그인해도 달라지지 않는다.
   */
  if (response.status === 403 && error.errorCode === "UNAUTHENTICATED" && !error.synthesized) {
    if (!options.noRedirect) redirectTo("login");
    throw new UnauthenticatedError(response.status, error.errorCode, error.message);
  }

  throw new ApiError(response.status, error.errorCode, error.message);
}

async function readError(response: Response): Promise<ApiErrorBody> {
  try {
    const text = await response.text();
    if (!text)
      return {
        errorCode: "UNKNOWN",
        message: `요청이 실패했습니다 (${response.status})`,
      };
    // 미인증 시 프록시가 로그인 HTML 을 돌려줄 수 있다 — JSON 이 아니면 상태코드로 표현한다.
    // ★`synthesized` 표식: 이 UNAUTHENTICATED 는 **우리가 지어낸 값**이지 서버의 선언이 아니다.
    //   403 세션-소실 판정이 이 값을 서버 선언처럼 믿으면, CORS 거절("Invalid CORS request" 평문 403)
    //   같은 비-JSON 403 에서 로그인으로 보내고 — 로그인해도 같은 실패라 **무한 루프**가 된다.
    if (!text.trimStart().startsWith("{")) {
      return { errorCode: "UNAUTHENTICATED", message: "로그인이 필요합니다", synthesized: true };
    }
    const parsed = JSON.parse(text) as Partial<ApiErrorBody>;
    // ★필드 누락 정규화. 우리 GlobalExceptionHandler 는 {errorCode, message} 를 주지만, 프레임워크 기본
    // 오류 응답(Spring Boot BasicErrorController)은 {timestamp, status, error, path} 라 둘 다 없다.
    // 그대로 두면 ApiError.message 가 undefined 가 되고, 화면의 오류 표시는 "빈 문자열이면 렌더하지 않음"
    // 관용구 때문에 **아무것도 보여주지 않는다** — 사용자는 실패를 인지조차 못 한 채 방치된다.
    return {
      errorCode: parsed.errorCode ?? "UNKNOWN",
      message:
        parsed.message ??
        // Boot 기본 응답의 error 필드("Internal Server Error")라도 있으면 상태와 함께 보여준다.
        (typeof (parsed as { error?: unknown }).error === "string"
          ? `${(parsed as { error: string }).error} (${response.status})`
          : `요청이 실패했습니다 (${response.status})`),
      timestamp: parsed.timestamp,
    };
  } catch {
    return {
      errorCode: "UNKNOWN",
      message: `요청이 실패했습니다 (${response.status})`,
    };
  }
}

export const api = {
  get: <T>(path: string, options?: RequestOptions) => apiRequest<T>(path, { ...options, method: "GET" }),
  post: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    apiRequest<T>(path, { ...options, method: "POST", body }),
  put: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    apiRequest<T>(path, { ...options, method: "PUT", body }),
  patch: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    apiRequest<T>(path, { ...options, method: "PATCH", body }),
  delete: <T>(path: string, options?: RequestOptions) =>
    apiRequest<T>(path, { ...options, method: "DELETE" }),
};
