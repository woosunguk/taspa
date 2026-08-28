import { test, expect, Page, APIRequestContext } from '@playwright/test';
import { execFileSync } from 'child_process';
import { randomUUID } from 'crypto';

/**
 * **돈 화면 교차 일관성 e2e — 렌더된 화면끼리 맞대는 유일한 지점.**
 *
 * 이 제품에서 실제로 터진 결함은 "계산이 틀렸다"보다 **"같은 사실을 두 화면이 다르게 말한다"** 쪽이었다
 * (CLAUDE.md "돈 표면 교차 일관성"). 그래서 방어선이 두 겹 있다:
 *   - 서버: `billing/MoneySurfaceConsistencyIntegrationTest` — 청구서·원장·대사·정산·지급이 같은 숫자인지
 *   - 프런트: `app/merchant/_lib/summarize.test.ts`, `app/meal/amounts.test.ts` — 순수 함수의 집계 규칙
 *
 * 그 **사이**가 비어 있었다. 두 겹 모두 초록불인데 화면이 서로 다른 말을 하는 상태가 가능하다 —
 * 실제로 전액 환불이 요약 카드에서만 사라지고 바로 아래 표에는 남아 있던 적이 있다. 이 스펙은
 * **진짜 결제·환불을 만들고**(SQL 픽스처가 아니라 QR 발급 → M2M redeem → refund 실제 경로),
 * 다섯 화면을 브라우저로 열어 숫자가 일치하는지 본다.
 *
 * ★타임존은 org·merchant 둘 다 UTC 로 고정한다. 실제로는 두 문서가 다른 달력을 써서 경계일 거래만큼
 * **정당하게 다를 수 있는데**, 그 차이를 결함으로 오인하지 않으려고 변수를 제거한 것이다
 * (서버 통합테스트가 같은 이유로 같은 통제를 한다).
 *
 * 실행 전제: taspa(9100) + PostgreSQL(5433) + Mailpit(1025/8025) + 웹 dev 서버(3000)
 */

const WEB_BASE = process.env.WEB_BASE ?? 'http://localhost:3000';
const API_BASE = process.env.API_BASE ?? 'http://localhost:9100';
const MAILPIT_API = 'http://localhost:8025/api/v1';
const PASSWORD = 'SecureP@ssw0rd123';

/** 한 끼 한도. 15,000 결제는 이 값을 넘겨 개인부담이 갈라진다. */
const PER_MEAL_LIMIT = 12_000;

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

