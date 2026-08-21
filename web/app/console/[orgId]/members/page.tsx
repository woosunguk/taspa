"use client";

import { useMemo, useState } from "react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { EmptyState, ErrorNotice, Loading, RowsSkeleton } from "@/components/feedback";
import { api } from "@/lib/api";
import { useApi, useMutation } from "@/lib/useApi";
import {
  Choice,
  ConfirmButton,
  Field,
  Section,
  TableScroll,
  type Option,
} from "../../_components/console-ui";
import { orgPath, useOrg } from "../../_lib/org-context";
import {
  EMPLOYMENT_STATUS_OPTIONS,
  EMPLOYMENT_TYPE_OPTIONS,
  ROLE_OPTIONS,
  changeTypeLabel,
  employmentStatusLabel,
  employmentTypeLabel,
  formatDate,
  formatDateTime,
  membershipStatusLabel,
  roleLabel,
} from "../../_lib/labels";
import { flattenTree } from "../../_lib/tree";
import type { Department, Membership, MembershipHistoryEntry, Site } from "../../_lib/types";

/**
 * 구성원 탭 — 목록·역할 변경·제거·부서/사업장 배정·HR 속성 편집·변경 이력.
 *
 * 서버가 거부하는 일(마지막 조직관리자 강등·제거 등)은 **화면에서 미리 막지 않는다**. 판정은 서버가
 * 동시성까지 고려해 내리므로, 화면이 흉내 내면 두 판정이 어긋나는 순간 사용자가 혼란스러워진다.
 * 대신 서버가 돌려준 문구를 그대로 보여준다.
 */
