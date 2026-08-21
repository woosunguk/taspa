import { test, expect, Page, APIRequestContext } from '@playwright/test';
import { execFileSync } from 'child_process';
import { randomUUID } from 'crypto';

// 조직 커스텀 역할 e2e — **권한을 만드는 화면**이라 조직 안에서 가장 민감한 표면이다.
//
// 백엔드 불변식(부여 가능 목록·자기 증식 차단·org 경계)은 `OrgRoleIntegrationTest` 가 지킨다.
// 여기서 증명하는 것은 그 불변식이 **화면까지 그대로 이어지는지**다:
//   ① 체크박스 목록이 서버 카탈로그에서 온다(프런트가 목록을 들고 있으면 서버가 막은 능력을 계속 보여준다)
//   ② 만든 역할이 목록에 나타나고 구성원에게 붙는다
//   ③ 역할 관리 자체는 역할로 넘길 수 없다는 사실이 화면에 드러난다
//
// 실행 전제: taspa(9100) + PostgreSQL(5433) + Mailpit(1025/8025) + 웹 dev 서버(3000)

const WEB_BASE = process.env.WEB_BASE ?? 'http://localhost:3000';
const MAILPIT_API = 'http://localhost:8025/api/v1';
const PASSWORD = 'SecureP@ssw0rd123';

