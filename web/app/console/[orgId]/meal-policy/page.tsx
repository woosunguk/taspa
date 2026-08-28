"use client";

import { useState } from "react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { api } from "@/lib/api";
import { useApi, useMutation } from "@/lib/useApi";
import { Field, Section } from "../../_components/console-ui";
import { orgPath, useOrg } from "../../_lib/org-context";
import { formatDateTime, formatMinor } from "../../_lib/labels";
import type { MealPolicy, MealPolicyRevision } from "../../_lib/types";
import { OverrideSection } from "./OverrideSection";

/** 폼 상태 — 전 필드 문자열로 잡는다(입력 중간 상태를 숫자로 강제하면 지우는 순간 0 이 된다). */
interface FormState {
  perMealLimitMinor: string;
  dailyMealCount: string;
  monthlyCapMinor: string;
  breakfastStart: string;
  breakfastEnd: string;
  lunchStart: string;
  lunchEnd: string;
  dinnerStart: string;
  dinnerEnd: string;
}

/**
 * `HH:mm:ss` 로 오는 서버 값을 `<input type="time">` 이 받는 `HH:mm` 으로 줄인다.
 *
 * null 을 받는 이유: 같은 이력 표에 **재정의** 스냅샷도 들어오는데, 재정의는 필드 단위라 재정의하지
 * 않은 시각이 null 이다. 조직 기준만 있던 시절의 타입(전부 string)을 그대로 두면 그 행에서 화면이
 * 통째로 죽는다(실제로 죽었다).
 */
function toTimeInput(value: string | null | undefined): string {
  return value ? value.slice(0, 5) : "";
}

function formOf(policy: MealPolicy): FormState {
  return {
    perMealLimitMinor: String(policy.perMealLimitMinor),
    dailyMealCount: String(policy.dailyMealCount),
    monthlyCapMinor: String(policy.monthlyCapMinor),
    breakfastStart: toTimeInput(policy.breakfastStart),
    breakfastEnd: toTimeInput(policy.breakfastEnd),
    lunchStart: toTimeInput(policy.lunchStart),
    lunchEnd: toTimeInput(policy.lunchEnd),
    dinnerStart: toTimeInput(policy.dinnerStart),
    dinnerEnd: toTimeInput(policy.dinnerEnd),
  };
}

const MEALS: { key: "breakfast" | "lunch" | "dinner"; label: string }[] = [
  { key: "breakfast", label: "아침" },
  { key: "lunch", label: "점심" },
  { key: "dinner", label: "저녁" },
];

/**
 * 저장 전 우리 문구로 하는 검증 — **브라우저 기본 검증을 쓰지 않는 이유**가 여기 있다.
 *
 * `<input type="number" max=…>` 는 제출을 막아 주지만 뜨는 말풍선이 브라우저 언어의 영문
 * ("Value must be less than or equal to 1000000")이라, 한국어 화면 한가운데 영문 오류가 뜨고
 * 문구도 서버가 돌려주는 한국어 400 과 달라진다. 같은 규칙이면 같은 말로 거절해야 한다.
 *
 * 판정 권위는 여전히 서버다 — 여기는 왕복 한 번을 아끼는 안내일 뿐이다.
 */
function localIssue(form: FormState, policy: MealPolicy): string | null {
  const perMeal = Number(form.perMealLimitMinor);
  const daily = Number(form.dailyMealCount);
  const monthly = Number(form.monthlyCapMinor);
  if (!Number.isFinite(perMeal) || perMeal < 0 || perMeal > policy.ceilingPerMealLimitMinor) {
    return `1식 한도는 0원 이상 ${formatMinor(policy.ceilingPerMealLimitMinor)} 이하여야 합니다`;
  }
  if (!Number.isInteger(daily) || daily < 1 || daily > policy.ceilingDailyMealCount) {
    return `1일 횟수는 1회 이상 ${policy.ceilingDailyMealCount}회 이하여야 합니다`;
  }
  if (!Number.isFinite(monthly) || monthly < 0 || monthly > policy.ceilingMonthlyCapMinor) {
    return `월 한도는 0원 이상 ${formatMinor(policy.ceilingMonthlyCapMinor)} 이하여야 합니다`;
  }
  for (const meal of MEALS) {
    const start = form[`${meal.key}Start` as keyof FormState];
    const end = form[`${meal.key}End` as keyof FormState];
    if (!start || !end) return `${meal.label} 시간을 모두 입력해 주세요`;
    if (start >= end) {
      return `${meal.label} 시간은 시작이 종료보다 빨라야 합니다 (자정을 넘는 끼니는 지원하지 않습니다)`;
    }
  }
  return null;
}

