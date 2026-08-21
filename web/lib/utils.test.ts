import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { CUSTOM_TEXT_SIZES, cn } from "./utils";

/**
 * `cn()` 이 **커스텀 타이포 스케일을 지우지 않는지** 고정한다.
 *
 * ★이 테스트가 없던 동안, `Stat` 의 값 크기가 `cn()` 안에서 조용히 삭제돼 좁은 화면에서 본문 크기로
 * 렌더됐다. tailwind-merge 는 모르는 `text-*` 이름을 **글자색**으로 추측하고, 같은 그룹의 뒤 클래스가
 * 앞을 지우기 때문이다. 화면에는 "원래 저 크기인가 보다"로 보여서 눈으로는 잡히지 않는다.
 */
describe("cn — 커스텀 타이포 스케일 보존", () => {
  it("크기와 색을 함께 넘겨도 크기가 살아남는다", () => {
    // Stat 의 값이 실제로 만드는 조합.
    const result = cn("tabular text-metric-sm @lg:text-metric", "text-foreground");
    expect(result).toContain("text-metric-sm");
    expect(result).toContain("@lg:text-metric");
    expect(result).toContain("text-foreground");
  });

  it("라벨 크기도 muted 색과 공존한다", () => {
    expect(cn("text-label", "text-muted-foreground")).toContain("text-label");
  });

  it.each([...CUSTOM_TEXT_SIZES])("text-%s 가 색 클래스에 지워지지 않는다", (size) => {
    expect(cn(`text-${size}`, "text-danger")).toContain(`text-${size}`);
  });

  /** 대조군 — 등록했다고 해서 **같은 축의 충돌 해소가 깨지면** 안 된다(뒤가 이겨야 한다). */
  it("같은 크기 축끼리는 여전히 뒤가 이긴다", () => {
    expect(cn("text-sm", "text-metric")).toBe("text-metric");
    expect(cn("text-metric", "text-sm")).toBe("text-sm");
  });

  /**
   * ★목록이 `globals.css` 의 `--text-*` 토큰과 어긋나면 실패한다.
   *
   * 빠진 토큰은 그 토큰만 조용히 삭제되는 상태로 돌아가고(어느 화면에서 사라지는지는 함께 들어오는
   * 색 클래스에 달려 있어 재현이 들쭉날쭉하다), 반대로 없는 토큰이 목록에 남으면 오해를 부른다.
   */
  it("등록 목록이 globals.css 의 --text-* 토큰과 정확히 일치한다", () => {
    const css = readFileSync(join(__dirname, "..", "app", "globals.css"), "utf-8");
    // `--text-metric: …` 은 잡고 `--text-metric--line-height: …`(수식어)는 제외한다.
    const declared = new Set(
      [...css.matchAll(/^\s*--text-([a-z0-9-]+):/gm)].map((m) => m[1]).filter((name) => !name.includes("--")),
    );
    expect([...declared].sort()).toEqual([...CUSTOM_TEXT_SIZES].sort());
  });
});
