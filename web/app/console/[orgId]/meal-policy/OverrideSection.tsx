"use client";

import { useMemo, useState } from "react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { api } from "@/lib/api";
import { useApi, useMutation } from "@/lib/useApi";
import { ConfirmButton, Field, Section } from "../../_components/console-ui";
import { orgPath } from "../../_lib/org-context";
import { formatMinor } from "../../_lib/labels";
import type { Department, MealPolicy, MealPolicyOverride, MealPolicyPreview, Site } from "../../_lib/types";

/** 재정의 폼 — 비워 두면 "재정의하지 않음"이다. 그래서 전 필드가 문자열이고 빈 문자열이 곧 상속이다. */
interface OverrideForm {
  scopeType: "DEPARTMENT" | "SITE";
  scopeId: string;
  perMealLimitMinor: string;
  dailyMealCount: string;
  monthlyCapMinor: string;
  breakfastStart: string;
  breakfastEnd: string;
  lunchStart: string;
  lunchEnd: string;
  dinnerStart: string;
  dinnerEnd: string;
  effectiveFrom: string;
  effectiveTo: string;
  reason: string;
}

const EMPTY: OverrideForm = {
  scopeType: "DEPARTMENT",
  scopeId: "",
  perMealLimitMinor: "",
  dailyMealCount: "",
  monthlyCapMinor: "",
  breakfastStart: "",
  breakfastEnd: "",
  lunchStart: "",
  lunchEnd: "",
  dinnerStart: "",
  dinnerEnd: "",
  effectiveFrom: "",
  effectiveTo: "",
  reason: "",
};

/**
 * 재정의할 수 있는 끼니창 3종. **서버는 처음부터 6개 필드를 다 받았고**(MealPolicyOverrideRequest),
 * DB CHECK 도 끼니별 쌍 원자성·순서를 세 끼 모두에 걸어 두었다 — 화면만 점심 하나를 노출하고 있었다.
 * 목록으로 도는 이유: JSX 를 세 번 복제하면 한 끼만 고치는 실수가 반드시 생긴다.
 */
type TimeKey = "breakfastStart" | "breakfastEnd" | "lunchStart" | "lunchEnd" | "dinnerStart" | "dinnerEnd";

const MEAL_WINDOWS: { label: string; start: TimeKey; end: TimeKey }[] = [
  { label: "아침", start: "breakfastStart", end: "breakfastEnd" },
  { label: "점심", start: "lunchStart", end: "lunchEnd" },
  { label: "저녁", start: "dinnerStart", end: "dinnerEnd" },
];

function toTime(value: string | null): string {
  return value ? value.slice(0, 5) : "";
}

/** 빈 문자열은 보내지 않는다 — 서버에서 null 이 곧 "상속"이라 의미가 정확히 일치한다. */
function numberOrNull(raw: string): number | null {
  const trimmed = raw.trim();
  if (!trimmed) return null;
  const value = Number(trimmed);
  return Number.isFinite(value) ? value : null;
}

function textOrNull(raw: string): string | null {
  return raw.trim() || null;
}

/**
 * 부서·사업장 재정의 관리.
 *
 * 화면이 지켜야 할 한 가지: **비워 둔 칸은 "0"이 아니라 "물려받음"이다.** 그 구분이 흐려지면
 * 조직관리자가 점심시간만 바꾸려다 그 부서의 한도를 0원으로 만들 수 있다.
 */
