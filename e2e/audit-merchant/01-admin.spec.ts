/**
 * 1단계: 플랫폼 관리자가 /admin/merchants 에서 가맹점을 만들고 담당자를 지정한다.
 * 담당자로 지정된 사람이 그 사실을 어떻게 아는지(메일/화면) 확인한다.
 */
import { test } from '@playwright/test';
import { signup, login, sql, dump, WEB, mailsFor } from './lib';
import fs from 'fs';

test('가맹점 생성 + 담당자 지정', async ({ page, request }) => {
  const STAMP = Date.now();
  const ADMIN_EMAIL = `mx-plat-${STAMP}@example.com`;
  const OWNER_EMAIL = `mx-owner-${STAMP}@example.com`;

  page.on('console', (m) => {
    if (m.type() === 'error') console.log(`[console.error] ${m.text()}`);
  });

  await signup(page, request, OWNER_EMAIL);
  console.log(`사장 계정 생성: ${OWNER_EMAIL}`);
  await signup(page, request, ADMIN_EMAIL);
  sql(`UPDATE users SET role='ADMIN' WHERE email='${ADMIN_EMAIL}'`);
  await login(page, ADMIN_EMAIL);
  console.log(`플랫폼 관리자: ${ADMIN_EMAIL}`);

  await page.goto(`${WEB}/admin`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1200);
  await dump(page, '/admin 대시보드');

  await page.goto(`${WEB}/admin/merchants`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);
  await dump(page, '/admin/merchants (목록)');
  await page.screenshot({ path: 'audit-merchant/shots/01-merchants-list.png', fullPage: true });

  const inputs = await page
    .locator('input, select, textarea')
    .evaluateAll((els) =>
      els.map((e: any) => ({ tag: e.tagName, id: e.id, name: e.name, type: e.type, ph: e.placeholder })),
    );
  console.log('\n[입력 필드]', JSON.stringify(inputs));
  const buttons = await page
    .locator('button')
    .evaluateAll((els) => els.map((e: any) => e.innerText.trim()).filter(Boolean));
  console.log('[버튼]', JSON.stringify(buttons));

  fs.writeFileSync('audit-merchant/state.json', JSON.stringify({ STAMP, ADMIN_EMAIL, OWNER_EMAIL }, null, 2));
  console.log('\n[사장 받은 메일]', JSON.stringify(await mailsFor(request, OWNER_EMAIL)).slice(0, 1500));
});
