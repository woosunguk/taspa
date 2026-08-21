import { test } from '@playwright/test';
import { signup, login, sql, WEB } from './lib';
import { randomUUID } from 'crypto';

const TERMINAL_KEY = 'fSBUMjOL7/zjY35i1WGRaTHnwdiZJatRW7n/gAnAwpQ=';
const POS_MERCHANT = '6dc75701-36b5-4a3f-8ee5-5b6d2484685f';
const OWNER = 'mx-owner-1785680662869@example.com';

/** 페이지의 모든 조작 가능 요소를 그대로 나열한다 — "다른 진입점이 있는가"를 보려는 것이다. */
async function controls(page: any, label: string) {
  const list = await page.evaluate(() => {
    const out: any[] = [];
    document.querySelectorAll('button, a, [role="button"], input, [onclick]').forEach((el: any) => {
      out.push({
        tag: el.tagName,
        text: (el.innerText || el.value || el.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim().slice(0, 60),
        href: el.getAttribute('href') ?? undefined,
      });
    });
    return out;
  });
  console.log(`\n[조작요소 · ${label}] ${JSON.stringify(list, null, 0)}`);
  return list;
}

test('반증 시도 — 다음 손님 이후 취소·환불 진입점이 정말 없는가', async ({ page, request, browser }) => {
  test.setTimeout(280_000);
  const stamp = Date.now();
  const cust = `rf-nogoback-${stamp}@example.com`;
  await signup(page, request, cust);
  const userId = sql(`SELECT id FROM users WHERE email='${cust}'`);
  const orgId = randomUUID();
  sql(`INSERT INTO organizations (id,name,slug,status,timezone) VALUES ('${orgId}','반증조직','refute-nogo-${stamp}','ACTIVE','UTC')`);
  sql(`INSERT INTO org_memberships (id,org_id,user_id,role,status) VALUES (gen_random_uuid(),'${orgId}','${userId}','MEMBER','ACTIVE')`);
  sql(`INSERT INTO meal_policies (org_id,per_meal_limit_minor,daily_meal_count,monthly_cap_minor,
        breakfast_start,breakfast_end,lunch_start,lunch_end,dinner_start,dinner_end)
       VALUES ('${orgId}',12000,9,500000,'00:00:00','07:59:59.999','08:00:00','15:59:59.999','16:00:00','23:59:59.999')`);

  const t = (await (await page.request.get(`${WEB}/api/csrf`)).json()).token;
  const qr = await page.request.post(`${WEB}/api/meal/qr`, {
    headers: { 'X-CSRF-TOKEN': t, 'Content-Type': 'application/json' },
    data: { orgId },
  });
  const token = (await qr.json()).token;
  console.log('QR 발급', qr.status());

  // ── POS 단말 ──
  const posCtx = await browser.newContext({ locale: 'ko-KR', viewport: { width: 430, height: 900 } });
  const pos = await posCtx.newPage();
  await pos.goto(`${WEB}/pos`);
  await pos.waitForLoadState('networkidle');
  const enroll = pos.locator('#pos-enroll-key');
  if (await enroll.count()) {
    await enroll.fill(TERMINAL_KEY);
    await pos.getByRole('button', { name: '단말 등록' }).click();
    await pos.waitForTimeout(2500);
  }
  const manual = pos.getByRole('button', { name: /코드 직접 입력/ });
  if (await manual.count()) await manual.first().click();
  await pos.locator('#pos-manual-token').fill(token);
  await pos.getByRole('button', { name: '코드 확인' }).click();
  await pos.waitForTimeout(600);
  for (const ch of '90000') await pos.getByRole('button', { name: ch, exact: true }).click();
  await pos.getByRole('button', { name: /승인$/ }).click();
  await pos.waitForTimeout(3000);

  console.log('\n[승인 직후 화면]\n' + (await pos.locator('body').innerText()));
  await controls(pos, '승인 직후');
  const authId = (await pos.locator('dd').last().innerText()).trim();
  console.log('authId =', authId, '| DB =', sql(`SELECT status||' '||amount_minor||'/'||self_paid_minor FROM meal_transactions WHERE auth_id='${authId}'`));

  // ── 다음 손님 ──
  await pos.getByRole('button', { name: '다음 손님' }).click();
  await pos.waitForTimeout(1200);
  console.log('\n[다음 손님 이후 화면]\n' + (await pos.locator('body').innerText()));
  const after = await controls(pos, '다음 손님 이후');
  console.log('authId 를 언급하는 조작요소:', after.filter((c: any) => (c.text ?? '').includes(authId.slice(0, 8))).length);

  // 최근 승인 항목이 눌리는가 / 저장되는가
  const li = await pos.locator('li').evaluateAll((els: any[]) =>
    els.map((e) => ({
      text: e.innerText.replace(/\n/g, ' '),
      hasButton: !!e.querySelector('button,a,[role="button"]'),
      hasClickHandler: !!e.onclick,
      cursor: getComputedStyle(e).cursor,
    })),
  );
  console.log('\n[최근 승인 항목]', JSON.stringify(li));
  const storage = await pos.evaluate(() => ({
    local: Object.keys(localStorage).map((k) => `${k}=${(localStorage.getItem(k) ?? '').slice(0, 80)}`),
    session: Object.keys(sessionStorage).map((k) => `${k}=${(sessionStorage.getItem(k) ?? '').slice(0, 80)}`),
  }));
  console.log('[저장소]', JSON.stringify(storage));

  // 뒤로가기로 승인 화면이 돌아오는가
  await pos.goBack().catch(() => {});
  await pos.waitForTimeout(1000);
  console.log('\n[뒤로가기 후] url=' + pos.url() + '\n' + (await pos.locator('body').innerText()).slice(0, 400));

  // 새로고침
  await pos.goto(`${WEB}/pos`);
  await pos.waitForLoadState('networkidle');
  await pos.waitForTimeout(1500);
  console.log('\n[새로고침 후]\n' + (await pos.locator('body').innerText()));
  await controls(pos, '새로고침 후');
  await posCtx.close();

  // ── 가맹 관리자 콘솔에 그 거래가 남아 있는가 / 조작할 수 있는가 ──
  const ownerCtx = await browser.newContext({ locale: 'ko-KR', viewport: { width: 1280, height: 900 } });
  const owner = await ownerCtx.newPage();
  await login(owner, OWNER);
  await owner.goto(`${WEB}/merchant/${POS_MERCHANT}/transactions`);
  await owner.waitForLoadState('networkidle');
  await owner.waitForTimeout(2500);
  const body = await owner.locator('body').innerText();
  console.log('\n[가맹 콘솔 거래로그] authId 노출=' + body.includes(authId) + ' | authId 앞8자 노출=' + body.includes(authId.slice(0, 8)));
  console.log(body.slice(0, 2500));
  await controls(owner, '가맹 거래로그');
  await ownerCtx.close();
});
