import { test, expect, Page, APIRequestContext } from '@playwright/test';

/**
 * 조사용 프로브(제품 코드 아님). 주장 검증:
 *   "TASPA_PUBLIC_BASE_URL 을 어느 오리진으로 잡아도 초대 링크 또는 청구서 초안 링크 하나는 404"
 *
 * 확인 두 축:
 *  A) 웹(SPA) 오리진에서 서버 소유 페이지 경로들(/orgs/invite/accept, /activate, /activated) → 404?
 *  B) 서버 오리진에서 SPA 전용 경로(/console/{orgId}/invoices) → **인증된 상태로** 404?
 */

const WEB_BASE = 'http://localhost:3000';
const SRV_BASE = 'http://localhost:9100';
const MAILPIT_API = 'http://localhost:8025/api/v1';
const PASSWORD = 'SecureP@ssw0rd123';

async function code(request: APIRequestContext, email: string): Promise<string> {
  for (let i = 0; i < 40; i++) {
    const list = await (await request.get(`${MAILPIT_API}/messages?limit=200`)).json();
    const m = (list.messages ?? []).find((x: any) =>
      (x.To ?? []).some((t: any) => t.Address === email),
    );
    if (m) {
      const d = await (await request.get(`${MAILPIT_API}/message/${m.ID}`)).json();
      const match = ((d.Text ?? '') as string).match(/\b\d{6}\b/);
      if (match) return match[0];
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error('no mail');
}

async function signup(page: Page, request: APIRequestContext, email: string) {
  await page.goto(`${SRV_BASE}/signup`);
  await page.fill('#email', email);
  await page.fill('#password', PASSWORD);
  await page.click('button[type="submit"]');
  await expect(page).toHaveURL(/\/login\/verify-email/);
  await page.fill('#code', await code(request, email));
  await page.click('form[action="/login/verify-email"] button[type="submit"]');
}

test('probe: 두 오리진에서 상대편 소유 경로를 연다', async ({ page, request }) => {
  test.setTimeout(180_000);

  // ── A) 웹 오리진: 서버 소유 페이지 경로 ────────────────────────────────
  for (const p of [
    '/orgs/invite/accept?token=abc',
    '/activate',
    '/activated',
    '/password-reset', // 대조군 — 프록시 목록에 있는 서버 경로
  ]) {
    const res = await page.goto(`${WEB_BASE}${p}`);
    const body = (await page.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, 160);
    console.log(`[WEB ] ${p} → ${res?.status()} | ${body}`);
  }

  // ── B) 서버 오리진: SPA 전용 경로 (인증 상태로) ─────────────────────────
  const email = `origin-probe-${Date.now()}@example.com`;
  await signup(page, request, email);
  console.log('[AUTH] after signup url =', page.url());

  for (const p of [
    '/console/11111111-1111-1111-1111-111111111111/invoices',
    '/console/orgs', // 대조군 — 서버가 실제로 가진 콘솔 페이지
    '/account',
  ]) {
    const res = await page.goto(`${SRV_BASE}${p}`);
    const body = (await page.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, 200);
    console.log(`[SRV ] ${p} → ${res?.status()} (${page.url()}) | ${body}`);
  }

  expect(true).toBe(true);
});
