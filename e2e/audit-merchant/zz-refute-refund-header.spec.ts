import { test } from '@playwright/test';
import { signup, sql, WEB } from './lib';
import { randomUUID } from 'crypto';

const TERMINAL_KEY = 'fSBUMjOL7/zjY35i1WGRaTHnwdiZJatRW7n/gAnAwpQ=';

/**
 * 반증 프로브: "환불 성공 화면이 승인 화면과 구별 불가하다"는 주장을 검증한다.
 * 원 프로브(10-double3)는 첫 번째 rounded-2xl div(=머리)만 덤프했다 — 그 아래 dl 은 보지 않았다.
 * 여기서는 패널 **전체**를 덤프한다.
 */
test('환불 후 화면 전체가 승인 화면과 같은가', async ({ page, request, browser }) => {
  test.setTimeout(240_000);
  const stamp = Date.now();
  const cust = `rf-${stamp}@example.com`;
  await signup(page, request, cust);
  const userId = sql(`SELECT id FROM users WHERE email='${cust}'`);
  const orgId = randomUUID();
  sql(
    `INSERT INTO organizations (id,name,slug,status,timezone) VALUES ('${orgId}','환불반증조직','rf-${stamp}','ACTIVE','UTC')`,
  );
  sql(
    `INSERT INTO org_memberships (id,org_id,user_id,role,status) VALUES (gen_random_uuid(),'${orgId}','${userId}','MEMBER','ACTIVE')`,
  );
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

  // 패널 전체 = main 안의 텍스트(머리 + dl + 버튼들)
  const panel = async () => (await pos.locator('body').innerText()).replace(/\n+/g, ' | ');
  const authId = sql(
    `SELECT auth_id FROM meal_transactions WHERE user_id='${userId}' ORDER BY approved_at DESC LIMIT 1`,
  );
  const state = () =>
    sql(
      `SELECT 'amount='||amount_minor||' self='||self_paid_minor||' refunded='||refunded_minor||' status='||status||' rows='||(SELECT count(*) FROM meal_refunds r WHERE r.transaction_id=t.id) FROM meal_transactions t WHERE auth_id='${authId}'`,
    );

  console.log('\n===== 승인 직후 =====');
  console.log('DB   :', state());
  console.log('화면 :', await panel());
  await pos.screenshot({ path: 'audit-merchant/shots/rf-0-approved.png', fullPage: true });

  for (const round of [1, 2]) {
    await pos.getByRole('button', { name: '일부 금액 환불' }).click();
    await pos.waitForTimeout(300);
    console.log(`\n--- ${round}회차 환불 폼 열림 ---`);
    console.log('폼  :', (await pos.locator('label[for="refund-amount"]').innerText()).trim());
    await pos.locator('#refund-amount').fill('3000');
    await pos.getByRole('button', { name: /환불$/ }).click();
    await pos.waitForTimeout(4000);
    console.log(`\n===== 환불 ${round}회차 후 =====`);
    console.log('DB   :', state());
    console.log('화면 :', await panel());
    await pos.screenshot({ path: `audit-merchant/shots/rf-${round}-refunded.png`, fullPage: true });
  }

  await posCtx.close();
});