const CHANGE_LABEL: Record<string, string> = {
  CREATED: "최초 설정",
  UPDATED: "변경",
  REMOVED: "해제",
};

/**
 * 식대정책 탭 — 조직관리자가 우리 회사의 1식 한도·1일 횟수·월 한도·끼니시간을 정한다.
 *
 * 이 화면이 생기기 전까지 그 값들은 서버 코드의 기본값(12,000원/1일 1회/월 20만원)으로 고정돼 있었고
 * 조직관리자는 자기 회사 한도조차 바꿀 수 없었다.
 *
 * 저장한 값은 즉시 직원 화면(`/meal`)과 계산대(POS)가 함께 쓴다 — 판정 코드가 한 곳이라 화면과
 * 계산대가 갈라질 수 없다. 그래서 이 폼은 **곧 회사 지출**이고, 변경 이력을 같은 화면에 붙여 둔다.
 */
export default function MealPolicyPage() {
  const { orgId } = useOrg();
  const policy = useApi<MealPolicy>(orgPath(orgId, "/meal-policy"), [orgId]);
  const history = useApi<MealPolicyRevision[]>(orgPath(orgId, "/meal-policy/history?limit=20"), [orgId]);
  const [form, setForm] = useState<FormState | null>(null);
  const [issue, setIssue] = useState<string | null>(null);

  // 서버 값이 도착하면 폼을 채운다. 사용자가 편집 중이면 덮어쓰지 않는다(form 이 이미 있으면 유지).
  // effect 가 아니라 **렌더 중**에 채우는 이유: effect 로 하면 값이 도착한 프레임에 폼이 한 번
  // 비어 보였다가 다음 프레임에 채워진다 — 금액 입력란이 0원으로 깜빡이는 형태라 오해를 만든다.
  if (policy.data && form === null) setForm(formOf(policy.data));

  const save = useMutation(async (next: FormState) =>
    api.put<MealPolicy>(orgPath(orgId, "/meal-policy"), {
      perMealLimitMinor: Number(next.perMealLimitMinor),
      dailyMealCount: Number(next.dailyMealCount),
      monthlyCapMinor: Number(next.monthlyCapMinor),
      breakfastStart: next.breakfastStart,
      breakfastEnd: next.breakfastEnd,
      lunchStart: next.lunchStart,
      lunchEnd: next.lunchEnd,
      dinnerStart: next.dinnerStart,
      dinnerEnd: next.dinnerEnd,
    }),
  );

  function set(key: keyof FormState, value: string) {
    setForm((prev) => (prev ? { ...prev, [key]: value } : prev));
  }

  const revisions = history.data ?? [];
  // 클로저 안에서 좁혀진 타입이 풀리므로 지역 상수로 붙잡는다.
  const current = policy.data;

  return (
    <div className="flex flex-col gap-5">
      <Section
        title="식사 기준"
        description="이 조직 구성원 전체에 적용됩니다. 저장하면 직원 화면과 계산대가 곧바로 같은 값을 사용합니다."
        action={
          policy.data?.usingDefaults ? (
            <Badge variant="secondary">기본값 사용 중</Badge>
          ) : policy.data?.updatedAt ? (
            <span className="text-sm text-muted-foreground">
              마지막 변경 {formatDateTime(policy.data.updatedAt)}
            </span>
          ) : null
        }
      >
        {policy.error && <ErrorNotice message={policy.error} onRetry={policy.reload} />}
        {save.error && <ErrorNotice message={save.error} onDismiss={save.clearError} />}
        {policy.loading && !form && <RowsSkeleton rows={4} />}

        {policy.data?.usingDefaults && (
          <p className="text-sm text-muted-foreground">
            아직 이 조직의 기준을 정한 적이 없어 taspa 기본값으로 운영되고 있습니다. 아래 값을 확인하고
            저장하면 이 조직의 기준이 됩니다.
          </p>
        )}

        {form && current && (
          <form
            className="flex flex-col gap-5"
            // 브라우저 기본 검증 말풍선(영문)을 끄고 우리 문구로 안내한다 — localIssue 주석 참고.
            noValidate
            onSubmit={async (event) => {
              event.preventDefault();
              const found = localIssue(form, current);
              setIssue(found);
              if (found) return;
              const saved = await save.mutate(form);
              if (saved) {
                toast.success("식대 기준을 저장했습니다");
                setForm(formOf(saved));
                policy.reload();
                history.reload();
              }
            }}
          >
            <div className="grid gap-4 sm:grid-cols-3">
              <Field
                label="1식 한도"
                htmlFor="per-meal"
                hint={`조직이 부담하는 한 끼 상한. 초과분은 거절이 아니라 개인 부담으로 나뉘어 승인됩니다. 최대 ${formatMinor(current.ceilingPerMealLimitMinor)}`}
              >
                <Input
                  id="per-meal"
                  type="number"
                  value={form.perMealLimitMinor}
                  onChange={(event) => set("perMealLimitMinor", event.target.value)}
                />
              </Field>

              <Field
                label="1일 횟수"
                htmlFor="daily-count"
                hint={`하루에 결제 가능한 횟수. 최대 ${current.ceilingDailyMealCount}회`}
              >
                <Input
                  id="daily-count"
                  type="number"
                  value={form.dailyMealCount}
                  onChange={(event) => set("dailyMealCount", event.target.value)}
                />
              </Field>

              <Field
                label="월 한도"
                htmlFor="monthly-cap"
                hint={`한 사람당 월 조직 부담 상한. 최대 ${formatMinor(current.ceilingMonthlyCapMinor)}`}
              >
                <Input
                  id="monthly-cap"
                  type="number"
                  value={form.monthlyCapMinor}
                  onChange={(event) => set("monthlyCapMinor", event.target.value)}
                />
              </Field>
            </div>

            <div className="flex flex-col gap-3">
              <div>
                <h3 className="text-sm font-medium text-foreground">끼니 시간</h3>
                <p className="text-sm text-muted-foreground">
                  조직 타임존({current.timezone}) 기준입니다. 시간대가 서로 겹치거나 자정을 넘으면 저장되지
                  않습니다 — 그런 설정은 해당 끼니가 조용히 사라지기 때문입니다.
                </p>
              </div>

              {MEALS.map((meal) => (
                /*
                  ★고정폭(w-32)을 쓰면 390px 에서 time 입력이 좁아 '06:00 AM' 의 'AM' 이 시계
                  아이콘에 잘리고, 시작·종료가 다른 줄로 갈라져 '~' 가 첫 줄 끝에 매달린다.
                  격자가 폭을 나눠 갖게 하면 어느 폭에서도 한 줄에 선다.
                */
                <div key={meal.key} className="grid grid-cols-[2.5rem_1fr_auto_1fr] items-center gap-2">
                  <span className="text-sm text-muted-foreground">{meal.label}</span>
                  <Input
                    aria-label={`${meal.label} 시작`}
                    type="time"
                    className="w-full min-w-0"
                    value={form[`${meal.key}Start` as keyof FormState]}
                    onChange={(event) => set(`${meal.key}Start` as keyof FormState, event.target.value)}
                  />
                  <span className="text-sm text-muted-foreground">~</span>
                  <Input
                    aria-label={`${meal.label} 종료`}
                    type="time"
                    className="w-full min-w-0"
                    value={form[`${meal.key}End` as keyof FormState]}
                    onChange={(event) => set(`${meal.key}End` as keyof FormState, event.target.value)}
                  />
                </div>
              ))}
            </div>

            {issue && (
              <p role="alert" className="text-sm text-[color:var(--taspa-danger)]">
                {issue}
              </p>
            )}

            <div className="flex items-center gap-2">
              <Button type="submit" disabled={save.busy}>
                {save.busy ? "저장 중…" : "저장"}
              </Button>
              <Button
                type="button"
                variant="ghost"
                disabled={save.busy}
                onClick={() => {
                  setForm(formOf(current));
                  setIssue(null);
                }}
              >
                되돌리기
              </Button>
            </div>
          </form>
        )}
      </Section>

      <OverrideSection
        orgId={orgId}
        policy={policy.data}
        onChanged={() => {
          history.reload();
        }}
      />

      <Section
        title="변경 이력"
        description="한도 변경은 그대로 회사 지출이 됩니다. 누가 언제 무엇을 바꿨는지 남습니다."
      >
        {history.error && <ErrorNotice message={history.error} onRetry={history.reload} />}
        {history.loading && <RowsSkeleton rows={3} />}
        {!history.loading && revisions.length === 0 && (
          <EmptyState
            title="아직 변경 이력이 없습니다"
            description="식대 기준을 저장하면 그 시점의 값이 여기에 남습니다."
          />
        )}

        {revisions.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border text-left text-muted-foreground">
                  <th className="py-2 pr-4 font-medium">시각</th>
                  <th className="py-2 pr-4 font-medium">대상</th>
                  <th className="py-2 pr-4 font-medium">구분</th>
                  <th className="py-2 pr-4 font-medium">1식 / 1일 / 월</th>
                  <th className="py-2 pr-4 font-medium">끼니 시간</th>
                  <th className="py-2 font-medium">변경자</th>
                </tr>
              </thead>
              <tbody>
                {revisions.map((revision) => {
                  const snapshot = parseSnapshot(revision.document);
                  return (
                    <tr key={revision.id} className="border-b border-border/60">
                      <td className="py-2 pr-4 whitespace-nowrap tabular-nums">
                        {formatDateTime(revision.recordedAt)}
                      </td>
                      <td className="py-2 pr-4 whitespace-nowrap">
                        {revision.scopeType === "ORG" ? (
                          <span className="text-muted-foreground">조직 전체</span>
                        ) : (
                          <div className="flex items-center gap-1.5">
                            <Badge variant="secondary">
                              {revision.scopeType === "DEPARTMENT" ? "부서" : "사업장"}
                            </Badge>
                            <span>{revision.scopeLabel ?? "(삭제됨)"}</span>
                          </div>
                        )}
                      </td>
                      <td className="py-2 pr-4">
                        {CHANGE_LABEL[revision.changeType] ?? revision.changeType}
                      </td>
                      <td className="py-2 pr-4 whitespace-nowrap tabular-nums">
                        {snapshot
                          ? [
                              inherited(snapshot.perMealLimitMinor, formatMinor),
                              inherited(snapshot.dailyMealCount, (v) => `${v}회`),
                              inherited(snapshot.monthlyCapMinor, formatMinor),
                            ].join(" / ")
                          : "—"}
                      </td>
                      <td className="py-2 pr-4 whitespace-nowrap tabular-nums">
                        {snapshot ? windowsOf(snapshot) : "—"}
                      </td>
                      <td className="py-2">
                        {revision.actorIsOrgMember ? (
                          (revision.actorEmail ?? "구성원")
                        ) : (
                          // 플랫폼 운영자의 변경임을 감추지 않는다 — "우리는 안 바꿨는데 한도가 달라졌다"를
                          // 조직이 스스로 가려낼 수 있어야 한다.
                          <Badge variant="secondary">taspa 운영자</Badge>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Section>
    </div>
  );
}

/** 이력에 저장된 전체 스냅샷(서버 `MealPolicyService.snapshotOf` 와 1:1). */
interface PolicySnapshot {
  // 조직 기준 스냅샷은 전 필드가 채워지지만, **재정의 스냅샷은 재정의한 필드만** 값이 있다.
  // null = "물려받음"이고, 그건 0 과 다른 사실이므로 표시도 달라야 한다.
  perMealLimitMinor: number | null;
  dailyMealCount: number | null;
  monthlyCapMinor: number | null;
  breakfastStart: string | null;
  breakfastEnd: string | null;
  lunchStart: string | null;
  lunchEnd: string | null;
  dinnerStart: string | null;
  dinnerEnd: string | null;
  effectiveFrom?: string | null;
  effectiveTo?: string | null;
  reason?: string | null;
}

/** 값이 없으면 "물려받음" — 0 과 혼동되면 안 되는 사실이다. */
function inherited<T>(value: T | null | undefined, format: (value: T) => string): string {
  return value === null || value === undefined ? "물려받음" : format(value);
}

/** 스냅샷의 끼니창 요약. 재정의 스냅샷은 지정한 창만 나온다(없으면 "물려받음"). */
function windowsOf(snapshot: PolicySnapshot): string {
  const parts = (
    [
      ["아침", snapshot.breakfastStart, snapshot.breakfastEnd],
      ["점심", snapshot.lunchStart, snapshot.lunchEnd],
      ["저녁", snapshot.dinnerStart, snapshot.dinnerEnd],
    ] as const
  )
    .filter(([, start]) => start)
    .map(([label, start, end]) => `${label} ${toTimeInput(start)}~${toTimeInput(end)}`);
  return parts.length > 0 ? parts.join(" · ") : "물려받음";
}

/** 이력 스냅샷 파싱 — 손상된 행 하나가 표 전체를 깨뜨리지 않게 실패는 null 로 흘린다. */
function parseSnapshot(document: string): PolicySnapshot | null {
  try {
    return JSON.parse(document) as PolicySnapshot;
  } catch {
    return null;
  }
}
