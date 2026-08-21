import { describe, expect, it } from "vitest";
import { sameSignature } from "./useApi";

/**
 * `useApi` 는 "요청을 다시 보낼 조건"과 "화면을 loading 으로 되돌릴 조건"을 **따로** 판정한다 —
 * 전자는 React 의 effect 의존성 비교, 후자는 이 함수다. 둘이 갈리면 증상이 고약하다:
 * 느슨하면 요청은 나가는데 화면은 이전 조직의 숫자를 그대로 보여주고(돈 화면에서 특히 위험),
 * 엄격하면 매 렌더가 상태를 초기화해 무한 렌더가 된다. 그래서 React 와 **같은 규칙**(얕은
 * `Object.is` 비교)임을 고정한다.
 */
describe("sameSignature", () => {
  it("길이와 각 원소가 같으면 같다고 본다", () => {
    expect(sameSignature(["/api/orgs/a/members", 0, "org-a"], ["/api/orgs/a/members", 0, "org-a"])).toBe(
      true,
    );
  });

  it("원소 하나만 달라도 다르다 — 조직을 바꾸면 다시 조회해야 한다", () => {
    expect(sameSignature(["/p", 0, "org-a"], ["/p", 0, "org-b"])).toBe(false);
  });

  it("reload 로 nonce 만 올라가도 다르다 — 같은 경로 재조회가 막히면 '다시 시도'가 죽는다", () => {
    expect(sameSignature(["/p", 0], ["/p", 1])).toBe(false);
  });

  it("길이가 다르면 다르다", () => {
    expect(sameSignature(["/p", 0], ["/p", 0, "extra"])).toBe(false);
  });

  it("null path 와 실제 경로를 구분한다 — 선행 조건이 생긴 순간 loading 으로 돌아가야 한다", () => {
    expect(sameSignature([null, 0], ["/p", 0])).toBe(false);
  });

  /**
   * ★`Object.is` 라서 **참조 비교**다. 매 렌더 새로 만든 객체·배열을 deps 로 넘기면 신호가 영원히
   * 달라져 렌더마다 상태를 초기화한다(= 무한 렌더). 호출부는 원시값을 넘겨야 한다는 계약을 못박는다.
   */
  it("내용이 같아도 참조가 다른 객체는 다르다 (deps 에 원시값만 넘기라는 계약)", () => {
    expect(sameSignature(["/p", { a: 1 }], ["/p", { a: 1 }])).toBe(false);
    const shared = { a: 1 };
    expect(sameSignature(["/p", shared], ["/p", shared])).toBe(true);
  });

  it("NaN 은 자기 자신과 같다고 본다 (=== 가 아니라 Object.is)", () => {
    expect(sameSignature([NaN], [NaN])).toBe(true);
  });
});
