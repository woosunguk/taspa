"use client";

/**
 * 인라인 SVG 막대 그래프 — **차트 라이브러리를 넣지 않는다.**
 *
 * recharts·chart.js 는 번들에 수백 KB 를 더하는데 이 화면들이 필요한 것은 "일자별 막대" 하나다.
 * `qrcode.js` 를 벤더링한 것과 같은 판단이고, 대가로 기능은 여기 있는 것뿐이다(줌·툴팁 추적 없음).
 *
 * ★색은 반드시 디자인 토큰(`--chart-1..5`)으로 쓴다. 임의 색상값을 넣으면 라이트/다크 한쪽에서
 *   막대가 배경에 묻힌다 — 그때 화면은 "데이터가 없는 것"처럼 보인다(가장 나쁜 실패다).
 */

export interface BarSegment {
  /** 세그먼트 이름(범례·접근성 텍스트에 쓰인다). */
  name: string;
  value: number;
}

export interface BarDatum {
  /** x축 라벨. 좁은 화면에서 잘리므로 짧게(예: "08-24"). */
  label: string;
  segments: BarSegment[];
}

const TONES = ["var(--chart-1)", "var(--chart-2)", "var(--chart-3)", "var(--chart-4)", "var(--chart-5)"];

function sum(segments: BarSegment[]): number {
  return segments.reduce((acc, s) => acc + s.value, 0);
}

/**
 * 누적 막대 그래프.
 *
 * @param data      막대 하나 = 하루. 세그먼트 순서는 전 막대에서 같아야 색이 흔들리지 않는다.
 * @param unit      값 뒤에 붙는 단위(접근성 텍스트·툴팁용).
 * @param reference 기준선(예: 무예측 준비량). 있으면 점선으로 그린다.
 */
