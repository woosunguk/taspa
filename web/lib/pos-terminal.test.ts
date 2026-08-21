import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * POS BFF 의 **오리진 분기(split-brain) 가드**.
 *
 * `next.config.ts` 의 프록시 목적지는 빌드 중 한 번 평가돼 `.next/routes-manifest.json` 에 굳는다.
 * 반면 이 모듈은 요청마다 `process.env.TASPA_ORIGIN` 을 읽는다. 그래서 운영자가 런타임 env 로만
 * 오리진을 덮으면 **로그인은 A 서버에서 되는데 결제 승인은 B 서버로 나가는** 분기가 조용히 생긴다.
 *
 * 결제 경로라 조용한 분기보다 시끄러운 정지가 낫다 — 가드가 첫 import 에서 실패시킨다.
 * 이 테스트가 없으면 그 가드는 "문서에만 있는 약속"으로 되돌아간다(이 저장소에서 실제로 반복된 실패다).
 */
describe("TASPA_ORIGIN 빌드/런타임 분기 가드", () => {
  const original = { ...process.env };

  beforeEach(() => {
    vi.resetModules();
  });

  afterEach(() => {
    process.env = { ...original };
    vi.resetModules();
  });

  it("빌드 시각 오리진과 런타임 오리진이 다르면 모듈 적재가 실패한다", async () => {
    process.env.NEXT_PUBLIC_TASPA_BUILD_ORIGIN = "http://built-in:9100";
    process.env.TASPA_ORIGIN = "http://runtime-override:9100";

    await expect(import("./pos-terminal")).rejects.toThrow(/빌드 시각.*런타임|다시 빌드/);
  });

  it("두 값이 같으면 정상 적재된다", async () => {
    process.env.NEXT_PUBLIC_TASPA_BUILD_ORIGIN = "http://taspa-server:9100";
    process.env.TASPA_ORIGIN = "http://taspa-server:9100";

    await expect(import("./pos-terminal")).resolves.toBeDefined();
  });

  it("빌드 변수가 없으면(개발·dev 서버) 런타임 값을 그대로 쓴다", async () => {
    // dev 는 rewrites 를 매 기동에 다시 평가하므로 굳는 문제가 없다. 가드가 개발을 막으면 안 된다.
    delete process.env.NEXT_PUBLIC_TASPA_BUILD_ORIGIN;
    process.env.TASPA_ORIGIN = "http://localhost:9100";

    await expect(import("./pos-terminal")).resolves.toBeDefined();
  });
});
