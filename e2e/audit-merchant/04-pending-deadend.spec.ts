import { test } from '@playwright/test';
import { signup, login, sql, dump, WEB, mailsFor } from './lib';
import fs from 'fs';

const S = JSON.parse(fs.readFileSync('audit-merchant/state.json', 'utf-8'));

test('PENDING 매장만 가진 담당자', async ({ page, request }) => {
  page.on('console', (m) => {
    if (m.type() === 'error') console.log(`[console.error] ${m.text()}`);
  });
  const solo = `mx-solo-${Date.now()}@example.com`;
  await signup(page, request, solo);

  // 플랫폼 관리자가 PENDING 매장 담당자로만 지정
  await login(page, S.ADMIN_EMAIL);
  const token = (await (await page.request.get(`${WEB}/api/csrf`)).json()).token;
  const res = await page.request.post(`${WEB}/api/admin/merchants/${S.MERCHANT_ID}/members`, {
    headers: { 'X-CSRF-TOKEN': token, 'Content-Type': 'application/json' },
    data: { email: solo },
  });
  console.log('>>> 담당자 지정:', res.status(), await res.text());

  // 그 사람으로 로그인
  await login(page, solo);
  await page.waitForTimeout(1500);
  console.log('\n[헤더 — "매장 관리" 링크가 있는가]\n' + (await page.locator('header').innerText()));
  await page.screenshot({ path: 'audit-merchant/shots/04-solo-header.png', fullPage: true });

  await page.goto(`${WEB}/merchant`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);
  await dump(page, '/merchant (PENDING 매장만 보유)');
  await page.screenshot({ path: 'audit-merchant/shots/04-solo-merchant.png', fullPage: true });

  console.log('\n[API 응답] /api/merchant-console/mine =', await (await page.request.get(`${WEB}/api/merchant-console/mine`)).text());

  await page.goto(`${WEB}/merchant/${S.MERCHANT_ID}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);
  await dump(page, 'URL 직접 입력 시');
  console.log('\n[사장 메일함]', (await mailsFor(request, solo)).map((m: any) => m.Subject).join(' | '));

  // ── 이제 ACTIVE 로 바꾸면 어떻게 되는가 ──
  sql(`UPDATE merchants SET status='ACTIVE' WHERE id='${S.MERCHANT_ID}'`);
  await page.goto(`${WEB}/merchant`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);
  await dump(page, 'ACTIVE 로 바꾼 뒤 /merchant');
  sql(`UPDATE merchants SET status='PENDING' WHERE id='${S.MERCHANT_ID}'`);
});
