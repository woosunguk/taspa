import type { NextRequest } from "next/server";
import {
  callMerchantApi,
  notConfiguredResponse,
  readJsonBody,
  requireAmountMinor,
  requireString,
  terminalConfig,
  terminalErrorResponse,
  terminalUnauthorizedResponse,
} from "@/lib/pos-terminal";
import { isEnrolledTerminal } from "@/lib/pos-session";

/**
 * 승인 요청 중계. 브라우저 → 이 핸들러 → taspa `/api/merchant/redeem`.
 *
 * 이 핸들러는 **판정하지 않는다.** 끼니 시간·일 횟수·한도 분리(조직부담/개인부담)는 전부 taspa 의
 * 트랜잭션 안에서 결정되고, 여기서는 자격증명을 붙여 전달하고 결과를 상태코드까지 그대로 돌려준다.
 * 중간에서 성공/실패를 재해석하면 계산대가 손님에게 잘못된 금액을 말하게 된다.
 *
 * `posTxnId` 는 **단말이 생성해 재시도에서 재사용**하는 멱등키다(taspa 의 (merchant, posTxnId)
 * UNIQUE). 서버가 매번 새로 만들면 네트워크가 끊긴 뒤 재시도가 이중 승인이 된다 — 그래서
 * 여기서 생성하지 않고 받기만 한다. 값은 taspa 컬럼 상한(VARCHAR(128))에 맞춰 길이만 검사한다.
 */
export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/** taspa meal_transactions.pos_txn_id 상한(V25) — 초과분은 왕복 없이 여기서 끊는다. */
const MAX_POS_TXN_ID_LENGTH = 128;

/** QR 토큰은 SecureTokenGenerator 산출물이라 짧다. 스캔 오류로 들어온 거대 문자열을 걸러 낸다. */
const MAX_QR_TOKEN_LENGTH = 512;

export async function POST(request: NextRequest): Promise<Response> {
  // ★단말 인증이 **가장 먼저**다. 이 검사가 없으면 손님이 자기 QR 을 여기로 직접 보내 매장에 가지도 않고
  // 회사 예산으로 결제를 성립시킬 수 있다(/pos 와 /meal 은 같은 앱이라 네트워크로 가둘 수 없다).
  if (!isEnrolledTerminal(request)) return terminalUnauthorizedResponse();

  const config = terminalConfig();
  if (!config.ok) return notConfiguredResponse(config.missing);

  try {
    const payload = await readJsonBody(request);
    const body = {
      token: requireString(payload.token, "QR 코드", MAX_QR_TOKEN_LENGTH),
      amountMinor: requireAmountMinor(payload.amountMinor),
      posTxnId: requireString(payload.posTxnId, "거래 번호", MAX_POS_TXN_ID_LENGTH),
      /**
       * 손님이 받은 메뉴(선택). 한 끼니에 코너가 여럿일 때 **단말만이 아는 정보**라 서버가 추측하지
       * 않는다. 형식만 통과시키고 판정은 서버가 한다 — 그 끼니의 메뉴가 아니면 결제는 그대로 승인되고
       * 응답 `menuName` 이 null 로 와서 화면이 "메뉴 미기록"을 표시한다(결제를 메타데이터로 막지 않는다).
       */
      menuId: typeof payload.menuId === "string" && payload.menuId.length > 0 ? payload.menuId : undefined,
    };

    const result = await callMerchantApi(config.config, "/api/merchant/redeem", body);
    return Response.json(result.body, {
      status: result.status,
      headers: { "Cache-Control": "no-store" },
    });
  } catch (cause) {
    return terminalErrorResponse(cause);
  }
}
