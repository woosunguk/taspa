import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: '.',
  timeout: 180000,
  retries: 0,
  use: { baseURL: 'http://localhost:9100', headless: true, locale: 'ko-KR' },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
});
