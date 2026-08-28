/**
 * 예측 신호 — 서버 계약 미러(`forecast/ForecastSignals.kt`).
 *
 * 가맹 그레인은 매장별로 **저장**된다(`/api/merchant-console/{id}/forecast-settings`). "저장하면 누가
 * 언제 켰는지 모른다"는 우려는 서버 감사 이벤트(MERCHANT_FORECAST_SETTINGS_UPDATED)가 답한다.
 * ★기본값은 서버 기본값과 같아야 한다 — 다르면 설정을 만진 적 없는 매장의 화면과 API 숫자가 갈린다.
 */
export interface ForecastSignals {
  headcountAdjust: boolean;
  absenceAware: boolean;
  holidayAware: boolean;
  eventAware: boolean;
  menuAware: boolean;
  nowcast: boolean;
  methodSelection: boolean;
}

export const DEFAULT_SIGNALS: ForecastSignals = {
  headcountAdjust: true,
  absenceAware: true,
  holidayAware: true,
  eventAware: false,
  menuAware: false,
  nowcast: true,
  methodSelection: false,
};

export interface SignalDef {
  key: keyof ForecastSignals;
  label: string;
  hint: string;
}

/** 화면 순서 = 신호의 확실성 순서(항상 안전한 것 → 데이터가 있어야 뜻이 생기는 것). */
export const SIGNAL_DEFS: SignalDef[] = [
  {
    key: "nowcast",
    label: "당일 보정",
    hint: "오늘 이미 나간 인분을 예측의 하한으로 씁니다. 40인분이 나갔는데 예측이 35라면 그 숫자는 이미 틀린 것이 확정입니다.",
  },
  {
    key: "headcountAdjust",
    label: "재실 인원 보정",
    hint: "각 조직의 과거 실적을 그 시점 재직 인원 대비로 환산합니다. 채용·퇴사로 인원이 바뀌어도 1인당 참여율을 유지합니다.",
  },
  {
    key: "absenceAware",
    label: "연차·휴가 반영",
    hint: "조직이 등록한 부재(연차·반차·출장)만큼 그날 그 조직 몫을 낮춥니다. 반차는 0.5명입니다.",
  },
  {
    key: "holidayAware",
    label: "휴일 인지",
    hint: "조직 캘린더의 휴일은 과거 휴일 실적만 근거로 씁니다. 평일 실적을 휴일에 대입하지 않습니다.",
  },
  {
    key: "eventAware",
    label: "사내 행사 인지",
    hint: "조직 캘린더에 종일 행사로 선언된 날(워크숍·체육대회)은 과거 행사일 실적만 근거로 씁니다.",
  },
  {
    key: "menuAware",
    label: "메뉴 신호",
    hint: "특식·면류 같은 식단 카테고리가 같았던 과거를 우선 근거로 씁니다. 연결 사업장 조직의 식단이 있어야 작동합니다.",
  },
  {
    key: "methodSelection",
    label: "방법 자동 선택",
    hint: "최근 구간을 채점해 가장 잘 맞은 예측 방법을 자동으로 고릅니다. 이력이 짧으면 근거가 약해 기본은 꺼져 있습니다.",
  },
];

/** 기본 조합과 다른 신호 라벨 — "실험 중" 표시의 근거. */
export function changedSignals(signals: ForecastSignals): string[] {
  return SIGNAL_DEFS.filter((d) => signals[d.key] !== DEFAULT_SIGNALS[d.key]).map((d) => d.label);
}
