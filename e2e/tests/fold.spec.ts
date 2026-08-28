import { expect, test, type Page } from '@playwright/test';
import path from 'node:path';

/**
 * **fold 회귀** — 표가 첫 화면(스크롤 없이 보이는 영역)에 들어오는지 픽셀로 단언한다.
 *
 * 왜 필요한가: 밀로그에서 KPI 한 줄이 접히고 차트가 표 위에 있었더니 목적물(raw 표)이 첫 화면 밖으로
 * 밀렸다. 눈으로는 "좀 아래인가?" 정도로만 보여 그대로 남아 있었다 — 위쪽에 무엇을 더할 때마다
 * 조용히 다시 밀리는 종류라 숫자로 고정한다.
 */
const WEB = process.env.WEB_BASE ?? 'http://localhost:3000';
const MERCHANT = '6dc75701-36b5-4a3f-8ee5-5b6d2484685f';
const ORG = 'd0000000-0000-4000-8000-000000000001';
const FOLD = 900;

test.use({ viewport: { width: 1440, height: FOLD } });

async function login(page: Page, email: string) {
  await page.goto(`${WEB}/login`);
  await page.fill('#email', email);
  await page.click('button[type="submit"]');
  await page.fill('#password', '1111');
  await page.click('button[type="submit"]');
  await expect(page).not.toHaveURL(/\/login/);
}

/** 표가 있는 화면들 — 그 표가 이 화면에 온 이유이므로 첫 화면에 있어야 한다. */
const SCREENS: { label: string; email: string; path: string }[] = [
  { label: '밀로그', email: 'merchant@taspa.example', path: `/merchant/${MERCHANT}/transactions` },
  { label: '가맹 정산', email: 'merchant@taspa.example', path: `/merchant/${MERCHANT}/settlement` },
  { label: '조직 구성원', email: 'orgadmin@taspa.example', path: `/console/${ORG}/members` },
  { label: '조직 청구서', email: 'orgadmin@taspa.example', path: `/console/${ORG}/invoices` },
  { label: '조직 활동로그', email: 'orgadmin@taspa.example', path: `/console/${ORG}/audit` },
  { label: '관리 사용자', email: 'admin@taspa.example', path: '/admin/users' },
  { label: '관리 가맹점', email: 'admin@taspa.example', path: '/admin/merchants' },
];

test('표가 있는 화면은 표가 첫 화면에 들어온다', async ({ page }) => {
  test.setTimeout(180_000);
  const results: string[] = [];
  const tooLow: string[] = [];
  let current = '';

  for (const screen of SCREENS) {
    if (current !== screen.email) {
      await page.context().clearCookies();
      await login(page, screen.email);
      current = screen.email;
    }
    await page.goto(`${WEB}${screen.path}`);
    await page.waitForLoadState('networkidle');
    const table = page.locator('table thead').first();
    const box = (await table.count()) > 0 ? await table.boundingBox() : null;
    const y = box === null ? null : Math.round(box.y);
    results.push(`  ${screen.label.padEnd(12)} ${y === null ? '표 없음(빈 상태)' : `y=${y}`}`);
    if (y !== null && y >= FOLD) tooLow.push(`${screen.label} (y=${y})`);
    await page.screenshot({
      path: path.join(process.cwd(), 'fold', `${screen.path.replace(/[^a-z]+/gi, '-')}.png`),
    });
  }

  console.log(`FOLD=${FOLD}\n${results.join('\n')}`);
  expect(tooLow, `첫 화면 밖으로 밀린 표: ${tooLow.join(', ')}`).toEqual([]);
});
