import { test, expect } from '@playwright/test';

// Stage 5 Device Authorization Grant 사용자 화면(/activate)의 경량 e2e.
// 전체 왕복(device_authorization → 토큰 교환)은 통합 테스트
// (DeviceAuthorizationGrantIntegrationTest)에서 검증한다. 여기서는 UI 게이트 불변식만 확인한다.
test.describe('device authorization grant', () => {
  test('미인증 /activate 접근은 로그인 페이지로 유도된다', async ({ page }) => {
    await page.goto('/activate');
    await expect(page).toHaveURL(/\/login/);
  });

  test('미인증 /activate?user_code=.. 도 로그인으로 유도된다(코드 프리필 진입)', async ({ page }) => {
    await page.goto('/activate?user_code=WDJB-MJHT');
    await expect(page).toHaveURL(/\/login/);
  });
});
