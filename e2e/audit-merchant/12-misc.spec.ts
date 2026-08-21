import { test } from '@playwright/test';
import { login, sql, dump, WEB } from './lib';
import fs from 'fs';

const S = JSON.parse(fs.readFileSync('audit-merchant/state.json', 'utf-8'));
const TERMINAL_KEY = 'fSBUMjOL7/zjY35i1WGRaTHnwdiZJatRW7n/gAnAwpQ=';

test('매장 전환 · 모바일 · POS 매장 식별', async ({ page, browser }) => {
  test.setTimeout(200_000);

  // ── POS 단말이 "어느 매장" 인지 화면 어디서든 말하는가 ──
  const posCtx = await browser.newContext({ locale: 'ko-KR', viewport: { width: 430, height: 900 } });
  const pos = await posCtx.newPage();
  await pos.goto(`${WEB}/pos`);
  await pos.waitForLoadState('networkidle');
  console.log('[/api/pos/status]', await (await pos.request.get(`${WEB}/api/pos/status`)).text());
  await pos.locator('#pos-enroll-key').fill(TERMINAL_KEY);
  await pos.getByRole('button', { name: '단말 등록' }).click();
  await pos.waitForTimeout(2500);
  const posText = await pos.locator('body').innerText();
  console.log('\n[POS 전체 화면 텍스트]\n' + posText);
  console.log('>>> 매장 이름(본사 구내식당)이 화면에 있는가:', posText.includes('본사 구내식당'));
  await posCtx.close();

  // ── 매장 2곳이 모두 ACTIVE 일 때 선택 화면 ──
  sql(`UPDATE merchants SET status='ACTIVE' WHERE id='${S.MERCHANT_ID}'`);
  await login(page, S.OWNER_EMAIL);
  await page.goto(`${WEB}/merchant`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);
  await dump(page, '/merchant — 매장 2곳');
  await page.screenshot({ path: 'audit-merchant/shots/12-picker.png', fullPage: true });

  // ── 모바일(390px)에서 매장 콘솔 ──
  const mob = await browser.newContext({ locale: 'ko-KR', viewport: { width: 390, height: 844 } });
  const m = await mob.newPage();
  await m.goto(`${WEB}/login`);
  await m.fill('#email', S.OWNER_EMAIL);
  await m.click('form[action="/login/identifier"] button[type="submit"]');
  await m.waitForURL(/\/login\/password/);
  await m.fill('#password', 'SecureP@ssw0rd123');
  await m.click('form[action="/login/password"] button[type="submit"]');
  await m.waitForLoadState('networkidle');

  for (const [path, label] of [
    ['/merchant', '모바일 매장 목록'],
    [`/merchant/${S.POS_MERCHANT}`, '모바일 매장 개요'],
    [`/merchant/${S.POS_MERCHANT}/transactions`, '모바일 식수 로그'],
    [`/merchant/${S.POS_MERCHANT}/settlement`, '모바일 정산'],
  ] as const) {
    await m.goto(`${WEB}${path}`);
    await m.waitForLoadState('networkidle');
    await m.waitForTimeout(2500);
    const overflow = await m.evaluate(() => ({
      scrollW: document.documentElement.scrollWidth,
      clientW: document.documentElement.clientWidth,
    }));
    console.log(`\n[${label}] 가로넘침 ${overflow.scrollW} > ${overflow.clientW} = ${overflow.scrollW > overflow.clientW}`);
    await m.screenshot({
      path: `audit-merchant/shots/12-${label.replace(/\s/g, '_')}.png`,
      fullPage: true,
    });
  }
  await mob.close();
  sql(`UPDATE merchants SET status='PENDING' WHERE id='${S.MERCHANT_ID}'`);
});
