import type { NextRequest } from "next/server";
import {
  callMerchantApi,
  notConfiguredResponse,
  terminalConfig,
  terminalErrorResponse,
  terminalUnauthorizedResponse,
} from "@/lib/pos-terminal";
import { isEnrolledTerminal } from "@/lib/pos-session";

/**
 * 오늘 이 시각 끼니의 식단 — 계산원이 배식 코너를 고르기 위한 목록.
 *
 * 단말 인증이 **가장 먼저**다(다른 POS BFF 와 같은 규약). 이 목록 자체는 민감하지 않지만, 게이트를
 * 경로마다 다르게 두면 어느 경로가 열려 있는지 아무도 추적하지 못한다.
 *
 * 조회 실패는 화면을 깨뜨리지 않는다 — 메뉴를 못 읽어도 승인은 되어야 하고(메뉴 없이 승인하면 그 끼니
 * 메뉴가 하나뿐인 경우 서버가 자동 귀속한다), 계산대는 결제가 되는 것이 최우선이다.
 */
export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(request: NextRequest): Promise<Response> {
  if (!isEnrolledTerminal(request)) return terminalUnauthorizedResponse();

  const config = terminalConfig();
  if (!config.ok) return notConfiguredResponse(config.missing);

  try {
    const result = await callMerchantApi(config.config, "/api/merchant/menus", null, "GET");
    return Response.json(result.body, {
      status: result.status,
      headers: { "Cache-Control": "no-store" },
    });
  } catch (cause) {
    return terminalErrorResponse(cause);
  }
}
