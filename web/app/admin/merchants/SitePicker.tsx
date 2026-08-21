"use client";

import { useEffect, useState } from "react";
import { useApi } from "@/lib/useApi";
import { SelectField, TextField } from "../_components/kit";
import type { OrgView } from "../_lib/types";

/**
 * 가맹점 ↔ 고객사 사업장 연결 피커(조직 → 사업장 2단).
 *
 * ★그전에는 "사업장 UUID (선택)" **텍스트 입력** 하나였다. 그런데 사업장을 만드는 유일한 화면
 * (조직 콘솔 → 조직구조 탭)이 그 UUID 를 화면 어디에도 싣지 않아서, 온보딩의 마지막 단계인
 * 가맹점 연결을 **DB 를 직접 열지 않고는 완료할 수 없었다**. 목록의 '사업장' 열도 이름이 아니라
 * UUID 앞 8자만 보여 사후 확인조차 되지 않았다.
 *
 * 결과는 조용한 실패다: 연결을 건너뛰면 등록은 성공하고 결제도 되지만 소비 이벤트의 site 축이 비어,
 * 사업장별 식수 예측이 **틀렸다는 신호 없이** 계속 어긋난다.
 *
 * 사업장 조회는 `/api/orgs/{orgId}/sites` — 이 경로의 인가는 "플랫폼 ADMIN ∨ 그 조직의 ORG_ADMIN"
 * 이라 플랫폼 관리자가 그대로 쓸 수 있다(관리 전용 엔드포인트를 새로 만들 필요가 없다).
 */

/** `/api/orgs/{orgId}/sites` 응답 중 이 화면이 쓰는 부분. */
interface SiteOption {
  id: string;
  name: string;
  timezone: string;
}

const NONE = "__none__";
/** 목록에 없는 값을 이미 갖고 있을 때의 탈출구(조직이 지워졌거나 다른 조직의 사업장인 경우). */
const RAW = "__raw__";

export function SitePicker({
  value,
  onChange,
  /** 수정 중인 가맹점이 이미 갖고 있는 사업장 id — 어느 조직 소속인지 역추적하는 출발점. */
  initialSiteId,
}: {
  value: string;
  onChange: (siteId: string) => void;
  initialSiteId?: string;
}) {
  const orgs = useApi<OrgView[]>("/api/admin/orgs");
  const [orgId, setOrgId] = useState("");
  // 직접 입력 모드는 사용자가 명시적으로 고를 때만 — 자동으로 빠지면 피커를 만든 의미가 없다.
  const [manual, setManual] = useState(false);
  const sites = useApi<SiteOption[]>(orgId ? `/api/orgs/${orgId}/sites` : null);

  /*
   * 수정 진입 시 기존 값이 어느 조직 것인지 모른다(가맹점 DTO 는 siteId 만 갖는다). 조직을 고르면
   * 그 조직의 사업장 목록에서 일치하는 항목이 드러나고, 아니면 사용자가 다른 조직을 고르면 된다.
   * 조직을 훑어 다니며 자동 탐색하지 않는다 — 조직이 수십 개면 그만큼의 요청이 나간다.
   */
  const known = sites.data?.some((site) => site.id === value) ?? false;
  const hasUnresolved = value.length > 0 && !known && !manual;

  useEffect(() => {
    // 조직을 바꾸면 이전 조직의 사업장을 그대로 들고 있지 않는다(교차 테넌트 오귀속 방지).
    if (!orgId) return;
    if (value && sites.data && !sites.data.some((site) => site.id === value) && value !== initialSiteId) {
      onChange("");
    }
  }, [orgId, sites.data, value, initialSiteId, onChange]);

  if (manual) {
    return (
      <>
        <TextField
          label="사업장 UUID 직접 입력"
          value={value}
          onChange={onChange}
          placeholder="00000000-0000-0000-0000-000000000000"
          hint="목록에서 고를 수 없을 때만 사용합니다. 서버가 존재 여부를 검증합니다."
        />
        <button
          type="button"
          className="w-fit text-sm text-primary underline-offset-2 hover:underline"
          onClick={() => setManual(false)}
        >
          목록에서 고르기로 돌아가기
        </button>
      </>
    );
  }

  return (
    <>
      <SelectField
        label="사업장 연결 — 고객사 (선택)"
        value={orgId}
        onChange={setOrgId}
        options={[
          { value: "", label: orgs.loading ? "조직을 불러오는 중…" : "선택 안 함 (연결 없음)" },
          ...(orgs.data ?? []).map((org) => ({
            value: org.id,
            label: `${org.name} (${org.slug})`,
          })),
        ]}
        hint="연결하면 이 매장의 결제가 그 고객사 사업장의 소비로 집계됩니다. 사업장별 식수 예측의 축입니다."
      />
      {orgId && (
        <SelectField
          label="사업장"
          value={known ? value : NONE}
          onChange={(next) => {
            if (next === RAW) {
              setManual(true);
              return;
            }
            onChange(next === NONE ? "" : next);
          }}
          options={[
            { value: NONE, label: sites.loading ? "사업장을 불러오는 중…" : "선택 안 함 (연결 없음)" },
            ...(sites.data ?? []).map((site) => ({
              value: site.id,
              label: `${site.name} · ${site.timezone}`,
            })),
            { value: RAW, label: "UUID 직접 입력…" },
          ]}
          hint={
            sites.data?.length === 0
              ? "이 조직에는 아직 사업장이 없습니다. 조직 콘솔의 '조직구조' 탭에서 먼저 만들어 주세요."
              : undefined
          }
        />
      )}
      {hasUnresolved && (
        <p className="text-sm text-muted-foreground">
          현재 연결된 사업장: <span className="font-mono">{value}</span> — 소속 고객사를 위에서 고르면
          이름으로 확인할 수 있습니다.{" "}
          <button
            type="button"
            className="text-primary underline-offset-2 hover:underline"
            onClick={() => onChange("")}
          >
            연결 해제
          </button>
        </p>
      )}
    </>
  );
}
