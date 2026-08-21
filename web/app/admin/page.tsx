"use client";

import Link from "next/link";
import { ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useApi, type Query } from "@/lib/useApi";
import { PageHeader, Section, StatCard, TableScroll, formatDateTime } from "./_components/kit";
import type {
  AdminAuditEventView,
  AdminClientView,
  AdminUserSummary,
  MerchantView,
  OrgView,
  UnfinalizedInvoicesView,
} from "./_lib/types";

/**
 * 관리 대시보드.
 *
 * 서버에는 "전체 개수" 전용 API 가 없다(서버 렌더링 /admin 이 JdbcTemplate 로 직접 센다). 그래서 여기서는
 * 목록 API 가 실제로 돌려준 것만 세고, 상한이 걸린 값(사용자 검색은 최대 50)은 `50+` 로 정직하게 표기한다.
 * 없는 숫자를 그럴듯하게 지어내면 운영 판단이 틀어진다.
 */
export default function AdminDashboardPage() {
  const users = useApi<AdminUserSummary[]>("/api/admin/users");
  const orgs = useApi<OrgView[]>("/api/admin/orgs");
  const clients = useApi<AdminClientView[]>("/api/admin/clients");
  const merchants = useApi<MerchantView[]>("/api/admin/merchants");
  const audit = useApi<AdminAuditEventView[]>("/api/admin/audit?limit=10&offset=0");
  const unfinalized = useApi<UnfinalizedInvoicesView>("/api/admin/invoices/unfinalized");

  const userCount = users.data ? (users.data.length >= 50 ? "50+" : users.data.length) : "—";

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="플랫폼 관리"
        description="조직·사용자·클라이언트·가맹점과 IAM 정책을 관리합니다. 변경 작업은 최근 재인증(step-up)이 필요하며, 필요할 때 자동으로 재인증 화면으로 이동합니다."
      />

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="사용자"
          value={userCount}
          hint={
            users.data && users.data.length >= 50
              ? "검색 API 상한 50건 — 정확한 총계는 제공되지 않습니다"
              : "최근 가입 순"
          }
          href="/admin/users"
        />
        <StatCard label="조직" value={orgs.data?.length ?? "—"} hint="테넌트" href="/admin/orgs" />
        <StatCard
          label="OAuth 클라이언트"
          value={clients.data?.length ?? "—"}
          hint="등록된 RP·M2M"
          href="/admin/clients"
        />
        <StatCard
          label="가맹점"
          value={merchants.data?.length ?? "—"}
          hint="식권 사용처"
          href="/admin/merchants"
        />
      </div>

      {(users.error || orgs.error || clients.error || merchants.error) && (
        <ErrorNotice
          message={
            users.error ?? orgs.error ?? clients.error ?? merchants.error ?? "요약을 불러오지 못했습니다"
          }
          onRetry={() => {
            users.reload();
            orgs.reload();
            clients.reload();
            merchants.reload();
          }}
        />
      )}

      <UnfinalizedInvoicesSection query={unfinalized} />

      <Section
        title="최근 감사 이벤트"
        description="플랫폼 전체에서 최근 발생한 10건입니다."
        actions={
          <Link href="/admin/audit" className="text-sm font-medium text-primary hover:underline">
            전체 보기
          </Link>
        }
      >
        {audit.loading ? (
          <RowsSkeleton rows={5} />
        ) : audit.error ? (
          <ErrorNotice message={audit.error} onRetry={audit.reload} />
        ) : !audit.data || audit.data.length === 0 ? (
          <p className="py-6 text-center text-sm text-muted-foreground">
            아직 기록된 감사 이벤트가 없습니다.
          </p>
        ) : (
          <TableScroll>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-40">시각</TableHead>
                  <TableHead>유형</TableHead>
                  <TableHead>대상 계정</TableHead>
                  <TableHead>상세</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {audit.data.map((event) => (
                  <TableRow key={event.id}>
                    <TableCell className="tabular whitespace-nowrap text-muted-foreground">
                      {formatDateTime(event.createdAt)}
                    </TableCell>
                    <TableCell className="font-medium whitespace-nowrap">{event.type}</TableCell>
                    <TableCell className="whitespace-nowrap">{event.email ?? "—"}</TableCell>
                    <TableCell
                      className="max-w-md truncate font-mono text-xs text-muted-foreground"
                      title={event.detail ?? ""}
                    >
                      {event.detail ?? "—"}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableScroll>
        )}
      </Section>

      <Section title="바로가기">
        <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
          <Shortcut
            href="/admin/orgs"
            title="조직"
            description="생성·정지·타임존·멤버 역할과 자동가입 도메인"
          />
          <Shortcut href="/admin/users" title="사용자" description="검색·정지·전 세션 종료·역할 변경" />
          <Shortcut href="/admin/clients" title="OAuth 클라이언트" description="등록·수정·시크릿 재발급" />
          <Shortcut href="/admin/iam" title="IAM 정책" description="정책·그룹·부착과 인가 시뮬레이터" />
          <Shortcut href="/admin/sso" title="기업 SSO" description="OIDC·SAML 커넥션과 도메인 검증" />
          <Shortcut href="/admin/calendar" title="캘린더" description="조직 휴일·근무 피드 동기화" />
          <Shortcut
            href="/admin/payables"
            title="지급 현황"
            description="이번 달 가맹점에 지급할 금액(집계 — 실 이체는 별도)"
          />
          <Shortcut
            href="/admin/reconciliation"
            title="정합성 대사"
            description="원장·장부·소비이벤트가 갈라진 조직 조기 경보"
          />
        </div>
      </Section>
    </div>
  );
}

/**
 * 지난달 청구서 중 **확정되지 않은 것**.
 *
 * ★자동 생성 루프의 마지막 구멍이다: 초안은 매일 자동으로 만들어지고 조직관리자에게 메일까지 나가지만,
 * 그 사람이 확정하지 않으면 청구서는 방치되고 회사가 쓴 식대를 우리가 **끝내 청구하지 않는다**.
 * 알람이 울리지 않는 매출 누락이라, 운영자가 볼 곳이 없으면 아무도 모른다.
 *
 * 정상일 때는 조용하다(줄이 없으면 한 문장). 대신 **훑은 조직 수**를 함께 말해 "다 확정됐다"와
 * "아무것도 안 봤다"를 구별한다.
 */
function UnfinalizedInvoicesSection({ query }: { query: Query<UnfinalizedInvoicesView> }) {
  const data = query.data;
  const lines = data?.lines ?? [];

  return (
    <Section
      title="미확정 청구서"
      description={
        data
          ? `${data.period} 기준 — 확정되지 않으면 그 달은 청구되지 않습니다.`
          : "지난달 기준 — 확정되지 않으면 그 달은 청구되지 않습니다."
      }
    >
      {query.loading && !data ? (
        <RowsSkeleton rows={3} />
      ) : query.error ? (
        <ErrorNotice message={query.error} onRetry={query.reload} />
      ) : !data ? null : data.scanned === 0 ? (
        <p className="py-6 text-center text-sm text-muted-foreground">
          이 기간에 결제가 있는 조직이 없어 <strong>확인할 청구서가 없습니다</strong>.
        </p>
      ) : lines.length === 0 ? (
        <p className="py-6 text-center text-sm text-muted-foreground">
          {/* ★실패·상한을 말하지 않으면 "모두 확정"이 거짓 완결 선언이 된다. */}
          {data.failed > 0 || data.skipped > 0
            ? `${data.scanned - data.failed}개 조직에서 미확정 청구서를 찾지 못했습니다` +
              `(${data.failed > 0 ? `검사 실패 ${data.failed}개` : ""}` +
              `${data.failed > 0 && data.skipped > 0 ? ", " : ""}` +
              `${data.skipped > 0 ? `상한 초과 ${data.skipped}개` : ""} 제외).`
            : `${data.scanned}개 조직의 청구서가 모두 확정되었습니다.`}
        </p>
      ) : (
        <div className="flex flex-col gap-3">
          {(data.failed > 0 || data.skipped > 0) && (
            <p className="rounded-lg border border-[color:var(--taspa-warning)]/40 bg-[color:var(--taspa-warning-soft)] px-3 py-2 text-sm text-[color:var(--taspa-warning)]">
              {data.failed > 0 && <>검사에 실패한 조직 {data.failed}개가 있습니다. </>}
              {data.skipped > 0 && <>상한을 넘어 {data.skipped}개를 확인하지 못했습니다. </>}
              아래 목록은 <strong>전부가 아닙니다</strong>.
            </p>
          )}
          <TableScroll>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>조직</TableHead>
                  <TableHead>상태</TableHead>
                  <TableHead className="text-right">청구 예정액</TableHead>
                  <TableHead className="w-24" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {lines.map((line) => (
                  <TableRow key={line.orgId}>
                    <TableCell>
                      <p className="font-medium">{line.orgName}</p>
                      <p className="text-xs text-muted-foreground">{line.timezone}</p>
                    </TableCell>
                    <TableCell>
                      {line.state === "MISSING" ? (
                        // 사람이 안 누른 것보다 시스템이 못 만든 것이 심각하다 — 색으로도 구별한다.
                        <span className="text-sm font-medium text-destructive">청구서 없음</span>
                      ) : line.state === "PENDING" ? (
                        // 아직 만들 시점이 아니다(유예 기간) — 정상 상태라 경보 색을 쓰지 않는다.
                        <span className="text-sm text-muted-foreground">생성 대기 (유예 기간)</span>
                      ) : (
                        <span className="text-sm text-foreground">초안 (미확정)</span>
                      )}
                    </TableCell>
                    <TableCell className="tabular text-right whitespace-nowrap">
                      {line.subtotalMinor === null ? (
                        <span className="text-muted-foreground">—</span>
                      ) : (
                        `${line.subtotalMinor.toLocaleString("ko-KR")}원`
                      )}
                    </TableCell>
                    <TableCell className="text-right">
                      <Link
                        href={`/console/${line.orgId}/invoices`}
                        className="text-sm font-medium text-primary hover:underline"
                      >
                        청구서 열기
                      </Link>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableScroll>
        </div>
      )}
    </Section>
  );
}

function Shortcut({ href, title, description }: { href: string; title: string; description: string }) {
  return (
    <Link
      href={href}
      className="rounded-lg border border-border px-3 py-2.5 transition-colors hover:border-primary hover:bg-accent/40"
    >
      <p className="text-sm font-medium text-foreground">{title}</p>
      <p className="mt-0.5 text-xs text-muted-foreground">{description}</p>
    </Link>
  );
}
