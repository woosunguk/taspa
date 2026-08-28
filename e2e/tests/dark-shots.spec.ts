import { expect, test, type Page } from '@playwright/test';
import path from 'node:path';

/** 다크 모드 캡처 — 라이트만 보고 팔레트를 고치면 다크에서만 깨진 것을 못 본다. */
const WEB = process.env.WEB_BASE ?? 'http://localhost:3000';
const OUT = process.env.SHOT_OUT ?? path.join(process.cwd(), 'dark-ui');
const ORG = 'd0000000-0000-4000-8000-000000000001';
const MERCHANT = '6dc75701-36b5-4a3f-8ee5-5b6d2484685f';

test.use({ colorScheme: 'dark', viewport: { width: 1440, height: 1000 } });

async function login(page: Page, email: string) {
  await page.goto(`${WEB}/login`);
  await page.fill('#email', email);
  await page.click('button[type="submit"]');
  await expect(page).toHaveURL(/\/login\/password/);
  await page.fill('#password', '1111');
  await page.click('button[type="submit"]');
  await expect(page).not.toHaveURL(/\/login/);
}

test('다크 모드 — 가맹·조직·식권', async ({ page }) => {
  test.setTimeout(180_000);
  await login(page, 'merchant@taspa.example');
  for (const [slug, url] of [
    ['merchant-forecast', `${WEB}/merchant/${MERCHANT}`],
    ['merchant-meallog', `${WEB}/merchant/${MERCHANT}/transactions`],
  ] as const) {
    await page.goto(url);
    await page.waitForLoadState('networkidle');
    await page.screenshot({ path: path.join(OUT, `${slug}.png`), fullPage: true });
  }
  await page.context().clearCookies();
  await login(page, 'orgadmin@taspa.example');
  await page.goto(`${WEB}/console/${ORG}/members`);
  await page.waitForLoadState('networkidle');
  await page.screenshot({ path: path.join(OUT, 'org-members.png'), fullPage: true });
});
