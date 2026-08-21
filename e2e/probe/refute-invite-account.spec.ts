import { test, expect, Page, APIRequestContext } from '@playwright/test';
import { execFileSync } from 'child_process';
import { randomUUID } from 'crypto';

/**
 * 조사용 프로브(제품 코드 아님). 초대 수락을 **끝까지** 밟아 완료 화면의 링크를 따라간 뒤,
 * 도착한 계정 화면에 무엇이 있는지 실측한다.
 */

const WEB_BASE = 'http://localhost:3000';
const SRV_BASE = 'http://localhost:9100';
const MAILPIT_API = 'http://localhost:8025/api/v1';
const PASSWORD = 'SecureP@ssw0rd123';

function sql(statement: string): string {
  return execFileSync(
    'docker',
    ['exec', 'taspa-postgres-1', 'psql', '-U', 'taspa', '-d', 'taspa', '-tAc', statement],
    { encoding: 'utf-8' },
  )
    .trim()
    .split('\n')[0]
    .trim();
}

async function mailFor(request: APIRequestContext, email: string, needle: RegExp): Promise<string> {
  for (let attempt = 0; attempt < 40; attempt++) {
    const list = await (await request.get(`${MAILPIT_API}/messages?limit=200`)).json();
    const messages = (list.messages ?? []).filter((m: any) =>
      (m.To ?? []).some((to: any) => to.Address === email),
    );
    for (const m of messages) {
      const detail = await (await request.get(`${MAILPIT_API}/message/${m.ID}`)).json();
      const text = (detail.Text ?? '') as string;
      const match = text.match(needle);
      if (match) return match[0];
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`mail for ${email} matching ${needle} not found`);
}

async function signup(page: Page, request: APIRequestContext, email: string): Promise<void> {
  await page.goto(`${WEB_BASE}/signup`);
  await page.fill('#email', email);
  await page.fill('#password', PASSWORD);
  await page.click('button[type="submit"]');
  await expect(page).toHaveURL(/\/login\/verify-email/);
  const code = await mailFor(request, email, /\b\d{6}\b/);
  await page.fill('#code', code);
  await page.click('form[action="/login/verify-email"] button[type="submit"]');
  await expect(page).toHaveURL(/\/(account|meal)/);
}

async function links(page: Page): Promise<string[]> {
  return page.$$eval('a', (as) =>
    as.map((a) => `${(a.textContent ?? '').trim()} -> ${a.getAttribute('href')}`),
  );
}

test('probe: 초대 수락 완료 화면 → 내 계정으로 이동 이 도착하는 곳', async ({ page, request }) => {
  test.setTimeout(300_000);
  const stamp = Date.now();
  const inviterEmail = `acct-probe-admin-${stamp}@example.com`;
  const inviteeEmail = `acct-probe-member-${stamp}@example.com`;

  await signup(page, request, inviterEmail);
  const inviterId = sql(`SELECT id FROM users WHERE email = '${inviterEmail}'`);
  const orgId = randomUUID();
  sql(
    `INSERT INTO organizations (id, name, slug, status, timezone)
     VALUES ('${orgId}', '계정링크 프로브 조직', 'acct-probe-${stamp}', 'ACTIVE', 'Asia/Seoul')`,
  );
  sql(
    `INSERT INTO org_memberships (id, org_id, user_id, role, status)
     VALUES (gen_random_uuid(), '${orgId}', '${inviterId}', 'ORG_ADMIN', 'ACTIVE')`,
  );

  await page.goto('about:blank');
  await page.context().clearCookies();
  await page.goto(`${WEB_BASE}/login`, { waitUntil: 'domcontentloaded' });
  await page.fill('#email', inviterEmail);
  await page.click('button[type="submit"]');
  await page.fill('#password', PASSWORD);
  await page.click('button[type="submit"]');
  await page.waitForURL(/\/(account|meal)/);

  await page.goto(`${WEB_BASE}/console/${orgId}/invitations`);
  await page.waitForTimeout(2500);
  await page.fill('input[type="email"]', inviteeEmail);
  await page
    .getByRole('button', { name: /초대/ })
    .first()
    .click();
  await page.waitForTimeout(3000);

  const acceptUrl = await mailFor(request, inviteeEmail, /https?:\/\/\S*orgs\/invite\/accept\S*/);
  console.log(`\n>>> 초대 메일 수락 링크 = ${acceptUrl}`);

  await page.goto('about:blank');
  await page.context().clearCookies();
  await signup(page, request, inviteeEmail);

  // 초대 링크를 메일에 적힌 그대로 연다
  await page.goto(acceptUrl);
  await page.waitForTimeout(1000);
  console.log(`\n===== 수락 화면 (${page.url()}) =====`);
  console.log((await page.locator('body').innerText()).slice(0, 800));
  console.log('links:', JSON.stringify(await links(page), null, 1));

  // 수락 제출
  await page.locator('form button[type="submit"], button[type="submit"]').first().click();
  await page.waitForTimeout(1500);
  console.log(`\n===== 수락 완료 화면 (${page.url()}) =====`);
  console.log((await page.locator('body').innerText()).slice(0, 800));
  const doneLinks = await links(page);
  console.log('links:', JSON.stringify(doneLinks, null, 1));

  // '내 계정으로 이동' 클릭
  await page
    .getByRole('link', { name: /계정/ })
    .first()
    .click();
  await page.waitForTimeout(2000);
  const acctUrl = page.url();
  const acctBody = await page.locator('body').innerText();
  console.log(`\n===== 링크를 따라간 계정 화면 (${acctUrl}) =====`);
  console.log(acctBody.slice(0, 2500));
  console.log('links:', JSON.stringify(await links(page), null, 1));
  console.log('>>> 패스키 섹션 =', acctBody.includes('패스키'));
  console.log('>>> 연결된 앱 =', acctBody.includes('연결된 앱'));
  console.log('>>> /meal 링크 =', (await links(page)).some((l) => l.endsWith('-> /meal')));
  console.log(
    '>>> 조직 콘솔 링크 =',
    (await links(page)).some((l) => l.includes('/console')),
  );

  // '식권 QR' 링크를 실제로 따라가 본다(막다른 길인지)
  await page
    .getByRole('link', { name: /식권/ })
    .first()
    .click();
  await page.waitForTimeout(2000);
  console.log(`\n===== 식권 링크를 따라간 화면 (${page.url()}) =====`);
  console.log((await page.locator('body').innerText()).slice(0, 1200));
  console.log('links:', JSON.stringify(await links(page), null, 1));

  // 웹 오리진에서 같은 초대 경로를 열면?
  const webAccept = await page.goto(`${WEB_BASE}/orgs/invite/accept?token=x`);
  console.log(`\n===== 웹 오리진 초대 경로 status = ${webAccept?.status()} (${page.url()}) =====`);
  console.log((await page.locator('body').innerText()).slice(0, 400));

  // 비교: 웹 오리진(SPA) 계정 화면
  await page.goto(`${WEB_BASE}/account`);
  await page.waitForTimeout(2500);
  console.log(`\n===== SPA 계정 화면 (${page.url()}) =====`);
  console.log((await page.locator('body').innerText()).slice(0, 1500));
  console.log('links:', JSON.stringify(await links(page), null, 1));

  // 서버 오리진 계정 화면(대조)
  await page.goto(`${SRV_BASE}/account`);
  await page.waitForTimeout(1500);
  console.log(`\n===== 서버 오리진 /account (${page.url()}) =====`);
  console.log((await page.locator('body').innerText()).slice(0, 1200));

  expect(acctUrl.length).toBeGreaterThan(0);
});
