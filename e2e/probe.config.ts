import { defineConfig } from '@playwright/test';

// 조사 전용(제품 코드·기존 스펙 무변경). e2e/probe/ 아래 프로브만 돌린다.
export default defineConfig({
  testDir: './probe',
  timeout: 180000,
  retries: 0,
  use: {
    baseURL: 'http://localhost:9100',
    headless: true,
    locale: 'ko-KR',
  },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
});
