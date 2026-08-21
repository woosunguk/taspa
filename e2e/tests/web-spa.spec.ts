import { test, expect, Page, APIRequestContext } from '@playwright/test';

// 웹 SPA(Next.js) e2e — **동일 오리진 프록시 아키텍처의 핵심 전제**를 검증한다.
//
// 이 앱의 인증 전략은 "SPA(:3000)와 taspa(:9100)를 브라우저에게 한 오리진으로 보이게 한다"는 것이다.
// 그래야 taspa 가 발급한 세션 쿠키가 SPA 의 API 호출에 그대로 실리고, 서버의 인가 모델(위임 베어러 거부·
// step-up·CSRF)을 한 줄도 고치지 않아도 된다. 그 전제가 깨지면 SPA 는 로그인해도 모든 API 에서 401 을 받는다.
//
// 실행 전제: taspa(9100) + PostgreSQL(5433) + Mailpit(1025/8025) + **웹 dev 서버(3000)**
//   docker compose up -d postgres mailpit
//   ./gradlew :server:bootRun --args='--spring.profiles.active=dev'
//   cd web && npm run dev

const WEB_BASE = process.env.WEB_BASE ?? 'http://localhost:3000';
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

/**
 * SPA 오리진에서 가입·이메일 인증까지 마친다.
 * 회원가입·인증 화면은 **서버가 소유**하고 SPA 는 프록시만 한다 — 이 테스트가 그 프록시도 함께 증명한다.
 */
async function signupThroughProxy(page: Page, request: APIRequestContext, email: string): Promise<void> {
  await page.goto(`${WEB_BASE}/signup`);
  await page.fill('#email', email);
  await page.fill('#password', PASSWORD);
  await page.click('button[type="submit"]');
  await expect(page).toHaveURL(/\/login\/verify-email/);

  const code = await latestVerificationCode(request, email);
  await page.fill('#code', code);
  await page.click('form[action="/login/verify-email"] button[type="submit"]');
}

/**
 * **메일·기기 링크가 착지하는 서버 소유 경로가 웹 오리진에서도 열리는가.**
 *
 * ★조직 초대 메일의 수락 링크(`/orgs/invite/accept`)가 `next.config.ts` 의 프록시 허용 목록에서
 * 빠져 있어, 배포 문서가 지시하는 구성(공개 도메인 = SPA)에서 **Next 기본 404** 로 끝났다.
 * 조직에 사람을 넣는 자율 경로는 초대뿐이라 온보딩이 그 자리에서 멈추는데, 메일 발송은 성공했으므로
 * 서버 로그에도 흔적이 없다 — 조직관리자도 운영자도 원인을 알 수 없는 형태다.
 *
 * 그래서 **목록 자체를 테스트로 못박는다**: 한 줄 누락이 같은 침묵을 다시 만들지 않게.
 * 인증이 필요한 경로는 로그인으로 유도(302/200 로그인 화면)되면 충분하다 — 여기서 확인하는 것은
 * "이 앱이 그 경로를 가로채 404 로 만들지 않는가"이지 인가가 아니다.
 */
test.describe('서버 소유 경로가 웹 오리진에서 열린다 (프록시 허용 목록)', () => {
  const SERVER_OWNED = [
    { path: '/orgs/invite/accept?token=nonexistent', why: '조직 초대 메일의 수락 링크' },
    { path: '/activate', why: 'Device Grant — TV·CLI 가 화면에 띄우고 사용자가 손으로 입력한다' },
    { path: '/login', why: '서버 렌더링 로그인' },
    { path: '/password-reset', why: '비밀번호 재설정 메일 링크의 출발점' },
    { path: '/signup', why: '가입' },
  ];

  for (const { path, why } of SERVER_OWNED) {
    test(`${path} — ${why}`, async ({ page }) => {
      const response = await page.goto(`${WEB_BASE}${path}`);
      expect(response, `${path} 응답 없음`).not.toBeNull();
      expect(response!.status(), `${path} 가 404 다 — next.config.ts 의 프록시 목록을 확인할 것`).not.toBe(404);
    });
  }

  test('무관한 경로는 그대로 404 다 (대조군)', async ({ page }) => {
    // 이 대조군이 없으면 "전부 200 을 주는 설정"으로도 위 테스트가 통과한다.
    const response = await page.goto(`${WEB_BASE}/definitely-not-a-route-xyz`);
    expect(response!.status()).toBe(404);
  });
});

