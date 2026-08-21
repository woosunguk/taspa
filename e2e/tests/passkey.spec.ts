import { test, expect, Page, APIRequestContext, CDPSession } from '@playwright/test';

// 패스키(WebAuthn) e2e — Chromium 전용 (CDP 가상 인증기 사용).
// 실행 전제: taspa 서버(9100) + PostgreSQL(5433) + Mailpit(1025/8025) 이 떠 있어야 한다.
//   docker compose up -d postgres mailpit
//   ./gradlew :server:bootRun

const MAILPIT_API = 'http://localhost:8025/api/v1';
const PASSWORD = 'SecureP@ssw0rd123';

function uniqueEmail(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 10000)}@example.com`;
}

async function setupVirtualAuthenticator(page: Page): Promise<CDPSession> {
  const client = await page.context().newCDPSession(page);
  await client.send('WebAuthn.enable');
  await client.send('WebAuthn.addVirtualAuthenticator', {
    options: {
      protocol: 'ctap2',
      transport: 'internal',
      hasResidentKey: true,
      hasUserVerification: true,
      isUserVerified: true,
      automaticPresenceSimulation: true,
    },
  });
  return client;
}

async function latestVerificationCode(request: APIRequestContext, email: string): Promise<string> {
  // 메일 도착까지 폴링한다.
  for (let attempt = 0; attempt < 20; attempt++) {
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
  throw new Error(`verification mail for ${email} not found in Mailpit`);
}

/** 가입 → 이메일 인증 → /account 도달. */
async function signupAndVerify(page: Page, request: APIRequestContext, email: string): Promise<void> {
  await page.goto('/signup');
  await page.fill('#email', email);
  await page.fill('#password', PASSWORD);
  await page.click('button[type="submit"]');
  await expect(page).toHaveURL(/\/login\/verify-email/);

  const code = await latestVerificationCode(request, email);
  await page.fill('#code', code);
  // 재발송 폼에도 submit 버튼이 있으므로 인증 폼의 버튼을 특정한다.
  await page.click('form[action="/login/verify-email"] button[type="submit"]');
  await expect(page).toHaveURL(/\/account/);
}

/** /account 에서 라벨을 입력하고 패스키를 등록한다 (credentialAdded 이벤트 대기). */
async function registerPasskey(page: Page, client: CDPSession, label: string): Promise<void> {
  const credentialAdded = new Promise((resolve) => client.once('WebAuthn.credentialAdded', resolve));
  await page.fill('#passkey-label', label);
  await page.click('#passkey-create');
  await credentialAdded;
  await expect(page.locator('#passkey-list')).toContainText(label);
}

async function logout(page: Page): Promise<void> {
  await page.click('form[action="/logout"] button[type="submit"]');
  await expect(page).toHaveURL(/\/login/);
}

test.describe('passkey', () => {
  test('가입 → 패스키 등록 → 로그아웃 → 이메일 입력 → 패스키 화면에서 "계속"으로 로그인한다', async ({
    page,
    request,
  }) => {
    const email = uniqueEmail('passkey-flow');
    const client = await setupVirtualAuthenticator(page);

    await signupAndVerify(page, request, email);
    await registerPasskey(page, client, '내 테스트 기기');
    await logout(page);

    // identifier-first: 패스키 보유 사용자는 /login/passkey 로 유도된다.
    await page.goto('/login');
    await page.fill('#email', email);
    await page.click('button[type="submit"]');
    await expect(page).toHaveURL(/\/login\/passkey/);
    await expect(page.locator('.auth-title')).toContainText('본인임을 확인');
    await expect(page.locator('.email-chip')).toContainText(email);

    const credentialAsserted = new Promise((resolve) => client.once('WebAuthn.credentialAsserted', resolve));
    await page.click('#passkey-signin');
    await credentialAsserted;
    await expect(page).toHaveURL(/\/account/);
  });

  test('/login 에서 usernameless "패스키로 로그인" 으로 직행한다', async ({ page, request }) => {
    const email = uniqueEmail('passkey-usernameless');
    const client = await setupVirtualAuthenticator(page);

    await signupAndVerify(page, request, email);
    await registerPasskey(page, client, '내 기기');
    await logout(page);

    // 이메일 입력 없이 discoverable credential 로 바로 로그인한다.
    await page.goto('/login');
    const credentialAsserted = new Promise((resolve) => client.once('WebAuthn.credentialAsserted', resolve));
    await page.click('#passkey-signin');
    await credentialAsserted;
    await expect(page).toHaveURL(/\/account/);
  });
});
