import type { NextRequest } from "next/server";
import {
  applyEnrollPenalty,
  clearEnrollAttempts,
  enroll,
  enrollClientKey,
  enrollmentConfigured,
  registerEnrollAttempt,
} from "@/lib/pos-session";

/**
 * 단말 등록 — 매장 직원이 등록 키를 한 번 입력하면 이 브라우저가 "단말"이 된다.
 *
 * 이 단계가 없으면 승인 중계가 누구에게나 열린다(lib/pos-session.ts 참조).
 * 응답에는 어떤 비밀도 싣지 않는다 — 자격은 httpOnly 쿠키로만 전달되어 페이지 스크립트가 읽을 수 없다.
 *
 * 시도 제한의 순서가 이 파일의 핵심이다: **계수 → 검증 → (틀렸을 때만) 지연·거절**.
 *  - 계수가 검증보다 먼저여야 실패 응답이 무료 추측 기회가 되지 않는다.
 *  - 검증이 지연·거절보다 먼저여야 **맞는 키는 압력과 무관하게 통과**한다. 그래야 외부인이 틀린 키를
 *    퍼부어 매장의 등록을 봉쇄하는 표적 공격이 성립하지 않는다 — 제한이 지키려던 것(정상 영업)을
 *    제한이 깨뜨려서는 안 된다.
 *  - 지연·429 는 틀린 키 경로에만 있고, 발신지와 무관하게(=위조 불가능하게) 걸린다.
 */
export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function POST(request: NextRequest): Promise<Response> {
  if (!enrollmentConfigured()) {
    return Response.json(
      {
        errorCode: "TERMINAL_NOT_CONFIGURED",
        message: "단말 등록이 설정되지 않았습니다. 관리자에게 POS_TERMINAL_KEY 설정을 요청하세요.",
      },
      { status: 503 },
    );
  }

  // 계수는 검증보다 먼저 — 나중에 세면 실패 응답 자체가 무료 추측 기회가 된다.
  const source = enrollClientKey(request);
  const gate = registerEnrollAttempt(source);

  let key = "";
  try {
    const body = (await request.json()) as { key?: unknown };
    key = typeof body.key === "string" ? body.key : "";
  } catch {
    key = "";
  }

  // ★검증이 지연·거절보다 **먼저**여야 한다. 압력을 이유로 검증 전에 되돌리면, 공격자가 틀린 키를
  //   퍼부어 대기열을 포화 상태로 유지하는 것만으로 **맞는 키를 가진 매장의 등록을 무기한 봉쇄**할 수
  //   있다(초당 한 번이면 충분하다). 그건 제한이 지키려던 것을 제한이 깨뜨리는 일이고, 이 파일이
  //   주장하는 "429 는 틀린 키에만"과도 모순된다. 검증은 HMAC 비교 한 번이라 비용이 없다.
  const session = enroll(key);
  if (session) {
    clearEnrollAttempts(source);
    // terminalId 는 비밀이 아니다(무작위 식별자) — 어느 기기가 언제 단말이 됐는지 되짚을 유일한 단서다.
    console.info(`[pos] 단말 등록 terminalId=${session.terminalId}`);
    const response = Response.json({ enrolled: true });
    response.headers.append("Set-Cookie", session.setCookie);
    return response;
  }

  // 여기부터는 **틀린 키**만 온다. 감속과 거절은 전부 이 경로에 있다.
  await applyEnrollPenalty(gate);
  if (gate.throttled) return rateLimited(gate.retryAfterSeconds);
  // 키가 틀렸는지 형식이 틀렸는지 구분해 알려주지 않는다(추측 공격에 정보를 주지 않기 위해).
  return Response.json(
    { errorCode: "TERMINAL_UNAUTHORIZED", message: "등록 키가 올바르지 않습니다." },
    { status: 401 },
  );
}

/**
 * 추측 거절. `Retry-After` 를 함께 준다 — 화면은 이 안내를 그대로 보여주고, 자동화는 여기서
 * 얻을 것이 없다는 신호를 받는다.
 */
function rateLimited(retryAfterSeconds: number): Response {
  return Response.json(
    {
      errorCode: "TERMINAL_RATE_LIMITED",
      message: `등록 시도가 너무 많습니다. ${retryAfterSeconds}초 후 다시 시도하세요.`,
      retryAfterSeconds,
    },
    { status: 429, headers: { "Retry-After": String(retryAfterSeconds) } },
  );
}
