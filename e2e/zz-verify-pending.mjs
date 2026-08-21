import { chromium, request as pwRequest } from '@playwright/test';

const WEB = 'http://localhost:3000';
const MAILPIT = 'http://localhost:8025/api/v1';
const PASSWORD = 'SecureP@ssw0rd123';
const ADMIN = process.env.ADMIN_EMAIL;

async function latestCode(request, email) {
  for (let i = 0; i < 40; i++) {
    const list = await (await request.get(`${MAILPIT}/messages`)).json();
    const msg = (list.messages ?? []).find((m) => (m.To ?? []).some((t) => t.Address === email));
    if (msg) {
      const detail = await (await request.get(`${MAILPIT}/message/${msg.ID}`)).json();
      const match = ((detail.Text ?? '')).match(/\b\d{6}\b/);
      if (match) return match[0];
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`no mail for ${email}`);
}

async function signup(page, request, email) {
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

async function login(page, email) {
  await page.context().clearCookies();
  await page.goto(`${WEB}/login`);
  await page.fill('#email', email);
  await page.click('form[action="/login/identifier"] button[type="submit"]');
  await page.waitForURL(/\/login\/password/, { timeout: 20000 });
  await page.fill('#password', PASSWORD);
  await page.click('form[action="/login/password"] button[type="submit"]');
  await page.waitForLoadState('networkidle');
}

async function dump(page, label) {
  const main = page.locator('main');
  const text = (await main.count()) > 0 ? await main.innerText() : await page.locator('body').innerText();
  console.log(`\n${'='.repeat(72)}\n### ${label}\nURL: ${page.url()}\n${'-'.repeat(72)}\n${text}\n`);
  return text;
}

const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ locale: 'ko-KR', viewport: { width: 1280, height: 900 } });
const page = await context.newPage();
const request = await pwRequest.newContext();
page.on('console', (m) => { if (m.type() === 'error') console.log(`[console.error] ${m.text()}`); });

const stamp = Date.now();
const owner = `vp-owner-${stamp}@example.com`;
const name = `검증 분식 ${stamp}`;

// 0) 사장 계정 준비
await signup(page, request, owner);
console.log('사장 계정 준비 완료:', owner);

// 1) 플랫폼 관리자로 가맹점 등록 — 모달 기본값을 손대지 않는다
await login(page, ADMIN);
await page.goto(`${WEB}/admin/merchants`);
await page.waitForLoadState('networkidle');
await page.getByRole('button', { name: '가맹점 등록' }).click();
await page.waitForTimeout(500);
// 모달의 상태 필드가 무엇으로 보이는가
const modalText = await page.locator('[role="dialog"]').innerText().catch(() => '(dialog not found)');
console.log('\n[등록 모달 본문]\n' + modalText);
await page.locator('[role="dialog"] input').first().fill(name);
await page.getByRole('button', { name: '저장' }).click();
await page.waitForTimeout(1500);
const toast = await page.locator('[data-sonner-toast], [role="status"]').allInnerTexts().catch(() => []);
console.log('\n[등록 직후 토스트]', JSON.stringify(toast));

// 방금 만든 가맹 id
const list = await (await page.request.get(`${WEB}/api/admin/merchants`)).json();
const created = list.find((m) => m.name === name);
console.log('\n[생성 결과]', JSON.stringify(created));

// 2) 담당자 지정 — UI 로
await page.reload();
await page.waitForLoadState('networkidle');
const row = page.locator('tr', { hasText: name });
await row.getByRole('button', { name: '담당자' }).click();
await page.waitForTimeout(600);
const memberModal = await page.locator('[role="dialog"]').innerText();
console.log('\n[담당자 모달 본문 — 상태 관련 안내가 있는가]\n' + memberModal);
await page.locator('[role="dialog"] input').last().fill(owner);
await page.getByRole('button', { name: '추가', exact: true }).click();
await page.waitForTimeout(1500);
const afterAdd = await page.locator('[role="dialog"]').innerText();
console.log('\n[담당자 추가 후 모달]\n' + afterAdd);
const toast2 = await page.locator('[data-sonner-toast], [role="status"]').allInnerTexts().catch(() => []);
console.log('[담당자 추가 토스트]', JSON.stringify(toast2));
await page.screenshot({ path: 'zz-shots/admin-after-assign.png', fullPage: true });

// 3) 사장으로 로그인
await login(page, owner);
await page.waitForTimeout(1500);
console.log('\n[헤더]\n' + (await page.locator('header').innerText()));
await page.screenshot({ path: 'zz-shots/owner-home.png', fullPage: true });

console.log('\n[/api/merchant-console/mine] =', await (await page.request.get(`${WEB}/api/merchant-console/mine`)).text());

await page.goto(`${WEB}/merchant`);
await page.waitForLoadState('networkidle');
await page.waitForTimeout(1800);
await dump(page, '/merchant (PENDING 매장만 보유)');
await page.screenshot({ path: 'zz-shots/owner-merchant.png', fullPage: true });

await page.goto(`${WEB}/merchant/${created.id}`);
await page.waitForLoadState('networkidle');
await page.waitForTimeout(2200);
await dump(page, `/merchant/${created.id} 직접 진입`);
await page.screenshot({ path: 'zz-shots/owner-merchant-direct.png', fullPage: true });

// 메일 확인 — 담당자 지정 알림이 있는가
const mails = await (await request.get(`${MAILPIT}/search?query=${encodeURIComponent(`to:${owner}`)}`)).json();
console.log('\n[사장 메일함]', (mails.messages ?? []).map((m) => m.Subject).join(' | ') || '(없음)');

console.log('\nCREATED_ID=' + created.id);
await browser.close();
