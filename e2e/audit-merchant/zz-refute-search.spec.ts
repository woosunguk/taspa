import { test, expect } from '@playwright/test';
import { login, WEB, sql } from './lib';

const ADMIN = 'probe-adminval-1785683876803@example.com';

test('가맹점 목록: 검색·정렬·페이지네이션 존재 여부와 새 행 위치', async ({ page }) => {
  await login(page, ADMIN);
  await page.goto(`${WEB}/admin/merchants`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);

  const url = page.url();
  console.log('>>> URL:', url);

  // 페이지에 있는 입력 요소 전체 (검색창이 있는가)
  const inputs = await page.locator('main input, main select, main textarea').all();
  console.log('>>> main 안 입력 요소 수:', inputs.length);
  for (const i of inputs) {
    console.log('    -', await i.getAttribute('type'), '|', await i.getAttribute('placeholder'), '|', await i.getAttribute('aria-label'));
  }

  // 버튼 전체 목록 (정렬/페이지네이션 컨트롤이 있는가)
  const btns = await page.locator('main button').allInnerTexts();
  const uniq = [...new Set(btns.map((b) => b.trim()).filter(Boolean))];
  console.log('>>> main 버튼 종류:', JSON.stringify(uniq));

  // 표 헤더가 클릭 가능한 정렬 컨트롤인가
  const heads = page.locator('main thead th');
  const hn = await heads.count();
  for (let i = 0; i < hn; i++) {
    const h = heads.nth(i);
    const inner = await h.innerHTML();
    console.log(`>>> th[${i}] "${(await h.innerText()).trim()}" html=${inner.slice(0, 120)}`);
  }

  const rows = page.locator('main tbody tr');
  const n = await rows.count();
  console.log('>>> 데이터 행 수:', n);

  // 서버가 준 순서 = 등록순인가 (DB 와 대조)
  const firstName = (await rows.first().innerText()).split('\n')[0];
  const lastName = (await rows.last().innerText()).split('\n')[0];
  console.log('>>> 첫 행:', JSON.stringify(firstName), ' 마지막 행:', JSON.stringify(lastName));
  console.log('>>> DB 등록순 첫/마지막:',
    sql(`SELECT string_agg(name, ' || ') FROM (SELECT name FROM merchants ORDER BY created_at ASC LIMIT 1) t`),
    '/',
    sql(`SELECT string_agg(name, ' || ') FROM (SELECT name FROM merchants ORDER BY created_at DESC LIMIT 1) t`));

  // 마지막 행(= 새로 만든 매장이 붙는 자리)의 화면상 위치
  const box = await rows.last().boundingBox();
  const vp = page.viewportSize();
  console.log(`>>> 마지막 행 y=${box?.y} (문서 좌표 아님, 현재 뷰포트 기준), 뷰포트 높이=${vp?.height}`);
  const docY = await rows.last().evaluate((el) => el.getBoundingClientRect().top + window.scrollY);
  console.log(`>>> 마지막 행 문서 y=${docY}, 문서 높이=${await page.evaluate(() => document.body.scrollHeight)}`);
  console.log(`>>> 현재 scrollY=${await page.evaluate(() => window.scrollY)}`);

  // 표가 자체 스크롤 컨테이너인가 (max-height 로 잘리는가)
  const wrapClass = await page.locator('main table').evaluate((el) => (el.parentElement as HTMLElement).className);
  console.log('>>> 표 래퍼 class:', wrapClass);

  await page.screenshot({ path: 'audit-merchant/shots/zz-refute-search-top.png' });
  await page.screenshot({ path: 'audit-merchant/shots/zz-refute-search-full.png', fullPage: true });
});
