"use client";

import { useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { api } from "@/lib/api";
import { useApi, useMutation } from "@/lib/useApi";
import {
  Choice,
  ConfirmButton,
  Field,
  FieldAction,
  Section,
  TableScroll,
  type Option,
} from "../../_components/console-ui";
import { orgPath } from "../../_lib/org-context";
import { formatDateTime } from "../../_lib/labels";
import { flattenTree } from "../../_lib/tree";
import type { Department, DepartmentDelegation, Membership } from "../../_lib/types";

/**
 * 부서 관리자 위임 — "이 사람은 이 부서와 그 하위만 관리한다".
 *
 * 조직구조 탭에 두는 이유: 위임의 단위가 부서라서, 트리를 보면서 누구에게 어디를 맡길지 정하는 것이
 * 자연스럽다. 구성원 탭에 두면 사람 목록에서 부서를 떠올려야 한다.
 *
 * 화면이 분명히 말해야 할 것은 **위임이 무엇을 주지 않는가**다. 조직관리자는 "부서장을 세웠으니 이제
 * 안 봐도 되겠다"고 생각하기 쉬운데, 초대·역할 변경·식대 정책은 여전히 조직관리자만 할 수 있다.
 */
export function DelegationSection({ orgId }: { orgId: string }) {
  const delegations = useApi<DepartmentDelegation[]>(orgPath(orgId, "/delegations"), [orgId]);
  const departments = useApi<Department[]>(orgPath(orgId, "/departments"), [orgId]);
  const members = useApi<Membership[]>(orgPath(orgId, "/members"), [orgId]);

  const [userId, setUserId] = useState<string | null>(null);
  const [departmentId, setDepartmentId] = useState<string | null>(null);

  const grant = useMutation(async () =>
    api.post<DepartmentDelegation>(orgPath(orgId, "/delegations"), {
      userId,
      departmentId,
    }),
  );

  const revoke = useMutation(async (target: DepartmentDelegation) => {
    await api.delete<void>(orgPath(orgId, `/delegations/${encodeURIComponent(target.userId)}`));
    return true;
  });

  const rows = delegations.data ?? [];
  const delegatedUserIds = new Set(rows.map((row) => row.userId));

  // 이미 위임을 가진 사람과 조직관리자는 후보에서 뺀다 — 서버가 거절하는 조합을 화면이 먼저 걸러
  // "눌러 봐야 아는" 상황을 줄인다(판정 자체는 서버가 한다).
  const candidates: Option[] = (members.data ?? [])
    .filter((m) => m.role !== "ORG_ADMIN" && m.status === "ACTIVE" && !delegatedUserIds.has(m.userId))
    .map((m) => ({ value: m.userId, label: m.email ?? m.userId }));

  const departmentOptions: Option[] = flattenTree(departments.data ?? []).map((node) => ({
    value: node.item.id,
    label: `${"— ".repeat(node.depth)}${node.item.name}`,
  }));

  return (
    <Section
      title="부서 관리자 위임"
      description="지정한 사람이 그 부서와 하위 부서의 구성원만 조회·배정·정보 수정할 수 있습니다. 초대·역할 변경·식대 정책은 여전히 조직관리자만 할 수 있습니다."
    >
      {delegations.error && <ErrorNotice message={delegations.error} onRetry={delegations.reload} />}
      {grant.error && <ErrorNotice message={grant.error} onDismiss={grant.clearError} />}
      {revoke.error && <ErrorNotice message={revoke.error} onDismiss={revoke.clearError} />}
      {delegations.loading && <RowsSkeleton rows={2} />}

      <form
        className="grid gap-4 sm:grid-cols-[1fr_1fr_auto] sm:items-start"
        noValidate
        onSubmit={async (event) => {
          event.preventDefault();
          if (!userId || !departmentId) {
            toast.error("담당자와 부서를 모두 선택해 주세요");
            return;
          }
          const done = await grant.mutate();
          if (done) {
            toast.success("부서 관리자를 지정했습니다");
            setUserId(null);
            setDepartmentId(null);
            delegations.reload();
          }
        }}
      >
        {/*
          ★고를 것이 없으면 **왜 없는지**를 말한다. 예전엔 후보가 0명·부서가 0개인 초기 상태에서도
          "지정"만 완전 활성이라 화면에서 가장 강한 버튼이 눌러도 토스트 오류만 냈다 —
          바로 옆의 '부서 추가'·'사업장 추가'는 같은 상황에서 비활성인데 규칙이 어긋나 있었다.
        */}
        <Field
          label="담당자"
          htmlFor="delegate-user"
          hint={
            candidates.length === 0
              ? "위임할 수 있는 구성원이 없습니다(조직관리자는 제외)"
              : "조직관리자에게는 위임할 수 없습니다"
          }
        >
          <Choice
            id="delegate-user"
            value={userId}
            options={candidates}
            placeholder="구성원 선택"
            disabled={candidates.length === 0}
            onChange={setUserId}
          />
        </Field>
        <Field
          label="담당 부서"
          htmlFor="delegate-dept"
          hint={
            departmentOptions.length === 0
              ? "먼저 위 '부서'에서 부서를 만들어야 위임할 수 있습니다"
              : "하위 부서까지 함께 맡습니다"
          }
        >
          <Choice
            id="delegate-dept"
            value={departmentId}
            options={departmentOptions}
            placeholder="부서 선택"
            disabled={departmentOptions.length === 0}
            onChange={setDepartmentId}
          />
        </Field>
        {/* 힌트 줄 높이만큼 버튼이 내려가지 않게 입력 줄에 맞춰 붙인다(sm 이상에서만 의미가 있다). */}
        <FieldAction>
          <Button type="submit" disabled={grant.busy || !userId || !departmentId}>
            {grant.busy ? "지정 중…" : "지정"}
          </Button>
        </FieldAction>
      </form>

      {!delegations.loading && rows.length === 0 && (
        <EmptyState
          title="지정된 부서 관리자가 없습니다"
          description="부서를 만들고 담당자를 지정하면 그 부서와 하위 부서의 구성원 관리를 맡길 수 있습니다."
        />
      )}

      {rows.length > 0 && (
        <TableScroll>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>담당자</TableHead>
                <TableHead>담당 부서</TableHead>
                <TableHead>지정일</TableHead>
                <TableHead>작업</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((row) => (
                <TableRow key={row.id}>
                  <TableCell>{row.userEmail ?? row.userId}</TableCell>
                  <TableCell>{row.departmentName ?? "(삭제됨)"}</TableCell>
                  <TableCell className="whitespace-nowrap tabular-nums">
                    {formatDateTime(row.createdAt)}
                  </TableCell>
                  <TableCell>
                    <ConfirmButton
                      onConfirm={async () => {
                        const done = await revoke.mutate(row);
                        if (done) {
                          toast.success("위임을 해제했습니다");
                          delegations.reload();
                        }
                      }}
                    >
                      해제
                    </ConfirmButton>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableScroll>
      )}
    </Section>
  );
}
