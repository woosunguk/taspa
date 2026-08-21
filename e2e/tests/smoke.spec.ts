import { test, expect } from '@playwright/test';

test.describe('smoke', () => {
  test('actuator health 엔드포인트가 200을 반환한다', async ({ request }) => {
    const response = await request.get('/actuator/health');
    expect(response.status()).toBe(200);
  });
});
