import { test, expect, APIRequestContext } from '@playwright/test';
import { execFileSync } from 'child_process';

const WEB_BASE = process.env.WEB_BASE ?? 'http://localhost:3000';
const MAILPIT_API = 'http://localhost:8025/api/v1';
const PASSWORD = 'SecureP@ssw0rd123';

function sql(statement: string): string {
  return execFileSync(
    'docker',
    ['exec', 'taspa-postgres-1', 'psql', '-U', 'taspa', '-d', 'taspa', '-tAc', statement],
    { encoding: 'utf-8' },
  ).trim().split('\n')[0].trim();
}

async function latestVerificationCode(request: APIRequestContext, email: string): Promise<string> {
  for (let attempt = 0; attempt < 20; attempt++) {
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
  throw new Error(`verification mail for ${email} not found`);
}

test('scope 화이트리스트 노출·오류 문구 프로브', async ({ page, request }) => {
  test.setTimeout(180_000);
  const email = `scopeprobe-${Date.now()}@example.com`;

  await page.goto(`${WEB_BASE}/signup`);
  await page.fill('#email', email);
  await page.fill('#password', PASSWORD);
  await page.click('button[type="submit"]');
  await expect(page).toHaveURL(/\/login\/verify-email/);
  await page.fill('#code', await latestVerificationCode(request, email));
  await page.click('form[action="/login/verify-email"] button[type="submit"]');

  const userId = sql(`SELECT id FROM users WHERE email = '${email}'`);
  sql(`UPDATE users SET role = 'ADMIN' WHERE id = '${userId}'`);

  await page.context().clearCookies();
  await page.goto(`${WEB_BASE}/login`);
  await page.fill('#email', email);
  await page.click('form[action="/login/identifier"] button[type="submit"]');
  await expect(page).toHaveURL(/\/login\/password/);
  await page.fill('#password', PASSWORD);
  await page.click('form[action="/login/password"] button[type="submit"]');
  await expect(page).toHaveURL(/\/(account|meal|$)/);

  // --- 1) 관리 화면에서 클라이언트 등록 폼을 열고 보이는 텍스트 전부를 수집한다.
  await page.goto(`${WEB_BASE}/admin/clients`);
  await page.getByRole('button', { name: '클라이언트 등록' }).first().click();
  await page.waitForTimeout(1500);
  const formText = await page.locator('body').innerText();
  console.log('=== 등록 폼 화면 텍스트 ===');
  console.log(formText);
  console.log('=== org.roles 화면에 등장? ===', formText.includes('org.roles'));

  // --- 2) scope 화이트리스트를 내려주는 API 가 있는지 후보 경로 탐색
  const csrf = await (await page.request.get(`${WEB_BASE}/api/csrf`)).json();
  const candidates = [
    '/api/admin/clients/allowed-scopes',
    '/api/admin/clients/scopes',
    '/api/admin/scopes',
    '/api/admin/oauth/scopes',
    '/api/admin/clients/grantable-scopes',
  ];
  for (const path of candidates) {
    const res = await page.request.get(`${WEB_BASE}${path}`);
    console.log(`GET ${path} -> ${res.status()} ${(await res.text()).slice(0, 200)}`);
  }

  // --- 3) 오타 scope 로 등록 시도 → 400 본문 확인
  const bad = await page.request.post(`${WEB_BASE}/api/admin/clients`, {
    headers: { 'X-CSRF-TOKEN': csrf.token, 'Content-Type': 'application/json' },
    data: {
      clientId: `probe-${Date.now()}`,
      clientName: 'scope probe',
      publicClient: false,
      grantTypes: ['client_credentials'],
      redirectUris: [],
      postLogoutRedirectUris: [],
      scopes: ['openid', 'orgs.roles'],
    },
  });
  console.log('=== 오타 scope 응답 ===', bad.status(), await bad.text());

  // --- 4) 대조군: 조직 역할 표면의 grantable-actions
  const orgId = sql(`SELECT id FROM organizations LIMIT 1`);
  const grantable = await page.request.get(`${WEB_BASE}/api/orgs/${orgId}/roles/grantable-actions`);
  console.log('=== grantable-actions 대조군 ===', grantable.status(), (await grantable.text()).slice(0, 400));
});