export default function MembersPage() {
  const { orgId } = useOrg();
  const members = useApi<Membership[]>(orgPath(orgId, "/members"), [orgId]);
  const departments = useApi<Department[]>(orgPath(orgId, "/departments"), [orgId]);
  const sites = useApi<Site[]>(orgPath(orgId, "/sites"), [orgId]);

  const [keyword, setKeyword] = useState("");
  const [editing, setEditing] = useState<Membership | null>(null);
  const [historyOf, setHistoryOf] = useState<Membership | null>(null);

  const departmentName = useMemo(() => {
    const map = new Map<string, string>();
    for (const dept of departments.data ?? []) map.set(dept.id, dept.name);
    return map;
  }, [departments.data]);

  const siteName = useMemo(() => {
    const map = new Map<string, string>();
    for (const site of sites.data ?? []) map.set(site.id, site.name);
    return map;
  }, [sites.data]);

  const filtered = useMemo(() => {
    const rows = members.data ?? [];
    const needle = keyword.trim().toLowerCase();
    if (!needle) return rows;
    return rows.filter((member) =>
      [member.email, member.jobTitle, member.employeeId, member.department]
        .filter(Boolean)
        .some((value) => value!.toLowerCase().includes(needle)),
    );
  }, [members.data, keyword]);

  // 성공을 `true` 로 표현한다 — useMutation 은 실패 시 undefined 를 돌려주므로 void 반환이면 구분할 수 없다.
  const remove = useMutation(async (member: Membership) => {
    await api.delete<void>(orgPath(orgId, `/members/${encodeURIComponent(member.userId)}`));
    return true;
  });

  return (
    <div className="flex flex-col gap-5">
      <Section
        title="구성원"
        description="역할·부서·사업장 배정과 임직원 속성을 관리합니다."
        action={
          <Input
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            placeholder="이메일·직함·사번 검색"
            className="w-56"
            aria-label="구성원 검색"
          />
        }
      >
        {members.error && <ErrorNotice message={members.error} onRetry={members.reload} />}
        {remove.error && <ErrorNotice message={remove.error} onDismiss={remove.clearError} />}
        {members.loading && <RowsSkeleton rows={5} />}

        {!members.loading && !members.error && filtered.length === 0 && (
          <EmptyState
            title={keyword ? "검색 결과가 없습니다" : "구성원이 없습니다"}
            description={
              keyword
                ? "다른 검색어를 입력해 보세요."
                : "초대 탭에서 구성원을 초대하면 수락 후 이 목록에 나타납니다."
            }
          />
        )}

        {filtered.length > 0 && (
          <TableScroll>
            <Table>
              <TableHeader>
                {/*
                  ★모바일(390px)에서 8열을 그대로 밀면 **이메일 하나만 보이고** 이 탭의 존재 이유인
                  편집·이력·제거가 통째로 화면 밖으로 나간다(가로 스크롤이 가능하다는 표시도 없다).
                  그래서 ①보조 열은 lg 미만에서 숨기고 그 정보를 이메일 셀 아래 한 줄로 되살리며,
                  ②관리 열은 sticky 로 고정해 좁은 화면에서도 항상 손이 닿게 한다.
                */}
                <TableRow>
                  <TableHead>이메일</TableHead>
                  <TableHead>역할</TableHead>
                  <TableHead className="hidden lg:table-cell">부서</TableHead>
                  <TableHead className="hidden lg:table-cell">사업장</TableHead>
                  <TableHead className="hidden lg:table-cell">직함</TableHead>
                  <TableHead className="hidden sm:table-cell">재직</TableHead>
                  <TableHead className="hidden lg:table-cell">합류일</TableHead>
                  <TableHead className="sticky right-0 bg-card text-right">관리</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filtered.map((member) => (
                  <TableRow key={member.userId}>
                    <TableCell className="font-medium">
                      <div className="flex flex-col">
                        <span>{member.email ?? "(이메일 없음)"}</span>
                        {member.status !== "ACTIVE" && (
                          <span className="text-xs text-[color:var(--taspa-warning)]">
                            {membershipStatusLabel(member.status)}
                          </span>
                        )}
                        {/* 좁은 화면에서 숨긴 열(부서·사업장·직함)을 여기서 한 줄로 되살린다. */}
                        <span className="text-xs font-normal text-muted-foreground lg:hidden">
                          {[
                            member.departmentId
                              ? (departmentName.get(member.departmentId) ?? "(삭제된 부서)")
                              : member.department,
                            member.siteId ? (siteName.get(member.siteId) ?? "(삭제된 사업장)") : null,
                            member.jobTitle,
                          ]
                            .filter(Boolean)
                            .join(" · ") || "부서·사업장 미배정"}
                        </span>
                      </div>
                    </TableCell>
                    <TableCell>
                      <Badge variant={member.role === "ORG_ADMIN" ? "default" : "secondary"}>
                        {roleLabel(member.role)}
                      </Badge>
                    </TableCell>
                    <TableCell className="hidden lg:table-cell">
                      {member.departmentId
                        ? (departmentName.get(member.departmentId) ?? "(삭제된 부서)")
                        : (member.department ?? "—")}
                    </TableCell>
                    <TableCell className="hidden lg:table-cell">
                      {member.siteId ? (siteName.get(member.siteId) ?? "(삭제된 사업장)") : "—"}
                    </TableCell>
                    <TableCell className="hidden lg:table-cell">{member.jobTitle ?? "—"}</TableCell>
                    <TableCell className="hidden sm:table-cell">
                      {employmentStatusLabel(member.employmentStatus)}
                    </TableCell>
                    <TableCell className="tabular hidden whitespace-nowrap lg:table-cell">
                      {formatDate(member.joinedAt)}
                    </TableCell>
                    <TableCell className="sticky right-0 bg-card">
                      <div className="flex justify-end gap-1">
                        <Button variant="outline" size="sm" onClick={() => setEditing(member)}>
                          편집
                        </Button>
                        <Button variant="ghost" size="sm" onClick={() => setHistoryOf(member)}>
                          이력
                        </Button>
                        <ConfirmButton
                          variant="ghost"
                          disabled={remove.busy}
                          confirmLabel="제거 확정"
                          onConfirm={async () => {
                            const done = await remove.mutate(member);
                            if (done) {
                              toast.success("구성원을 제거했습니다");
                              members.reload();
                            }
                          }}
                        >
                          제거
                        </ConfirmButton>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableScroll>
        )}
      </Section>

      {editing && (
        <MemberEditor
          orgId={orgId}
          member={editing}
          departments={departments.data ?? []}
          sites={sites.data ?? []}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            members.reload();
          }}
        />
      )}

      {historyOf && (
        <HistoryDialog
          orgId={orgId}
          member={historyOf}
          departmentName={departmentName}
          siteName={siteName}
          onClose={() => setHistoryOf(null)}
        />
      )}
    </div>
  );
}