test.describe('web SPA (동일 오리진 프록시)', () => {
  test('SPA 오리진에서 가입 → 세션 쿠키가 프록시된 API 까지 흐른다', async ({ page, request }) => {
    const email = uniqueEmail('spa');
    await signupThroughProxy(page, request, email);

    // 서버는 인증 완료 후 /account 로 보낸다. 그 경로는 이제 **SPA 가 소유**한다(next.config 프록시에서 제외).
    await expect(page).toHaveURL(/\/account/);

    // ★핵심 단언: SPA 화면이 프록시된 API 로 자기 신원을 읽어온다.
    // 쿠키가 프록시를 넘지 못하면 이 호출이 401 이 되어 로그인으로 튕긴다.
    const me = await page.evaluate(async () => {
      const response = await fetch('/api/account/me', { credentials: 'same-origin' });
      return { status: response.status, body: response.ok ? await response.json() : null };
    });
    expect(me.status).toBe(200);
    expect(me.body.email).toBe(email);

    // CSRF 토큰 조달 경로(서버 렌더링 meta 태그를 읽을 수 없는 SPA 용)도 동작해야 한다.
    const csrf = await page.evaluate(async () => {
      const response = await fetch('/api/csrf', { credentials: 'same-origin' });
      return { status: response.status, body: response.ok ? await response.json() : null };
    });
    expect(csrf.status).toBe(200);
    expect(csrf.body.headerName).toBeTruthy();
    expect(csrf.body.token).toBeTruthy();
  });

  test('로그인 상태의 SPA 홈이 사용자 이름과 진입점을 보여준다', async ({ page, request }) => {
    const email = uniqueEmail('spa-home');
    await signupThroughProxy(page, request, email);

    await page.goto(`${WEB_BASE}/`);
    // 세션이 살아 있으면 익명 문구가 아니라 인사말과 식권 진입점이 보인다.
    await expect(page.getByRole('heading', { name: /안녕하세요/ })).toBeVisible();
    await expect(page.getByRole('heading', { name: '식권' })).toBeVisible();
  });

  test('식권 화면 — 소속 조직이 없으면 그 사실을 정직하게 알려준다', async ({ page, request }) => {
    const email = uniqueEmail('spa-meal');
    await signupThroughProxy(page, request, email);

    await page.goto(`${WEB_BASE}/meal`);
    // 빈 화면으로 숨기지 않는다 — 왜 쓸 수 없는지와 다음에 뭘 하면 되는지가 화면에 있어야 한다.
    await expect(page.getByText('소속 조직이 없습니다')).toBeVisible();
    await expect(page.getByText(/초대를 요청/)).toBeVisible();
  });

  /**
   * 회귀: 로그인 직후 **JSON 응답 화면에 착지**하던 버그.
   *
   * SPA 는 부팅할 때 `/api/account/me` 로 자기 신원을 확인하는데, 로그아웃 상태면 그 요청이 401 로 끝난다.
   * 그런데 서버의 RequestCache 가 종류를 가리지 않고 저장하는 바람에 그 **API 호출**이 "로그인 후 돌아갈
   * 곳"으로 기록됐고, 로그인에 성공하면 사용자가 원시 JSON 을 보게 됐다. 저장 URL 은 서버가 본 절대 주소라
   * 프런트 포트(:3000)를 벗어나 서버 포트(:9100)로 튕기기까지 했다.
   */
  test('로그인 직후 JSON 응답이 아니라 화면으로 돌아온다', async ({ page, request }) => {
    const email = uniqueEmail('spa-landing');

    // 로그아웃 상태에서 SPA 를 열어 신원 확인 API 가 401 로 끝나게 만든다(= 버그의 씨앗).
    await page.goto(`${WEB_BASE}/`);
    await expect(page.getByRole('heading', { name: /사내 계정과 식대/ })).toBeVisible();

    // 그 상태에서 가입·인증을 완료한다.
    await signupThroughProxy(page, request, email);

    // 착지 지점이 화면이어야 한다 — API 경로도, 서버 포트도 아니다.
    await expect(page).not.toHaveURL(/\/api\//);
    await expect(page).toHaveURL(new RegExp(`^${WEB_BASE}`));
    // 원시 JSON 이 본문에 노출되지 않는다.
    await expect(page.locator('body')).not.toContainText('"emailVerified"');
  });

  test('미인증 사용자는 보호 화면에서 서버 로그인으로 유도된다', async ({ page }) => {
    await page.goto(`${WEB_BASE}/console`);
    // SPA 가 로그인 UI 를 재구현하지 않는다 — 서버 로그인 화면으로 보낸다(MFA·패스키·소셜 게이트가 거기 있다).
    await expect(page).toHaveURL(/\/login/);
    await expect(page.locator('.wordmark')).toBeVisible();
  });

  /**
   * 위 케이스의 **대조군**이자 사각지대 보강.
   *
   * `/console` 이 부르는 `/api/orgs/**` 는 @Order(2) 리소스 서버 체인이라 미인증에 **401 + 빈 본문**
   * (`WWW-Authenticate: Bearer`, Spring Security 기본 진입점)을 준다. 즉 저 테스트는 "401 을 주는 경로"만
   * 증명했고, 기본 체인이 302 로 로그인 HTML 을 돌려주던 경로는 통과한 채였다.
   * 그 경우 `fetch` 가 리다이렉트를 투명하게 따라가 HTML 을 200 으로 받고, `JSON.parse` 가
   * "is not valid JSON" 을 던져 화면에 영문 파서 오류가 뜬다. 상태코드는 서버 통합테스트가 잠갔지만
   * **브라우저가 실제로 어디에 착지하는지**는 여기서만 드러난다.
   */
  test('미인증 /account 진입 — 로그인으로 가고, 영문 파서 오류를 보여주지 않는다', async ({ page }) => {
    await page.goto(`${WEB_BASE}/account`);

    await expect(page).toHaveURL(/\/login/);
    // 돌아올 곳이 API 가 아니라 화면이어야 한다(RequestCache 회귀와 같은 부류).
    await expect(page).toHaveURL(/continue=%2Faccount/);
    await expect(page.locator('.wordmark')).toBeVisible();

    const body = page.locator('body');
    await expect(body).not.toContainText('is not valid JSON');
    await expect(body).not.toContainText('Unexpected token');
  });

  /**
   * 회귀: **세션이 끊긴 탭에서의 변경 요청**.
   *
   * 서버 세션은 그대로 두고 브라우저 쿠키만 버리면 "오래 열어 둔 탭"이 정확히 재현된다. 이 테스트가
   * 잠그는 것은 그 401 이 **로그인 유도로 번역되는가** 하나다 — `/api/account/**` 는 CSRF 면제
   * (`SecurityConfig` 는 `/api/**` 중 `/api/sessions/**`·`/api/admin/**` 만 되돌려 놓는다)라
   * CSRF 헤더 부재가 403 을 만들 수 없고, 따라서 "세 단계를 한 번에 덮는다"고 말할 수 없다.
   */
  test('세션이 끊긴 뒤의 변경 요청은 오류가 아니라 로그인으로 유도한다', async ({ page, request, context }) => {
    const email = uniqueEmail('spa-expired');
    await signupThroughProxy(page, request, email);

    await page.goto(`${WEB_BASE}/account`);
    await expect(page.getByRole('heading', { name: '계정 설정' })).toBeVisible();
    await expect(page.locator('#new-email')).toBeVisible();

    // 여기서부터 이 탭의 자격증명은 없다. 화면은 아직 로그인된 상태를 그리고 있다(= 만료된 탭).
    await context.clearCookies();

    await page.fill('#new-email', uniqueEmail('spa-expired-new'));
    await page.getByRole('button', { name: '확인 코드 보내기' }).click();

    await expect(page).toHaveURL(/\/login/);
    await expect(page).toHaveURL(/continue=%2Faccount/);
    await expect(page.locator('.wordmark')).toBeVisible();
    await expect(page.locator('body')).not.toContainText('is not valid JSON');
  });

  /**
   * 회귀: **서버 장애를 로그아웃으로 위장**하던 결함(`lib/session.ts` 의 catch 가 전부 anonymous 로 수렴).
   *
   * 신원 조회가 500 이면 세션이 멀쩡한 사용자가 `RequireAuth` 에 의해 `/login` 으로 튕겼다. 로그인에
   * 성공해도 기본 착지가 보호 화면이라 제자리로 돌아오고, 사용자는 원인을 모른 채 같은 일을 반복했다.
   * **미인증의 근거는 서버의 401 뿐**이다 — 그 근거가 없으면 모르는 것이지 익명인 것이 아니다.
   *
   * 500 은 라우트 가로채기로 만든다. 실제 서버를 망가뜨리지 않고 이 분기만 정확히 겨냥할 수 있고,
   * 위의 "미인증 → /login" 케이스들과 짝을 이뤄야 둘을 **구분**한다는 사실이 증명된다.
   */
  test('신원 확인이 500 이면 로그인으로 튕기지 않고 재시도를 제안한다', async ({ page }) => {
    await page.route('**/api/account/me', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ errorCode: 'INTERNAL_ERROR', message: '서버 오류가 발생했습니다' }),
      }),
    );

    await page.goto(`${WEB_BASE}/console`);

    await expect(page.getByText('로그인 상태를 확인하지 못했습니다')).toBeVisible();
    await expect(page.getByRole('button', { name: '다시 시도' })).toBeVisible();
    // ★핵심: 장애를 미인증으로 오인하지 않는다.
    await expect(page).toHaveURL(/\/console/);
    await expect(page).not.toHaveURL(/\/login/);
  });

  /**
   * 회귀: 위 error 상태가 새로 만들어 낸 **절반만 살아나는 화면**.
   *
   * `AppShell`(헤더)과 `RequireAuth`(본문)가 `useSession` 을 각각 호출하던 시절엔 상태도 `retry` 도
   * 따로였다. "다시 시도"로 본문이 복구돼도 헤더는 error 스냅샷에 멈춰 네비게이션·사용자 이름·로그아웃이
   * 사라진 채 남았다 — 사용자에겐 장애보다 더 헷갈리는 상태다. 루트 `SessionProvider` 로 상태를 하나로
   * 합쳐 고쳤고, 여기서 **헤더와 본문이 함께** 복구되는지를 못박는다.
   *
   * ★차단은 **개수가 아니라 구간**으로 건다. "첫 요청만 500" 으로 만들면 조회가 둘이던 옛 코드에서는
   * 한쪽이 500, 다른 쪽이 200 을 받아 결과가 경합에 좌우된다 — 통과해도 무엇을 증명했는지 알 수 없다.
   * 초기 렌더 동안은 **모두** 실패시키고, 재시도 직전에 열어 준다. 그러면 옛 코드에서는 재조회를 한
   * 본문만 살아나고 헤더는 error 에 남아 이 테스트가 확실히 깨진다.
   */
  test('신원 확인 재시도는 본문과 헤더를 함께 복구한다', async ({ page, request }) => {
    const email = uniqueEmail('spa-retry');
    await signupThroughProxy(page, request, email);

    let serverHealthy = false;
    await page.route('**/api/account/me', (route) =>
      serverHealthy
        ? route.fallback()
        : route.fulfill({
            status: 500,
            contentType: 'application/json',
            body: JSON.stringify({ errorCode: 'INTERNAL_ERROR', message: '서버 오류가 발생했습니다' }),
          }),
    );

    await page.goto(`${WEB_BASE}/meal`);
    await expect(page.getByText('로그인 상태를 확인하지 못했습니다')).toBeVisible();
    // 조회가 하나뿐이므로 헤더도 같은 error 를 본다 — 이 시점엔 네비게이션이 없는 게 맞다.
    await expect(page.getByRole('navigation', { name: '주요 메뉴' })).toHaveCount(0);

    serverHealthy = true;
    await page.getByRole('button', { name: '다시 시도' }).click();

    // 본문 복구 …
    await expect(page.getByText('로그인 상태를 확인하지 못했습니다')).toHaveCount(0);
    // … 그리고 ★헤더도 함께. 예전에는 여기가 계속 비어 있었다.
    await expect(page.getByRole('navigation', { name: '주요 메뉴' })).toBeVisible();
    await expect(page.getByRole('link', { name: '로그아웃' })).toBeVisible();
  });
});
