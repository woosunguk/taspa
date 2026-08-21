import { clsx, type ClassValue } from "clsx";
import { extendTailwindMerge } from "tailwind-merge";

/**
 * 우리가 `@theme` 에서 만든 타이포 스케일 이름. `globals.css` 의 `--text-*` 토큰과 **같아야 한다**.
 *
 * 테스트(`lib/utils.test.ts`)가 이 목록과 globals.css 의 대응을 강제한다 — 주석 규약으로 두면
 * 새 토큰을 추가할 때 그 토큰만 조용히 삭제되는 상태로 돌아간다.
 */
export const CUSTOM_TEXT_SIZES = ["display", "metric", "metric-sm", "title", "label"] as const;

/**
 * ★**커스텀 타이포 스케일을 tailwind-merge 에 등록해야 한다.**
 *
 * `twMerge` 는 클래스 이름을 **자기 설정에 있는 그룹**으로만 분류한다. `text-metric-sm`·`text-label`
 * 처럼 우리가 만든 이름은 기본 설정에 없어서, `text-` 접두사만 보고 **글자색 그룹**으로 추측한다.
 * 그러면 같은 그룹의 뒤 클래스가 앞을 지운다:
 *
 *   twMerge("tabular text-metric-sm @lg:text-metric text-foreground")
 *     → "tabular @lg:text-metric text-foreground"   // text-metric-sm 이 사라진다
 *   twMerge("text-label text-muted-foreground")
 *     → "text-muted-foreground"                     // text-label 이 사라진다
 *
 * 실제로 `Stat` 의 값이 이 경로를 타서(`cn("tabular text-metric-sm @lg:text-metric", tone…)` + 색)
 * **좁은 화면에서 크기 지정이 통째로 날아가 본문 크기로 렌더**됐다. 대시보드 숫자를 키우려고 만든
 * 스케일이 정작 모바일에서만 적용되지 않는, 눈으로는 "원래 저런가 보다" 하고 넘어가기 쉬운 형태다.
 */
const twMerge = extendTailwindMerge({
  extend: {
    classGroups: {
      "font-size": [{ text: [...CUSTOM_TEXT_SIZES] }],
    },
  },
});

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
