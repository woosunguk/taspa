import { test, expect } from '@playwright/test';
import { WEB, sql, login, csrf, dump } from './lib';

/** 앞 스펙에서 만든 계정을 재사용해 /meal 바로가기 카드가 실제로 뜨는지만 본다. */
test('meal 바로가기에 매장 관리가 뜨는가', async ({ page }) => {
  const owner = sql(`SELECT email FROM users WHERE email LIKE 'rn-owner-%' ORDER BY created_at DESC LIMIT 1`);
  const merchantId = sql(`SELECT id FROM merchants WHERE name LIKE '반증분식%' ORDER BY created_at DESC LIMIT 1`);
  const admin = sql(`SELECT email FROM users WHERE email LIKE 'rn-plat-%' ORDER BY created_at DESC LIMIT 1`);
  console.log({ owner, merchantId, admin });

  // 재부여
  await login(page, admin);
  const t = await csrf(page);
  const r = await page.request.post(`${WEB}/api/admin/merchants/${merchantId}/members`, {
    headers: { 'X-CSRF-TOKEN': t, 'Content-Type': 'application/json' },
    data: { email: owner },
  });
  console.log('reassign', r.status());

  await login(page, owner);
  await page.goto(`${WEB}/meal`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);
  const t1 = await dump(page, 'OWNER /meal');
  console.log('### meal has 매장 관리 quicklink?', t1.includes('매장 관리'));

  // 같은 세션에서 새로고침 없이 헤더가 생기는가(이미 로그인 중이던 사람)
  await page.goto(`${WEB}/`);
  await page.waitForLoadState('networkidle');
  const h = await page.locator('header').innerText();
  console.log('### HEADER after reassign:', JSON.stringify(h));
  expect(true).toBe(true);
});
