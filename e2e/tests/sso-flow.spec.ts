import { test, expect, BrowserContext, Page, APIRequestContext } from '@playwright/test';

// SSO 플로우 e2e — demo-client(RP)가 taspa(IdP)로 OIDC Authorization Code + PKCE 로그인.
// 실행 전제: taspa(9100) + demo-client + PostgreSQL(5433) + Mailpit(1025/8025) 이 떠 있어야 한다.
//   docker compose up -d postgres mailpit
//   ./gradlew :server:bootRun --args='--spring.profiles.active=dev'   # demo-app 클라이언트 시딩 필수
//   ./gradlew :examples:demo-client:bootRun
// demo-client 기본 포트는 8080. 8080 이 다른 앱에 점유된 환경에서는 DEMO_BASE 환경변수로
// 오버라이드하고, taspa 의 demo-app 클라이언트에 그 오리진으로 (1) redirect_uri 와
// (2) post_logout_redirect_uri 를 함께 재등록/시딩해 실행한다. 둘 다 필요하다:
//   - redirect_uri: 로그인 콜백(test #1·#2). 미등록이면 authorize 가 거부된다.
//   - post_logout_redirect_uri: SLO 복귀 지점(test #3). demo-client 는 {baseUrl}/(= 오버라이드한
//     오리진)로 보내는데 taspa 가 이 값을 등록 목록과 "정확 일치"로 검증하므로, :8080 만 등록돼
//     있으면 SLO 가 거부되어 test #3 의 `toHaveURL(DEMO_BASE/)` 왕복이 실패한다.
//   DEMO_BASE=http://localhost:8081 npx playwright test sso-flow

const DEMO_BASE = process.env.DEMO_BASE ?? 'http://localhost:8080';
const MAILPIT_API = 'http://localhost:8025/api/v1';
const PASSWORD = 'SecureP@ssw0rd123';

function uniqueEmail(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 10000)}@example.com`;
}

/**
 * Mailpit 에서 해당 주소로 온 "[taspa] 이메일 인증 코드" 메일이 minCount 통 이상 쌓일 때까지
 * 기다렸다가 가장 최근 메일의 6자리 코드를 반환한다. minCount 는 이전 코드 메일(예: 가입 인증)을
 * 새 코드로 오인하지 않기 위한 하한이다(Mailpit 목록은 최신순).
 */
async function verificationCode(
  request: APIRequestContext,
  email: string,
  minCount: number,
): Promise<string> {
  for (let attempt = 0; attempt < 20; attempt++) {
    const listRes = await request.get(`${MAILPIT_API}/messages`);
    const list = await listRes.json();
    const messages = (list.messages ?? []).filter(
      (m: any) =>
        (m.To ?? []).some((to: any) => to.Address === email) &&
        (m.Subject ?? '').includes('이메일 인증 코드'),
    );
    if (messages.length >= minCount) {
      const detailRes = await request.get(`${MAILPIT_API}/message/${messages[0].ID}`);
      const detail = await detailRes.json();
      const match = ((detail.Text ?? '') as string).match(/\b\d{6}\b/);
      if (match) return match[0];
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`verification mail #${minCount} for ${email} not found in Mailpit`);
}

async function signupAndVerify(page: Page, request: APIRequestContext, email: string): Promise<void> {
  await page.goto('/signup');
  await page.fill('#email', email);
  await page.fill('#password', PASSWORD);
  await page.click('button[type="submit"]');
  await expect(page).toHaveURL(/\/login\/verify-email/);

  const code = await verificationCode(request, email, 1);
  await page.fill('#code', code);
  await page.click('form[action="/login/verify-email"] button[type="submit"]');
  await expect(page).toHaveURL(/\/account/);
}

/**
 * dev 서버는 taspa.risk.enabled=true — 비밀번호 로그인이 MEDIUM 이상으로 판정되면 MFA 미등록
 * 계정은 /login/risk-challenge(이메일 코드) 게이트로 빠질 수 있다(같은 (ip, ua) 이력이 있으면
 * 보통 LOW 로 안 뜬다). 뜨는 경우에만 두 번째 코드 메일을 읽어 통과시킨다.
 */
async function passRiskChallengeIfShown(
  page: Page,
  request: APIRequestContext,
  email: string,
): Promise<void> {
  if (!page.url().includes('/login/risk-challenge')) return;
  const code = await verificationCode(request, email, 2);
  await page.fill('#code', code);
  await page.click('form[action="/login/risk-challenge"] button[type="submit"]');
}

