import { test } from '@playwright/test';
import { signup, sql, WEB } from './lib';
import { randomUUID } from 'crypto';

const TERMINAL_KEY = 'fSBUMjOL7/zjY35i1WGRaTHnwdiZJatRW7n/gAnAwpQ=';

test('환불 버튼을 두 번 누르면', async ({ page, request, browser }) => {
  test.setTimeout(180_000);
  const stamp = Date.now();
  const cust = `mx-c3-${stamp}@example.com`;
  await signup(page, request, cust);
  const userId = sql(`SELECT id FROM users WHERE email='${cust}'`);
  const orgId = randomUUID();
  sql(`INSERT INTO organizations (id,name,slug,status,timezone) VALUES ('${orgId}','POS감사조직3','pos-a3-${stamp}','ACTIVE','UTC')`);
  sql(`INSERT INTO org_memberships (id,org_id,user_id,role,status) VALUES (gen_random_uuid(),'${orgId}','${userId}','MEMBER','ACTIVE')`);
  sql(`INSERT INTO meal_policies (org_id,per_meal_limit_minor,daily_meal_count,monthly_cap_minor,
        breakfast_start,breakfast_end,lunch_start,lunch_end,dinner_start,dinner_end)
       VALUES ('${orgId}',12000,9,500000,'00:00:00','07:59:59.999','08:00:00','15:59:59.999','16:00:00','23:59:59.999')`);
  const t = (await (await page.request.get(`${WEB}/api/csrf`)).json()).token;
  const token = (
    await (
      await page.request.post(`${WEB}/api/meal/qr`, {
        headers: { 'X-CSRF-TOKEN': t, 'Content-Type': 'application/json' },
        data: { orgId },
      })
    ).json()
  ).token;

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

  const state = () =>
    sql(
      `SELECT 'amount='||amount_minor||' refunded='||refunded_minor||' rows='||(SELECT count(*) FROM meal_refunds r WHERE r.transaction_id=t.id) FROM meal_transactions t WHERE auth_id='${authId}'`,
    );
  console.log('승인 직후 :', state());

  // 1회차
  await pos.getByRole('button', { name: '일부 금액 환불' }).click();
  await pos.locator('#refund-amount').fill('3000');
  await pos.getByRole('button', { name: /환불$/ }).click();
  await pos.waitForTimeout(4000);
  console.log('환불 1회차:', state());
  console.log('   화면 머리:', (await pos.locator('div[class*="rounded-2xl"]').first().innerText()).replace(/\n/g, ' | '));

  // 계산원이 "안 됐나?" 하고 한 번 더 — 2회차
  await pos.getByRole('button', { name: '일부 금액 환불' }).click();
  await pos.locator('#refund-amount').fill('3000');
  await pos.getByRole('button', { name: /환불$/ }).click();
  await pos.waitForTimeout(4000);
  console.log('환불 2회차:', state());
  console.log('   화면 머리:', (await pos.locator('div[class*="rounded-2xl"]').first().innerText()).replace(/\n/g, ' | '));
  await pos.screenshot({ path: 'audit-merchant/shots/10-double-refund.png', fullPage: true });
  await posCtx.close();
});
