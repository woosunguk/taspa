import { test, expect } from '@playwright/test';
import { sql, signup, login, WEB } from './lib';

/**
 * 주장 검증: /admin/merchants 담당자 추가 실패 시 서버의 구체 사유가 화면에서 사라지는가?
 *  - 서버 원문(raw fetch)과 렌더된 화면 텍스트를 나란히 찍어 비교한다.
 */
test('probe: merchant member add — server message vs rendered text', async ({ page, request }) => {
  test.setTimeout(240_000);
  const email = `probe-adminval-${Date.now()}@example.com`;

  await signup(page, request, email);
  const userId = sql(`SELECT id FROM users WHERE email = '${email}'`);
  sql(`UPDATE users SET role = 'ADMIN' WHERE id = '${userId}'`);
  // 역할은 로그인 시점에 세션에 굳는다 → 재로그인 필수
  await login(page, email);

  const merchantId = sql(`SELECT id FROM merchants WHERE status='ACTIVE' ORDER BY created_at LIMIT 1`);
  const missing = `nobody-typo-${Date.now()}@example.com`;

  // ── 1) 서버 원문 ──────────────────────────────────────────────────────────
  const raw = await page.evaluate(
    async ({ merchantId, missing }) => {
      const csrf = await (await fetch('/api/csrf', { credentials: 'same-origin' })).json();
      const res = await fetch(`/api/admin/merchants/${merchantId}/members`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify({ email: missing }),
      });
      return { status: res.status, body: await res.text() };
    },
    { merchantId, missing },
  );
  console.log('PROBE_RAW_SERVER', JSON.stringify(raw));

  // ── 2) 실제 화면 ──────────────────────────────────────────────────────────
  await page.goto(`${WEB}/admin/merchants`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  const row = page.locator('tr').filter({ has: page.getByRole('button', { name: '담당자' }) }).first();
  await row.scrollIntoViewIfNeeded();
  await row.getByRole('button', { name: '담당자' }).click();
  await page.waitForTimeout(1500);

  const dialog = page.getByRole('dialog').first();
  await dialog.getByLabel('이메일로 추가').fill(missing);
  await dialog.getByRole('button', { name: '추가' }).click();
  await page.waitForTimeout(3000);

  const dialogText = await dialog.innerText();
  console.log('PROBE_DIALOG_TEXT', JSON.stringify(dialogText));
  console.log('PROBE_HAS_GENERIC', dialogText.includes('입력값을 확인하세요'));
  console.log('PROBE_HAS_SERVER_REASON', dialogText.includes('해당 이메일의 사용자를 찾을 수 없습니다'));
  await page.screenshot({ path: 'shots/probe-adminval.png', fullPage: true });
});