/**
 * 구성원 편집 — 서버가 역할·배정·속성을 **서로 다른 엔드포인트**로 나눠 두었다(각각 감사 이벤트가 다르다).
 * 그래서 저장은 바뀐 부분만 순서대로 호출하고, 하나라도 실패하면 거기서 멈춘 뒤 사유를 보여준다
 * (이미 성공한 앞 호출은 서버에 반영된 상태 — 목록을 다시 읽어 실제 상태를 보여준다).
 */
function MemberEditor({
  orgId,
  member,
  departments,
  sites,
  onClose,
  onSaved,
}: {
  orgId: string;
  member: Membership;
  departments: Department[];
  sites: Site[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [role, setRole] = useState<string | null>(member.role);
  const [departmentId, setDepartmentId] = useState<string | null>(member.departmentId);
  const [siteId, setSiteId] = useState<string | null>(member.siteId);
  const [employeeId, setEmployeeId] = useState(member.employeeId ?? "");
  const [jobTitle, setJobTitle] = useState(member.jobTitle ?? "");
  const [employmentType, setEmploymentType] = useState<string | null>(member.employmentType);
  const [hireDate, setHireDate] = useState(member.hireDate ?? "");
  const [employmentStatus, setEmploymentStatus] = useState<string | null>(member.employmentStatus);

  const departmentOptions: Option[] = flattenTree(departments).map(({ item, depth }) => ({
    value: item.id,
    label: `${"  ".repeat(depth)}${depth > 0 ? "└ " : ""}${item.name}`,
  }));
  const siteOptions: Option[] = sites.map((site) => ({
    value: site.id,
    label: site.name,
  }));

  const save = useMutation(async () => {
    const path = orgPath(orgId, `/members/${encodeURIComponent(member.userId)}`);

    if (role && role !== member.role) {
      await api.put<Membership>(`${path}/role`, { role });
    }
    if (departmentId !== member.departmentId || siteId !== member.siteId) {
      await api.put<Membership>(`${path}/assignment`, { departmentId, siteId });
    }

    const attributesChanged =
      (employeeId.trim() || null) !== member.employeeId ||
      (jobTitle.trim() || null) !== member.jobTitle ||
      employmentType !== member.employmentType ||
      (hireDate.trim() || null) !== member.hireDate ||
      employmentStatus !== member.employmentStatus;

    if (attributesChanged) {
      await api.put<Membership>(`${path}/attributes`, {
        employeeId: employeeId.trim() || null,
        jobTitle: jobTitle.trim() || null,
        employmentType,
        hireDate: hireDate.trim() || null,
        employmentStatus,
      });
    }
    return true;
  });

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>구성원 편집</DialogTitle>
          <DialogDescription>{member.email ?? member.userId}</DialogDescription>
        </DialogHeader>

        {save.error && <ErrorNotice message={save.error} onDismiss={save.clearError} />}

        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="역할" htmlFor="member-role">
            <Choice id="member-role" value={role} onChange={setRole} options={ROLE_OPTIONS} />
          </Field>
          <Field label="재직 상태" htmlFor="member-emp-status">
            <Choice
              id="member-emp-status"
              value={employmentStatus}
              onChange={setEmploymentStatus}
              options={EMPLOYMENT_STATUS_OPTIONS}
            />
          </Field>
          <Field label="부서" htmlFor="member-dept" hint="배정을 비우면 부서 미배정이 됩니다.">
            <Choice
              id="member-dept"
              value={departmentId}
              onChange={setDepartmentId}
              options={departmentOptions}
              emptyLabel="미배정"
            />
          </Field>
          <Field label="사업장" htmlFor="member-site">
            <Choice
              id="member-site"
              value={siteId}
              onChange={setSiteId}
              options={siteOptions}
              emptyLabel="미배정"
            />
          </Field>
          <Field label="사번" htmlFor="member-employee-id">
            <Input
              id="member-employee-id"
              value={employeeId}
              onChange={(event) => setEmployeeId(event.target.value)}
              placeholder="예: 2024-0031"
            />
          </Field>
          <Field label="직함" htmlFor="member-job-title">
            <Input
              id="member-job-title"
              value={jobTitle}
              onChange={(event) => setJobTitle(event.target.value)}
              placeholder="예: 백엔드 엔지니어"
            />
          </Field>
          <Field label="고용 형태" htmlFor="member-emp-type">
            <Choice
              id="member-emp-type"
              value={employmentType}
              onChange={setEmploymentType}
              options={EMPLOYMENT_TYPE_OPTIONS}
              emptyLabel="미지정"
            />
          </Field>
          <Field label="입사일" htmlFor="member-hire-date">
            <Input
              id="member-hire-date"
              type="date"
              value={hireDate}
              onChange={(event) => setHireDate(event.target.value)}
            />
          </Field>
        </div>

        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose} disabled={save.busy}>
            취소
          </Button>
          <Button
            disabled={save.busy}
            onClick={async () => {
              const done = await save.mutate();
              if (done) {
                toast.success("구성원 정보를 저장했습니다");
                onSaved();
              }
            }}
          >
            {save.busy ? "저장 중" : "저장"}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

/** 멤버십 변경 이력(SCD). 개인정보는 담기지 않고 멤버십 상태 스냅샷만 온다. */
function HistoryDialog({
  orgId,
  member,
  departmentName,
  siteName,
  onClose,
}: {
  orgId: string;
  member: Membership;
  departmentName: Map<string, string>;
  siteName: Map<string, string>;
  onClose: () => void;
}) {
  const history = useApi<MembershipHistoryEntry[]>(
    orgPath(orgId, `/members/${encodeURIComponent(member.userId)}/history`),
    [orgId, member.userId],
  );

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>변경 이력</DialogTitle>
          <DialogDescription>{member.email ?? member.userId}</DialogDescription>
        </DialogHeader>

        {history.error && <ErrorNotice message={history.error} onRetry={history.reload} />}
        {history.loading && <Loading />}

        {!history.loading && history.data?.length === 0 && (
          <EmptyState title="이력이 없습니다" description="역할·배정·속성이 바뀌면 여기에 기록됩니다." />
        )}

        {(history.data?.length ?? 0) > 0 && (
          <TableScroll>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>시각</TableHead>
                  <TableHead>변경</TableHead>
                  <TableHead>역할</TableHead>
                  <TableHead>부서</TableHead>
                  <TableHead>사업장</TableHead>
                  <TableHead>재직</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {history.data?.map((entry) => (
                  <TableRow key={entry.id}>
                    <TableCell className="tabular whitespace-nowrap">
                      {formatDateTime(entry.recordedAt)}
                    </TableCell>
                    <TableCell>{changeTypeLabel(entry.changeType)}</TableCell>
                    <TableCell>{roleLabel(entry.role)}</TableCell>
                    <TableCell>
                      {entry.departmentId ? (departmentName.get(entry.departmentId) ?? "(삭제된 부서)") : "—"}
                    </TableCell>
                    <TableCell>
                      {entry.siteId ? (siteName.get(entry.siteId) ?? "(삭제된 사업장)") : "—"}
                    </TableCell>
                    <TableCell>
                      {employmentStatusLabel(entry.employmentStatus)}
                      {entry.employmentType && (
                        <span className="text-muted-foreground">
                          {" "}
                          · {employmentTypeLabel(entry.employmentType)}
                        </span>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableScroll>
        )}

        <div className="flex justify-end">
          <Button variant="outline" onClick={onClose}>
            닫기
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