export function OverrideSection({
  orgId,
  policy,
  onChanged,
}: {
  orgId: string;
  policy: MealPolicy | undefined;
  onChanged: () => void;
}) {
  const overrides = useApi<MealPolicyOverride[]>(orgPath(orgId, "/meal-policy/overrides"), [orgId]);
  const departments = useApi<Department[]>(orgPath(orgId, "/departments"), [orgId]);
  const sites = useApi<Site[]>(orgPath(orgId, "/sites"), [orgId]);

  const [form, setForm] = useState<OverrideForm>(EMPTY);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [previewOf, setPreviewOf] = useState<{
    type: string;
    id: string;
  } | null>(null);

  const preview = useApi<MealPolicyPreview>(
    previewOf
      ? orgPath(orgId, `/meal-policy/preview?scopeType=${previewOf.type}&scopeId=${previewOf.id}`)
      : null,
    [orgId, previewOf?.type, previewOf?.id],
  );

  const save = useMutation(async (next: OverrideForm) => {
    const body = {
      scopeType: next.scopeType,
      scopeId: next.scopeId,
      perMealLimitMinor: numberOrNull(next.perMealLimitMinor),
      dailyMealCount: numberOrNull(next.dailyMealCount),
      monthlyCapMinor: numberOrNull(next.monthlyCapMinor),
      breakfastStart: textOrNull(next.breakfastStart),
      breakfastEnd: textOrNull(next.breakfastEnd),
      lunchStart: textOrNull(next.lunchStart),
      lunchEnd: textOrNull(next.lunchEnd),
      dinnerStart: textOrNull(next.dinnerStart),
      dinnerEnd: textOrNull(next.dinnerEnd),
      effectiveFrom: textOrNull(next.effectiveFrom),
      effectiveTo: textOrNull(next.effectiveTo),
      reason: textOrNull(next.reason),
    };
    return editingId
      ? api.put<MealPolicyOverride>(orgPath(orgId, `/meal-policy/overrides/${editingId}`), body)
      : api.post<MealPolicyOverride>(orgPath(orgId, "/meal-policy/overrides"), body);
  });

  const remove = useMutation(async (target: MealPolicyOverride) => {
    await api.delete<void>(orgPath(orgId, `/meal-policy/overrides/${target.id}`));
    return true;
  });

  const scopeOptions = useMemo(
    () =>
      form.scopeType === "DEPARTMENT"
        ? (departments.data ?? []).map((d) => ({ id: d.id, name: d.name }))
        : (sites.data ?? []).map((s) => ({ id: s.id, name: s.name })),
    [form.scopeType, departments.data, sites.data],
  );

  function set(key: keyof OverrideForm, value: string) {
    setForm((prev) => ({ ...prev, [key]: value }));
  }

  function startEdit(target: MealPolicyOverride) {
    setEditingId(target.id);
    setForm({
      scopeType: target.scopeType,
      scopeId: target.scopeId,
      perMealLimitMinor: target.perMealLimitMinor?.toString() ?? "",
      dailyMealCount: target.dailyMealCount?.toString() ?? "",
      monthlyCapMinor: target.monthlyCapMinor?.toString() ?? "",
      breakfastStart: toTime(target.breakfastStart),
      breakfastEnd: toTime(target.breakfastEnd),
      lunchStart: toTime(target.lunchStart),
      lunchEnd: toTime(target.lunchEnd),
      dinnerStart: toTime(target.dinnerStart),
      dinnerEnd: toTime(target.dinnerEnd),
      effectiveFrom: target.effectiveFrom ?? "",
      effectiveTo: target.effectiveTo ?? "",
      reason: target.reason ?? "",
    });
  }

  const rows = overrides.data ?? [];

  return (
    <>
      <Section
        title="부서·사업장 예외"
        description="비워 둔 항목은 조직 기준을 그대로 물려받습니다. 그래서 조직 기준을 올리면 예외를 두지 않은 항목은 함께 오릅니다."
      >
        {overrides.error && <ErrorNotice message={overrides.error} onRetry={overrides.reload} />}
        {save.error && <ErrorNotice message={save.error} onDismiss={save.clearError} />}
        {remove.error && <ErrorNotice message={remove.error} onDismiss={remove.clearError} />}
        {overrides.loading && <RowsSkeleton rows={2} />}

        <form
          className="flex flex-col gap-4 rounded-lg border border-border p-4"
          noValidate
          onSubmit={async (event) => {
            event.preventDefault();
            if (!form.scopeId) {
              toast.error("대상을 선택해 주세요");
              return;
            }
            const saved = await save.mutate(form);
            if (saved) {
              toast.success(editingId ? "예외를 수정했습니다" : "예외를 추가했습니다");
              setForm(EMPTY);
              setEditingId(null);
              overrides.reload();
              onChanged();
            }
          }}
        >
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="대상 종류" htmlFor="scope-type">
              <select
                id="scope-type"
                className="h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm"
                value={form.scopeType}
                disabled={editingId !== null}
                onChange={(event) => {
                  set("scopeType", event.target.value);
                  set("scopeId", "");
                }}
              >
                <option value="DEPARTMENT">부서</option>
                <option value="SITE">사업장</option>
              </select>
            </Field>

            <Field label="대상" htmlFor="scope-id">
              <select
                id="scope-id"
                className="h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm"
                value={form.scopeId}
                disabled={editingId !== null}
                onChange={(event) => set("scopeId", event.target.value)}
              >
                <option value="">선택하세요</option>
                {scopeOptions.map((option) => (
                  <option key={option.id} value={option.id}>
                    {option.name}
                  </option>
                ))}
              </select>
            </Field>
          </div>

          <div className="grid gap-4 sm:grid-cols-3">
            <Field
              label="1식 한도"
              htmlFor="ov-per-meal"
              hint={
                policy ? `비우면 ${formatMinor(policy.perMealLimitMinor)} (조직 기준)` : "비우면 조직 기준"
              }
            >
              <Input
                id="ov-per-meal"
                type="number"
                placeholder="물려받음"
                value={form.perMealLimitMinor}
                onChange={(event) => set("perMealLimitMinor", event.target.value)}
              />
            </Field>
            <Field
              label="1일 횟수"
              htmlFor="ov-daily"
              hint={policy ? `비우면 ${policy.dailyMealCount}회 (조직 기준)` : "비우면 조직 기준"}
            >
              <Input
                id="ov-daily"
                type="number"
                placeholder="물려받음"
                value={form.dailyMealCount}
                onChange={(event) => set("dailyMealCount", event.target.value)}
              />
            </Field>
            <Field
              label="월 한도"
              htmlFor="ov-monthly"
              hint={policy ? `비우면 ${formatMinor(policy.monthlyCapMinor)} (조직 기준)` : "비우면 조직 기준"}
            >
              <Input
                id="ov-monthly"
                type="number"
                placeholder="물려받음"
                value={form.monthlyCapMinor}
                onChange={(event) => set("monthlyCapMinor", event.target.value)}
              />
            </Field>
          </div>

          <Field
            label="끼니 시간"
            hint="끼니별로 시작·종료를 함께 지정하거나, 둘 다 비워 조직 기준을 물려받습니다."
          >
            <div className="flex flex-col gap-3">
              {MEAL_WINDOWS.map((meal) => (
                /* 조직 기준 폼과 같은 이유로 고정폭을 쓰지 않는다(좁은 화면에서 AM/PM 이 잘린다). */
                <div
                  key={meal.label}
                  className="grid max-w-md grid-cols-[2.5rem_1fr_auto_1fr] items-center gap-2"
                >
                  <span className="text-sm text-muted-foreground">{meal.label}</span>
                  <Input
                    aria-label={`${meal.label} 시작`}
                    type="time"
                    className="w-full min-w-0"
                    value={form[meal.start]}
                    onChange={(event) => set(meal.start, event.target.value)}
                  />
                  <span className="text-sm text-muted-foreground">~</span>
                  <Input
                    aria-label={`${meal.label} 종료`}
                    type="time"
                    className="w-full min-w-0"
                    value={form[meal.end]}
                    onChange={(event) => set(meal.end, event.target.value)}
                  />
                </div>
              ))}
            </div>
          </Field>

          <div className="grid gap-4 sm:grid-cols-3">
            <Field label="적용 시작일" htmlFor="ov-from" hint="비우면 계속 적용됩니다">
              <Input
                id="ov-from"
                type="date"
                value={form.effectiveFrom}
                onChange={(event) => set("effectiveFrom", event.target.value)}
              />
            </Field>
            <Field label="적용 종료일" htmlFor="ov-to" hint="기간을 정하면 상시 예외보다 우선합니다">
              <Input
                id="ov-to"
                type="date"
                value={form.effectiveTo}
                onChange={(event) => set("effectiveTo", event.target.value)}
              />
            </Field>
            <Field label="사유" htmlFor="ov-reason" hint="나중에 왜 이렇게 정했는지 알 수 있게">
              <Input
                id="ov-reason"
                value={form.reason}
                placeholder="예: 야근 잦은 팀"
                onChange={(event) => set("reason", event.target.value)}
              />
            </Field>
          </div>

          <div className="flex items-center gap-2">
            <Button type="submit" disabled={save.busy}>
              {save.busy ? "저장 중…" : editingId ? "수정" : "예외 추가"}
            </Button>
            {editingId && (
              <Button
                type="button"
                variant="ghost"
                onClick={() => {
                  setEditingId(null);
                  setForm(EMPTY);
                }}
              >
                취소
              </Button>
            )}
          </div>
        </form>

        {!overrides.loading && rows.length === 0 && (
          <EmptyState
            title="예외가 없습니다"
            description="모든 구성원이 조직 기준을 그대로 적용받고 있습니다."
          />
        )}

        {rows.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border text-left text-muted-foreground">
                  <th className="py-2 pr-4 font-medium">대상</th>
                  <th className="py-2 pr-4 font-medium">1식 / 1일 / 월</th>
                  <th className="py-2 pr-4 font-medium">점심</th>
                  <th className="py-2 pr-4 font-medium">기간</th>
                  <th className="py-2 font-medium">작업</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id} className="border-b border-border/60">
                    <td className="py-2 pr-4">
                      <div className="flex items-center gap-2">
                        <Badge variant="secondary">
                          {row.scopeType === "DEPARTMENT" ? "부서" : "사업장"}
                        </Badge>
                        <span>{row.scopeLabel ?? "(삭제됨)"}</span>
                      </div>
                      {row.reason && <p className="mt-0.5 text-xs text-muted-foreground">{row.reason}</p>}
                    </td>
                    <td className="py-2 pr-4 whitespace-nowrap tabular-nums">
                      {inheritedOr(row.perMealLimitMinor, formatMinor)} /{" "}
                      {inheritedOr(row.dailyMealCount, (v) => `${v}회`)} /{" "}
                      {inheritedOr(row.monthlyCapMinor, formatMinor)}
                    </td>
                    <td className="py-2 pr-4 whitespace-nowrap tabular-nums">
                      {row.lunchStart ? `${toTime(row.lunchStart)}~${toTime(row.lunchEnd)}` : "물려받음"}
                    </td>
                    <td className="py-2 pr-4 whitespace-nowrap">
                      {row.effectiveFrom || row.effectiveTo ? (
                        <Badge>{`${row.effectiveFrom ?? "…"} ~ ${row.effectiveTo ?? "…"}`}</Badge>
                      ) : (
                        <span className="text-muted-foreground">상시</span>
                      )}
                    </td>
                    <td className="py-2">
                      <div className="flex flex-wrap gap-1">
                        <Button size="sm" variant="ghost" onClick={() => startEdit(row)}>
                          수정
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() =>
                            setPreviewOf({
                              type: row.scopeType,
                              id: row.scopeId,
                            })
                          }
                        >
                          적용 결과
                        </Button>
                        <ConfirmButton
                          onConfirm={async () => {
                            const done = await remove.mutate(row);
                            if (done) {
                              toast.success("예외를 삭제했습니다");
                              overrides.reload();
                              onChanged();
                            }
                          }}
                        >
                          삭제
                        </ConfirmButton>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Section>

      {previewOf && (
        <Section
          title="적용 결과 확인"
          description="이 대상에 속한 구성원이 실제로 적용받는 값입니다. 계산대(POS)도 같은 값을 사용합니다."
          action={
            <Button size="sm" variant="ghost" onClick={() => setPreviewOf(null)}>
              닫기
            </Button>
          }
        >
          {preview.error && <ErrorNotice message={preview.error} onRetry={preview.reload} />}
          {preview.loading && <RowsSkeleton rows={2} />}
          {preview.data && (
            <dl className="grid gap-3 sm:grid-cols-3">
              <PreviewItem
                term="1식 한도"
                value={formatMinor(preview.data.perMealLimitMinor)}
                source={preview.data.sources.PER_MEAL_LIMIT}
                label={preview.data.sourceLabels.PER_MEAL_LIMIT}
              />
              <PreviewItem
                term="1일 횟수"
                value={`${preview.data.dailyMealCount}회`}
                source={preview.data.sources.DAILY_MEAL_COUNT}
                label={preview.data.sourceLabels.DAILY_MEAL_COUNT}
              />
              <PreviewItem
                term="월 한도"
                value={formatMinor(preview.data.monthlyCapMinor)}
                source={preview.data.sources.MONTHLY_CAP}
                label={preview.data.sourceLabels.MONTHLY_CAP}
              />
              <PreviewItem
                term="점심 시간"
                value={`${toTime(preview.data.lunchStart)}~${toTime(preview.data.lunchEnd)}`}
                source={preview.data.sources.LUNCH_WINDOW}
                label={preview.data.sourceLabels.LUNCH_WINDOW}
              />
            </dl>
          )}
        </Section>
      )}
    </>
  );
}

/** 재정의하지 않은 값은 숫자가 아니라 "물려받음"으로 보여야 한다 — 0 과 혼동되면 안 된다. */
function inheritedOr<T>(value: T | null, format: (value: T) => string): string {
  return value === null ? "물려받음" : format(value);
}

const SOURCE_LABEL: Record<string, string> = {
  CODE_DEFAULT: "taspa 기본값",
  ORG: "조직 기준",
  SITE: "사업장 예외",
  DEPARTMENT: "부서 예외",
};

function PreviewItem({
  term,
  value,
  source,
  label,
}: {
  term: string;
  value: string;
  source: string | undefined;
  label: string | null | undefined;
}) {
  return (
    <div className="rounded-lg border border-border px-3 py-2.5">
      <dt className="text-sm text-muted-foreground">{term}</dt>
      <dd className="mt-0.5 text-base font-medium tabular-nums text-foreground">{value}</dd>
      <p className="mt-1 text-xs text-muted-foreground">
        {SOURCE_LABEL[source ?? ""] ?? source}
        {label ? ` · ${label}` : ""}
      </p>
    </div>
  );
}
