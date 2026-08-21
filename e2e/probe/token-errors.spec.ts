import { test, expect, type APIRequestContext } from '@playwright/test';
import { createHash, randomBytes } from 'node:crypto';

const WEB_BASE = 'http://localhost:3000';
const API_BASE = 'http://localhost:9100';
const MAILPIT_API = 'http://localhost:8025/api/v1';
const PASSWORD = 'SecureP@ssw0rd123';
const REDIRECT = 'http://localhost:8080/login/oauth2/code/taspa';

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
  throw new Error(`verification mail for ${email} not found`);
}

const b64url = (b: Buffer) => b.toString('base64url');

test('실제 인가 코드로 토큰 엔드포인트 오류 전수 측정', async ({ page, request }) => {
  test.setTimeout(180_000);
  const stamp = Date.now();
  const email = `tokerr-${stamp}@example.com`;

  // ── 계정 (브라우저로 실제 가입·이메일 인증) ────────────────────────────
  await page.goto(`${WEB_BASE}/signup`);
  await page.fill('#email', email);
  await page.fill('#password', PASSWORD);
  await page.click('button[type="submit"]');
  await expect(page).toHaveURL(/\/login\/verify-email/);
  await page.fill('#code', await latestVerificationCode(request, email));
  await page.click('form[action="/login/verify-email"] button[type="submit"]');

  const verifier = b64url(randomBytes(32));
  const challenge = b64url(createHash('sha256').update(verifier).digest());

  // page.request 는 브라우저 컨텍스트의 쿠키를 공유하되 리다이렉트를 우리가 통제할 수 있다.
  // → 콜백이 demo-client(:8080) 에 도달하지 않아 code 가 소비되지 않는다.
  const api = page.request;

  const authorize = async (): Promise<string> => {
    const url =
      `${API_BASE}/oauth2/authorize?response_type=code&client_id=demo-app` +
      `&redirect_uri=${encodeURIComponent(REDIRECT)}&scope=${encodeURIComponent('openid profile email')}` +
      `&code_challenge=${challenge}&code_challenge_method=S256&state=s${stamp}`;
    let res = await api.get(url, { maxRedirects: 0, failOnStatusCode: false });
    let loc = res.headers()['location'] ?? '';

    if (loc.includes('/oauth2/consent') || res.status() === 200) {
      // 동의 화면을 브라우저로 통과시킨 뒤 다시 시도한다(동의는 저장된다).
      await page.goto(url);
      if (page.url().includes('/oauth2/consent')) {
        const boxes = page.locator('form[action="/oauth2/authorize"] input[type="checkbox"]');
        for (let i = 0; i < (await boxes.count()); i++) await boxes.nth(i).check().catch(() => {});
        await page.click('form[action="/oauth2/authorize"] button[type="submit"]').catch(() => {});
        await page.waitForTimeout(2000);
      }
      res = await api.get(url, { maxRedirects: 0, failOnStatusCode: false });
      loc = res.headers()['location'] ?? '';
    }
    const m = loc.match(/[?&]code=([^&]+)/);
    if (!m) throw new Error(`no code: status=${res.status()} loc=${loc}`);
    return decodeURIComponent(m[1]);
  };

  const post = async (label: string, form: Record<string, string>, basic?: string) => {
    const headers: Record<string, string> = {};
    if (basic) headers.Authorization = 'Basic ' + Buffer.from(basic).toString('base64');
    const res = await request.post(`${API_BASE}/oauth2/token`, {
      headers,
      form,
      failOnStatusCode: false,
    });
    const body = await res.text();
    console.log(`\n### ${label}\n  HTTP ${res.status()}  ${body.slice(0, 400)}`);
  };

  const code = await authorize();
  console.log('\n>>> 실제 인가 코드 획득 OK');

  await post(
    '(A) 정상 교환',
    { grant_type: 'authorization_code', code, redirect_uri: REDIRECT, code_verifier: verifier },
    'demo-app:demo-secret',
  );
  await post(
    '(B) 소진된 code 재사용',
    { grant_type: 'authorization_code', code, redirect_uri: REDIRECT, code_verifier: verifier },
    'demo-app:demo-secret',
  );

  const c2 = await authorize();
  await post(
    '(C) 유효 code + 틀린 code_verifier',
    {
      grant_type: 'authorization_code',
      code: c2,
      redirect_uri: REDIRECT,
      code_verifier: b64url(randomBytes(32)),
    },
    'demo-app:demo-secret',
  );

  const c3 = await authorize();
  await post(
    '(D) 유효 code + code_verifier 누락',
    { grant_type: 'authorization_code', code: c3, redirect_uri: REDIRECT },
    'demo-app:demo-secret',
  );

  const c4 = await authorize();
  await post(
    '(E) 유효 code + redirect_uri 불일치',
    {
      grant_type: 'authorization_code',
      code: c4,
      redirect_uri: 'http://localhost:8080/other',
      code_verifier: verifier,
    },
    'demo-app:demo-secret',
  );

  await post(
    '(F) 잘못된 secret',
    { grant_type: 'authorization_code', code: 'x', redirect_uri: REDIRECT },
    'demo-app:totally-wrong',
  );
  await post(
    '(G) 존재하지 않는 client_id',
    { grant_type: 'authorization_code', code: 'x', redirect_uri: REDIRECT },
    'no-such-client:whatever',
  );
  await post(
    '(H) 미등록 scope(client_credentials)',
    { grant_type: 'client_credentials', scope: 'openid meal.redeem' },
    'demo-app:demo-secret',
  );
  await post(
    '(I) 공개 클라이언트 + Basic(secret 값 있음)',
    { grant_type: 'authorization_code', code: 'x', redirect_uri: 'http://localhost:3000/cb' },
    'spa-1785681470364:some-secret',
  );
  await post(
    '(J) 공개 클라이언트 + Basic(빈 secret)',
    { grant_type: 'authorization_code', code: 'x', redirect_uri: 'http://localhost:3000/cb' },
    'spa-1785681470364:',
  );
  await post('(K) grant_type 누락', { code: 'x' }, 'demo-app:demo-secret');
  await post(
    '(L) 지원하지 않는 grant_type',
    { grant_type: 'password', username: 'a', password: 'b' },
    'demo-app:demo-secret',
  );
});
