import { test, expect } from '@playwright/test';
import { WEB, MAILPIT, PASSWORD, sql, signup, login, csrf, mailsFor, dump } from './lib';
import { request as pwRequest } from '@playwright/test';

const STAMP = Date.now();
const ADMIN = `rn-plat-${STAMP}@example.com`;
const OWNER = `rn-owner-${STAMP}@example.com`;
const MERCHANT_NAME = `반증분식 ${STAMP}`;

test('가맹 담당자 지정 통지 경로 반증 시도', async ({ page }) => {
  const api = await pwRequest.newContext();

  // 1) 플랫폼 관리자 + 사장 계정 생성
  await signup(page, api, ADMIN);
  sql(`UPDATE users SET role='ADMIN' WHERE email='${ADMIN}'`);
  await signup(page, api, OWNER);

  // 사장 계정에 지금까지 온 메일 목록 (기준선)
  const before = await mailsFor(api, OWNER);
  console.log('### BEFORE assign — owner mails:', JSON.stringify(before.map((m: any) => m.Subject)));

  // 2) 관리자로 로그인 → 가맹 생성 → 담당자 지정
  await login(page, ADMIN);
  const token = await csrf(page);
  const created = await page.request.post(`${WEB}/api/admin/merchants`, {
    headers: { 'X-CSRF-TOKEN': token, 'Content-Type': 'application/json' },
    data: { name: MERCHANT_NAME, category: 'RESTAURANT', status: 'ACTIVE' },
  });
  console.log('create merchant status', created.status());
  const merchant = await created.json();
  console.log('merchant', JSON.stringify(merchant));

  const assigned = await page.request.post(`${WEB}/api/admin/merchants/${merchant.id}/members`, {
    headers: { 'X-CSRF-TOKEN': token, 'Content-Type': 'application/json' },
    data: { email: OWNER },
  });
  console.log('assign status', assigned.status(), await assigned.text());

  // 3) 지정 후 메일 (10초 폴링 — 비동기 발송 가능성 대비)
  for (let i = 0; i < 20; i++) {
    const now = await mailsFor(api, OWNER);
    if (now.length > before.length) {
      console.log('### NEW MAIL after assign:', JSON.stringify(now.map((m: any) => m.Subject)));
      break;
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  const after = await mailsFor(api, OWNER);
  console.log('### AFTER assign — owner mails:', JSON.stringify(after.map((m: any) => m.Subject)));
  console.log('### mail count before/after:', before.length, after.length);

  // 전체 mailpit 에서 merchant 이름을 언급한 메일이 있는가 (수신자 무관)
  const all = await (await api.get(`${MAILPIT}/search?query=${encodeURIComponent(MERCHANT_NAME)}`)).json();
  console.log('### mails mentioning merchant name:', JSON.stringify((all.messages ?? []).map((m: any) => ({ s: m.Subject, to: m.To }))));

  // 4) 사장으로 로그인 — 홈/계정/헤더에서 사실을 알 수 있는가
  await login(page, OWNER);
  await page.goto(`${WEB}/`);
  await page.waitForLoadState('networkidle');
  const home = await dump(page, 'OWNER HOME /');
  const headerText = await page.locator('header').innerText();
  console.log('### HEADER:', JSON.stringify(headerText));

  await page.goto(`${WEB}/account`);
  await page.waitForLoadState('networkidle');
  const acct = await dump(page, 'OWNER /account');

  console.log('### home mentions 매장?', /매장|가맹/.test(home));
  console.log('### account mentions 매장?', /매장|가맹/.test(acct));
  console.log('### header mentions 매장?', /매장|가맹/.test(headerText));

  // 5) 해제 후 통지가 있는가
  await login(page, ADMIN);
  const t2 = await csrf(page);
  const uid = sql(`SELECT id FROM users WHERE email='${OWNER}'`);
  const removed = await page.request.delete(`${WEB}/api/admin/merchants/${merchant.id}/members/${uid}`, {
    headers: { 'X-CSRF-TOKEN': t2 },
  });
  console.log('remove status', removed.status());
  await new Promise((r) => setTimeout(r, 3000));
  const afterRemove = await mailsFor(api, OWNER);
  console.log('### AFTER remove — owner mails:', JSON.stringify(afterRemove.map((m: any) => m.Subject)));

  expect(true).toBe(true);
});
