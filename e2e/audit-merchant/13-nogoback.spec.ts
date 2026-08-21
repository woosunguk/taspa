import { test } from '@playwright/test';
import { signup, sql, WEB } from './lib';
import { randomUUID } from 'crypto';

const TERMINAL_KEY = 'fSBUMjOL7/zjY35i1WGRaTHnwdiZJatRW7n/gAnAwpQ=';

test('다음 손님 이후 되돌아갈 수 있는가', async ({ page, request, browser }) => {
  test.setTimeout(180_000);
  const stamp = Date.now();
  const cust = `mx-c4-${stamp}@example.com`;
  await signup(page, request, cust);
  const userId = sql(`SELECT id FROM users WHERE email='${cust}'`);
  const orgId = randomUUID();
  sql(`INSERT INTO organizations (id,name,slug,status,timezone) VALUES ('${orgId}','되돌리기감사','undo-${stamp}','ACTIVE','UTC')`);
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
  // 계산원이 0 을 하나 더 눌렀다: 90,000원
  for (const ch of '90000') await pos.getByRole('button', { name: ch, exact: true }).click();
  await pos.getByRole('button', { name: /승인$/ }).click();
  await pos.waitForTimeout(3000);
  const authId = (await pos.locator('dd').last().innerText()).trim();
  console.log('과다 승인:', authId, sql(`SELECT amount_minor||'/'||self_paid_minor FROM meal_transactions WHERE auth_id='${authId}'`));

  // 다음 손님으로 넘어간다
  await pos.getByRole('button', { name: '다음 손님' }).click();
  await pos.waitForTimeout(1000);
  const body = await pos.locator('body').innerText();
  console.log('\n[다음 손님 이후 화면]\n' + body);
  const clickable = await pos.locator('li').evaluateAll((els) =>
    els.map((e: any) => ({ text: e.innerText.replace(/\n/g, ' '), hasButton: !!e.querySelector('button,a') })),
  );
  console.log('\n[최근 승인 항목이 눌리는가]', JSON.stringify(clickable));

  // 새로고침하면?
  await pos.reload();
  await pos.waitForLoadState('networkidle');
  await pos.waitForTimeout(1500);
  console.log('\n[새로고침 후 최근 승인]\n' + (await pos.locator('body').innerText()));
  await pos.screenshot({ path: 'audit-merchant/shots/13-after-reload.png', fullPage: true });
  await posCtx.close();
});
