import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

/**
 * 단위 테스트 설정.
 *
 * ★환경은 반드시 **node** 다. `lib/pos-session.ts` 는 `typeof window !== "undefined"` 이면 즉시
 * throw 하는 서버 전용 모듈이라(등록 키가 클라이언트 번들에 섞이는 것을 직접 막는다), jsdom 에서는
 * import 조차 되지 않는다. 브라우저 API 가 필요한 테스트는 그 파일 안에서 최소 스텁을 심는다 —
 * jsdom 의존성을 들이는 것보다 무엇을 가정하는지가 드러난다.
 *
 * 별칭은 tsconfig 의 `@/*` 와 일치시킨다(플러그인 없이 한 줄로 끝나므로 의존성을 늘리지 않는다).
 */
export default defineConfig({
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./", import.meta.url)),
    },
  },
  test: {
    environment: "node",
    // `components/**` 도 포함한다 — 디자인 시스템의 불변식(대비·클래스 병합)을 여기서 고정한다.
    // 빠져 있던 동안 새로 쓴 테스트가 "No test files found" 로 조용히 실행되지 않았다.
    include: ["lib/**/*.test.ts", "app/**/*.test.ts", "components/**/*.test.ts"],
  },
});
