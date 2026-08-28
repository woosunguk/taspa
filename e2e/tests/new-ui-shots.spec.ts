import { expect, test, type Page } from '@playwright/test';
import path from 'node:path';

/**
 * 방금 바꾼 화면들을 **데이터가 있는 데모 조직/매장**으로 캡처한다(리뷰용, CI 대상 아님).
 *
 * `ui-audit.spec.ts` 는 자체 계정·조직을 새로 만들어 돌기 때문에 실적이 없는 빈 화면만 잡힌다 —
 * 그래프·잔반 절감 패널처럼 **데이터가 있어야 그려지는 것**을 검증할 수 없다. 그래서 데모 계정으로 붙는다.
 */
const WEB = process.env.WEB_BASE ?? 'http://localhost:3000';
const OUT = process.env.SHOT_OUT ?? path.join(process.cwd(), 'new-ui');
const PASSWORD = process.env.DEMO_PASSWORD ?? '1111';
const ORG = 'd0000000-0000-4000-8000-000000000001';
const MERCHANT = '6dc75701-36b5-4a3f-8ee5-5b6d2484685f';

async function login(page: Page, email: string) {
  await page.goto(`${WEB}/login`);
  await page.fill('#email', email);
  await page.click('button[type="submit"]');
  await expect(page).toHaveURL(/\/login\/password/);
  await page.fill('#password', PASSWORD);
  await page.click('button[type="submit"]');
  await expect(page).not.toHaveURL(/\/login/);
}

async function capture(page: Page, url: string, slug: string, errors: string[]) {
  await page.goto(url);
  // 클라이언트 렌더 + API 왕복을 기다린다. 로딩 표시가 사라지는 것만으로는 부족하다(아직 시작 전일 수 있다).
  await page.waitForLoadState('networkidle');
  for (const viewport of [
    { name: 'desktop', width: 1440, height: 1000 },
    { name: 'mobile', width: 390, height: 900 },
  ]) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await page.waitForTimeout(400);
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
    );
    if (overflow && viewport.name === 'mobile') errors.push(`${slug}: 모바일 가로 넘침`);
    await page.screenshot({
      path: path.join(OUT, `${viewport.name}-${slug}.png`),
      fullPage: true,
    });
  }
}

test('변경 화면 캡처 — 가맹(식수예측·밀로그)', async ({ page }) => {
  test.setTimeout(180_000);
  const errors: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(`console: ${m.text().slice(0, 160)}`);
  });

  await login(page, 'merchant@taspa.example');
  await capture(page, `${WEB}/merchant/${MERCHANT}`, 'merchant-forecast', errors);
  await capture(page, `${WEB}/merchant/${MERCHANT}/transactions`, 'merchant-meallog', errors);
  await capture(page, `${WEB}/merchant/${MERCHANT}/settlement`, 'merchant-settlement', errors);

  console.log('DEFECTS(merchant)=' + JSON.stringify(errors, null, 1));
  expect(errors, errors.join('\n')).toEqual([]);
});

test('변경 화면 캡처 — 조직(구성원·식사정책)', async ({ page }) => {
  test.setTimeout(180_000);
  const errors: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(`console: ${m.text().slice(0, 160)}`);
  });

  await login(page, 'orgadmin@taspa.example');
  await capture(page, `${WEB}/console/${ORG}/members`, 'org-members', errors);
  await capture(page, `${WEB}/console/${ORG}/meal-policy`, 'org-meal-policy', errors);
  await capture(page, `${WEB}/console/${ORG}/forecast`, 'org-forecast', errors);

  console.log('DEFECTS(org)=' + JSON.stringify(errors, null, 1));
  expect(errors, errors.join('\n')).toEqual([]);
});
