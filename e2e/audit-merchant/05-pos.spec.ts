import { test, expect, Page } from '@playwright/test';
import { signup, login, sql, dump, WEB } from './lib';
import { randomUUID } from 'crypto';
import fs from 'fs';

const S = JSON.parse(fs.readFileSync('audit-merchant/state.json', 'utf-8'));
const POS_MERCHANT = '6dc75701-36b5-4a3f-8ee5-5b6d2484685f'; // .env.local 의 POS_CLIENT 결속 매장
const TERMINAL_KEY = 'fSBUMjOL7/zjY35i1WGRaTHnwdiZJatRW7n/gAnAwpQ=';
const LIMIT = 12_000;

test('POS 단말 — 등록 → 승인 → 환불', async ({ page, browser }) => {
  test.setTimeout(280_000);
  page.on('console', (m) => {
    if (m.type() === 'error') console.log(`[console.error] ${m.text()}`);
  });

  // ── 손님(직원) 준비 ──
  const stamp = Date.now();
  const cust = `mx-cust-${stamp}@example.com`;
  await signup(page, page.request as any, cust).catch(async () => {});
  const custPage = page;
  const userId = sql(`SELECT id FROM users WHERE email='${cust}'`);
  const orgId = randomUUID();
  sql(`INSERT INTO organizations (id,name,slug,status,timezone) VALUES ('${orgId}','POS감사조직','pos-audit-${stamp}','ACTIVE','UTC')`);
  sql(`INSERT INTO org_memberships (id,org_id,user_id,role,status) VALUES (gen_random_uuid(),'${orgId}','${userId}','MEMBER','ACTIVE')`);
  sql(
    `INSERT INTO meal_policies (org_id,per_meal_limit_minor,daily_meal_count,monthly_cap_minor,
       breakfast_start,breakfast_end,lunch_start,lunch_end,dinner_start,dinner_end)
     VALUES ('${orgId}',${LIMIT},5,500000,'00:00:00','07:59:59.999','08:00:00','15:59:59.999','16:00:00','23:59:59.999')`,
  );
  console.log(`손님=${cust} org=${orgId}`);

  async function issueQr(): Promise<string> {
    const token = (await (await custPage.request.get(`${WEB}/api/csrf`)).json()).token;
    const res = await custPage.request.post(`${WEB}/api/meal/qr`, {
      headers: { 'X-CSRF-TOKEN': token, 'Content-Type': 'application/json' },
      data: { orgId },
    });
    const body = await res.json();
    if (res.status() !== 200) throw new Error(`QR 발급 실패 ${res.status()} ${JSON.stringify(body)}`);
    return body.token;
  }

  // ── POS 단말 (별도 컨텍스트: 손님 세션과 섞이지 않게) ──
  const posCtx = await browser.newContext({ locale: 'ko-KR', viewport: { width: 430, height: 900 } });
  const pos = await posCtx.newPage();
  pos.on('console', (m) => {
    if (m.type() === 'error') console.log(`[POS console.error] ${m.text()}`);
  });

  await pos.goto(`${WEB}/pos`);
  await pos.waitForLoadState('networkidle');
  await pos.waitForTimeout(1200);
  await dump(pos, 'POS — 최초 진입(미등록)');
  await pos.screenshot({ path: 'audit-merchant/shots/05-pos-enroll.png', fullPage: true });

  // 오타 키
  await pos.locator('#pos-enroll-key').fill('wrong-key-attempt');
  await pos.getByRole('button', { name: '단말 등록' }).click();
  await pos.waitForTimeout(2500);
  console.log('\n[틀린 키 결과]\n' + (await pos.locator('form').innerText()));

  // 진짜 키
  await pos.locator('#pos-enroll-key').fill(TERMINAL_KEY);
  await pos.getByRole('button', { name: '단말 등록' }).click();
  await pos.waitForTimeout(3000);
  await dump(pos, 'POS — 등록 직후');
  await pos.screenshot({ path: 'audit-merchant/shots/05-pos-scan.png', fullPage: true });

  async function enterToken(token: string) {
    const manual = pos.getByRole('button', { name: /코드 직접 입력/ });
    if (await manual.count()) await manual.first().click();
    await pos.locator('#pos-manual-token').fill(token);
    await pos.getByRole('button', { name: '코드 확인' }).click();
    await pos.waitForTimeout(800);
  }
  async function enterAmount(amount: number) {
    for (const ch of String(amount)) {
      await pos.getByRole('button', { name: ch, exact: true }).click();
    }
  }

  // ── ① 한도 초과 승인: 15,000 → 개인 3,000 ──
  await enterToken(await issueQr());
  await dump(pos, 'POS — 금액 입력 단계');
  await enterAmount(15000);
  await pos.screenshot({ path: 'audit-merchant/shots/05-pos-amount.png', fullPage: true });
  await pos.getByRole('button', { name: /승인$/ }).click();
  await pos.waitForTimeout(3000);
  const approved = await dump(pos, 'POS — 승인 결과(한도 초과 · 개인부담 발생)');
  await pos.screenshot({ path: 'audit-merchant/shots/05-pos-approved.png', fullPage: true });
  expect(approved).toContain('3,000원');

  // ── ② 부분 환불 3,000 → 개인에게 3,000 반환 ──
  await pos.getByRole('button', { name: '일부 금액 환불' }).click();
  await pos.locator('#refund-amount').fill('3000');
  await pos.getByRole('button', { name: /환불$/ }).click();
  await pos.waitForTimeout(3000);
  await dump(pos, 'POS — 부분 환불 결과 (개인부담 환불)');
  await pos.screenshot({ path: 'audit-merchant/shots/05-pos-refund-self.png', fullPage: true });

  // ── ③ 한도 내 승인 후 조직부담만 환불 → "돌려줄 현금 0" 일 때 화면이 뭐라 하나 ──
  await pos.getByRole('button', { name: '다음 손님' }).click();
  await pos.waitForTimeout(11_000); // QR 발급 쿨다운 10초
  await enterToken(await issueQr());
  await enterAmount(10000);
  await pos.getByRole('button', { name: /승인$/ }).click();
  await pos.waitForTimeout(3000);
  await dump(pos, 'POS — 한도 내 승인(개인부담 0)');
  await pos.getByRole('button', { name: '일부 금액 환불' }).click();
  await pos.locator('#refund-amount').fill('3000');
  await pos.getByRole('button', { name: /환불$/ }).click();
  await pos.waitForTimeout(3000);
  const orgRefund = await dump(pos, 'POS — 조직부담만 환불한 결과 ★');
  await pos.screenshot({ path: 'audit-merchant/shots/05-pos-refund-org.png', fullPage: true });
  console.log('>>> 화면이 "환불" 이라고 말하는가:', /환불되었습니다/.test(orgRefund));
  console.log('>>> 화면이 "승인되었습니다" 라고 말하는가:', /승인되었습니다/.test(orgRefund));

  fs.writeFileSync(
    'audit-merchant/state.json',
    JSON.stringify({ ...S, CUST: cust, CUST_ORG: orgId, POS_MERCHANT }, null, 2),
  );
  await posCtx.close();
});
