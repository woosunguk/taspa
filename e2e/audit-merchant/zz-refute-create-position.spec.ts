import { test } from '@playwright/test';
import { login, WEB } from './lib';

const ADMIN = 'probe-adminval-1785683876803@example.com';

test('등록 직후 새 행이 보이는가 / 스크롤·강조가 있는가', async ({ page }) => {
  await login(page, ADMIN);
  await page.goto(`${WEB}/admin/merchants`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1200);

  const NAME = `반증-검색-${Date.now()}`;
  await page.getByRole('button', { name: '가맹점 등록' }).click();
  await page.waitForTimeout(500);
  const modal = page.locator('[role="dialog"]');
  await modal.locator('input[type="text"]').first().fill(NAME);
  await page.getByRole('button', { name: '저장' }).click();
  await page.waitForTimeout(2500);

  // 저장 직후 화면 상태
  console.log('>>> 저장 후 scrollY =', await page.evaluate(() => window.scrollY));
  console.log('>>> 토스트:', await page.locator('[data-sonner-toast], [role="status"]').allInnerTexts());

  const row = page.getByRole('row').filter({ hasText: NAME }).first();
  const box = await row.boundingBox();
  const vp = page.viewportSize()!;
  const inViewport = !!box && box.y >= 0 && box.y + box.height <= vp.height;
  console.log(`>>> 새 행 뷰포트 좌표 y=${box?.y}, 뷰포트 높이=${vp.height}, 스크롤 없이 보이는가=${inViewport}`);

  // 강조(하이라이트) 클래스가 붙었는가
  console.log('>>> 새 행 class:', await row.evaluate((el) => el.className));
  const otherRow = page.locator('main tbody tr').first();
  console.log('>>> 다른 행 class:', await otherRow.evaluate((el) => el.className));

  // 몇 번 스크롤해야 도달하는가
  const docY = await row.evaluate((el) => el.getBoundingClientRect().top + window.scrollY);
  console.log(`>>> 새 행 문서 y=${docY}, 화면 한 번 스크롤(900px) 기준 ${Math.ceil(docY / 900)}회`);

  // 표에서 같은 이름이 여럿일 때 구분 가능한 정보
  console.log('>>> 새 행 텍스트:', JSON.stringify((await row.innerText()).replace(/\n/g, ' | ')));

  await page.screenshot({ path: 'audit-merchant/shots/zz-refute-after-create.png' });
});
