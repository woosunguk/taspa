import { test, expect, Page, APIRequestContext } from '@playwright/test';
import { execFileSync } from 'child_process';
import { randomUUID } from 'crypto';

/**
 * 가맹 예측 신호 여정 — 매장 관리자가 "빠른 설정"에서 신호를 켜면 **같은 화면의 예측 숫자가 바뀌고
 * 그 이유(행사 배지)가 표에 나타난다**를 브라우저 경로로 고정한다.
 *
 * 서버 통합 테스트(MerchantForecastSignalsIntegrationTest)는 API 계약을 지키지만, 이 여정의 가치는
 * 그 사이다: 토글(낙관적 상태) → PUT 저장 → onSaved reload → 표의 신호 열 렌더. 어느 하나가 끊겨도
 * 화면은 "저장됐다"고 말하면서 숫자는 옛 조합으로 남는다 — 정확히 그 형태를 잡는 테스트다.
 *
 * 픽스처 규약은 money-surfaces.spec.ts 와 동일(가입은 실경로, 조직·가맹 결속은 SQL).
 */
const WEB_BASE = process.env.WEB_BASE ?? 'http://localhost:3000';
const MAILPIT_API = 'http://localhost:8025/api/v1';
const PASSWORD = 'SecureP@ssw0rd123';

function sql(statement: string): string {
  return execFileSync(
    'docker',
    ['exec', 'taspa-postgres-1', 'psql', '-U', 'taspa', '-d', 'taspa', '-tAc', statement],
    { encoding: 'utf-8' },
  )
    .trim()
    .split('\n')[0]
    .trim();
}

async function latestVerificationCode(request: APIRequestContext, email: string): Promise<string> {
  for (let attempt = 0; attempt < 20; attempt++) {
    const list = await (await request.get(`${MAILPIT_API}/messages`)).json();
    const message = (list.messages ?? []).find((m: any) =>
      (m.To ?? []).some((to: any) => to.Address === email),
    );
    if (message) {
      const detail = await (await request.get(`${MAILPIT_API}/message/${message.ID}`)).json();
      const match = ((detail.Text ?? '') as string).match(/\b\d{6}\b/);
      if (match) return match[0];
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`verification mail for ${email} not found`);
}

async function login(page: Page, email: string): Promise<void> {
  await page.context().clearCookies();
  await page.goto(`${WEB_BASE}/login`);
  await page.fill('#email', email);
  await page.click('form[action="/login/identifier"] button[type="submit"]');
  await expect(page).toHaveURL(/\/login\/password/);
  await page.fill('#password', PASSWORD);
  await page.click('form[action="/login/password"] button[type="submit"]');
}

/** 타깃일 — 오늘로부터 3~9일 뒤의 평일(기본 지평 14일 안, basis 4주는 항상 과거 완결 구간). */
function targetDate(): Date {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + 3);
  while (d.getUTCDay() === 0 || d.getUTCDay() === 6) d.setUTCDate(d.getUTCDate() + 1);
  return d;
}

const iso = (d: Date) => d.toISOString().slice(0, 10);
/** 화면 표기(formatDate) — "2026.09.02". */
const shown = (d: Date) => iso(d).replaceAll('-', '.');