function uniqueEmail(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 10000)}@example.com`;
}

/**
 * dev DB 에 직접 쓴다. 조직 생성·ORG_ADMIN 임명은 플랫폼 관리자 권한이 필요한데, 그 경로는
 * `AdminOrgController` 가 이미 통합테스트로 덮고 있다 — 여기서 화면으로 다시 밟으면 이 스펙이
 * 검증하려는 것(역할 화면)이 아니라 준비 과정에서 깨지기 쉬워진다.
 */
function sql(statement: string): string {
  return execFileSync(
    'docker',
    ['exec', 'taspa-postgres-1', 'psql', '-U', 'taspa', '-d', 'taspa', '-tAc', statement],
    { encoding: 'utf-8' },
  ).trim().split('\n')[0].trim();  // RETURNING 뒤에 붙는 psql 명령 태그("INSERT 0 1")를 잘라낸다
}

async function latestVerificationCode(request: APIRequestContext, email: string): Promise<string> {
  for (let attempt = 0; attempt < 20; attempt++) {
    const listRes = await request.get(`${MAILPIT_API}/messages`);
    const list = await listRes.json();
    const message = (list.messages ?? []).find((m: any) =>
      (m.To ?? []).some((to: any) => to.Address === email),
    );
    if (message) {
      const detailRes = await request.get(`${MAILPIT_API}/message/${message.ID}`);
      const detail = await detailRes.json();
      const match = ((detail.Text ?? '') as string).match(/\b\d{6}\b/);
      if (match) return match[0];
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`verification mail for ${email} not found in Mailpit`);
}

async function signupThroughProxy(page: Page, request: APIRequestContext, email: string): Promise<void> {
  await page.goto(`${WEB_BASE}/signup`);
  await page.fill('#email', email);
  await page.fill('#password', PASSWORD);
  await page.click('button[type="submit"]');
  await expect(page).toHaveURL(/\/login\/verify-email/);
  const code = await latestVerificationCode(request, email);
  await page.fill('#code', code);
  await page.click('form[action="/login/verify-email"] button[type="submit"]');
}

test.describe('조직 커스텀 역할', () => {
  test('역할을 만들어 구성원에게 부여하고, 다시 거둔다', async ({ page, request }) => {
    const adminEmail = uniqueEmail('role-admin');
    const memberEmail = uniqueEmail('role-member');

    await signupThroughProxy(page, request, adminEmail);
    const adminId = sql(`SELECT id FROM users WHERE email = '${adminEmail}'`);
    expect(adminId).not.toBe('');

    // 구성원과 조직은 화면을 거치지 않고 만든다 — 이 스펙의 대상이 아니다.
    // id 를 **여기서** 만들어 넘긴다(DB 의 RETURNING 에 기대지 않는다 — psql 출력 파싱이 한 겹 줄어든다).
    const memberId = randomUUID();
    const orgId = randomUUID();
    const slug = `role-e2e-${Date.now()}`;
    sql(
      `INSERT INTO users (id, email, password_hash, email_verified, status, role)
       VALUES ('${memberId}', '${memberEmail}', NULL, true, 'ACTIVE', 'USER')`,
    );
    sql(
      `INSERT INTO organizations (id, name, slug, status, timezone)
       VALUES ('${orgId}', '역할 테스트 조직', '${slug}', 'ACTIVE', 'Asia/Seoul')`,
    );
    sql(
      `INSERT INTO org_memberships (id, org_id, user_id, role, status)
       VALUES (gen_random_uuid(), '${orgId}', '${adminId}', 'ORG_ADMIN', 'ACTIVE'),
              (gen_random_uuid(), '${orgId}', '${memberId}', 'MEMBER', 'ACTIVE')`,
    );

    await page.goto(`${WEB_BASE}/console/${orgId}/roles`);

    // ① 카탈로그는 서버에서 온다. 화면이 목록을 들고 있으면 "있어야 할 라벨" 단언은 하드코딩이라도
    //    통과하므로, **없어야 할 것**을 함께 단언한다(③) — 그 짝이 있어야 의미가 생긴다.
    await page.getByRole('button', { name: '역할 만들기' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText('구성원 조회')).toBeVisible();
    await expect(dialog.getByText('청구서 조회')).toBeVisible();

    // ③ 역할 관리·구성원 역할 변경·부서 위임은 카탈로그에 **없어야 한다** —
    //    있으면 한 번 부여된 역할이 새 역할을 만들며 스스로 자란다.
    await expect(dialog.getByText('역할 관리')).toHaveCount(0);
    await expect(dialog.getByText('구성원 역할 변경')).toHaveCount(0);
    await expect(dialog.getByText('부서 위임 관리')).toHaveCount(0);

    await dialog.getByLabel('역할 이름').fill('회계 담당');
    await dialog.getByLabel('설명 (선택)').fill('청구서만 봅니다');
    await dialog.getByText('청구서 조회').click();
    await dialog.getByRole('button', { name: '저장' }).click();

    // 방금 로그인했으므로 step-up 은 걸리지 않아야 한다. 걸린다면 그것 자체가 회귀다(조용히 우회하지 않는다).
    await expect(page).not.toHaveURL(/\/reauth/);

    // ② 저장이 끝나면 대화상자가 닫히고 표에 나타난다.
    //    ★`getByText` 로 확인하면 **대화상자 입력칸의 값**에도 걸려, 저장이 실패해도 통과한다
    //    (실제로 그렇게 통과했다 — 서버는 400 을 돌려주고 있었다). 표의 셀로 좁혀야 의미가 있다.
    await expect(dialog).toBeHidden();
    await expect(page.getByRole('cell').filter({ hasText: '회계 담당' })).toBeVisible();
    // 권한 요약 배지는 서버가 돌려준 action 을 서버 카탈로그의 라벨로 옮긴 것이다.
    await expect(page.getByRole('cell').filter({ hasText: '청구서 조회' })).toBeVisible();

    // 구성원에게 부여 → 목록에 뜬다 → 해제하면 사라진다
    await page.getByRole('button', { name: '구성원', exact: true }).first().click();
    const members = page.getByRole('dialog');
    await expect(members).toBeVisible();
    await members.getByLabel('구성원 추가').selectOption({ label: memberEmail });
    await members.getByRole('button', { name: '추가' }).click();
    await expect(members.getByText(memberEmail)).toBeVisible();

    await members.getByRole('button', { name: '해제' }).click();
    await expect(members.getByText('아직 부여된 구성원이 없습니다')).toBeVisible();
  });
});
