import type { NextRequest } from "next/server";
import { callMerchantApi, terminalConfig } from "@/lib/pos-terminal";
import {
  enrolledTerminal,
  enrollmentConfigured,
  enrollmentProblem,
  renewTerminalCookie,
} from "@/lib/pos-session";

/**
 * 단말 상태 확인. **화면이 조용히 실패하지 않기 위한 엔드포인트다** — 자격증명이 없거나 이 기기가
 * 등록되지 않았으면, 스캔·승인 버튼을 눌러 본 뒤에야 알게 되는 게 아니라 화면을 열자마자 알려주고
 * 다음에 뭘 하면 되는지(등록 키 입력 / 관리자에게 환경변수 요청)를 제시한다.
 *
 * 노출하는 것은 **설정 여부와 누락된 환경변수 이름뿐**이다. clientId·등록 키·쿠키 값은 내보내지 않는다 —
 * 계산대 화면은 그 값으로 할 일이 없고, 없는 정보는 새어 나갈 수도 없다.
 */
export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(request: NextRequest): Promise<Response> {
  const result = terminalConfig();
  const session = enrolledTerminal(request);

  /*
   * ★**이 단말이 어느 매장인지**를 함께 내려보낸다.
   *
   * 그전까지 POS 화면 어디에도 매장 이름이 없었다 — 결속은 환경변수(POS_CLIENT_ID) 안에만 있어서
   * 계산원은 눈앞의 화면이 자기 가게 것인지 확인할 방법이 없었다. 매장이 여럿인 사업자나 단말을
   * 옮겨 설치하는 현장에서 이건 곧 "옆 가게 이름으로 승인"이고, 발견은 월말 정산 때다.
   *
   * 조회 실패는 **상태 응답 전체를 깨뜨리지 않는다**(merchant=null). 이름을 못 읽는 것보다 화면이
   * 안 뜨는 쪽이 훨씬 나쁘다 — 계산대는 결제가 되는 것이 최우선이다.
   */
  let merchant: unknown = null;
  if (result.ok && session !== null) {
    try {
      const identity = await callMerchantApi(result.config, "/api/merchant/me", null, "GET");
      if (identity.status === 200) merchant = identity.body;
    } catch {
      merchant = null;
    }
  }

  const response = Response.json(
    {
      configured: result.ok,
      missing: result.ok ? [] : result.missing,
      /** 등록 기능 자체가 설정됐는가(POS_TERMINAL_KEY). 없으면 어떤 기기도 단말이 될 수 없다. */
      enrollmentAvailable: enrollmentConfigured(),
      /**
       * 등록이 막힌 사유("missing" | "weak-key"). **약한 키는 미설정과 증상이 같아서**, 이 구분이
       * 없으면 이미 키를 넣어 둔 운영자가 "환경변수를 설정하세요" 안내를 보고 원인을 못 찾는다.
       */
      enrollmentProblem: enrollmentProblem(),
      /** 이 브라우저가 등록된 단말인가. */
      enrolled: session !== null,
      /** 이 단말이 결속된 가맹점({merchantId, name, category, timezone}) — 조회 실패 시 null. */
      merchant,
    },
    { headers: { "Cache-Control": "no-store" } },
  );

  // 단말 쿠키의 슬라이딩 갱신 지점. 계산대는 화면을 열 때마다 이 엔드포인트를 부르므로, 영업 중인
  // 단말은 유휴 만료로 죽지 않고 서랍에 들어간 기기만 죽는다(수명 상한은 쿠키의 절대 만료가 잡는다).
  const renewal = session ? renewTerminalCookie(session) : null;
  if (renewal) response.headers.append("Set-Cookie", renewal);
  return response;
}
