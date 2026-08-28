/**
 * 데모 영상 녹화 — 실제 앱을 주행하며 Playwright 로 캡처한다(합성 화면이 아니다).
 * 산출: submission/demo.webm (이후 ffmpeg-static 으로 mp4 변환)
 *
 * 여정(발표 시나리오 6장면의 축약): 매장 히어로 → 신호 설정 → 셀 근거 → 잔반 리포트 →
 * 조직(부재·캘린더) → 직원 QR → POS 자동 등록.
 */
import { chromium } from '@playwright/test';
import { readFileSync } from 'fs';

const WEB = 'http://localhost:3000';
const MID = '6dc75701-36b5-4a3f-8ee5-5b6d2484685f';
const ORG = 'd0000000-0000-4000-8000-000000000001';
const pause = (ms) => new Promise((r) => setTimeout(r, ms));

/** 장면 자막 — 페이지에 하단 배너를 주입한다(녹화에 그대로 찍힌다). 무음 영상의 내레이션 역할. */
async function banner(pg, title, sub) {
  await pg.evaluate(
    ([t, s2]) => {
      let el = document.getElementById('demo-banner');
      if (!el) {
        el = document.createElement('div');
        el.id = 'demo-banner';
        el.style.cssText =
          'position:fixed;left:50%;bottom:36px;transform:translateX(-50%);z-index:99999;' +
          'background:rgba(14,42,28,.92);color:#f3f7ea;padding:16px 28px;border-radius:14px;' +
          'font-family:Pretendard,sans-serif;box-shadow:0 8px 30px rgba(0,0,0,.35);max-width:1100px;text-align:center';
        document.body.appendChild(el);
      }
      el.innerHTML =
        '<div style="font-size:22px;font-weight:700;letter-spacing:-0.01em">' + t + '</div>' +
        (s2 ? '<div style="font-size:15px;opacity:.85;margin-top:4px">' + s2 + '</div>' : '');
    },
    [title, sub ?? ''],
  );
}

const browser = await chromium.launch();
const context = await browser.newContext({
  viewport: { width: 1920, height: 1080 },
  recordVideo: { dir: '../submission/raw-video', size: { width: 1920, height: 1080 } },
  colorScheme: 'light',
});
const page = await context.newPage();

async function login(email) {
  await context.clearCookies();
  await page.goto(`${WEB}/login`);
  await page.fill('#email', email);
  await page.click('form[action="/login/identifier"] button[type="submit"]');
  await page.waitForURL(/\/login\/password/);
  await page.fill('#password', '1111');
  await page.click('form[action="/login/password"] button[type="submit"]');
  await page.waitForLoadState('networkidle');
}

async function slowScroll(px, steps = 6) {
  for (let i = 0; i < steps; i++) {
    await page.mouse.wheel(0, px / steps);
    await pause(350);
  }
}

// ── 1. 매장: 오늘 몇 인분 준비할까요? ──
await login('merchant@taspa.example');
await page.goto(`${WEB}/merchant/${MID}`);
await page.getByText('오늘 몇 인분 준비할까요?').waitFor({ timeout: 20000 });
await banner(page, '① 매장 관리 — 오늘 몇 인분 준비할까요?', '이용 조직별로 나눠 예측하고 합산합니다 · 잔반을 줄이는 발주의 근거');
await pause(4500);
await slowScroll(700);
await pause(2500);

// ── 2. 신호 설정 패널 — 행사 인지 토글 ──
await page.mouse.wheel(0, -2000); await pause(800);
await banner(page, '② 신호 설정 — 연차·휴일·행사·메뉴·당일 보정', '신호 7종을 켜고 끄며 우리 매장에 맞는 조합을 찾습니다 · 토글 즉시 저장 + 감사 기록');
await page.getByRole('button', { name: '예측 신호 설정' }).click();
await pause(2400);
const eventLabel = page.locator('label[for="qs-eventAware"]');
await eventLabel.scrollIntoViewIfNeeded();
await pause(600);
await eventLabel.click();
await pause(2600);
await eventLabel.click(); // 원상 복구(데모 데이터 보존)
await pause(1400);
await page.keyboard.press('Escape');
await pause(800);

// ── 3. 숫자 클릭 → 셀 근거 상세 ──
await page.goto(`${WEB}/merchant/${MID}`);
await page.getByText('기간별 예상 식수').waitFor({ timeout: 20000 });
const todayIso = new Date().toISOString().slice(0, 10);
await page.goto(`${WEB}/merchant/${MID}/cell/${todayIso}/LUNCH`);
await page.getByText('메뉴별 예상').waitFor({ timeout: 20000 });
await banner(page, '③ 숫자를 클릭하면 근거가 나옵니다', '어떤 메뉴가 몇 인분인지(실측 선택 비율) · 조직별 분해 · 근거가 된 과거 실적 날짜');
await pause(5000);
await slowScroll(500, 4);
await pause(2500);

// ── 4. 잔반 리포트 ──
await page.goto(`${WEB}/merchant/${MID}/report`);
await page.getByText('잔반 절감 효과').waitFor({ timeout: 20000 });
await banner(page, '④ 잔반 리포트 — 제로웨이스트 성과', '예측 도입 가정 대비 과잉 준비 감소량을 계산 가정과 함께 공개합니다');
await pause(5000);
await slowScroll(400, 3);
await pause(2000);

// ── 5. 조직: 구성원 부재 + 캘린더 ──
await login('orgadmin@taspa.example');
await page.goto(`${WEB}/console/${ORG}/members`);
await page.getByText('부재 (연차·휴가)').waitFor({ timeout: 20000 });
await banner(page, '⑤ 조직 관리 — 신호의 원천', '연차·반차를 등록하면 그날 매장 예측이 그만큼 낮아집니다 · 행사·휴일은 iCalendar(.ics) 표준으로');
await page.getByText('부재 (연차·휴가)').scrollIntoViewIfNeeded();
await pause(5000);

// ── 6. 직원: 식권 QR ──
await login('staff@taspa.example');
await page.goto(`${WEB}/meal`);
await page.getByText('식권 QR 발급').waitFor({ timeout: 20000 });
await banner(page, '⑥ 직원 — 식권 QR', '결제 한 건이 곧 실적 한 건 · 예측의 정답 데이터가 스스로 쌓입니다');
await pause(1500);
await page.getByText('식권 QR 발급').click().catch(() => {});
await pause(4500);

// ── 7. POS: URL 키 자동 등록 ──
const envLine = readFileSync('../web/.env.local', 'utf-8').split('\n').find((l) => l.startsWith('POS_TERMINAL_KEY='));
if (envLine) {
  const key = encodeURIComponent(envLine.slice('POS_TERMINAL_KEY='.length).trim());
  await context.clearCookies();
  await page.goto(`${WEB}/pos?key=${key}`);
  await page.waitForLoadState('networkidle');
  await banner(page, '⑦ POS — 링크 하나로 단말 등록', 'URL 의 등록 키는 열리는 즉시 지워집니다 · 스캔 → 정액 자동 승인 → 배식 코너 기록');
  await pause(5000);
}

await context.close();
await browser.close();
console.log('녹화 완료');
