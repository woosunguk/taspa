import { test, expect, Page, APIRequestContext } from '@playwright/test';

// 원격 세션 관리(Spring Session JDBC) e2e.
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

async function login(page: Page, email: string): Promise<void> {
  await page.goto('/login');
  await page.fill('#email', email);
  await page.click('form[action="/login/identifier"] button[type="submit"]');
  await expect(page).toHaveURL(/\/login\/password/);
  await page.fill('#password', PASSWORD);
  await page.click('form[action="/login/password"] button[type="submit"]');
  await expect(page).toHaveURL(/\/account/);
}

test.describe('session management', () => {
  test('두 브라우저 로그인 → 세션 2개 표시 → 다른 모든 세션 로그아웃 → 상대 세션은 로그인으로 이동', async ({
    browser,
    request,
  }) => {
    const email = uniqueEmail('session-mgmt');

    // 컨텍스트 A: 가입 + 이메일 인증으로 로그인 상태 확보.
    const contextA = await browser.newContext();
    const pageA = await contextA.newPage();
    await signupAndVerify(pageA, request, email);

    // 컨텍스트 B(별도 브라우저): 같은 계정으로 로그인 → 세션 2개.
    const contextB = await browser.newContext();
    const pageB = await contextB.newPage();
    await login(pageB, email);

    // A 의 계정 페이지 "활성 세션" 섹션에 두 세션이 보이고, 현재 세션 뱃지가 붙는다.
    await pageA.goto('/account');
    await expect(pageA.locator('main')).toContainText('활성 세션');
    await expect(pageA.locator('#session-list .session-row')).toHaveCount(2);
    await expect(pageA.locator('#session-list')).toContainText('현재 세션');

    // A 에서 "다른 모든 세션 로그아웃".
    pageA.once('dialog', (dialog) => dialog.accept());
    await pageA.click('#session-revoke-others');
    await expect(pageA.locator('#session-list .session-row')).toHaveCount(1);

    // B 의 다음 이동은 미인증 처리 → 로그인 페이지로.
    await pageB.goto('/account');
    await expect(pageB).toHaveURL(/\/login/);

    // A 는 여전히 로그인 상태.
    await pageA.goto('/account');
    await expect(pageA).toHaveURL(/\/account/);

    await contextA.close();
    await contextB.close();
  });
});
