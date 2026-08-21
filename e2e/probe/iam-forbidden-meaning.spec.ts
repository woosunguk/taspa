import { test, expect, APIRequestContext } from '@playwright/test';
import { execFileSync } from 'child_process';

/**
 * 조사 전용 — /api/admin/** 에서 errorCode FORBIDDEN 이 "플랫폼 관리자가 아니다"를 뜻할 수 있는가?
 * 케이스 A: 일반 사용자, 케이스 B: DB 승격했으나 세션 stale.
 */

const WEB = 'http://localhost:3000';
const MAILPIT = 'http://localhost:8025/api/v1';
const PASSWORD = 'SecureP@ssw0rd123';

function sql(statement: string): string {
  return execFileSync(
    'docker',
    ['exec', 'taspa-postgres-1', 'psql', '-U', 'taspa', '-d', 'taspa', '-tAc', statement],
    { encoding: 'utf-8' },
  ).trim();
}

async function code(request: APIRequestContext, email: string): Promise<string> {
  for (let i = 0; i < 30; i++) {
    const list = await (await request.get(`${MAILPIT}/messages`)).json();
    const m = (list.messages ?? []).find((x: any) => (x.To ?? []).some((t: any) => t.Address === email));
    if (m) {
      const d = await (await request.get(`${MAILPIT}/message/${m.ID}`)).json();
      const hit = ((d.Text ?? '') as string).match(/\b\d{6}\b/);
      if (hit) return hit[0];
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error('mail not found');
}

test('FORBIDDEN 의 의미 — 비관리자 / stale 세션', async ({ page, request }) => {
  test.setTimeout(300_000);
  const email = `probe-fmean-${Date.now()}@example.com`;

  await page.goto(`${WEB}/signup`);
  await page.fill('#email', email);
  await page.fill('#password', PASSWORD);
  await page.click('button[type="submit"]');
  await expect(page).toHaveURL(/\/login\/verify-email/);
  await page.fill('#code', await code(request, email));
  await page.click('form[action="/login/verify-email"] button[type="submit"]');

  const userId = sql(`SELECT id FROM users WHERE email = '${email}'`);

  const probe = async (label: string) => {
    await page.goto(`${WEB}/admin/iam`);
    await page.waitForTimeout(2000);
    const screen = (await page.locator('body').innerText()).replace(/\s+/g, ' ');
    console.log(`### ${label} SCREEN >>> ${screen.slice(0, 700)}`);
    const get = await page.evaluate(async () => {
      const r = await fetch('/api/admin/iam/policies', { headers: { Accept: 'application/json' } });
      return { status: r.status, ct: r.headers.get('content-type'), body: (await r.text()).slice(0, 300) };
    });
    console.log(`### ${label} GET >>> ${JSON.stringify(get)}`);
    const post = await page.evaluate(async () => {
      const t = await (await fetch('/api/csrf')).json().catch(() => null);
      const h: any = { 'Content-Type': 'application/json' };
      if (t) h[t.headerName] = t.token;
      const r = await fetch('/api/admin/iam/policies', {
        method: 'POST',
        headers: h,
        body: JSON.stringify({ name: 'probe-x', description: '', document: '{"Version":"2012-10-17","Statement":[]}' }),
      });
      return { status: r.status, body: (await r.text()).slice(0, 300) };
    });
    console.log(`### ${label} POST >>> ${JSON.stringify(post)}`);
  };

  await probe('CASE-A(일반사용자)');

  sql(`UPDATE users SET role = 'ADMIN' WHERE id = '${userId}'`);
  await probe('CASE-B(DB=ADMIN, 세션 stale)');

  console.log('### cleanup: user left as ADMIN (dev data)');
});
