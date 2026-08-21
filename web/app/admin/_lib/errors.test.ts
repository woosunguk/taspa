import { describe, expect, it } from "vitest";
import { ApiError } from "@/lib/api";
import { adminErrorText } from "./errors";

/**
 * ★이 테스트가 고정하는 것은 "**서버가 준 사유를 지우지 않는다**"이다.
 *
 * 이전 구현은 코드→문구 표 하나였고, `VALIDATION_ERROR` 를 무조건 "입력값을 확인하세요."로 덮었다.
 * 그래서 서버가 정확히 무엇이 잘못됐는지 알려 준 경우에도 관리자는 그 문장만 봤고, 어느 값이 문제인지
 * 알 수 없어 같은 값을 다시 넣었다.
 *
 * ★그 뒤 "영어 기본 상수와 같으면 우리 문구로 대신한다"는 갈래를 넣었는데 **그것도 틀렸다**:
 * 서버는 응답을 만들 때 이미 로케일로 해석하므로 영어 상수가 프런트에 도착하는 일이 없고,
 * 그 갈래에 넣은 안내는 한 번도 렌더되지 않았다(죽은 코드). 아래 마지막 두 테스트가 그 교훈을 고정한다.
 */
describe("adminErrorText", () => {
  it("서버가 구체적 사유를 주면 그대로 보여준다", () => {
    const text = adminErrorText(
      new ApiError(400, "VALIDATION_ERROR", "이미 이 매장의 담당자로 등록된 사용자입니다"),
    );
    expect(text).toBe("이미 이 매장의 담당자로 등록된 사용자입니다");
  });

  it("서버가 일반 안내를 줘도 덮지 않는다 — 우리가 지어낼 이유가 없다", () => {
    const serverGeneric = "입력값이 올바르지 않습니다. 다시 확인해 주세요.";
    expect(adminErrorText(new ApiError(400, "VALIDATION_ERROR", serverGeneric))).toBe(serverGeneric);
  });

  it("메시지가 비어 있어도 빈 화면이 되지 않는다", () => {
    expect(adminErrorText(new ApiError(404, "NOT_FOUND", ""))).toContain("처리하지 못했습니다");
  });

  it("운영 안내가 서버 문구보다 나은 코드는 우리 문구가 이긴다", () => {
    const text = adminErrorText(new ApiError(409, "IAM_LOCKOUT", "lockout"));
    expect(text).toContain("되돌릴 방법이 없어집니다");
  });

  /**
   * ★관리 콘솔의 403 은 **언제나** IAM 거부다 — `/admin/**` 은 체인에서 이미 `hasRole("ADMIN")` 을
   * 통과했으므로 서버의 "관리자 계정인지 확인하세요"가 이 자리에서는 사실일 수 없다.
   * 서버가 **로케일 문구를 보내는데도** 우리 안내가 이겨야 한다는 것이 요점이다(그러지 못해 죽은
   * 코드였던 적이 있다).
   */
  it("관리 콘솔의 FORBIDDEN 은 서버 문구를 받아도 IAM 안내로 바뀐다", () => {
    const serverLocalized = "이 작업을 수행할 권한이 없습니다. 필요한 경우 관리자에게 문의해 주세요.";
    const text = adminErrorText(new ApiError(403, "FORBIDDEN", serverLocalized));
    expect(text).toContain("IAM 정책");
    expect(text).not.toBe(serverLocalized);
  });

  it("모르는 코드는 서버 문구를 통과시킨다", () => {
    expect(adminErrorText(new ApiError(409, "SOME_NEW_CODE", "새로운 사유"))).toBe("새로운 사유");
  });
});
