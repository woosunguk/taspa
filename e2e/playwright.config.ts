import { defineConfig } from '@playwright/test';

// taspa IdP 서버는 9100 포트에서 동작한다 (application.yml: server.port=9100).
export default defineConfig({
  testDir: './tests',
  timeout: 30000,
  retries: 0,
  use: {
    baseURL: 'http://localhost:9100',
    headless: true,
    screenshot: 'only-on-failure',
    // i18n(Stage 6): 기본 로케일은 ko 이나, 리졸버가 Accept-Language 를 존중하므로 브라우저 로케일을
    // ko-KR 로 고정한다. 그래야 실행 호스트/CI 의 시스템 로케일과 무관하게 한국어 UI 가 렌더되어
    // 기존 텍스트 단언(활성 세션·본인임을 확인 등)이 결정적으로 통과한다.
    locale: 'ko-KR',
  },
  projects: [
    { name: 'chromium', use: { browserName: 'chromium' } },
  ],
});