test.describe('가맹 예측 신호 여정', () => {
  test.setTimeout(120_000);

  test('빠른 설정에서 행사 인지를 켜면 예측이 바뀌고 표가 이유를 말한다', async ({ page, request }) => {
    const stamp = Date.now();
    const email = `signal-${stamp}@example.com`;

    // ── 1. 계정(실경로 가입 — bcrypt 해시를 지어내지 않는다) ──────────────
    await page.goto(`${WEB_BASE}/signup`);
    await page.fill('#email', email);
    await page.fill('#password', PASSWORD);
    await page.click('button[type="submit"]');
    await expect(page).toHaveURL(/\/login\/verify-email/);
    await page.fill('#code', await latestVerificationCode(request, email));
    await page.click('form[action="/login/verify-email"] button[type="submit"]');

    const userId = sql(`SELECT id FROM users WHERE email = '${email}'`);
    const orgId = randomUUID();
    const merchantId = randomUUID();
    const target = targetDate();

    // ── 2. 조직·가맹·실적·행사 선언(모두 UTC — 날짜 경계 논쟁 제거) ────────
    sql(
      `INSERT INTO organizations (id, name, slug, status, timezone)
       VALUES ('${orgId}', '신호 e2e 조직', 'signal-e2e-${stamp}', 'ACTIVE', 'UTC')`,
    );
    sql(
      `INSERT INTO merchants (id, name, category, status, timezone)
       VALUES ('${merchantId}', '신호 e2e 식당', 'RESTAURANT', 'ACTIVE', 'UTC')`,
    );
    sql(
      `INSERT INTO merchant_members (id, merchant_id, user_id, role, status)
       VALUES (gen_random_uuid(), '${merchantId}', '${userId}', 'MERCHANT_ADMIN', 'ACTIVE')`,
    );
    // basis 4주 — 전주 동요일 20인분(중식). 분해가 이 조직 하나로 수렴하므로 합계 = 20.
    for (let week = 1; week <= 4; week++) {
      const basis = new Date(target);
      basis.setUTCDate(basis.getUTCDate() - 7 * week);
      sql(
        `INSERT INTO consumption_events
           (source, external_id, org_id, merchant_id, meal_window, quantity, status, occurred_at)
         VALUES ('pos', 'signal-e2e-${stamp}-${week}', '${orgId}', '${merchantId}',
                 'LUNCH', 20, 'CONFIRMED', '${iso(basis)} 12:00:00')`,
      );
    }
    // 타깃일을 종일 EVENT 로 선언 — 과거 행사 실적이 없으므로 인지가 켜지면 근거 없음(NO_DATA)이 된다.
    const feedId = sql(
      `INSERT INTO calendar_feeds (id, org_id, name, type, enabled)
       VALUES (gen_random_uuid(), '${orgId}', '행사', 'EVENT', true) RETURNING id`,
    );
    sql(
      `INSERT INTO calendar_events (org_id, feed_id, uid, summary, category, starts_at, ends_at, all_day, source)
       VALUES ('${orgId}', '${feedId}', 'signal-e2e-${stamp}', '전사 체육대회', 'EVENT',
               '${iso(target)} 00:00:00', '${iso(target)} 00:00:00'::timestamp + interval '1 day', true, 'UPLOAD')`,
    );

    // ── 3. 기본 조합(행사 인지 OFF): 행사를 모르고 전주 동요일 20 을 낸다 ──
    await login(page, email);
    await page.goto(`${WEB_BASE}/merchant/${merchantId}`);
    await expect(page.getByText('기간별 예상 식수')).toBeVisible({ timeout: 20_000 });

    const row = page.locator('tr', { hasText: shown(target) }).first();
    await expect(row).toBeVisible({ timeout: 20_000 });
    await expect(row).toContainText('20');
    await expect(row).not.toContainText('체육대회');

    // ── 4. 빠른 설정에서 "사내 행사 인지" ON — 저장 + 자동 반영 ───────────
    await page.getByRole('button', { name: '예측 신호 설정' }).click();
    // ★Base UI Switch 는 id 를 **숨은 체크박스**(aria-hidden)에 붙인다 — id 를 직접 클릭하면
    //   "outside of the viewport" 로 영영 재시도한다(실측). 사용자가 실제로 누르는 것은 라벨이다.
    const eventLabel = page.locator('label[for="qs-eventAware"]');
    await eventLabel.scrollIntoViewIfNeeded();
    await eventLabel.click();
    // 저장 확인 토스트가 아니라 **표가 바뀌는 것**을 기다린다 — 토스트는 저장까지만 증명하고
    // reload 가 끊겨도 뜬다(이 여정이 잡으려는 바로 그 형태).
    await page.keyboard.press('Escape'); // 패널 뒤의 표를 읽기 위해 닫는다(오버레이가 클릭을 막는다)
    await page.getByRole('button', { name: '설정 닫기' }).click().catch(() => {});

    await expect(row).toContainText('체육대회', { timeout: 20_000 });
    await expect(row).toContainText('데이터 없음');
    await expect(row).not.toContainText('20인분');

    // ── 5. 새로고침해도 설정이 남는다(저장형의 정의) ───────────────────────
    await page.reload();
    await expect(page.getByText('기간별 예상 식수')).toBeVisible({ timeout: 20_000 });
    const rowAfter = page.locator('tr', { hasText: shown(target) }).first();
    await expect(rowAfter).toContainText('체육대회', { timeout: 20_000 });
  });
});
