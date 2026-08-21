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
 * 부분 환불 중계. 브라우저 → 이 핸들러 → taspa `POST /api/merchant/redeem/{authId}/refund`.
 *
 * 취소(void) 중계와 같은 두 가지 함정을 같은 방식으로 막는다:
 *  1. **단말 인증 우선** — 없으면 결제한 손님이 응답으로 받은 authId 로 자기 결제를 환불받는다.
 *  2. **authId 는 URL 경로 요소** — 형식(UUID)을 먼저 검증하고 인코딩해서 붙인다(경로 조작·SSRF).
 *
 * 환불 금액의 분담(조직/개인)과 한도 복원은 taspa 가 판정한다. 여기서 흉내 내면 화면과 장부가
 * 갈라지고, 그 차이는 돈이다.
 */
export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/** taspa 의 auth_id 는 UUID 문자열이다(MealRedeemService: UUID.randomUUID().toString()). */
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export async function POST(request: NextRequest): Promise<Response> {
  if (!isEnrolledTerminal(request)) return terminalUnauthorizedResponse();

  const config = terminalConfig();
  if (!config.ok) return notConfiguredResponse(config.missing);

  try {
    const payload = await readJsonBody(request);
    const authId = typeof payload.authId === "string" ? payload.authId.trim() : "";
    if (!UUID_PATTERN.test(authId)) {
      throw new BadRequestError("환불할 거래 번호가 올바르지 않습니다");
    }

    const amountMinor = Number(payload.amountMinor);
    if (!Number.isInteger(amountMinor) || amountMinor <= 0) {
      throw new BadRequestError("환불 금액을 올바르게 입력해 주세요");
    }

    // ★멱등키는 **단말이 만들어 재시도에서 재사용**해야 한다. 여기서 매번 새로 만들면 통신 단절 후
    // 재시도가 이중 환불이 되고, 그건 그대로 회사·직원의 손실이다(승인의 posTxnId 와 같은 이유).
    const posRefundId = typeof payload.posRefundId === "string" ? payload.posRefundId.trim() : "";
    if (!posRefundId) {
      throw new BadRequestError("환불 요청 번호가 없습니다");
    }

    const result = await callMerchantApi(
      config.config,
      `/api/merchant/redeem/${encodeURIComponent(authId)}/refund`,
      { amountMinor, posRefundId, reason: payload.reason },
    );
    return Response.json(result.body, {
      status: result.status,
      headers: { "Cache-Control": "no-store" },
    });
  } catch (cause) {
    return terminalErrorResponse(cause);
  }
}
