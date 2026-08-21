import type { NextRequest } from "next/server";
import {
  BadRequestError,
  callMerchantApi,
  notConfiguredResponse,
  readJsonBody,
  terminalConfig,
  terminalErrorResponse,
  terminalUnauthorizedResponse,
} from "@/lib/pos-terminal";
import { isEnrolledTerminal } from "@/lib/pos-session";

/**
 * 취소 중계. 브라우저 → 이 핸들러 → taspa `POST /api/merchant/redeem/{authId}/void`.
 *
 * authId 가 **URL 경로 요소**라는 점이 이 파일의 유일한 함정이다. 단말이 보낸 문자열을 그대로 이어
 * 붙이면 `../`·`?`·`#` 같은 문자로 상류 경로를 바꿔치기할 수 있다(SSRF·경로 조작). 그래서
 * taspa 가 발급하는 형식(UUID)인지 **먼저 검증**하고, 그 뒤 인코딩해서 붙인다. 형식 검사만으로도
 * 위험 문자가 전부 배제되지만 인코딩은 남겨 둔다 — 검증이 느슨해지는 날의 안전망이다.
 *
 * 취소 권한은 taspa 가 판정한다: 토큰에 결속된 가맹의 거래가 아니면 404(존재 오라클 방지),
 * 이미 취소된 거래는 멱등하게 현재 상태를 재반환한다. 여기서 흉내 내지 않는다.
 */
export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/** taspa 의 auth_id 는 UUID 문자열이다(MealRedeemService: UUID.randomUUID().toString()). */
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export async function POST(request: NextRequest): Promise<Response> {
  // ★단말 인증 우선. 없으면 결제한 손님이 응답으로 받은 authId 로 자기 결제를 취소해 매장 매출을 지운다.
  if (!isEnrolledTerminal(request)) return terminalUnauthorizedResponse();

  const config = terminalConfig();
  if (!config.ok) return notConfiguredResponse(config.missing);

  try {
    const payload = await readJsonBody(request);
    const authId = typeof payload.authId === "string" ? payload.authId.trim() : "";
    if (!UUID_PATTERN.test(authId)) {
      throw new BadRequestError("취소할 거래 번호가 올바르지 않습니다");
    }

    const result = await callMerchantApi(
      config.config,
      `/api/merchant/redeem/${encodeURIComponent(authId)}/void`,
      {},
    );
    return Response.json(result.body, {
      status: result.status,
      headers: { "Cache-Control": "no-store" },
    });
  } catch (cause) {
    return terminalErrorResponse(cause);
  }
}
