import { test, expect, Page, APIRequestContext } from '@playwright/test';
import { execFileSync } from 'child_process';
import { randomUUID } from 'crypto';
import * as fs from 'fs';
import * as path from 'path';

/**
 * **UI 감사용 스펙 — 단언보다 화면 캡처가 목적이다.**
 *
 * 배포 전 훑어야 할 것(빈 상태·오류 상태·모바일 폭·다크모드·긴 텍스트 넘침)은 라우트가 30개면
 * 손으로 못 본다. 한 계정에 모든 역할을 심고 전 라우트를 **모바일/데스크톱 × 라이트/다크**로
 * 캡처한다. 산출물은 `OUT_DIR` 이고, 기본은 스크린샷만 남기며 콘솔 오류가 있으면 실패시킨다
 * (오류는 화면으로 안 보이는 결함이라 사람이 훑어도 놓친다).
 *
 * 상시 CI 용이 아니다 — `UI_AUDIT=1` 일 때만 돈다.
 */

const WEB_BASE = process.env.WEB_BASE ?? 'http://localhost:3000';
const MAILPIT_API = 'http://localhost:8025/api/v1';
const PASSWORD = 'SecureP@ssw0rd123';
const OUT_DIR = process.env.UI_AUDIT_OUT ?? path.join(process.cwd(), 'ui-audit');

