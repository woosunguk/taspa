import { test } from '@playwright/test';
import { login, sql, WEB } from './lib';

/**
 * 반증 프로브 2: "나중에 정산 명세에서 금액이 안 맞을 때 원인을 되짚을 수 없다" 를 검증한다.
 * 두 번 환불된 거래가 가맹 관리자 화면에서 어떻게 보이는가.
 */
test('두 번 환불된 거래가 가맹 식수 로그에 어떻게 보이는가', async ({ page }) => {
  test.setTimeout(120_000);
  const merchantId = '6dc75701-36b5-4a3f-8ee5-5b6d2484685f';
  const owner = sql(
    `SELECT u.email FROM merchant_members mm JOIN users u ON u.id=mm.user_id WHERE mm.merchant_id='${merchantId}' LIMIT 1`,
  );
  console.log('가맹 관리자:', owner);
  await login(page, owner);
  await page.goto(`${WEB}/merchant/${merchantId}/transactions`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2500);

  const body = await page.locator('body').innerText();
  console.log('\n===== /merchant/{id}/transactions =====');
  // 두 번 환불된 거래(6,000원 환불 / 원금 10,000원)를 담은 줄만 추린다.
  for (const line of body.split('\n')) {
    if (/6,000|원금|회$|2회|환불/.test(line)) console.log('  ', line);
  }
  await page.screenshot({ path: 'audit-merchant/shots/rf-merchant-log.png', fullPage: true });
});
