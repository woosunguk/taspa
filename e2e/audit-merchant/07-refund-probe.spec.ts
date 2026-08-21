import { test } from '@playwright/test';
import { login, sql, dump, WEB } from './lib';
import fs from 'fs';

const S = JSON.parse(fs.readFileSync('audit-merchant/state.json', 'utf-8'));
const TERMINAL_KEY = 'fSBUMjOL7/zjY35i1WGRaTHnwdiZJatRW7n/gAnAwpQ=';

test('매장 정지 거절 + 환불 반복 프로브', async ({ page, browser }) => {
  test.setTimeout(280_000);
  sql(`UPDATE org_memberships SET employment_status='EMPLOYED' WHERE org_id='${S.CUST_ORG}'`);
  sql(`UPDATE meal_policies SET daily_meal_count=9,
        breakfast_start='00:00:00',breakfast_end='07:59:59.999',
        lunch_start='08:00:00',lunch_end='15:59:59.999',
        dinner_start='16:00:00',dinner_end='23:59:59.999' WHERE org_id='${S.CUST_ORG}'`);
  await login(page, S.CUST);

  async function issueQr(): Promise<string> {
    for (let i = 0; i < 20; i++) {
      const token = (await (await page.request.get(`${WEB}/api/csrf`)).json()).token;
      const res = await page.request.post(`${WEB}/api/meal/qr`, {
        headers: { 'X-CSRF-TOKEN': token, 'Content-Type': 'application/json' },
        data: { orgId: S.CUST_ORG },
      });
      if (res.status() === 200) return (await res.json()).token;
      if (i === 0) console.log('   (QR 대기)', res.status(), await res.text());
      await new Promise((r) => setTimeout(r, 2500));
    }
    throw new Error('QR 발급 실패');
  }

  const posCtx = await browser.newContext({ locale: 'ko-KR', viewport: { width: 430, height: 900 } });
  const pos = await posCtx.newPage();
  await pos.goto(`${WEB}/pos`);
  await pos.waitForLoadState('networkidle');
  await pos.locator('#pos-enroll-key').fill(TERMINAL_KEY);
  await pos.getByRole('button', { name: '단말 등록' }).click();
  await pos.waitForTimeout(2500);

  async function approve(token: string, amount: number) {
    const manual = pos.getByRole('button', { name: /코드 직접 입력/ });
    if (await manual.count()) await manual.first().click();
    await pos.locator('#pos-manual-token').fill(token);
    await pos.getByRole('button', { name: '코드 확인' }).click();
    await pos.waitForTimeout(600);
    for (const ch of String(amount)) await pos.getByRole('button', { name: ch, exact: true }).click();
    await pos.getByRole('button', { name: /승인$/ }).click();
    await pos.waitForTimeout(3000);
  }

  // ── 매장 정지 상태에서의 거절 ──
  sql(`UPDATE merchants SET status='SUSPENDED' WHERE id='${S.POS_MERCHANT}'`);
  await approve(await issueQr(), 9000);
  await dump(pos, '거절 — 매장 정지(PENDING 도 같은 코드)');
  await pos.screenshot({ path: 'audit-merchant/shots/07-suspended.png', fullPage: true });
  sql(`UPDATE merchants SET status='ACTIVE' WHERE id='${S.POS_MERCHANT}'`);
  const back = pos.getByRole('button', { name: /처음으로|새 QR 스캔하기/ });
  if (await back.count()) await back.first().click();
  await pos.waitForTimeout(500);

  // ── 환불 반복 프로브 ──
  await approve(await issueQr(), 10000);
  const authId = (await pos.locator('dd').last().innerText()).trim();
  console.log('\n>>> 승인 번호:', authId);

  for (const pass of [1, 2]) {
    await pos.getByRole('button', { name: '일부 금액 환불' }).click();
    await pos.locator('#refund-amount').fill('3000');
    await pos.getByRole('button', { name: /원 환불$/ }).click();
    await pos.waitForTimeout(3000);
    const head = (await pos.locator('main').innerText()).split('\n').slice(0, 14).join(' | ');
    console.log(`\n### 환불 ${pass}회차 직후 화면 머리: ${head}`);
    console.log(
      `   DB: ${sql(`SELECT 'amount='||amount_minor||' refunded='||refunded_minor FROM meal_transactions WHERE auth_id='${authId}'`)}` +
        ` / 환불행 ${sql(`SELECT count(*) FROM meal_refunds r JOIN meal_transactions t ON t.id=r.transaction_id WHERE t.auth_id='${authId}'`)}건`,
    );
    await pos.screenshot({ path: `audit-merchant/shots/07-refund-pass${pass}.png`, fullPage: true });
  }
  await dump(pos, '환불 2회 누른 뒤 최종 화면');

  await posCtx.close();
});
