import { test, expect, APIRequestContext } from '@playwright/test';
import { execFileSync } from 'child_process';

/**
 * 조사 전용 프로브 — IAM 자기 Deny 락아웃 상태에서 화면이 무슨 문구를 말하는지 실측.
 * 제품 코드는 건드리지 않는다. 만든 행만 정리한다.
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

test('IAM 자기 Deny 락아웃 화면 문구 실측', async ({ page, request }) => {
  test.setTimeout(300_000);
  const email = `probe-iamlock-${Date.now()}@example.com`;

  // --- 가입 + 이메일 인증
  await page.goto(`${WEB}/signup`);
  await page.fill('#email', email);
  await page.fill('#password', PASSWORD);
  await page.click('button[type="submit"]');
  await expect(page).toHaveURL(/\/login\/verify-email/);
  await page.fill('#code', await code(request, email));
  await page.click('form[action="/login/verify-email"] button[type="submit"]');

  const userId = sql(`SELECT id FROM users WHERE email = '${email}'`);
  console.log('USERID', userId);

  // ============ 케이스 A: 아직 일반 사용자인 상태에서 /admin/iam ============
  await page.goto(`${WEB}/admin/iam`);
  await page.waitForTimeout(2500);
  console.log('--- CASE A (비관리자, 화면) ---');
  console.log((await page.locator('body').innerText()).slice(0, 1200));

  // 비관리자가 /api/admin/iam/policies 를 직접 호출하면?
  const rawA = await page.evaluate(async () => {
    const r = await fetch('/api/admin/iam/policies', { headers: { Accept: 'application/json' } });
    return { status: r.status, ct: r.headers.get('content-type'), body: (await r.text()).slice(0, 400) };
  });
  console.log('CASE A raw:', JSON.stringify(rawA));

  // ============ 케이스 B: DB 로 승격했지만 세션은 옛 것 (재로그인 전) ============
  sql(`UPDATE users SET role = 'ADMIN' WHERE id = '${userId}'`);
  await page.goto(`${WEB}/admin/iam`);
  await page.waitForTimeout(2500);
  console.log('--- CASE B (DB=ADMIN, 세션 stale, 화면) ---');
  console.log((await page.locator('body').innerText()).slice(0, 1500));
  const rawB = await page.evaluate(async () => {
    const r = await fetch('/api/admin/iam/policies', { headers: { Accept: 'application/json' } });
    return { status: r.status, ct: r.headers.get('content-type'), body: (await r.text()).slice(0, 400) };
  });
  console.log('CASE B raw:', JSON.stringify(rawB));
  const rawBpost = await page.evaluate(async () => {
    const t = await (await fetch('/api/csrf')).json();
    const r = await fetch('/api/admin/iam/policies', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', [t.headerName]: t.token },
      body: JSON.stringify({ name: 'probe-x', description: '', document: '{"Version":"2012-10-17","Statement":[]}' }),
    });
    return { status: r.status, body: (await r.text()).slice(0, 400) };
  });
  console.log('CASE B POST raw:', JSON.stringify(rawBpost));

  // ============ 재로그인 → 진짜 플랫폼 관리자 ============
  await page.context().clearCookies();
  await page.goto(`${WEB}/login`);
  await page.fill('#email', email);
  await page.click('form[action="/login/identifier"] button[type="submit"]');
  await expect(page).toHaveURL(/\/login\/password/);
  await page.fill('#password', PASSWORD);
  await page.click('form[action="/login/password"] button[type="submit"]');
  await page.waitForURL(/\/(account|meal|admin|$)/, { timeout: 20000 });

  await page.goto(`${WEB}/admin/iam`);
  await page.waitForTimeout(2500);
  console.log('--- BASELINE (관리자, 정상) ---');
  console.log((await page.locator('body').innerText()).slice(0, 1500));

  // ============ 케이스 C: 자기 자신에게 iam:* Deny 부착 ============
  const created = await page.evaluate(async () => {
    const t = await (await fetch('/api/csrf')).json();
    const doc = JSON.stringify({
      Version: '2012-10-17',
      Statement: [{ Sid: 'probeDeny', Effect: 'Deny', Action: ['iam:*'], Resource: ['*'] }],
    });
    const r = await fetch('/api/admin/iam/policies', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', [t.headerName]: t.token },
      body: JSON.stringify({ name: `probe-selfdeny-${Date.now()}`, description: '조사용', document: doc }),
    });
    return { status: r.status, body: await r.text() };
  });
  console.log('CREATE policy:', created.status, created.body.slice(0, 300));
  const policyId = JSON.parse(created.body).id;

  const attached = await page.evaluate(async ([pid, uid]) => {
    const t = await (await fetch('/api/csrf')).json();
    const r = await fetch('/api/admin/iam/attachments', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', [t.headerName]: t.token },
      body: JSON.stringify({ policyId: pid, principalType: 'USER', principalId: uid }),
    });
    return { status: r.status, body: (await r.text()).slice(0, 400) };
  }, [policyId, userId]);
  console.log('ATTACH:', JSON.stringify(attached));

  try {
    // 락아웃 상태 화면
    await page.goto(`${WEB}/admin/iam`);
    await page.waitForTimeout(3000);
    console.log('--- CASE C (자기 Deny, /admin/iam 화면 전문) ---');
    console.log(await page.locator('body').innerText());

    const rawC = await page.evaluate(async () => {
      const r = await fetch('/api/admin/iam/policies', { headers: { Accept: 'application/json' } });
      return { status: r.status, body: (await r.text()).slice(0, 400) };
    });
    console.log('CASE C GET raw:', JSON.stringify(rawC));

    // 정책 만들기 → 저장 시도 (mutation 경로 문구)
    await page.getByRole('button', { name: '정책 만들기' }).click();
    await page.waitForTimeout(800);
    const dlg = await page.locator('body').innerText();
    console.log('--- CASE C 정책 만들기 다이얼로그 ---');
    console.log(dlg.slice(0, 2500));

    // 폼 채우고 저장
    const nameInput = page.locator('input').first();
    await nameInput.fill(`probe-attempt-${Date.now()}`);
    const save = page.getByRole('button', { name: /만들기|저장/ }).last();
    await save.click();
    await page.waitForTimeout(2500);
    console.log('--- CASE C 저장 시도 후 (토스트/오류) ---');
    console.log(await page.locator('body').innerText());

    // 다른 관리 화면들은 어떤가
    for (const route of ['/admin', '/admin/orgs', '/admin/users']) {
      await page.goto(`${WEB}${route}`);
      await page.waitForTimeout(2000);
      const txt = (await page.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, 400);
      console.log(`--- CASE C ${route}: ${txt}`);
    }
  } finally {
    // 정리 — 내가 만든 행만 삭제
    sql(`DELETE FROM iam_policy_attachments WHERE policy_id = '${policyId}'`);
    sql(`DELETE FROM iam_policies WHERE id = '${policyId}'`);
    console.log('CLEANED policy', policyId);
    console.log('remaining policies:', sql(`SELECT count(*) FROM iam_policies`));
    console.log('remaining attachments:', sql(`SELECT count(*) FROM iam_policy_attachments`));
  }
});
