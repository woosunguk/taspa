"use client";

import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import type { Query } from "@/lib/useApi";
import { Section, Stat, TableScroll } from "../../_components/console-ui";
import { employmentStatusLabel, employmentTypeLabel, formatCount, roleLabel } from "../../_lib/labels";
import { flattenTree } from "../../_lib/tree";
import type { OrgDashboard } from "../../_lib/types";

/**
 * 인원 현황 — 식대·예측의 **모수**다. 재실 인원이 예측 보정에 그대로 쓰이므로 대시보드에 남긴다.
 * 개별 구성원 정보는 포함하지 않는다(구성원 탭의 일이다).
 */
export function HeadcountSection({ dashboard }: { dashboard: Query<OrgDashboard> }) {
  return (
    <Section
      title="인원 현황"
      description="활성 멤버십 기준 집계입니다(단위: 명). 식수 예측의 재실 보정 모수와 같은 기준입니다."
    >
      {dashboard.error && <ErrorNotice message={dashboard.error} onRetry={dashboard.reload} />}
      {dashboard.loading && <RowsSkeleton rows={3} />}
      {dashboard.data && <Body data={dashboard.data} />}
    </Section>
  );
}

function Body({ data }: { data: OrgDashboard }) {
  const departments = flattenTree(data.byDepartment);

  return (
    <div className="flex flex-col gap-5">
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="활성 구성원" value={`${formatCount(data.memberCount)}명`} />
        <Stat
          label="대기 중 초대"
          value={`${formatCount(data.pendingInvitations)}건`}
          hint="만료 전 초대만"
        />
        <Stat label="최근 30일 합류" value={`${formatCount(data.recentJoins30d)}명`} />
        <Stat label="사업장" value={`${formatCount(data.siteCount)}곳`} />
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <Breakdown title="역할" entries={data.byRole} labelOf={roleLabel} />
        <Breakdown title="재직 상태" entries={data.byEmploymentStatus} labelOf={employmentStatusLabel} />
        <Breakdown title="고용 형태" entries={data.byEmploymentType} labelOf={employmentTypeLabel} />
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <div>
          <h3 className="mb-2 text-sm font-medium text-foreground">부서별 인원</h3>
          {/*
            ★사업장 축과 **같은 조건**이어야 한다. 예전엔 `departments.length === 0` 만 보고
            '부서 미배정 N명'을 통째로 삼켰다 — 오른쪽 사업장 칸은 같은 상황에서 미배정 행을 보여주니
            두 축의 규칙이 달라 보였고, 미배정 인원이 있는데 화면에서 사라졌다(청구서 귀속이 흐려지는
            바로 그 인원이다).
          */}
          {departments.length === 0 && data.departmentUnassignedCount === 0 ? (
            <EmptyState
              title="등록된 부서가 없습니다"
              description="조직구조 탭에서 부서를 만들면 여기에 인원이 집계됩니다."
            />
          ) : (
            <TableScroll>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>부서</TableHead>
                    <TableHead className="text-right">직접</TableHead>
                    <TableHead className="text-right">하위 포함</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {departments.map(({ item, depth }) => (
                    <TableRow key={item.id}>
                      <TableCell>
                        <span style={{ paddingLeft: `${depth * 16}px` }} className="inline-block">
                          {depth > 0 && <span className="text-muted-foreground">└ </span>}
                          {item.name}
                        </span>
                      </TableCell>
                      <TableCell className="tabular text-right">{formatCount(item.directCount)}</TableCell>
                      <TableCell className="tabular text-right">{formatCount(item.rollupCount)}</TableCell>
                    </TableRow>
                  ))}
                  <TableRow>
                    <TableCell className="text-muted-foreground">부서 미배정</TableCell>
                    <TableCell className="tabular text-right">
                      {formatCount(data.departmentUnassignedCount)}
                    </TableCell>
                    <TableCell />
                  </TableRow>
                </TableBody>
              </Table>
            </TableScroll>
          )}
        </div>

        <div>
          <h3 className="mb-2 text-sm font-medium text-foreground">사업장별 인원</h3>
          {data.bySite.length === 0 && data.siteUnassignedCount === 0 ? (
            <EmptyState
              title="등록된 사업장이 없습니다"
              description="조직구조 탭에서 사업장을 만들면 여기에 인원이 집계됩니다."
            />
          ) : (
            <TableScroll>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>사업장</TableHead>
                    <TableHead className="text-right">인원</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {data.bySite.map((site) => (
                    <TableRow key={site.id}>
                      <TableCell>{site.name}</TableCell>
                      <TableCell className="tabular text-right">{formatCount(site.count)}</TableCell>
                    </TableRow>
                  ))}
                  <TableRow>
                    <TableCell className="text-muted-foreground">사업장 미배정</TableCell>
                    <TableCell className="tabular text-right">
                      {formatCount(data.siteUnassignedCount)}
                    </TableCell>
                  </TableRow>
                </TableBody>
              </Table>
            </TableScroll>
          )}
        </div>
      </div>
    </div>
  );
}

/** 서버는 enum 전 키를 0 포함해 내려주므로 빈 상태도 그대로 표기된다(임의로 감추지 않는다). */
function Breakdown({
  title,
  entries,
  labelOf,
}: {
  title: string;
  entries: Record<string, number>;
  labelOf: (value: string) => string;
}) {
  const rows = Object.entries(entries);
  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <h3 className="text-sm font-medium text-foreground">{title}</h3>
      {rows.length === 0 ? (
        <p className="mt-2 text-sm text-muted-foreground">집계 항목이 없습니다.</p>
      ) : (
        <dl className="mt-2 flex flex-col gap-1">
          {rows.map(([key, count]) => (
            <div key={key} className="flex items-baseline justify-between gap-3 text-sm">
              <dt className="text-muted-foreground">{labelOf(key)}</dt>
              <dd className="tabular font-medium text-foreground">{formatCount(count)}</dd>
            </div>
          ))}
        </dl>
      )}
    </div>
  );
}