/** 화면에서 '12,000원' 같은 표기를 숫자로 되돌린다 — 화면이 말하는 값을 그대로 비교하기 위해서다. */
function wonOf(text: string): number {
  const m = text.replace(/,/g, '').match(/(-?\d+)\s*원/);
  if (!m) throw new Error(`금액을 찾지 못했습니다: ${JSON.stringify(text)}`);
  return Number(m[1]);
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

/**
 * 로딩이 끝난 뒤의 본문 텍스트.
 *
 * ★`goto` 직후 바로 읽으면 **"불러오는 중"** 을 읽는다(실제로 이 스펙이 처음에 그렇게 실패했다).
 * 화면이 아직 서버 응답을 기다리는 중인데 숫자가 없다고 단언하면, 통과·실패가 네트워크 속도에
 * 좌우되는 테스트가 된다.
 */
/**
 * 라벨이 붙은 요약 카드(`Stat`)의 **값**을 읽는다.
 *
 * ★본문 전체에서 `toContain('8,000')` 으로 검사하면 **표 행**이 그 값을 갖고 있어 요약 카드가
 * 틀려도 통과한다(이 스펙이 처음에 그랬다 — 프런트 결함을 일부러 심었는데 초록불이었다).
 * 화면이 *어디서* 그 숫자를 말하는지가 이 테스트의 전부이므로 카드로 좁힌다.
 */
/** 같은 카드에서 **개수**를 읽는다(금액 카드가 아니다 — '원' 이 없다). */
async function statCount(page: Page, label: string): Promise<number> {
  const text = await statCard(page, label).innerText();
  const m = text.replace(/,/g, '').match(/(\d+)/);
  if (!m) throw new Error(`개수를 찾지 못했습니다: ${JSON.stringify(text)}`);
  return Number(m[1]);
}

async function statValue(page: Page, label: string): Promise<number> {
  return wonOf(await statCard(page, label).innerText());
}

/**
 * `Stat` 은 **정의 목록**이다 — `<dl><dt>라벨</dt><dd>값</dd><dd>힌트</dd></dl>`.
 *
 * ★한동안 이 헬퍼가 `<p>` 를 찾고 있었다. `Stat` 이 `<p>` 두 개에서 `<dl>/<dt>/<dd>` 로 바뀌었을 때
 * (라벨과 값을 프로그램적으로 연결해 스크린 리더가 표처럼 훑을 수 있게 한 변경) 함께 따라가지 못했고,
 * 그 뒤로 이 테스트는 **요소를 영영 기다리다 3분 만에 타임아웃**했다. 즉 돈 화면 교차 일관성 검사가
 * 그 시점부터 아무것도 확인하지 못하고 있었다 — 실패가 눈에 띄는 형태였던 것이 그나마 다행이다.
 *
 * 라벨 `<dt>` 를 정확히 집고 그 부모(`<dl>` = 타일 하나)를 읽는다. 넓은 셀렉터(`div:has(text)`)는
 * 표 셀 래퍼까지 잡아 엉뚱한 값을 준다(실제로 0 을 읽었다).
 */
function statCard(page: Page, label: string) {
  return page
    .locator('dt')
    .filter({ hasText: new RegExp(`^${label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`) })
    .first()
    .locator('xpath=..');
}

async function mainTextAfterLoad(page: Page, marker: string): Promise<string> {
  // 로딩 표시가 사라지는 것만으로는 부족하다 — 아직 요청이 시작되지 않았을 수도 있어서, 그 순간엔
  // '로딩 아님 + 결과 없음' 이 참이 된다. 결과에만 있는 문구가 보일 때까지 기다린다.
  await expect(page.getByText(marker).first()).toBeVisible({ timeout: 20_000 });
  await expect(page.getByRole('status')).toHaveCount(0, { timeout: 20_000 });
  return await page.locator('main').innerText();
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

/** 세션 CSRF 토큰 — SPA 와 같은 방식으로 얻는다(meta 태그를 읽을 수 없는 클라이언트용 엔드포인트). */
async function csrfToken(page: Page): Promise<string> {
  const res = await page.request.get(`${WEB_BASE}/api/csrf`);
  return (await res.json()).token;
}

test.describe('돈 화면 교차 일관성', () => {
  test.setTimeout(180_000);

  test('실제 승인·환불을 만들고 다섯 화면이 같은 숫자를 말한다', async ({ page, request }) => {
    const stamp = Date.now();
    const email = `money-${stamp}@example.com`;

    // ── 1. 계정 ────────────────────────────────────────────────────────────
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

    // ── 2. 조직·가맹·정책 ──────────────────────────────────────────────────
    // 타임존은 둘 다 UTC(위 주석 참고). 끼니창은 하루를 빈틈없이 덮어 실행 시각과 무관하게 승인된다.
    // ★'하루 끝'을 23:59:59.999999 로 두면 DB 왕복에서 00:00 으로 넘어가 저녁 창이 항상 거짓이 된다
    //   (CLAUDE.md 의 END_OF_DAY 함정). 밀리초 최대값을 쓴다.
    sql(
      `INSERT INTO organizations (id, name, slug, status, timezone)
       VALUES ('${orgId}', '정합성 e2e 조직', 'money-e2e-${stamp}', 'ACTIVE', 'UTC')`,
    );
    sql(
      `INSERT INTO org_memberships (id, org_id, user_id, role, status)
       VALUES (gen_random_uuid(), '${orgId}', '${userId}', 'ORG_ADMIN', 'ACTIVE')`,
    );
    sql(
      `INSERT INTO meal_policies
         (org_id, per_meal_limit_minor, daily_meal_count, monthly_cap_minor,
          breakfast_start, breakfast_end, lunch_start, lunch_end, dinner_start, dinner_end)
       VALUES ('${orgId}', ${PER_MEAL_LIMIT}, 5, 500000,
          '00:00:00', '07:59:59.999', '08:00:00', '15:59:59.999', '16:00:00', '23:59:59.999')`,
    );
    sql(
      `INSERT INTO merchants (id, name, category, status, timezone)
       VALUES ('${merchantId}', '정합성 e2e 식당', 'RESTAURANT', 'ACTIVE', 'UTC')`,
    );
    sql(
      `INSERT INTO merchant_members (id, merchant_id, user_id, role, status)
       VALUES (gen_random_uuid(), '${merchantId}', '${userId}', 'MERCHANT_ADMIN', 'ACTIVE')`,
    );
    // 역할은 로그인 시점에 세션에 굳는다 — 승격 뒤 다시 로그인해야 /admin 이 열린다.
    sql(`UPDATE users SET role = 'ADMIN' WHERE id = '${userId}'`);

    await login(page, email);

    // ── 3. 가맹 결속 M2M 클라이언트(POS 단말 역할) ─────────────────────────
    // secret 은 등록 응답에서 **1회만** 내려온다 — 여기서 받아 두지 않으면 다시 볼 수 없다.
    const clientId = `money-e2e-pos-${stamp}`;
    const registered = await page.request.post(`${WEB_BASE}/api/admin/clients`, {
      headers: { 'X-CSRF-TOKEN': await csrfToken(page), 'Content-Type': 'application/json' },
      data: {
        clientId,
        clientName: '정합성 e2e POS',
        scopes: ['meal.redeem'],
        grantTypes: ['client_credentials'],
        publicClient: false,
        merchantId,
      },
    });
    expect(registered.status(), await registered.text()).toBe(201);
    const clientSecret = (await registered.json()).clientSecret as string;

    const tokenRes = await request.post(`${API_BASE}/oauth2/token`, {
      headers: {
        Authorization: `Basic ${Buffer.from(`${clientId}:${clientSecret}`).toString('base64')}`,
      },
      form: { grant_type: 'client_credentials', scope: 'meal.redeem' },
    });
    expect(tokenRes.status(), await tokenRes.text()).toBe(200);
    const accessToken = (await tokenRes.json()).access_token as string;
    const posHeaders = { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' };

    // ── 4. 진짜 결제 3건 ───────────────────────────────────────────────────
    // QR 발급은 사용자 쿨다운(10초)이 있다 — 한 건 승인하고 다음 QR 을 받기 전에 기다린다.
    async function approve(amountMinor: number, posTxnId: string) {
      const qr = await page.request.post(`${WEB_BASE}/api/meal/qr`, {
        headers: { 'X-CSRF-TOKEN': await csrfToken(page), 'Content-Type': 'application/json' },
        data: { orgId },
      });
      expect(qr.status(), await qr.text()).toBe(200);
      const token = (await qr.json()).token as string;

      const res = await request.post(`${API_BASE}/api/merchant/redeem`, {
        headers: posHeaders,
        data: { token, amountMinor, posTxnId },
      });
      expect(res.status(), await res.text()).toBe(200);
      return await res.json();
    }

    // ① 한도 초과 — 조직 12,000 / 개인 3,000 으로 갈린다.
    const over = await approve(15_000, `pos-${stamp}-1`);
    expect(over.approvedAmountMinor).toBe(PER_MEAL_LIMIT);
    expect(over.selfPaidMinor).toBe(3_000);

    await new Promise((r) => setTimeout(r, 11_000)); // QR 발급 쿨다운
    // ② 한도 내 — 전액 조직 부담.
    const inside = await approve(10_000, `pos-${stamp}-2`);
    expect(inside.approvedAmountMinor).toBe(10_000);
    expect(inside.selfPaidMinor).toBe(0);

    await new Promise((r) => setTimeout(r, 11_000));
    // ③ 전액 환불 대상.
    const voided = await approve(8_000, `pos-${stamp}-3`);
    expect(voided.approvedAmountMinor).toBe(8_000);

    // ── 5. 환불 2건 ────────────────────────────────────────────────────────
    // ★부분 환불의 분담 재계산: 15,000 중 3,000 을 돌려주면 남은 12,000 은 한도 안이라
    //   조직이 전액 부담하고 **개인이 3,000 을 돌려받는다**(CLAUDE.md 의 핵심 불변식).
    const partial = await request.post(
      `${API_BASE}/api/merchant/redeem/${over.authId}/refund`,
      { headers: posHeaders, data: { amountMinor: 3_000, posRefundId: `ref-${stamp}-1` } },
    );
    expect(partial.status(), await partial.text()).toBe(200);
    const partialBody = await partial.json();
    expect(partialBody.selfRefundedMinor, '개인이 돌려받는 금액').toBe(3_000);
    expect(partialBody.orgRefundedMinor, '조직 부담은 줄지 않는다').toBe(0);

    const full = await request.post(
      `${API_BASE}/api/merchant/redeem/${voided.authId}/refund`,
      { headers: posHeaders, data: { amountMinor: 8_000, posRefundId: `ref-${stamp}-2` } },
    );
    expect(full.status(), await full.text()).toBe(200);

    // 조회 기간. 세 화면(정산·청구서·지급현황)이 **같은 달**을 봐야 비교가 성립한다.
    const period = new Date().toISOString().slice(0, 7);

    // 기대값 — 이 숫자가 다섯 화면에서 모두 같아야 한다.
    const expectedOrgPayable = PER_MEAL_LIMIT + 10_000; // 12,000 + 10,000 (전액 환불 건은 0)
    const expectedRefunded = 3_000 + 8_000;

    // ── 6. 화면 ①: 직원 사용내역 ───────────────────────────────────────────
    // ★환불이 amount_minor 를 소급 변경하므로, 환불이 있는 거래는 **원금**을 머리 금액으로 보여야 한다.
    //   전액 환불이 '0원 · 취소됨' 한 줄로 남으면 돈이 실제로 오간 거래일수록 숫자가 사라진다.
    await page.goto(`${WEB_BASE}/meal`);
    const history = page.getByText('최근 사용 내역').locator('xpath=ancestor::*[3]');
    await expect(page.getByText('아직 사용 내역이 없습니다')).toHaveCount(0);
    await expect(history.getByText('15,000원').first()).toBeVisible();
    await expect(history.getByText('8,000원').first()).toBeVisible();

    // ── 7. 화면 ②: 가맹 거래 로그 ──────────────────────────────────────────
    await page.goto(`${WEB_BASE}/merchant/${merchantId}/transactions`);
    await expect(page.getByText('정합성 e2e 식당').first()).toBeVisible();
    // 화면 이름이 '식수 로그' → '밀로그' 로 바뀌었다(마커는 화면의 실제 제목을 따라간다).
    await mainTextAfterLoad(page, '밀로그');
    // ★요약 **카드**를 본다. 전액 환불(8,000)이 카드에서 빠지면 카드는 "환불 3,000원"인데
    //   바로 아래 표에는 8,000원 환불 행이 보여 한 화면이 두 가지를 주장하게 된다.
    expect(await statValue(page, '환불'), '환불 합계 = 부분 3,000 + 전액 8,000').toBe(expectedRefunded);
    expect(await statValue(page, '조직 부담(청구 대상)'), '조직 부담 합계').toBe(expectedOrgPayable);

    // ── 8. 화면 ③: 가맹 정산 명세 ──────────────────────────────────────────
    // ★지급 대상은 **조직부담 합**뿐이다 — 개인부담은 손님이 계산대에서 이미 냈다.
    await page.goto(`${WEB_BASE}/merchant/${merchantId}/settlement`);
    await page.fill('#settlement-period', period);
    await page.getByRole('button', { name: '조회' }).click();
    await mainTextAfterLoad(page, '지급 예정액');
    expect(await statValue(page, '지급 예정액'), '지급 대상은 조직 부담 합뿐이다').toBe(
      expectedOrgPayable,
    );
    // 정산 명세는 환불을 카드가 아니라 **안내 문장**으로 말한다(지급액이 왜 줄었는지 설명하려고).
    // 전액 환불이 여기서 빠지면 매장은 자기 POS 기록과 대사할 수 없다.
    const settlementText = await page.locator('main').innerText();
    expect(settlementText, '전액 환불이 정산 명세에서 사라지면 안 된다').toContain(
      `${expectedRefunded.toLocaleString('en-US')}원이 환불`,
    );

    // ── 9. 화면 ④: 조직 청구서 ─────────────────────────────────────────────
    await page.goto(`${WEB_BASE}/console/${orgId}/invoices`);
    await page.fill('#invoice-period', period);
    await page.getByRole('button', { name: '초안 생성' }).click();
    await page.getByRole('button', { name: '덮어쓰고 생성' }).click();
    await expect(page.getByText(period).first()).toBeVisible();
    await mainTextAfterLoad(page, '청구서 목록');
    // 다른 네 화면과 같은 규칙으로 좁힌다 — 본문 전체 `toContain` 은 표 행이 값을 갖고 있어
    // 요약이 틀려도 통과한다(이 파일 statValue 주석이 못박은 안티패턴이다).
    const invoiceRow = page.getByRole('row').filter({ hasText: period }).first();
    await expect(invoiceRow).toBeVisible({ timeout: 20_000 });
    expect(wonOf(await invoiceRow.innerText()), '청구서 금액 = 가맹 정산의 조직부담').toBe(
      expectedOrgPayable,
    );

    // ── 10. 화면 ⑤: 플랫폼 지급 현황 ───────────────────────────────────────
    // ★매장별 settlement() 을 그대로 재사용하므로 운영자가 보는 총액과 매장 화면이 갈릴 수 없다.
    await page.goto(`${WEB_BASE}/admin/payables`);
    await page.fill('#payables-period', period);
    await page.getByRole('button', { name: '조회' }).click();
    await mainTextAfterLoad(page, '지급 예정 총액');
    // ★**총액이 아니라 이 매장의 행**을 본다. 총액은 dev DB 의 다른 매장까지 합한 값이라 이 조직 금액과
    //   같을 수 없다(실측 198,000원 / 9매장). 불변식은 "운영자가 보는 **매장별 금액** = 그 매장이 자기
    //   화면에서 보는 금액" 이고, 그건 `platformPayables` 가 매장별 `settlement()` 을 재사용하기 때문이다.
    const payablesRow = page.getByRole('row').filter({ hasText: '정합성 e2e 식당' }).first();
    await expect(payablesRow).toBeVisible({ timeout: 20_000 });
    expect(wonOf(await payablesRow.innerText()), '운영자가 보는 매장 금액 = 매장 화면 금액').toBe(
      expectedOrgPayable,
    );

    // ── 11. 화면 ⑥(보너스): 정합성 대사가 불일치를 보고하지 않아야 한다 ────
    await page.goto(`${WEB_BASE}/admin/reconciliation`);
    await page.fill('#recon-period', period);
    await page.getByRole('button', { name: '조회' }).click();
    // 전역 대사는 활동이 있는 조직을 훑으므로 다른 화면보다 느리다 — 기본 5초 단언을 쓰지 않는다.
    await mainTextAfterLoad(page, '대사한 조직');
    // ★"불일치 0"만 확인하면 아무것도 증명하지 못한다 — 이 조직이 후보에서 통째로 빠져도,
    //   실제로 불일치가 보고돼도 같은 문구는 나타나지 않기 때문이다. 둘 다 직접 단언한다.
    expect(await statCount(page, '대사한 조직'), '이 조직은 원장 활동이 있어 대사 대상이다').toBeGreaterThan(
      0,
    );
    await expect(
      page.getByRole('row').filter({ hasText: '정합성 e2e 조직' }),
      '이 조직은 불일치 목록에 없어야 한다',
    ).toHaveCount(0);

    // ★org 앵커가 있는 대사로 drift 0 을 **직접** 확인한다 — 전역 화면은 "이 조직이 대사됐는가"를
    //   표현할 수 없다(합계만 말한다).
    await page.goto(`${WEB_BASE}/console/${orgId}/invoices`);
    // 이 섹션은 버튼 없이 입력값 변경만으로 조회한다(useApi 의 deps).
    await page.fill('#recon-period', period);
    await expect(page.getByText('이상 없음'), '원장·장부·소비이벤트가 모두 일치해야 한다').toBeVisible({
      timeout: 20_000,
    });
  });
});
