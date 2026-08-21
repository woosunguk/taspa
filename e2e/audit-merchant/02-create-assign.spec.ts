import { test, expect } from '@playwright/test';
import { login, sql, dump, WEB, mailsFor } from './lib';
import fs from 'fs';

const S = JSON.parse(fs.readFileSync('audit-merchant/state.json', 'utf-8'));

test('가맹점 등록 → 담당자 지정', async ({ page, request }) => {
  page.on('console', (m) => {
    if (m.type() === 'error') console.log(`[console.error] ${m.text()}`);
  });
  await login(page, S.ADMIN_EMAIL);
  await page.goto(`${WEB}/admin/merchants`);
  await page.waitForLoadState('networkidle');

  // ── 등록 모달 ──
  await page.getByRole('button', { name: '가맹점 등록' }).click();
  await page.waitForTimeout(600);
  const modal = page.locator('[role="dialog"]');
  console.log('\n### 등록 모달 내용\n' + (await modal.innerText()));
  await page.screenshot({ path: 'audit-merchant/shots/02-create-modal.png', fullPage: true });

  const NAME = `사장님 분식 ${S.STAMP}`;
  // 이름 필드
  await modal.locator('input[type="text"]').first().fill(NAME);
  await page.getByRole('button', { name: '저장' }).click();
  await page.waitForTimeout(2000);
  await dump(page, '등록 직후 목록 (상태 확인)');

  const created = sql(`SELECT id||'|'||status||'|'||timezone FROM merchants WHERE name='${NAME}'`);
  console.log('\n>>> 생성된 가맹:', created);
  const merchantId = created.split('|')[0];

  // 방금 만든 행이 화면 어디에 있는가 (스크롤 없이 보이는가)
  const row = page.getByRole('row').filter({ hasText: NAME }).first();
  const box = await row.boundingBox();
  const vp = page.viewportSize();
  console.log(`>>> 새 행 위치 y=${box?.y}, 뷰포트 높이=${vp?.height}, 총 행 수=${await page.getByRole('row').count()}`);

  // ── 담당자 모달 ──
  await row.getByRole('button', { name: '담당자' }).click();
  await page.waitForTimeout(1200);
  const memberModal = page.locator('[role="dialog"]');
  console.log('\n### 담당자 모달 (빈 상태)\n' + (await memberModal.innerText()));
  await page.screenshot({ path: 'audit-merchant/shots/02-members-empty.png', fullPage: true });

  // 오타 이메일 — 오류 문구가 다음 행동을 말하는가
  await memberModal.locator('input').last().fill('nobody-typo@example.com');
  await page.getByRole('button', { name: '추가', exact: true }).click();
  await page.waitForTimeout(1500);
  console.log('\n### 없는 이메일 추가 시도 결과\n' + (await memberModal.innerText()));
  await page.screenshot({ path: 'audit-merchant/shots/02-members-typo.png', fullPage: true });

  // 진짜 사장 지정
  await memberModal.locator('input').last().fill(S.OWNER_EMAIL);
  await page.getByRole('button', { name: '추가', exact: true }).click();
  await page.waitForTimeout(1800);
  console.log('\n### 담당자 지정 후\n' + (await memberModal.innerText()));
  await page.screenshot({ path: 'audit-merchant/shots/02-members-added.png', fullPage: true });

  // POS 결속 매장(본사 구내식당)에도 같은 사람을 담당자로 — 실제 결제 확인용
  const POS_MERCHANT = '6dc75701-36b5-4a3f-8ee5-5b6d2484685f';
  const res = await page.request.post(`${WEB}/api/admin/merchants/${POS_MERCHANT}/members`, {
    headers: {
      'X-CSRF-TOKEN': (await (await page.request.get(`${WEB}/api/csrf`)).json()).token,
      'Content-Type': 'application/json',
    },
    data: { email: S.OWNER_EMAIL },
  });
  console.log('\n>>> POS 결속 매장 담당자 추가:', res.status(), await res.text());

  // ── 담당자가 그 사실을 어떻게 아는가: 메일함 확인 ──
  await page.waitForTimeout(1500);
  const mails = await mailsFor(request, S.OWNER_EMAIL);
  console.log(
    '\n### 사장이 받은 메일 전체 제목\n' + mails.map((m: any) => `- ${m.Subject} (${m.Created})`).join('\n'),
  );

  fs.writeFileSync(
    'audit-merchant/state.json',
    JSON.stringify({ ...S, MERCHANT_ID: merchantId, MERCHANT_NAME: NAME, POS_MERCHANT }, null, 2),
  );
});
