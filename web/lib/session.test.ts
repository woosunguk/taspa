import { describe, expect, it } from "vitest";
import { ApiError, UnauthenticatedError } from "@/lib/api";
import { classifySessionFailure } from "@/lib/session";

/**
 * 신원 조회 실패의 분류 — 이 한 줄이 "장애를 로그아웃으로 위장"하던 결함의 자리다.
 *
 * 예전 `useSession` 은 catch 를 통째로 anonymous 로 수렴시켰다. `/account` 에 `RequireAuth` 가 붙은 뒤로는
 * 서버가 500 을 내는 동안 세션이 멀쩡한 사용자가 `/login` 으로 튕겼고, 로그인 성공 후 기본 착지가 다시
 * `/account` 라 같은 자리로 돌아왔다. **미인증의 근거는 서버의 401 뿐**임을 여기서 못 박는다.
 *
 * 훅이 아니라 순수 함수를 검증하는 이유: 이 판정에는 React 도 DOM 도 필요 없고, 렌더 트리를 끌어들이면
 * 정작 중요한 분기가 렌더링 잡음에 묻힌다.
 */
describe("classifySessionFailure", () => {
  it("401 계열(UnauthenticatedError)만 익명으로 본다", () => {
    const failure = classifySessionFailure(
      new UnauthenticatedError(401, "UNAUTHENTICATED", "로그인이 필요합니다"),
    );
    expect(failure).toEqual({ status: "anonymous" });
  });

  it("서버 오류(5xx)는 익명이 아니라 error 다 — 로그인으로 튕기면 안 된다", () => {
    const failure = classifySessionFailure(new ApiError(500, "UNKNOWN", "Internal Server Error (500)"));
    expect(failure.status).toBe("error");
  });

  it("네트워크 실패(fetch 예외)도 error 다", () => {
    const failure = classifySessionFailure(new TypeError("Failed to fetch"));
    expect(failure.status).toBe("error");
  });

  it("원인 문구를 지우지 않는다 — 사용자가 스크린샷 하나로 상황을 전달할 수 있어야 한다", () => {
    const failure = classifySessionFailure(new ApiError(502, "UNKNOWN", "요청이 실패했습니다 (502)"));
    expect(failure.status === "error" && failure.message).toContain("요청이 실패했습니다 (502)");
  });

  it("Error 가 아닌 값이 던져져도 빈 문구를 만들지 않는다", () => {
    const failure = classifySessionFailure("무언가 잘못됨");
    expect(failure.status).toBe("error");
    expect(failure.status === "error" && failure.message.length).toBeGreaterThan(0);
  });

  it("대조군: step-up(REAUTH_REQUIRED)은 로그아웃이 아니므로 익명이 아니다", () => {
    // `api` 계층이 REAUTH_REQUIRED 를 UnauthenticatedError 가 아닌 ApiError 로 던지는 계약에 의존한다.
    const failure = classifySessionFailure(new ApiError(401, "REAUTH_REQUIRED", "재인증이 필요합니다"));
    expect(failure.status).toBe("error");
  });
});
