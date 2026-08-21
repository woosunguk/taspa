"use client";

import { Button } from "@/components/ui/button";
import type { MyMembership } from "./types";

/**
 * 결제할 조직 선택.
 *
 * 소속이 하나면 선택지가 아니라 **사실**이므로 버튼을 만들지 않고 그대로 표시한다(탭 한 번을 아낀다).
 * 여러 개면 드롭다운 대신 한 줄 세그먼트 버튼 — 매장에서 한 손으로 쓰는 화면이라 열고·고르고·닫는
 * 3단계보다 한 번 누르는 편이 낫다.
 */
export function OrgPicker({
  memberships,
  selectedOrgId,
  onSelect,
  disabled,
}: {
  memberships: MyMembership[];
  selectedOrgId: string | null;
  onSelect: (orgId: string) => void;
  disabled?: boolean;
}) {
  if (memberships.length === 0) return null;

  if (memberships.length === 1) {
    const only = memberships[0];
    return (
      /*
        ★조직명을 **문장 안에 박지 않는다.** 예전에는 "{조직명} 식대로 결제합니다" 한 문장이었는데,
        조직명이 길면(실제 고객사 이름은 길다) 문장이 두 줄로 접히면서 이름과 서술어가 뒤섞여
        제목 줄이 무너졌다. 이름은 이름대로 한 줄, 설명은 그 아래 작은 글씨로 분리하면 이름이
        아무리 길어도 레이아웃이 흔들리지 않는다.
      */
      <div className="flex flex-col items-center gap-0.5 text-center">
        <p className="font-medium text-foreground">{only.orgName}</p>
        <p className="text-xs text-muted-foreground">이 조직의 식대로 결제합니다</p>
      </div>
    );
  }

  return (
    <div>
      <p className="mb-2 text-center text-xs text-muted-foreground">어느 조직의 식대로 결제할까요?</p>
      <div className="flex flex-wrap justify-center gap-2" role="group" aria-label="결제할 조직">
        {memberships.map((membership) => {
          const selected = membership.orgId === selectedOrgId;
          return (
            <Button
              key={membership.orgId}
              type="button"
              variant={selected ? "default" : "outline"}
              size="lg"
              disabled={disabled}
              aria-pressed={selected}
              onClick={() => onSelect(membership.orgId)}
            >
              {membership.orgName}
            </Button>
          );
        })}
      </div>
    </div>
  );
}