function sql(statement: string): string {
  return execFileSync(
    'docker',
    ['exec', 'taspa-postgres-1', 'psql', '-U', 'taspa', '-d', 'taspa', '-tAc', statement],
    { encoding: 'utf-8' },
  ).trim().split('\n')[0].trim();
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

test.describe('UI 감사 캡처', () => {
  test.skip(process.env.UI_AUDIT !== '1', 'UI_AUDIT=1 일 때만 실행');
  test.setTimeout(300_000);

  test('전 라우트를 모바일/데스크톱 × 라이트/다크로 캡처한다', async ({ page, request }) => {
    const email = `ui-audit-${Date.now()}@example.com`;

    // 가입 + 이메일 인증 (서버 소유 화면 — 프록시 경유)
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
    const stamp = Date.now();

    // 한 계정에 모든 역할 — 플랫폼 관리자 · 조직관리자 · 가맹 담당자.
    // (실제 운영에서 한 사람이 다 갖는 일은 없지만, 감사는 **모든 화면**을 봐야 한다.)
    sql(`UPDATE users SET role = 'ADMIN', display_name = '감사 계정' WHERE id = '${userId}'`);
    sql(
      `INSERT INTO organizations (id, name, slug, status, timezone)
       VALUES ('${orgId}', '감사용 조직 (아주 긴 이름으로 넘침을 확인한다)', 'ui-audit-${stamp}', 'ACTIVE', 'Asia/Seoul')`,
    );
    sql(
      `INSERT INTO org_memberships (id, org_id, user_id, role, status)
       VALUES (gen_random_uuid(), '${orgId}', '${userId}', 'ORG_ADMIN', 'ACTIVE')`,
    );
    sql(
      `INSERT INTO merchants (id, name, category, status, timezone)
       VALUES ('${merchantId}', '감사용 식당', 'RESTAURANT', 'ACTIVE', 'Asia/Seoul')`,
    );
    sql(
      `INSERT INTO merchant_members (id, merchant_id, user_id, role, status)
       VALUES (gen_random_uuid(), '${merchantId}', '${userId}', 'MERCHANT_ADMIN', 'ACTIVE')`,
    );

    // ★역할은 **로그인 시점에 세션에 굳는다**(CLAUDE.md). DB 로 승격만 하면 기존 세션은 여전히 일반
    // 사용자라 /admin/* 이 전부 403 으로 찍힌다 — 감사 스크린샷이 통째로 오류 화면이 된다(실제로 그랬다).
    // 그래서 승격 뒤 **다시 로그인**한다.
    // 쿠키를 지운다 — `/logout` 만으로는 서버 세션이 남아 로그인 폼이 곧장 /account 로 넘어간다.
    await page.context().clearCookies();
    await page.goto(`${WEB_BASE}/login`);
    await page.fill('#email', email);
    await page.click('form[action="/login/identifier"] button[type="submit"]');
    await expect(page).toHaveURL(/\/login\/password/);
    await page.fill('#password', PASSWORD);
    await page.click('form[action="/login/password"] button[type="submit"]');
    await expect(page).toHaveURL(/\/(account|meal|$)/);

    const routes = [
      '/', '/meal', '/account',
      '/console', `/console/${orgId}`,
      `/console/${orgId}/members`, `/console/${orgId}/invitations`, `/console/${orgId}/structure`,
      `/console/${orgId}/meal-policy`, `/console/${orgId}/domains`, `/console/${orgId}/forecast`,
      `/console/${orgId}/invoices`, `/console/${orgId}/roles`, `/console/${orgId}/audit`,
      '/admin', '/admin/orgs', '/admin/users', '/admin/clients', '/admin/merchants',
      '/admin/audit', '/admin/sso', '/admin/calendar', '/admin/iam',
      '/admin/payables', '/admin/reconciliation',
      '/merchant', `/merchant/${merchantId}`,
      `/merchant/${merchantId}/transactions`, `/merchant/${merchantId}/settlement`,
      '/pos',
    ];

    const viewports = [
      { name: 'mobile', width: 390, height: 844 },
      { name: 'desktop', width: 1280, height: 900 },
    ] as const;
    const themes = ['light', 'dark'] as const;

    fs.mkdirSync(OUT_DIR, { recursive: true });
    const consoleErrors: string[] = [];
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(`${page.url()} :: ${msg.text()}`);
    });

    // 가로 스크롤은 "본문이 화면을 넘쳤다"는 뜻이라 모바일에서 가장 흔한 배포 결함이다.
    const horizontalOverflow: string[] = [];

    for (const viewport of viewports) {
      for (const theme of themes) {
        await page.emulateMedia({ colorScheme: theme });
        await page.setViewportSize({ width: viewport.width, height: viewport.height });
        for (const route of routes) {
          await page.goto(`${WEB_BASE}${route}`, { waitUntil: 'networkidle' }).catch(() => {});
          const slug = route.replace(/[^a-zA-Z0-9]+/g, '_').replace(/^_|_$/g, '') || 'home';
          await page.screenshot({
            path: path.join(OUT_DIR, `${viewport.name}-${theme}-${slug}.png`),
            fullPage: true,
          });
          const overflows = await page.evaluate(
            () => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
          );
          if (overflows && viewport.name === 'mobile' && theme === 'light') {
            horizontalOverflow.push(route);
          }
        }
      }
    }

    // eslint-disable-next-line no-console
    console.log('AUDIT_OVERFLOW', JSON.stringify(horizontalOverflow));
    // eslint-disable-next-line no-console
    console.log('AUDIT_CONSOLE_ERRORS', JSON.stringify([...new Set(consoleErrors)].slice(0, 40)));
    // eslint-disable-next-line no-console
    console.log('AUDIT_OUT', OUT_DIR);

    expect(horizontalOverflow, '모바일에서 가로 스크롤이 생기는 화면').toEqual([]);

    /*
      ★콘솔 오류는 **실패시킨다**. 이 파일의 설명은 처음부터 "콘솔 오류가 있으면 실패시킨다"고
      적혀 있었는데 실제로는 출력만 하고 통과했다 — 약속이 지켜지지 않는 게이트는 게이트가 아니다.

      실제로 그 사이에 하이드레이션 오류(`<p>` 안의 `<div>`)가 들어왔고, 화면은 멀쩡해 보이고
      캡처에도 아무 흔적이 없어서 로그를 사람이 눈으로 읽을 때만 잡혔다. 이런 건 화면으로 안 보이는
      결함이라 사람이 훑어도 놓친다는 것이 이 하네스를 만든 이유였다.
    */
    expect([...new Set(consoleErrors)], '브라우저 콘솔 오류').toEqual([]);
  });
});
