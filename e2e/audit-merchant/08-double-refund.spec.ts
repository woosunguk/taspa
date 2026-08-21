import { test } from '@playwright/test';
import { login, sql, WEB } from './lib';
import fs from 'fs';

const S = JSON.parse(fs.readFileSync('audit-merchant/state.json', 'utf-8'));
const TERMINAL_KEY = 'fSBUMjOL7/zjY35i1WGRaTHnwdiZJatRW7n/gAnAwpQ=';

/**
 * 프로브: 조직부담만 환불되면 화면 머리가 "승인되었습니다" 로 돌아온다(05 에서 확인).
 * 계산원이 "안 먹혔나?" 하고 한 번 더 누르면 실제로 두 번째 환불이 실행되는가?
 */
test('환불 두 번 누르기', async ({ page, browser }) => {
  test.setTimeout(270_000);
  await login(page, S.CUST);

  async function issueQr(): Promise<string> {
    for (let i = 0; i < 30; i++) {
      const t = (await (await page.request.get(`${WEB}/api/csrf`)).json()).token;
      const res = await page.request.post(`${WEB}/api/meal/qr`, {
        headers: { 'X-CSRF-TOKEN': t, 'Content-Type': 'application/json' },
        data: { orgId: S.CUST_ORG },
      });
      if (res.status() === 200) return (await res.json()).token;
      await new Promise((r) => setTimeout(r, 2000));
    }
    throw new Error('QR 발급 실패');
  }
  const token = await issueQr();

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
  console.log('승인:', authId, sql(`SELECT amount_minor FROM meal_transactions WHERE auth_id='${authId}'`));

  for (const pass of [1, 2]) {
    await pos.getByRole('button', { name: '일부 금액 환불' }).click();
    await pos.waitForTimeout(400);
    await pos.locator('#refund-amount').fill('3000');
    await pos.locator('button', { hasText: '환불' }).last().click();
    await pos.waitForTimeout(3500);
    const lines = (await pos.locator('main').innerText()).split('\n').filter(Boolean);
    console.log(`\n### ${pass}회차 직후 화면 상단 6줄: ${JSON.stringify(lines.slice(2, 8))}`);
    console.log(
      `   DB → ${sql(`SELECT 'amount='||amount_minor||' refunded='||refunded_minor FROM meal_transactions WHERE auth_id='${authId}'`)}, 환불행 ${sql(`SELECT count(*) FROM meal_refunds r JOIN meal_transactions t ON t.id=r.transaction_id WHERE t.auth_id='${authId}'`)}건`,
    );
  }
  await pos.screenshot({ path: 'audit-merchant/shots/08-double-refund.png', fullPage: true });
  await posCtx.close();
});
