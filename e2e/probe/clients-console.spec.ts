import { test, type APIRequestContext } from '@playwright/test';
import { execFileSync } from 'node:child_process';

const WEB_BASE = 'http://localhost:3000';
const MAILPIT_API = 'http://localhost:8025/api/v1';
const PASSWORD = 'SecureP@ssw0rd123';

function sql(statement: string): string {
  return execFileSync(
    'docker',
    ['exec', 'taspa-postgres-1', 'psql', '-U', 'taspa', '-d', 'taspa', '-tAc', statement],
    { encoding: 'utf-8' },
  ).trim();
}

async function latestVerificationCode(request: APIRequestContext, email: string): Promise<string> {
  for (let attempt = 0; attempt < 30; attempt++) {
    const list = await (await request.get(`${MAILPIT_API}/messages`)).json();
    const message = (list.messages ?? []).find((m: any) =>
      (m.To ?? []).some((to: any) => to.Address === email),
    );
    if (message) {
      const detail = await (await request.get(`${MAILPIT_API}/message/${message.ID}`)).json();
      const match = ((detail.Text ?? '') as string).match(/\b\d{6}\b/);
      if (match) return match[0];
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`no mail for ${email}`);
}

test('/admin/clients 등록 폼이 공개 클라이언트·scope 카탈로그를 안내하는가', async ({
  page,
  request,
  context,
}) => {
  test.setTimeout(180_000);
  const email = `cli-${Date.now()}@example.com`;

  await page.goto(`${WEB_BASE}/signup`);
  await page.fill('#email', email);
  await page.fill('#password', PASSWORD);
  await page.click('button[type="submit"]');
  await page.fill('#code', await latestVerificationCode(request, email));
  await page.click('form[action="/login/verify-email"] button[type="submit"]');

  // 역할은 로그인 시점에 세션에 굳는다 — 승격 뒤 쿠키를 비우고 다시 로그인.
  sql(`UPDATE users SET role = 'ADMIN' WHERE email = '${email}'`);
  await context.clearCookies();

  await page.goto(`${WEB_BASE}/login`);
  await page.fill('#email', email);
  await page.click('form[action="/login/identifier"] button[type="submit"]');
  await page.fill('#password', PASSWORD);
  await page.click('form[action="/login/password"] button[type="submit"]');
  await page.waitForTimeout(1500);

  await page.goto(`${WEB_BASE}/admin/clients`);
  await page.waitForTimeout(2500);
  console.log('\n=== /admin/clients URL:', page.url());

  // 등록 폼(다이얼로그) 열기
  await page.getByRole('button', { name: '클라이언트 등록', exact: true }).first().click();
  await page.waitForTimeout(1500);

  const dialog = page.getByRole('dialog').first();
  const text = (await dialog.count())
    ? await dialog.innerText()
    : await page.locator('body').innerText();
  console.log('\n=== 등록 폼 텍스트 ===\n' + text.slice(0, 3000));
  await page.screenshot({ path: 'probe/clients-register-form.png', fullPage: true });
});