export function StackedBarChart({
  data,
  unit = "",
  reference,
  height = 180,
  ariaLabel,
  note,
}: {
  data: BarDatum[];
  unit?: string;
  reference?: { value: number; label: string };
  height?: number;
  ariaLabel: string;
  /** 이 그래프를 그대로 읽으면 안 되는 이유가 있을 때(예: 표시 상한에 걸려 앞 구간이 빠짐). */
  note?: string;
}) {
  if (data.length === 0) return null;

  const totals = data.map((d) => sum(d.segments));
  const peak = Math.max(...totals, reference?.value ?? 0, 1);
  const names = data[0].segments.map((s) => s.name);

  /*
   * 막대 폭은 **데이터 수에 따라 넓힌다.** 고정 28px 이면 5일짜리 조회에서 넓은 카드 왼쪽에 얇은 막대
   * 몇 개만 붙어 미완성 화면처럼 보인다(실측으로 확인). 반대로 한 달치는 좁혀서 가로 스크롤로 넘긴다 —
   * 기간을 넓게 잡는 것이 이 화면의 정상 사용이다.
   */
  const barWidth = data.length <= 8 ? 56 : data.length <= 16 ? 40 : 28;
  const gap = Math.max(8, Math.round(barWidth / 3));
  const chartWidth = data.length * (barWidth + gap);

  return (
    <div className="flex flex-col gap-3">
      {names.length > 1 && (
        <div className="flex flex-wrap items-center gap-4">
          {names.map((name, i) => (
            <span key={name} className="flex items-center gap-1.5 text-xs text-muted-foreground">
              <span
                aria-hidden
                className="inline-block h-2.5 w-2.5 rounded-sm"
                style={{ background: TONES[i % TONES.length] }}
              />
              {name}
            </span>
          ))}
        </div>
      )}

      {note && <p className="text-xs text-[color:var(--taspa-warning)]">{note}</p>}

      <div className="overflow-x-auto">
        <svg
          role="img"
          aria-label={ariaLabel}
          width={Math.max(chartWidth, 240)}
          height={height + 28}
          className="block"
        >
          {reference && (
            <g>
              <line
                x1={0}
                x2={Math.max(chartWidth, 240)}
                y1={height - (reference.value / peak) * height}
                y2={height - (reference.value / peak) * height}
                stroke="var(--color-muted-foreground)"
                strokeDasharray="4 4"
                strokeWidth={1}
              />
              <text
                x={4}
                y={Math.max(10, height - (reference.value / peak) * height - 4)}
                className="fill-[color:var(--color-muted-foreground)] text-[10px]"
              >
                {reference.label}
              </text>
            </g>
          )}

          {data.map((datum, index) => {
            const x = index * (barWidth + gap);
            let cursor = height;
            return (
              <g key={`${datum.label}-${index}`}>
                <title>{`${datum.label} · ${sum(datum.segments)}${unit}`}</title>
                {datum.segments.map((segment, si) => {
                  const barHeight = peak > 0 ? (segment.value / peak) * height : 0;
                  cursor -= barHeight;
                  return (
                    <rect
                      key={segment.name}
                      x={x}
                      y={cursor}
                      width={barWidth}
                      height={Math.max(barHeight, segment.value > 0 ? 1 : 0)}
                      rx={2}
                      fill={TONES[si % TONES.length]}
                    />
                  );
                })}
                {sum(datum.segments) > 0 && (
                  <text
                    x={x + barWidth / 2}
                    y={Math.max(10, cursor - 5)}
                    textAnchor="middle"
                    className="fill-[color:var(--color-foreground)] text-[11px] font-medium"
                  >
                    {sum(datum.segments)}
                  </text>
                )}
                <text
                  x={x + barWidth / 2}
                  y={height + 14}
                  textAnchor="middle"
                  className="fill-[color:var(--color-muted-foreground)] text-[10px]"
                >
                  {datum.label}
                </text>
              </g>
            );
          })}
        </svg>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ 추이 라인차트 */

export interface LinePoint {
  /** x축 라벨(예: "08-24"). */
  label: string;
  /** 라벨 아래 보조 표기(요일 등). */
  sublabel?: string;
  /** 확정 실적. 미래 날짜는 null. */
  actual: number | null;
  /** 예측. 근거가 없으면 null(0 이 아니다). */
  predicted: number | null;
  today?: boolean;
  /** 배경에 옅은 띠를 그린다(휴일 등 "다른 날"이라는 신호). */
  band?: boolean;
}

interface Segment {
  from: number;
  to: number;
}

/** 연속 구간으로 자른다 — null 이 섞인 계열을 한 줄로 이으면 없는 값을 지나가는 선이 그려진다. */
function segmentsOf(values: (number | null)[]): Segment[] {
  const out: Segment[] = [];
  let start: number | null = null;
  values.forEach((v, i) => {
    if (v === null) {
      if (start !== null && i - 1 > start) out.push({ from: start, to: i - 1 });
      start = null;
    } else if (start === null) {
      start = i;
    }
  });
  if (start !== null && values.length - 1 > start) out.push({ from: start, to: values.length - 1 });
  return out;
}

/**
 * 예측 vs 실적 추이 — **실선은 확정 실적, 점선은 예측**이다.
 *
 * ★두 선을 같은 굵기·같은 색의 실선으로 그리면 "이미 일어난 일"과 "아직 아닌 일"이 구분되지 않는다.
 *   발주 담당자가 미래 값을 실적으로 읽는 순간 이 화면은 오히려 위험해진다.
 * ★y축은 0 에서 시작하지 않는다(값 범위가 좁아 0 기준이면 선이 평평해져 추세가 사라진다). 대신
 *   **축 눈금을 항상 표시**해 바닥이 0 이 아니라는 사실을 숨기지 않는다.
 * ★값이 없는 구간(NO_DATA)은 선을 **끊는다** — 이으면 없는 값을 지나가는 선이 생긴다.
 */
export function ForecastLineChart({
  points,
  unit = "",
  height = 260,
  ariaLabel,
  actualName = "실적",
  predictedName = "예측",
}: {
  points: LinePoint[];
  unit?: string;
  height?: number;
  ariaLabel: string;
  actualName?: string;
  predictedName?: string;
}) {
  if (points.length === 0) return null;

  const PAD = { top: 26, right: 16, bottom: 38, left: 44 };
  const stepWidth = points.length <= 10 ? 92 : points.length <= 20 ? 62 : 44;
  const innerWidth = Math.max((points.length - 1) * stepWidth, stepWidth);
  const width = innerWidth + PAD.left + PAD.right;
  const plotHeight = height - PAD.top - PAD.bottom;

  const values = points.flatMap((p) => [p.actual, p.predicted]).filter((v): v is number => v !== null);
  const rawMin = values.length ? Math.min(...values) : 0;
  const rawMax = values.length ? Math.max(...values) : 1;
  const span = Math.max(rawMax - rawMin, 1);
  const yMin = Math.max(0, Math.floor((rawMin - span * 0.35) / 5) * 5);
  const yMax = Math.ceil((rawMax + span * 0.25) / 5) * 5;

  const x = (i: number) => PAD.left + (points.length === 1 ? innerWidth / 2 : i * stepWidth);
  const y = (v: number) => PAD.top + plotHeight - ((v - yMin) / (yMax - yMin)) * plotHeight;
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((r) => Math.round(yMin + (yMax - yMin) * r));

  const path = (values_: (number | null)[]) =>
    segmentsOf(values_).map((seg) => {
      const parts: string[] = [];
      for (let i = seg.from; i <= seg.to; i++)
        parts.push(`${i === seg.from ? "M" : "L"}${x(i)},${y(values_[i]!)}`);
      return parts.join(" ");
    });

  const actuals = points.map((p) => p.actual);
  const predictions = points.map((p) => p.predicted);

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-4 text-xs text-muted-foreground">
        <span className="flex items-center gap-1.5">
          <svg width="22" height="8" aria-hidden>
            <line x1="0" y1="4" x2="22" y2="4" stroke="var(--chart-1)" strokeWidth="2.5" />
          </svg>
          {actualName}
        </span>
        <span className="flex items-center gap-1.5">
          <svg width="22" height="8" aria-hidden>
            <line
              x1="0"
              y1="4"
              x2="22"
              y2="4"
              stroke="var(--chart-1)"
              strokeWidth="2.5"
              strokeDasharray="5 4"
            />
          </svg>
          {predictedName}
        </span>
      </div>

      <div className="overflow-x-auto">
        <svg role="img" aria-label={ariaLabel} width={width} height={height} className="block">
          {/* 휴일 띠 — 배경으로 먼저 깔아 선·점이 위에 오게 한다. */}
          {points.map((p, i) =>
            p.band ? (
              <rect
                key={`band-${p.label}`}
                x={x(i) - stepWidth / 2}
                y={PAD.top - 6}
                width={stepWidth}
                height={plotHeight + 12}
                fill="var(--chart-4)"
                opacity={0.25}
              />
            ) : null,
          )}

          {ticks.map((tick) => (
            <g key={`tick-${tick}`}>
              <line
                x1={PAD.left}
                x2={width - PAD.right}
                y1={y(tick)}
                y2={y(tick)}
                stroke="var(--color-border)"
                strokeWidth={1}
                opacity={0.7}
              />
              <text
                x={PAD.left - 8}
                y={y(tick) + 3}
                textAnchor="end"
                className="fill-[color:var(--color-muted-foreground)] text-[10px]"
              >
                {tick}
              </text>
            </g>
          ))}

          {path(predictions).map((d, i) => (
            <path
              key={`pred-${i}`}
              d={d}
              fill="none"
              stroke="var(--chart-1)"
              strokeWidth={2.5}
              strokeDasharray="5 4"
              opacity={0.75}
            />
          ))}
          {path(actuals).map((d, i) => (
            <path key={`act-${i}`} d={d} fill="none" stroke="var(--chart-1)" strokeWidth={2.5} />
          ))}

          {points.map((p, i) => (
            <g key={`pt-${p.label}`}>
              {p.predicted !== null && p.actual === null && (
                <circle
                  cx={x(i)}
                  cy={y(p.predicted)}
                  r={4}
                  fill="var(--color-surface)"
                  stroke="var(--chart-1)"
                  strokeWidth={2}
                />
              )}
              {p.actual !== null && <circle cx={x(i)} cy={y(p.actual)} r={4} fill="var(--chart-1)" />}

              {/* 값 라벨 — 실적이 있으면 실적을, 없으면 예측을 적는다(둘 다 적으면 좁은 폭에서 겹친다). */}
              {(() => {
                const shown = p.actual ?? p.predicted;
                if (shown === null) return null;
                if (p.today) {
                  const text = `오늘 ${shown}`;
                  const w = text.length * 8 + 16;
                  return (
                    <g>
                      <rect
                        x={x(i) - w / 2}
                        y={y(shown) - 30}
                        width={w}
                        height={22}
                        rx={11}
                        fill="var(--chart-1)"
                      />
                      <text
                        x={x(i)}
                        y={y(shown) - 15}
                        textAnchor="middle"
                        className="fill-[color:var(--color-primary-foreground)] text-[11px] font-semibold"
                      >
                        {text}
                      </text>
                    </g>
                  );
                }
                return (
                  <text
                    x={x(i)}
                    y={y(shown) - 10}
                    textAnchor="middle"
                    className="fill-[color:var(--color-foreground)] text-[11px] font-medium"
                  >
                    {shown}
                  </text>
                );
              })()}

              <text
                x={x(i)}
                y={height - PAD.bottom + 20}
                textAnchor="middle"
                className={
                  p.today
                    ? "fill-[color:var(--chart-1)] text-[10px] font-semibold"
                    : "fill-[color:var(--color-muted-foreground)] text-[10px]"
                }
              >
                {p.label}
              </text>
              {p.sublabel && (
                <text
                  x={x(i)}
                  y={height - PAD.bottom + 32}
                  textAnchor="middle"
                  className="fill-[color:var(--color-faint)] text-[9px]"
                >
                  {p.sublabel}
                </text>
              )}
            </g>
          ))}
        </svg>
      </div>

      {/*
        ★캡션은 **실제 축을 보고** 쓴다. "0 에서 시작하지 않습니다" 를 무조건 붙여 두면 값 범위가 넓어
        하한이 0 으로 내려간 날에는 화면이 사실과 다른 말을 한다(실측으로 잡았다 — 휴무일 2인분이
        섞여 하한이 0 이 됐는데 캡션은 그대로였다).
      */}
      <p className="text-xs text-muted-foreground">
        {yMin > 0
          ? `세로축이 ${yMin} 에서 시작합니다 — 값 범위가 좁아 0 기준이면 추세가 보이지 않습니다. 눈금을 함께 읽어 주세요.`
          : "세로축은 0 에서 시작합니다."}
        {unit ? ` 단위: ${unit}.` : ""}
      </p>
    </div>
  );
}

/* ------------------------------------------------------- KPI 안에 들어가는 미니 시각화 */

/**
 * 스파크라인 — 축·눈금·라벨이 **없다**. KPI 숫자 옆에서 "올라가는 중인가 내려가는 중인가"만 말한다.
 *
 * ★값 라벨을 넣지 않는 것이 의도다. 숫자는 이미 옆에 크게 있고, 작은 그림에 숫자를 더하면 둘 다 안 읽힌다.
 * ★점이 1개면 그리지 않는다 — 한 점으로 그린 선은 추세가 아니라 장식이다.
 */
export function Sparkline({
  values,
  width = 96,
  height = 28,
  ariaLabel,
}: {
  values: number[];
  width?: number;
  height?: number;
  ariaLabel: string;
}) {
  if (values.length < 2) return null;

  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  const x = (i: number) => (i / (values.length - 1)) * width;
  const y = (v: number) => height - ((v - min) / span) * (height - 4) - 2;
  const line = values.map((v, i) => `${i === 0 ? "M" : "L"}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(" ");
  const area = `${line} L${width},${height} L0,${height} Z`;

  return (
    <svg role="img" aria-label={ariaLabel} width={width} height={height} className="block">
      <path d={area} fill="var(--chart-1)" opacity={0.14} />
      <path d={line} fill="none" stroke="var(--chart-1)" strokeWidth={1.75} strokeLinejoin="round" />
      <circle cx={x(values.length - 1)} cy={y(values[values.length - 1])} r={2.5} fill="var(--chart-1)" />
    </svg>
  );
}

/**
 * 도넛 게이지 — 비율 하나를 원으로. 막대(`ProgressMeter`)와 달리 **숫자 옆 정사각 자리**에 들어간다.
 *
 * ★비율은 1.0 을 넘을 수 있다(월 한도 초과·목표 초과). 링은 1.0 에서 멈추되 가운데 숫자는 실제 값을
 *   보여준다 — 링만 보고 "꽉 찼다"로 읽는 것과 "넘었다"는 다른 사실이다.
 */
export function DonutMeter({
  ratio,
  size = 52,
  label,
  ariaLabel,
}: {
  ratio: number;
  size?: number;
  /** 가운데 표기. 생략하면 백분율을 쓴다. */
  label?: string;
  ariaLabel: string;
}) {
  const clamped = Math.max(0, Math.min(1, ratio));
  const stroke = 6;
  const radius = (size - stroke) / 2;
  const circumference = 2 * Math.PI * radius;
  const over = ratio > 1;

  return (
    <svg role="img" aria-label={ariaLabel} width={size} height={size} className="block">
      <circle
        cx={size / 2}
        cy={size / 2}
        r={radius}
        fill="none"
        stroke="var(--color-border)"
        strokeWidth={stroke}
      />
      <circle
        cx={size / 2}
        cy={size / 2}
        r={radius}
        fill="none"
        stroke={over ? "var(--taspa-warning)" : "var(--chart-1)"}
        strokeWidth={stroke}
        strokeLinecap="round"
        strokeDasharray={`${circumference * clamped} ${circumference}`}
        transform={`rotate(-90 ${size / 2} ${size / 2})`}
      />
      <text
        x={size / 2}
        y={size / 2 + 4}
        textAnchor="middle"
        className="fill-[color:var(--color-foreground)] text-[11px] font-semibold"
      >
        {label ?? `${Math.round(ratio * 100)}%`}
      </text>
    </svg>
  );
}
