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
   * KPI 숫자(`Stat` 의 기본 tone). 값은 브랜드 초록이고 배경은 **파인 면**(surface-sunken)이다 —
   * 두 색이 모두 초록 계열이라 눈으로는 "잘 어울린다"로 보이지만 대비가 무너지면 숫자가 흐려진다.
   * 대시보드에서 가장 크게 읽혀야 하는 글자라 여기서 고정한다.
   */
  it("KPI 숫자 — 브랜드 초록이 파인 면 위에서 AA 를 넘는다", () => {
    const fg = palette("taspa-brand");
    const bg = palette("taspa-sunken");
    expect(contrast(fg.light, bg.light)).toBeGreaterThanOrEqual(AA);
    expect(contrast(fg.dark, bg.dark)).toBeGreaterThanOrEqual(AA);
  });

  /**
   * ★대비의 **상한**. 하한만 보던 동안 다크의 브랜드색이 lime-bright(#6ee86a, 휘도 0.62)였고 어두운
   * 카드 위에서 11:1 이었다 — AA 는 통과했지만 큰 KPI 숫자와 차트 막대가 전부 그 색이라 화면이
   * 눈부셨다. "대비가 높을수록 좋다"는 텍스트 가독성 이야기이고, 어두운 배경에서 고휘도 채도색을
   * 넓은 면적에 쓰는 것은 다른 문제다(잔상·눈피로). 그래서 상한을 함께 고정한다.
   */
  it("다크 브랜드색은 필요 이상으로 밝지 않다(눈부심 상한)", () => {
    const brand = palette("taspa-brand");
    const sunken = palette("taspa-sunken");
    const ratio = contrast(brand.dark, sunken.dark);
    expect(ratio).toBeGreaterThanOrEqual(AA);
    expect(ratio, "다크 브랜드가 너무 밝다 — 넓은 면적에 쓰면 눈부시다").toBeLessThanOrEqual(9);
  });

  /**
   * 활성 네비게이션 항목(`bg-accent text-accent-foreground`). 14px semibold 라 large text 완화를 받지 못한다.
   * `--accent-foreground` 는 브랜드 초록과 분리돼 있어야 라이트에서 AA 를 넘는다.
   */
  it("활성 메뉴 — accent 전경이 soft 배경 위에서 AA 를 넘는다", () => {
    const bg = palette("taspa-brand-soft");
    const fg = CSS.match(/--accent-foreground:\s*light-dark\(\s*(#[0-9a-f]{3,8})\s*,/i);
    expect(fg, "--accent-foreground 가 light-dark() 로 분리돼 있어야 한다").not.toBeNull();
    expect(contrast(fg![1], bg.light)).toBeGreaterThanOrEqual(AA);

    // 다크는 브랜드 초록을 그대로 쓴다 — 그 조합도 함께 확인한다.
    const brand = palette("taspa-brand");
    expect(contrast(brand.dark, bg.dark)).toBeGreaterThanOrEqual(AA);
  });
});
