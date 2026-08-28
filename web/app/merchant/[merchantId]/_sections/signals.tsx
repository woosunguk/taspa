"use client";

import { useState } from "react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { EmptyState, ErrorNotice, Loading } from "@/components/feedback";
import { api } from "@/lib/api";
import { useApi, useMutation } from "@/lib/useApi";
import { Section, TableScroll } from "../../_components/kit";
import { merchantPath } from "../../_lib/merchant-context";
import { DEFAULT_SIGNALS, SIGNAL_DEFS, changedSignals, type ForecastSignals } from "@/lib/forecast-signals";
import type { MerchantOrgInfo } from "../../_lib/types";

/**
 * 예측 신호 **빠른 설정** — Gmail 의 우측 설정 패널 문법(기어 버튼 → 우측 슬라이드, 토글 즉시 적용).
 *
 * 설정은 매장에 **저장**된다(`PUT /forecast-settings`) — 새로고침해도, 다른 기기에서 열어도 같은 조합이다.
 * 토글 하나가 곧 저장이라 별도 저장 버튼이 없다(Gmail 과 같은 계약 — 끄고 켠 것이 즉시 사실이 된다).
 * 누가 언제 바꿨는지는 서버 감사 로그가 남긴다.
 */
export function SignalSettings({
  merchantId,
  onSaved,
}: {
  merchantId: string;
  /** 저장 성공 후 상위가 예측·백테스트를 다시 불러오게 한다(설정이 곧 그 숫자의 입력이다). */
  onSaved: () => void;
}) {
  const [open, setOpen] = useState(false);
  const stored = useApi<ForecastSignals>(merchantPath(merchantId, "/forecast-settings"), [merchantId]);
  // 패널이 열린 동안의 낙관적 상태 — 서버 응답이 오면 그 값이 진실이다.
  const [draft, setDraft] = useState<ForecastSignals | null>(null);
  // ★서버는 저장 행이 없으면 methodSelection 을 null 로 준다(배포 설정 위임의 표현). 그대로 비교하면
  //   null !== false 라 "조정 1"이 유령으로 뜬다 — 화면 기본값으로 정규화해 비교 축을 하나로 만든다.
  const normalized = stored.data
    ? { ...stored.data, methodSelection: stored.data.methodSelection ?? DEFAULT_SIGNALS.methodSelection }
    : null;
  const signals = draft ?? normalized ?? DEFAULT_SIGNALS;
  const changed = changedSignals(signals);

  const save = useMutation(async (next: ForecastSignals) => {
    const saved = await api.put<ForecastSignals>(merchantPath(merchantId, "/forecast-settings"), next);
    return saved;
  });

  const toggle = async (key: keyof ForecastSignals, value: boolean) => {
    const next = { ...signals, [key]: value };
    setDraft(next); // 즉시 반영(낙관적) — 스위치가 한 박자 늦게 움직이면 고장으로 읽힌다.
    const saved = await save.mutate(next);
    if (!saved) {
      setDraft(signals); // 실패 — 원상 복구(스위치가 사실과 다르게 남으면 안 된다)
      return;
    }
    setDraft(saved);
    onSaved();
  };

  return (
    <>
      <Button
        variant="outline"
        size="sm"
        onClick={() => setOpen(true)}
        aria-label="예측 신호 설정"
        title={
          changed.length > 0 ? `기본 조합과 다른 신호: ${changed.join(", ")}` : "모든 신호가 기본 조합입니다"
        }
      >
        <GearIcon />
        신호 설정
        {changed.length > 0 && <Badge variant="secondary">조정 {changed.length}</Badge>}
      </Button>

      {open && (
        <div className="fixed inset-0 z-50" role="dialog" aria-modal="true" aria-label="예측 신호 설정">
          <button
            type="button"
            aria-label="설정 닫기"
            className="absolute inset-0 bg-foreground/30"
            onClick={() => setOpen(false)}
          />
          <div className="absolute inset-y-0 right-0 flex w-[380px] max-w-[92vw] flex-col border-l border-line bg-card shadow-xl">
            <div className="flex items-center justify-between border-b border-line px-5 py-4">
              <h2 className="text-base font-semibold">빠른 설정</h2>
              <button
                type="button"
                aria-label="닫기"
                className="rounded-lg p-1.5 text-muted-foreground hover:bg-line hover:text-foreground"
                onClick={() => setOpen(false)}
              >
                <svg
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                  aria-hidden
                  className="size-4"
                >
                  <path d="M6 6l12 12M18 6L6 18" />
                </svg>
              </button>
            </div>

            <div className="flex-1 overflow-y-auto px-5 py-4">
              <p className="pb-3 text-xs font-medium text-muted-foreground">예측 신호</p>
              {stored.loading && <Loading label="설정을 불러오는 중" />}
              {stored.error && <ErrorNotice message={stored.error} onRetry={stored.reload} />}
              {!stored.loading && (
                <div className="flex flex-col divide-y divide-line">
                  {SIGNAL_DEFS.map((def) => (
                    <div key={def.key} className="flex items-start gap-3 py-3">
                      <div className="flex-1">
                        <label htmlFor={`qs-${def.key}`} className="cursor-pointer text-sm font-medium">
                          {def.label}
                          {signals[def.key] !== DEFAULT_SIGNALS[def.key] && (
                            <Badge variant="outline" className="ml-1.5 align-middle text-[10px]">
                              기본과 다름
                            </Badge>
                          )}
                        </label>
                        <p className="mt-0.5 text-xs leading-relaxed text-muted-foreground">{def.hint}</p>
                      </div>
                      <Switch
                        id={`qs-${def.key}`}
                        checked={signals[def.key]}
                        disabled={save.busy}
                        onCheckedChange={(next: boolean) => {
                          void toggle(def.key, next).then(() => {
                            if (!save.error) toast.success("저장됨 — 예측에 바로 반영됩니다");
                          });
                        }}
                      />
                    </div>
                  ))}
                </div>
              )}
              {save.error && <ErrorNotice message={save.error} onDismiss={save.clearError} />}
              <p className="mt-4 text-xs leading-relaxed text-muted-foreground">
                설정은 이 매장에 저장되어 모든 기기·모든 화면의 예측에 적용됩니다. 변경 이력은 감사 로그에
                남습니다. 신호는 이용 조직별로 적용됩니다 — A 조직의 휴일이 B 조직 손님 몫을 깎지 않습니다.
              </p>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

function GearIcon() {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
      className="size-4"
    >
      <path d="M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z" />
      <path d="M19 12a7 7 0 0 0-.1-1.2l2-1.5-2-3.4-2.3 1a7 7 0 0 0-2-1.2L14.2 3h-4l-.4 2.5a7 7 0 0 0-2 1.2l-2.3-1-2 3.4 2 1.5a7 7 0 0 0 0 2.4l-2 1.5 2 3.4 2.3-1a7 7 0 0 0 2 1.2l.4 2.5h4l.4-2.5a7 7 0 0 0 2-1.2l2.3 1 2-3.4-2-1.5c.06-.4.1-.8.1-1.2Z" />
    </svg>
  );
}

/** 이용 조직 — 이 매장 예측의 신호 원천(조직이 캘린더·연차를 등록할수록 그 조직 몫이 정확해진다). */
export function ClientOrgsSection({ orgs }: { orgs: MerchantOrgInfo[] }) {
  return (
    <Section
      title="이용 조직"
      description="이 매장을 이용하는 조직과 앞으로 14일의 수요 신호입니다. 조직이 캘린더·연차를 등록할수록 예측이 정확해집니다."
    >
      {orgs.length === 0 && (
        <EmptyState title="아직 이용 실적이 없습니다" description="결제가 쌓이면 조직 목록이 나타납니다." />
      )}
      {orgs.length > 0 && (
        <TableScroll>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>조직</TableHead>
                <TableHead className="text-right">최근 28일 인분</TableHead>
                <TableHead className="text-right">14일 내 휴일</TableHead>
                <TableHead className="text-right">14일 내 행사</TableHead>
                <TableHead className="text-right">14일 내 부재(인일)</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {orgs.map((org) => (
                <TableRow key={org.orgId}>
                  <TableCell className="font-medium">{org.name}</TableCell>
                  <TableCell className="text-right tabular-nums">
                    {org.recentPortions.toLocaleString()}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">
                    {org.upcomingHolidays > 0 ? (
                      <Badge variant="secondary">{org.upcomingHolidays}일</Badge>
                    ) : (
                      "—"
                    )}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">
                    {org.upcomingEvents > 0 ? <Badge variant="secondary">{org.upcomingEvents}일</Badge> : "—"}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">
                    {org.upcomingAbsentWeight > 0 ? org.upcomingAbsentWeight.toFixed(1) : "—"}
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
