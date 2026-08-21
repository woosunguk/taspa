import { test, expect, Page, APIRequestContext } from '@playwright/test';

const WEB_BASE = process.env.WEB_BASE ?? 'http://localhost:3000';
const MAILPIT_API = 'http://localhost:8025/api/v1';
const PASSWORD = 'SecureP@ssw0rd123';

const ORG_ID = 'ad603a88-cdcc-4829-8aff-76a0a013ef4d'; // 에이스미 주식회사
const MERCHANT_ID = '6dc75701-36b5-4a3f-8ee5-5b6d2484685f'; // 본사 구내식당

function uniqueEmail(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 10000)}@example.com`;
}

async function latestVerificationCode(request: APIRequestContext, email: string): Promise<string> {
  for (let attempt = 0; attempt < 30; attempt++) {
    const listRes = await request.get(`${MAILPIT_API}/messages`);
    const list = await listRes.json();
    const message = (list.messages ?? []).find((m: any) =>
      (m.To ?? []).some((to: any) => to.Address === email),
    );
    if (message) {
      const detailRes = await request.get(`${MAILPIT_API}/message/${message.ID}`);
      const detail = await detailRes.json();
      const match = ((detail.Text ?? '') as string).match(/\b\d{6}\b/);
      if (match) return match[0];
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`verification mail for ${email} not found`);
}

async function signupThroughProxy(page: Page, request: APIRequestContext, email: string): Promise<void> {
  await page.goto(`${WEB_BASE}/signup`);
  await page.fill('#email', email);
  await page.fill('#password', PASSWORD);
  await page.click('button[type="submit"]');
  await expect(page).toHaveURL(/\/login\/verify-email/);
  const code = await latestVerificationCode(request, email);
  await page.fill('#code', code);
  await page.click('form[action="/login/verify-email"] button[type="submit"]');
  await expect(page).toHaveURL(/\/(account|meal)/);
}

async function dump(page: Page, label: string) {
  await page.waitForTimeout(4000);
  const body = (await page.locator('body').innerText()).replace(/\n{3,}/g, '\n\n');
  console.log(`\n========== ${label} :: ${page.url()} ==========`);
  console.log(body);
  console.log(`========== /${label} ==========\n`);
  return body;
}

test('probe: 비멤버가 남의 조직/매장 콘솔을 연다', async ({ page, request }) => {
  test.setTimeout(180000);
  const email = uniqueEmail('probe-recovery');
  await signupThroughProxy(page, request, email);

  // 1) 플랫폼 관리 콘솔 (대조군)
  await page.goto(`${WEB_BASE}/admin`);
  const admin = await dump(page, 'ADMIN');

  await page.goto(`${WEB_BASE}/admin/orgs`);
  await dump(page, 'ADMIN_ORGS');

  // 2) 남의 조직 콘솔
  await page.goto(`${WEB_BASE}/console/${ORG_ID}`);
  const console1 = await dump(page, 'CONSOLE_OVERVIEW');

  const perm = (console1.match(/이 작업을 수행할 권한이 없습니다/g) ?? []).length;
  console.log(`>>> CONSOLE overview: '권한이 없습니다' 등장 횟수 = ${perm}`);
  console.log(`>>> CONSOLE 탭 수 = ${await page.locator('nav[aria-label="조직 관리 메뉴"] a').count()}`);
  console.log(`>>> '플랫폼 권한으로 열람 중' 문구 존재 = ${console1.includes('플랫폼 권한으로 열람 중')}`);

  for (const seg of ['members', 'invitations', 'structure', 'meal-policy', 'domains', 'forecast', 'invoices', 'roles', 'audit']) {
    await page.goto(`${WEB_BASE}/console/${ORG_ID}/${seg}`);
    await dump(page, `CONSOLE_${seg.toUpperCase()}`);
  }

  // 3) 남의 매장 콘솔
  await page.goto(`${WEB_BASE}/merchant/${MERCHANT_ID}`);
  const m1 = await dump(page, 'MERCHANT_OVERVIEW');
  console.log(`>>> MERCHANT: '가맹점의 관리자가 아닙니다' 횟수 = ${(m1.match(/가맹점의 관리자가 아닙니다/g) ?? []).length}`);

  await page.goto(`${WEB_BASE}/merchant/${MERCHANT_ID}/transactions`);
  await dump(page, 'MERCHANT_TRANSACTIONS');
  await page.goto(`${WEB_BASE}/merchant/${MERCHANT_ID}/settlement`);
  await dump(page, 'MERCHANT_SETTLEMENT');

  // 4) 진입점 화면
  await page.goto(`${WEB_BASE}/console`);
  await dump(page, 'CONSOLE_LIST');
  await page.goto(`${WEB_BASE}/merchant`);
  await dump(page, 'MERCHANT_LIST');

  expect(admin.length).toBeGreaterThan(0);
});
