import { execFileSync } from 'child_process';
import { chromium, Browser, Page, request as pwRequest, APIRequestContext } from '@playwright/test';

export const WEB = process.env.WEB_BASE ?? 'http://localhost:3000';
export const API = process.env.API_BASE ?? 'http://localhost:9100';
export const MAILPIT = 'http://localhost:8025/api/v1';
export const PASSWORD = 'SecureP@ssw0rd123';

export function sql(statement: string): string {
  return execFileSync(
    'docker',
    ['exec', 'taspa-postgres-1', 'psql', '-U', 'taspa', '-d', 'taspa', '-tAc', statement],
    { encoding: 'utf-8' },
  ).trim();
}

export async function latestCode(request: APIRequestContext, email: string): Promise<string> {
  for (let i = 0; i < 30; i++) {
    const list = await (await request.get(`${MAILPIT}/messages`)).json();
    const msg = (list.messages ?? []).find((m: any) => (m.To ?? []).some((t: any) => t.Address === email));
    if (msg) {
      const detail = await (await request.get(`${MAILPIT}/message/${msg.ID}`)).json();
      const match = ((detail.Text ?? '') as string).match(/\b\d{6}\b/);
      if (match) return match[0];
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`no mail for ${email}`);
}

export async function mailsFor(request: APIRequestContext, email: string): Promise<any[]> {
  const res = await request.get(`${MAILPIT}/search?query=${encodeURIComponent(`to:${email}`)}`);
  const body = await res.json();
  return body.messages ?? [];
}

export async function signup(page: Page, request: APIRequestContext, email: string) {
  await page.context().clearCookies();
  await page.goto(`${WEB}/signup`);
  await page.fill('#email', email);
  await page.fill('#password', PASSWORD);
  await page.click('button[type="submit"]');
  await page.waitForURL(/\/login\/verify-email/, { timeout: 20000 });
  await page.fill('#code', await latestCode(request, email));
  await page.click('form[action="/login/verify-email"] button[type="submit"]');
  await page.waitForLoadState('networkidle');
}

export async function login(page: Page, email: string) {
  await page.context().clearCookies();
  await page.goto(`${WEB}/login`);
  await page.fill('#email', email);
  await page.click('form[action="/login/identifier"] button[type="submit"]');
  await page.waitForURL(/\/login\/password/, { timeout: 20000 });
  await page.fill('#password', PASSWORD);
  await page.click('form[action="/login/password"] button[type="submit"]');
  await page.waitForLoadState('networkidle');
}

export async function csrf(page: Page): Promise<string> {
  const res = await page.request.get(`${WEB}/api/csrf`);
  return (await res.json()).token;
}

export async function openBrowser(): Promise<{ browser: Browser; page: Page; request: APIRequestContext }> {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ locale: 'ko-KR', viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();
  const request = await pwRequest.newContext();
  return { browser, page, request };
}

/** 화면 본문 텍스트를 그대로 덤프한다 — "무엇을 말하는가"를 보려는 것이므로 가공하지 않는다. */
export async function dump(page: Page, label: string) {
  const main = page.locator('main');
  const text = (await main.count()) > 0 ? await main.innerText() : await page.locator('body').innerText();
  console.log(`\n${'='.repeat(70)}\n### ${label}\nURL: ${page.url()}\n${'-'.repeat(70)}\n${text}\n`);
  return text;
}
