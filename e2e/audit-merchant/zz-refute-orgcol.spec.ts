import { test } from '@playwright/test';
import { login, dump, WEB } from './lib';

const MERCHANT = '6dc75701-36b5-4a3f-8ee5-5b6d2484685f';
const OWNER = 'mx-owner-1785680662869@example.com';

test('정산 명세·식수 로그의 조직 열에 식별자가 있는가', async ({ page }) => {
  test.setTimeout(200_000);
  await login(page, OWNER);

  // ── 정산 명세 (2026-08) ──
  await page.goto(`${WEB}/merchant/${MERCHANT}/settlement`);
  await page.waitForLoadState('networkidle');
  await page.locator('#settlement-period').fill('2026-08');
  await page.getByRole('button', { name: '조회' }).click();
  await page.waitForTimeout(3000);
  await dump(page, '정산 명세 2026-08');

  // 조직별 내역 표의 첫 열 셀 HTML 을 그대로 본다 — title 속성·부제·툴팁이 있는지.
  const rows = page.locator('table').last().locator('tbody tr');
  const n = await rows.count();
  console.log(`\n--- 조직별 내역 행 ${n}개, 첫 셀 outerHTML ---`);
  for (let i = 0; i < n; i++) {
    const cell = rows.nth(i).locator('td').first();
    console.log(`[${i}] ${await cell.evaluate((el) => el.outerHTML)}`);
  }
  await page.screenshot({ path: 'audit-merchant/shots/refute-settlement.png', fullPage: true });

  // ── 식수 로그 ──
  await page.goto(`${WEB}/merchant/${MERCHANT}/transactions`);
  await page.waitForLoadState('networkidle');
  await page.locator('#txn-from').fill('2026-08-01');
  await page.locator('#txn-to').fill('2026-08-03');
  await page.waitForTimeout(3500);
  await dump(page, '식수 로그 2026-08');
  const trows = page.locator('table').last().locator('tbody tr');
  const tn = await trows.count();
  console.log(`\n--- 식수 로그 행 ${tn}개, 조직 열(3번째) outerHTML ---`);
  for (let i = 0; i < Math.min(tn, 10); i++) {
    const cell = trows.nth(i).locator('td').nth(2);
    console.log(`[${i}] ${await cell.evaluate((el) => el.outerHTML)}`);
  }
  await page.screenshot({ path: 'audit-merchant/shots/refute-transactions.png', fullPage: true });

  // ── API 원본 응답에 orgId 가 실려 오는가 ──
  const s = await page.request.get(`${WEB}/api/merchant-console/${MERCHANT}/settlement?period=2026-08`);
  console.log('\n--- settlement API lines ---\n', JSON.stringify((await s.json()).lines, null, 1));
  const t = await page.request.get(`${WEB}/api/merchant-console/${MERCHANT}/transactions?from=2026-08-01&to=2026-08-03&limit=5`);
  const tj = await t.json();
  console.log('\n--- transactions API row[0] keys ---\n', Object.keys(tj.rows?.[0] ?? {}).join(', '));

  // ── CSV 본문 ──
  const csv = await page.request.get(`${WEB}/api/merchant-console/${MERCHANT}/settlement/csv?period=2026-08`);
  console.log('\n--- settlement CSV ---\n' + (await csv.text()));
});