test.describe.serial('sso flow', () => {
  const email = uniqueEmail('sso');
  let ssoContext: BrowserContext;
  let page: Page;

  test.beforeAll(async ({ browser }) => {
    // 가입·이메일 인증은 일회용 컨텍스트에서 끝내고 버린다 — taspa 세션이 이미 있으면
    // IdP 로그인 화면이 스킵되므로, 본 시나리오는 세션 없는 새 컨텍스트에서 시작해야 한다.
    const signupContext = await browser.newContext({ baseURL: 'http://localhost:9100' });
    const signupPage = await signupContext.newPage();
    await signupAndVerify(signupPage, signupContext.request, email);
    await signupContext.close();

    ssoContext = await browser.newContext();
    page = await ssoContext.newPage();
  });

  test.afterAll(async () => {
    await ssoContext?.close();
  });

  test('첫 로그인: demo-client → taspa 로그인 → 동의 "허용" → /me 에 이메일 표시', async ({ request }) => {
    await page.goto(`${DEMO_BASE}/`);
    await page.click('a[href="/oauth2/authorization/taspa"]');

    // taspa identifier-first 로그인.
    await expect(page).toHaveURL(/localhost:9100\/login/);
    await page.fill('#email', email);
    await page.click('form[action="/login/identifier"] button[type="submit"]');
    await expect(page).toHaveURL(/\/login\/password/);
    await page.fill('#password', PASSWORD);
    await page.click('form[action="/login/password"] button[type="submit"]');

    // 비밀번호 통과 후 착지 지점이 확정될 때까지 대기(리스크 게이트 유무로 갈린다).
    await page.waitForURL(/\/oauth2\/consent|\/login\/risk-challenge/);
    await passRiskChallengeIfShown(page, request, email);

    // 최초 로그인은 동의 화면(requireAuthorizationConsent=true)을 거친다.
    await expect(page).toHaveURL(/\/oauth2\/consent/);
    await page.click('form[action="/oauth2/authorize"] button[type="submit"]');

    // code 교환 후 demo-client 로 복귀 — /me 에 프로필(이메일) 표시.
    await expect(page).toHaveURL(`${DEMO_BASE}/me`);
    await expect(page.locator('main')).toContainText(email);
  });

  test('로컬 로그아웃 후 재로그인: taspa 재인증 없이 즉시 /me 복귀(SSO 세션 유지)', async () => {
    // demo-client 의 로컬 세션만 종료 — taspa(9100) SSO 세션과 저장된 동의는 유지된다.
    // form[action="/logout"] 는 정확 일치 셀렉터라 아래 SLO 폼(action="/logout?slo=true")과 구분된다.
    await page.click('form[action="/logout"] button[type="submit"]');
    await expect(page).toHaveURL(`${DEMO_BASE}/`);
    await expect(page.locator('a[href="/oauth2/authorization/taspa"]')).toBeVisible();

    await page.click('a[href="/oauth2/authorization/taspa"]');
    // 로그인 화면·동의 화면 없이 authorization code 재발급 → 즉시 /me. 이 단언은 최종 URL 이
    // 9100 로그인 페이지가 아니라 /me 임을 요구하므로 재인증이 끼면 실패한다.
    await expect(page).toHaveURL(`${DEMO_BASE}/me`);
    await expect(page.locator('main')).toContainText(email);
  });

  test('SLO(RP-Initiated Logout): taspa SSO 세션까지 종료 → 재로그인 시 taspa 재인증 요구', async () => {
    // "taspa 세션까지 로그아웃 (SLO)" — 이 앱 세션 + taspa OP SSO 세션을 함께 종료한다.
    // OidcClientInitiatedLogoutSuccessHandler 가 taspa /connect/logout 으로 id_token_hint +
    // post_logout_redirect_uri(http://localhost:8080/)를 보내고, taspa 는 OP 세션을 끊은 뒤
    // 등록된 post_logout_redirect_uri 로 리다이렉트한다(왕복).
    await page.click('form[action="/logout?slo=true"] button[type="submit"]');

    // OP 로그아웃 왕복 후 demo-client 홈으로 복귀. 위 로컬 로그아웃과 착지 지점(홈)은 같지만,
    // 결정적 차이는 이제 taspa SSO 세션이 사라졌다는 점이다(아래에서 검증).
    await expect(page).toHaveURL(`${DEMO_BASE}/`);
    await expect(page.locator('a[href="/oauth2/authorization/taspa"]')).toBeVisible();

    // 재로그인 시도 — OP SSO 세션이 종료됐으므로 즉시 /me 로 복귀하지 못하고 taspa 로그인
    // 화면으로 재인증이 요구된다. (test #2 의 로컬 로그아웃과 정반대 결과.)
    await page.click('a[href="/oauth2/authorization/taspa"]');
    await expect(page).toHaveURL(/localhost:9100\/login/);
  });
});
