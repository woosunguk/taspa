import { test } from '@playwright/test';
import { login, csrf, WEB, API } from './lib';

const TERMINAL_KEY = 'fSBUMjOL7/zjY35i1WGRaTHnwdiZJatRW7n/gAnAwpQ=';
const CUST = 'mx-cust-1785681003250@example.com';
const CUST_ORG = 'ba0e5599-f5f5-47e0-908b-1389b9b2211d';
const BOUND_MERCHANT_NAME = '본사 구내식당';
const BOUND_MERCHANT_ID = '6dc75701-36b5-4a3f-8ee5-5b6d2484685f';

function report(label: string, text: string, html: string) {
  console.log(`\n${'='.repeat(72)}\n### ${label}\n${'-'.repeat(72)}\n${text}\n`);
  console.log(
    `>>> [${label}] 매장이름 포함=${text.includes(BOUND_MERCHANT_NAME)} / HTML에 매장이름=${html.includes(
      BOUND_MERCHANT_NAME,
    )} / HTML에 매장UUID=${html.includes(BOUND_MERCHANT_ID)} / HTML에 '매장'=${html.includes('매장')}`,
  );
}

test('POS 단말이 결속 매장을 어디서든 말하는가 (반증 시도)', async ({ browser }) => {
  test.setTimeout(240_000);

  // ── 단말 브라우저 ──────────────────────────────────────────────
  const posCtx = await browser.newContext({ locale: 'ko-KR', viewport: { width: 430, height: 900 } });
  const pos = await posCtx.newPage();
  await pos.goto(`${WEB}/pos`);
  await pos.waitForLoadState('networkidle');
  await pos.waitForTimeout(1200);

  console.log('[title]', await pos.title());
  console.log('[/api/pos/status]', await (await pos.request.get(`${WEB}/api/pos/status`)).text());

  report('등록 전 /pos', await pos.locator('body').innerText(), await pos.content());

  // 등록
  await pos.locator('#pos-enroll-key').fill(TERMINAL_KEY);
  await pos.getByRole('button', { name: '단말 등록' }).click();
  await pos.waitForTimeout(2500);
  report('등록 후 /pos (스캔 화면)', await pos.locator('body').innerText(), await pos.content());

  // ── 손님 QR 발급 ───────────────────────────────────────────────
  const custCtx = await browser.newContext({ locale: 'ko-KR', viewport: { width: 430, height: 900 } });
  const cust = await custCtx.newPage();
  await login(cust, CUST);
  const qr = await cust.request.post(`${WEB}/api/meal/qr`, {
    headers: { 'X-CSRF-TOKEN': await csrf(cust), 'Content-Type': 'application/json' },
    data: { orgId: CUST_ORG },
  });
  console.log('[qr status]', qr.status());
  const token = (await qr.json()).token as string;

  // ── 실제 승인 (수동 코드 입력 경로) ────────────────────────────
  if ((await pos.locator('#pos-manual-token').count()) === 0) {
    await pos.getByRole('button', { name: /코드 직접 입력/ }).click();
  }
  await pos.locator('#pos-manual-token').fill(token);
  await pos.getByRole('button', { name: '코드 확인' }).click();
  await pos.waitForTimeout(800);
  report('금액 입력 화면', await pos.locator('body').innerText(), await pos.content());

  for (const d of ['5', '0', '0', '0']) {
    await pos.getByRole('button', { name: d, exact: true }).first().click();
  }
  await pos.getByRole('button', { name: /승인$/ }).click();
  await pos.waitForTimeout(3000);
  report('승인 결과 화면', await pos.locator('body').innerText(), await pos.content());

  // 다음 손님 → 스캔 화면 + 최근 승인 목록
  await pos.getByRole('button', { name: '다음 손님' }).click();
  await pos.waitForTimeout(600);
  report('최근 승인 목록', await pos.locator('body').innerText(), await pos.content());

  // 실제로 어느 매장 장부로 들어갔는지 (서버 진실)
  const check = await pos.request.get(`${API}/actuator/health`);
  console.log('[server health]', check.status());

  await posCtx.close();
  await custCtx.close();
});
