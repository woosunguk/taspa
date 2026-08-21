import { test } from '@playwright/test';
import { signup, sql, WEB } from './lib';
import { randomUUID } from 'crypto';
import fs from 'fs';

const S = JSON.parse(fs.readFileSync('audit-merchant/state.json', 'utf-8'));
const TERMINAL_KEY = 'fSBUMjOL7/zjY35i1WGRaTHnwdiZJatRW7n/gAnAwpQ=';

test('환불 두 번 누르기(신규 손님 — 쿨다운 회피)', async ({ page, request, browser }) => {
  test.setTimeout(200_000);
  const stamp = Date.now();
  const cust = `mx-c2-${stamp}@example.com`;
  await signup(page, request, cust);
  const userId = sql(`SELECT id FROM users WHERE email='${cust}'`);
  const orgId = randomUUID();
  sql(`INSERT INTO organizations (id,name,slug,status,timezone) VALUES ('${orgId}','POS감사조직2','pos-audit2-${stamp}','ACTIVE','UTC')`);
  sql(`INSERT INTO org_memberships (id,org_id,user_id,role,status) VALUES (gen_random_uuid(),'${orgId}','${userId}','MEMBER','ACTIVE')`);
  sql(`INSERT INTO meal_policies (org_id,per_meal_limit_minor,daily_meal_count,monthly_cap_minor,
        breakfast_start,breakfast_end,lunch_start,lunch_end,dinner_start,dinner_end)
       VALUES ('${orgId}',12000,9,500000,'00:00:00','07:59:59.999','08:00:00','15:59:59.999','16:00:00','23:59:59.999')`);

  const t = (await (await page.request.get(`${WEB}/api/csrf`)).json()).token;
  const qr = await page.request.post(`${WEB}/api/meal/qr`, {
    headers: { 'X-CSRF-TOKEN': t, 'Content-Type': 'application/json' },
    data: { orgId },
  });
  const token = (await qr.json()).token;

  const posCtx = await browser.newContext({ locale: 'ko-KR', viewport: { width: 430, height: 900 } });
  const pos = await posCtx.newPage();
  await pos.goto(`${WEB}/pos`);
  await pos.waitForLoadState('networkidle');
  await pos.locator('#pos-enroll-key').fill(TERMINAL_KEY);
  await pos.getByRole('button', { name: '단말 등록' }).click();
  await pos.waitForTimeout(2500);

  const manual = pos.getByRole('button', { name: /코드 직접 입력/ });
  if (await manual.count()) await manual.first().click();
  await pos.locator('#pos-manual-token').fill(token);
  await pos.getByRole('button', { name: '코드 확인' }).click();
  await pos.waitForTimeout(600);
  for (const ch of '10000') await pos.getByRole('button', { name: ch, exact: true }).click();
  await pos.getByRole('button', { name: /승인$/ }).click();
  await pos.waitForTimeout(3000);
  const authId = (await pos.locator('dd').last().innerText()).trim();
  console.log('승인:', authId);

  for (const pass of [1, 2]) {
    await pos.getByRole('button', { name: '일부 금액 환불' }).click();
    await pos.waitForTimeout(400);
    await pos.locator('#refund-amount').fill('3000');
    await pos.getByRole('button', { name: /환불$/ }).click();
    await pos.waitForTimeout(3500);
    const lines = (await pos.locator('main').innerText()).split('\n').filter(Boolean);
    console.log(`\n### ${pass}회차 직후 화면: ${JSON.stringify(lines.slice(2, 9))}`);
    console.log(
      `   DB → ${sql(`SELECT 'amount='||amount_minor||' refunded='||refunded_minor FROM meal_transactions WHERE auth_id='${authId}'`)} / 환불행 ${sql(`SELECT count(*) FROM meal_refunds r JOIN meal_transactions t ON t.id=r.transaction_id WHERE t.auth_id='${authId}'`)}건`,
    );
  }
  await pos.screenshot({ path: 'audit-merchant/shots/09-double-refund.png', fullPage: true });
  await posCtx.close();
});
