import { test } from '@playwright/test';
import { login, sql, dump, WEB } from './lib';
import fs from 'fs';

const S = JSON.parse(fs.readFileSync('audit-merchant/state.json', 'utf-8'));
const TERMINAL_KEY = 'fSBUMjOL7/zjY35i1WGRaTHnwdiZJatRW7n/gAnAwpQ=';

test('계산대 거절 안내 + 환불 반복 프로브', async ({ page, browser }) => {
  test.setTimeout(280_000);
  await login(page, S.CUST);
  const orgId = S.CUST_ORG;

  async function issueQr(): Promise<string> {
    for (let i = 0; i < 15; i++) {
      const token = (await (await page.request.get(`${WEB}/api/csrf`)).json()).token;
      const res = await page.request.post(`${WEB}/api/meal/qr`, {
        headers: { 'X-CSRF-TOKEN': token, 'Content-Type': 'application/json' },
        data: { orgId },
      });
      if (res.status() === 200) return (await res.json()).token;
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

  async function attempt(token: string, amount: number, label: string) {
    const manual = pos.getByRole('button', { name: /코드 직접 입력/ });
    if (await manual.count()) await manual.first().click();
    await pos.locator('#pos-manual-token').fill(token);
    await pos.getByRole('button', { name: '코드 확인' }).click();
    await pos.waitForTimeout(600);
    for (const ch of String(amount)) await pos.getByRole('button', { name: ch, exact: true }).click();
    await pos.getByRole('button', { name: /승인$/ }).click();
    await pos.waitForTimeout(3000);
    const text = await dump(pos, label);
    await pos.screenshot({
      path: `audit-merchant/shots/06-${label.replace(/[^a-zA-Z0-9가-힣]/g, '_')}.png`,
      fullPage: true,
    });
    // 화면을 처음으로 되돌린다
    const back = pos.getByRole('button', { name: /처음으로|새 QR 스캔하기|다음 손님/ });
    if (await back.count()) await back.first().click();
    await pos.waitForTimeout(500);
    return text;
  }

  // ── ① 이미 사용된 QR ──
  const used = await issueQr();
  await attempt(used, 9000, '거절-첫승인(대조군)');
  await attempt(used, 9000, '거절-이미 사용된 QR');

  // ── ② 끼니창 밖 ──
  sql(`UPDATE meal_policies SET breakfast_start='00:00:00',breakfast_end='00:00:01',
        lunch_start='00:00:02',lunch_end='00:00:03',dinner_start='00:00:04',dinner_end='00:00:05'
       WHERE org_id='${orgId}'`);
  await attempt(await issueQr(), 9000, '거절-끼니창 밖');

  // ── ③ 일 횟수 초과 ──
  sql(`UPDATE meal_policies SET breakfast_start='00:00:00',breakfast_end='07:59:59.999',
        lunch_start='08:00:00',lunch_end='15:59:59.999',dinner_start='16:00:00',dinner_end='23:59:59.999',
        daily_meal_count=1 WHERE org_id='${orgId}'`);
  await attempt(await issueQr(), 9000, '거절-일 횟수 초과');

  // ── ④ 재직 상태 아님(NOT_EMPLOYED) ──
  sql(`UPDATE meal_policies SET daily_meal_count=5 WHERE org_id='${orgId}'`);
  sql(`UPDATE org_memberships SET employment_status='TERMINATED' WHERE org_id='${orgId}'`);
  await attempt(await issueQr(), 9000, '거절-퇴사자');
  sql(`UPDATE org_memberships SET employment_status='EMPLOYED' WHERE org_id='${orgId}'`);

  // ── ⑤ 매장 정지 ──
  sql(`UPDATE merchants SET status='SUSPENDED' WHERE id='${S.POS_MERCHANT}'`);
  await attempt(await issueQr(), 9000, '거절-매장 정지');
  sql(`UPDATE merchants SET status='ACTIVE' WHERE id='${S.POS_MERCHANT}'`);

  // ── ⑥ 환불 반복 프로브: "승인되었습니다" 를 보고 계산원이 다시 누르면? ──
  await attempt(await issueQr(), 10000, '환불프로브-승인');
  // 승인 화면으로 되돌아가야 하므로 마지막 '다음 손님' 을 누르지 않은 상태에서 다시 승인
  const t = await issueQr();
  const manual = pos.getByRole('button', { name: /코드 직접 입력/ });
  if (await manual.count()) await manual.first().click();
  await pos.locator('#pos-manual-token').fill(t);
  await pos.getByRole('button', { name: '코드 확인' }).click();
  await pos.waitForTimeout(600);
  for (const ch of '10000') await pos.getByRole('button', { name: ch, exact: true }).click();
  await pos.getByRole('button', { name: /승인$/ }).click();
  await pos.waitForTimeout(3000);

  for (const pass of [1, 2]) {
    await pos.getByRole('button', { name: '일부 금액 환불' }).click();
    await pos.locator('#refund-amount').fill('3000');
    await pos.getByRole('button', { name: /환불$/ }).click();
    await pos.waitForTimeout(2500);
    console.log(`\n### 환불 ${pass}회차 후 화면 머리\n` + (await pos.locator('main').innerText()).slice(0, 320));
  }
  const authId = (await pos.locator('dd').last().innerText()).trim();
  console.log(
    '\n>>> DB 상의 환불 누계:',
    sql(`SELECT amount_minor||' / refunded='||refunded_minor FROM meal_transactions WHERE auth_id='${authId}'`),
  );
  console.log('>>> 환불 행 수:', sql(`SELECT count(*) FROM meal_refunds r JOIN meal_transactions t ON t.id=r.transaction_id WHERE t.auth_id='${authId}'`));

  await posCtx.close();
});
