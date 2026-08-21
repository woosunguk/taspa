import { test } from '@playwright/test';
import { login, sql, dump, WEB } from './lib';
import fs from 'fs';

const S = JSON.parse(fs.readFileSync('audit-merchant/state.json', 'utf-8'));
const period = new Date().toISOString().slice(0, 7);

test('정산 명세 vs 조직 청구서', async ({ page }) => {
  test.setTimeout(200_000);
  page.on('console', (m) => {
    if (m.type() === 'error') console.log(`[console.error] ${m.text()}`);
  });

  console.log('가맹 타임존:', sql(`SELECT name||' / '||timezone FROM merchants WHERE id='${S.POS_MERCHANT}'`));
  console.log('조직 타임존:', sql(`SELECT name||' / '||timezone FROM organizations WHERE id='${S.CUST_ORG}'`));
  console.log(
    '이 매장 이번달 거래:',
    sql(
      `SELECT count(*)||'건, 조직부담합='||coalesce(sum(amount_minor-self_paid_minor),0) FROM meal_transactions WHERE merchant_id='${S.POS_MERCHANT}' AND status='APPROVED'`,
    ),
  );

  // ── 가맹 사장 시점: 정산 명세 ──
  await login(page, S.OWNER_EMAIL);
  await page.goto(`${WEB}/merchant/${S.POS_MERCHANT}/settlement`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1200);
  await page.locator('#settlement-period').fill(period);
  await page.getByRole('button', { name: '조회' }).click();
  await page.waitForTimeout(3000);
  const settle = await dump(page, `가맹 정산 명세 (${period})`);
  await page.screenshot({ path: 'audit-merchant/shots/11-settlement.png', fullPage: true });
  console.log('>>> 정산 화면이 타임존 차이를 설명하는가:', /타임존|시간대|매장 시간|경계/.test(settle));

  // ── 식수 로그 ──
  await page.goto(`${WEB}/merchant/${S.POS_MERCHANT}/transactions`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3000);
  await dump(page, '식수 로그(환불 노출 확인)');
  await page.screenshot({ path: 'audit-merchant/shots/11-transactions.png', fullPage: true });

  // ── 플랫폼 관리자 시점: 조직 청구서 + 지급 현황 ──
  await login(page, S.ADMIN_EMAIL);
  await page.goto(`${WEB}/console/${S.CUST_ORG}/invoices`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);
  await page.locator('#invoice-period').fill(period);
  await page.getByRole('button', { name: '초안 생성' }).click();
  await page.waitForTimeout(1200);
  const overwrite = page.getByRole('button', { name: '덮어쓰고 생성' });
  if (await overwrite.count()) await overwrite.first().click();
  await page.waitForTimeout(3000);
  await dump(page, `조직 청구서 (${period})`);
  await page.screenshot({ path: 'audit-merchant/shots/11-invoice.png', fullPage: true });

  await page.goto(`${WEB}/admin/payables`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1200);
  await page.locator('#payables-period').fill(period);
  await page.getByRole('button', { name: '조회' }).click();
  await page.waitForTimeout(3500);
  const pay = await dump(page, '플랫폼 지급 현황');
  await page.screenshot({ path: 'audit-merchant/shots/11-payables.png', fullPage: true });
  console.log('>>> 지급현황이 매장 타임존/기간 경계를 설명하는가:', /타임존|시간대|매장 시간/.test(pay));
});
