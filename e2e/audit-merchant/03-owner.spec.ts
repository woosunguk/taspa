import { test } from '@playwright/test';
import { login, dump, WEB } from './lib';
import fs from 'fs';

const S = JSON.parse(fs.readFileSync('audit-merchant/state.json', 'utf-8'));

test('가맹 담당자 여정', async ({ page }) => {
  page.on('console', (m) => {
    if (m.type() === 'error') console.log(`[console.error] ${m.text()}`);
  });
  await login(page, S.OWNER_EMAIL);

  // 로그인 직후 착지 화면 — "나는 이제 매장 담당자다"를 어디서 알 수 있나
  await page.waitForTimeout(1500);
  await dump(page, '로그인 직후 착지');
  console.log('[헤더]', await page.locator('header').innerText());
  await page.screenshot({ path: 'audit-merchant/shots/03-landing.png', fullPage: true });

  // 계정 페이지에 매장 담당 사실이 나오는가
  await page.goto(`${WEB}/account`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);
  const acct = await dump(page, '/account');
  console.log('>>> /account 에 "매장" 언급:', /매장|가맹/.test(acct));

  // /merchant 매장 선택
  await page.goto(`${WEB}/merchant`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);
  await dump(page, '/merchant (매장 선택)');
  await page.screenshot({ path: 'audit-merchant/shots/03-merchant-list.png', fullPage: true });

  // 새로 만든(PENDING) 매장 상세
  await page.goto(`${WEB}/merchant/${S.MERCHANT_ID}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2500);
  await dump(page, `/merchant/{PENDING 매장} 개요`);
  await page.screenshot({ path: 'audit-merchant/shots/03-pending-overview.png', fullPage: true });

  await page.goto(`${WEB}/merchant/${S.MERCHANT_ID}/transactions`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2500);
  await dump(page, '식수 로그 (거래 0건)');
  await page.screenshot({ path: 'audit-merchant/shots/03-tx-empty.png', fullPage: true });

  await page.goto(`${WEB}/merchant/${S.MERCHANT_ID}/settlement`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2500);
  await dump(page, '정산 명세 (거래 0건)');
  await page.screenshot({ path: 'audit-merchant/shots/03-settlement-empty.png', fullPage: true });

  // 예측은 어디에 있나 — 전용 라우트가 없다. 개요 페이지 안 섹션인지 확인
  const links = await page
    .locator('a')
    .evaluateAll((els) => els.map((e: any) => `${e.innerText.trim()} → ${e.getAttribute('href')}`));
  console.log('\n[매장 화면 링크]', JSON.stringify(links, null, 1));
});
