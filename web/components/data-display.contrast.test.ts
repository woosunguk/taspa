import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * 의미색 조합의 **대비를 고정**한다.
 *
 * ★이 테스트가 없던 동안, 라이트의 success/warning 이 자기 soft 배경 위에서 3.70 / 4.34:1 로 AA 미달이었다.
 * 다크는 7.69 / 10.95:1 로 넉넉해서 **라이트에서만** 문장이 배경에 녹았고, 캡처 120장·tsc·eslint 어느
 * 게이트도 대비를 보지 않아 전부 초록불인 채로 남아 있었다. 눈으로 훑어서는 "좀 연하네" 정도로만 보인다.
 *
 * 값의 출처는 `globals.css` 를 **직접 파싱**한다 — 여기에 색을 복사해 두면 팔레트를 바꿔도 테스트는
 * 옛 값으로 계속 통과한다(그러면 고정하는 것이 아무것도 없다).
 */

const CSS = readFileSync(join(__dirname, "..", "app", "globals.css"), "utf-8");

/** `--taspa-success: light-dark(#137333, #81c995);` → { light, dark } */
function palette(token: string): { light: string; dark: string } {
  const match = CSS.match(
    new RegExp(`--${token}:\\s*light-dark\\(\\s*(#[0-9a-f]{3,8})\\s*,\\s*(#[0-9a-f]{3,8})\\s*\\)`, "i"),
  );
  if (!match) throw new Error(`globals.css 에서 --${token} 의 light-dark() 값을 찾지 못했습니다`);
  return { light: match[1], dark: match[2] };
}

function channel(value: number): number {
  const c = value / 255;
  return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

function luminance(hex: string): number {
  const h = hex.replace("#", "");
  const full = h.length === 3 ? [...h].map((c) => c + c).join("") : h;
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(full.slice(i, i + 2), 16));
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

function contrast(fg: string, bg: string): number {
  const [a, b] = [luminance(fg), luminance(bg)].sort((x, y) => y - x);
  return (a + 0.05) / (b + 0.05);
}

/** WCAG AA 본문 기준. 이 조합들은 전부 14px 본문(large text 완화 대상이 아니다). */
const AA = 4.5;

describe("의미색 대비 (WCAG AA 4.5:1)", () => {
  const PAIRS: [string, string, string][] = [
    ["성공", "taspa-success", "taspa-success-soft"],
    ["경고", "taspa-warning", "taspa-warning-soft"],
    ["위험", "taspa-danger", "taspa-danger-soft"],
  ];

  it.each(PAIRS)("%s — 라이트/다크 모두 AA 를 넘는다", (_label, fgToken, bgToken) => {
    const fg = palette(fgToken);
    const bg = palette(bgToken);
    expect(contrast(fg.light, bg.light)).toBeGreaterThanOrEqual(AA);
    expect(contrast(fg.dark, bg.dark)).toBeGreaterThanOrEqual(AA);
  });

  /**
   * 활성 네비게이션 항목(`bg-accent text-accent-foreground`). 14px semibold 라 large text 완화를 받지 못한다.
   * `--accent-foreground` 는 브랜드 파랑과 분리돼 있어야 라이트에서 AA 를 넘는다.
   */
  it("활성 메뉴 — accent 전경이 soft 배경 위에서 AA 를 넘는다", () => {
    const bg = palette("taspa-blue-soft");
    const fg = CSS.match(/--accent-foreground:\s*light-dark\(\s*(#[0-9a-f]{3,8})\s*,/i);
    expect(fg, "--accent-foreground 가 light-dark() 로 분리돼 있어야 한다").not.toBeNull();
    expect(contrast(fg![1], bg.light)).toBeGreaterThanOrEqual(AA);

    // 다크는 브랜드 파랑을 그대로 쓴다 — 그 조합도 함께 확인한다.
    const blue = palette("taspa-blue");
    expect(contrast(blue.dark, bg.dark)).toBeGreaterThanOrEqual(AA);
  });
});
