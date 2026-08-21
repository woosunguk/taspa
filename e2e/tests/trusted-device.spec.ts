import { test, expect, Page, APIRequestContext } from '@playwright/test';
import { createHmac } from 'crypto';

// 신뢰 기기(MFA 30일 스킵) e2e.
// 실행 전제: taspa 서버(9100) + PostgreSQL(5433) + Mailpit(1025/8025) 이 떠 있어야 한다.
//   docker compose up -d postgres mailpit
//   ./gradlew :server:bootRun

const MAILPIT_API = 'http://localhost:8025/api/v1';
const PASSWORD = 'SecureP@ssw0rd123';

function uniqueEmail(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 10000)}@example.com`;
}

// ---- TOTP (RFC 6238, SHA1/30s/6자리) — 화면의 수동 입력 키(base32)로 코드를 계산한다. ----

function base32Decode(input: string): Buffer {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = 0;
  let value = 0;
  const out: number[] = [];
  for (const ch of input.replace(/=+$/, '').toUpperCase()) {
    const idx = alphabet.indexOf(ch);
    if (idx < 0) continue;
    value = (value << 5) | idx;
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(out);
}

function totp(secret: string): string {
  const counter = Math.floor(Date.now() / 1000 / 30);
  const buf = Buffer.alloc(8);
  buf.writeBigUInt64BE(BigInt(counter));
  const hmac = createHmac('sha1', base32Decode(secret)).update(buf).digest();
  const offset = hmac[hmac.length - 1] & 0x0f;
  const code =
    ((hmac[offset] & 0x7f) << 24) |
    (hmac[offset + 1] << 16) |
    (hmac[offset + 2] << 8) |
    hmac[offset + 3];
  return String(code % 1_000_000).padStart(6, '0');
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

/** /account 에서 MFA 를 설정하고 수동 입력 키(base32 시크릿)를 반환한다. */
async function enableMfa(page: Page): Promise<string> {
  await page.click('#mfa-off > button.btn-primary');
  await expect(page.locator('#setup-panel')).toBeVisible();
  const secret = (await page.locator('#secret').textContent())!.trim();
  await page.fill('#activate-code', totp(secret));
  await page.click('#setup-panel .btn-primary');
  await expect(page.locator('#backup-panel')).toBeVisible();
  await page.click('#backup-panel a.btn-primary');
  await expect(page).toHaveURL(/\/account/);
  return secret;
}

async function logout(page: Page): Promise<void> {
  await page.click('form[action="/logout"] button[type="submit"]');
  await expect(page).toHaveURL(/\/login/);
}

async function loginToPassword(page: Page, email: string): Promise<void> {
  await page.goto('/login');
  await page.fill('#email', email);
  await page.click('form[action="/login/identifier"] button[type="submit"]');
  await expect(page).toHaveURL(/\/login\/password/);
  await page.fill('#password', PASSWORD);
  await page.click('form[action="/login/password"] button[type="submit"]');
}

test.describe('trusted device', () => {
  test('가입 → MFA 설정 → 재로그인(30일 체크) → 다음 로그인은 MFA 스킵', async ({ page, request }) => {
    const email = uniqueEmail('trusted-device');

    await signupAndVerify(page, request, email);
    const secret = await enableMfa(page);
    await logout(page);

    // 1) MFA 게이트 + "이 기기에서 30일 동안 묻지 않음" 체크.
    await loginToPassword(page, email);
    await expect(page).toHaveURL(/\/login\/mfa/);
    await page.check('#trustDevice');
    await page.fill('#code', totp(secret));
    await page.click('form[action="/login/mfa"] button[type="submit"]');
    await expect(page).toHaveURL(/\/account/);

    // 계정 페이지에 신뢰 기기가 표시된다.
    await expect(page.locator('main')).toContainText('신뢰하는 기기');
    await logout(page);

    // 2) 같은 브라우저 재로그인 → MFA 화면 없이 바로 /account (신뢰 기기 쿠키).
    await loginToPassword(page, email);
    await expect(page).toHaveURL(/\/account/);
  });
});
