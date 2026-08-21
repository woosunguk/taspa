import { test, expect, Page, APIRequestContext } from '@playwright/test';

// 매직 링크(이메일 로그인) e2e.
// 실행 전제: taspa 서버(9100) + PostgreSQL(5433) + Mailpit(1025/8025) 이 떠 있어야 한다.
//   docker compose up -d postgres mailpit
//   ./gradlew :server:bootRun

const MAILPIT_API = 'http://localhost:8025/api/v1';
const PASSWORD = 'SecureP@ssw0rd123';

function uniqueEmail(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 10000)}@example.com`;
}

async function latestVerificationCode(request: APIRequestContext, email: string): Promise<string> {
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

/** Mailpit 에서 매직 링크 메일을 찾아 로그인 링크 URL 을 추출한다. */
async function latestMagicLink(request: APIRequestContext, email: string): Promise<string> {
  for (let attempt = 0; attempt < 20; attempt++) {
    const listRes = await request.get(`${MAILPIT_API}/messages`);
    const list = await listRes.json();
    const message = (list.messages ?? []).find(
      (m: any) =>
        (m.To ?? []).some((to: any) => to.Address === email) &&
        (m.Subject ?? '').includes('로그인 링크'),
    );
    if (message) {
      const detailRes = await request.get(`${MAILPIT_API}/message/${message.ID}`);
      const detail = await detailRes.json();
      const match = ((detail.Text ?? '') as string).match(/https?:\/\/\S*\/login\/magic\?token=[A-Za-z0-9_-]+/);
      if (match) return match[0];
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`magic link mail for ${email} not found in Mailpit`);
}

async function signupAndVerify(page: Page, request: APIRequestContext, email: string): Promise<void> {
  await page.goto('/signup');
  await page.fill('#email', email);
  await page.fill('#password', PASSWORD);
  await page.click('button[type="submit"]');
  await expect(page).toHaveURL(/\/login\/verify-email/);

  const code = await latestVerificationCode(request, email);
  await page.fill('#code', code);
  await page.click('form[action="/login/verify-email"] button[type="submit"]');
  await expect(page).toHaveURL(/\/account/);
}

async function logout(page: Page): Promise<void> {
  await page.click('form[action="/logout"] button[type="submit"]');
  await expect(page).toHaveURL(/\/login/);
}

test.describe('magic link', () => {
  test('가입·인증 → 로그아웃 → 링크 받기 → 랜딩 → "로그인" 버튼으로 계정 도달', async ({ page, request }) => {
    const email = uniqueEmail('magic-link');

    await signupAndVerify(page, request, email);
    await logout(page);

    // 비밀번호 페이지에서 "이메일로 로그인 링크 받기".
    await page.goto('/login');
    await page.fill('#email', email);
    await page.click('form[action="/login/identifier"] button[type="submit"]');
    await expect(page).toHaveURL(/\/login\/password/);
    await page.click('form[action="/login/magic/request"] button');
    await expect(page.locator('main')).toContainText('메일함을 확인하세요');

    // 메일에서 링크 추출 → 랜딩(GET, 미소비) → "로그인"(POST, 소비) → /account.
    const link = await latestMagicLink(request, email);
    await page.goto(link);
    await expect(page.locator('.auth-title')).toContainText('로그인하시겠습니까');
    await expect(page.locator('.email-chip')).toContainText(email);
    await page.click('form[action="/login/magic"] button[type="submit"]');
    await expect(page).toHaveURL(/\/account/);

    // 같은 링크 재사용은 거부된다(단일 사용).
    await logout(page);
    await page.goto(link);
    await expect(page.locator('main')).toContainText('만료되었거나 이미 사용');
  });
});
