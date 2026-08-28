import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

/*
 * 숫자와 위계를 다루는 앱 **전역** 조각.
 *
 * 화면 상태(로딩·비어있음·오류)는 `@/components/feedback`, 조직 콘솔 전용 폼 조각은
 * `app/console/_components/console-ui` 에 있다. 여기 있는 것들은 식권·가맹·콘솔·관리 화면이
 * **모두** 쓰는 것들이라 어느 한 기능 폴더에 두면 다른 기능이 남의 내부를 임포트하게 된다.
 */

/**
 * 대시보드용 숫자 타일.
 *
 * ★**값이 라벨을 압도해야 한다.** 예전에는 라벨 12px / 값 16~20px 이라 둘의 무게가 비슷했고, 타일이
 * 나란히 놓이면 숫자가 아니라 회색 상자 몇 개로 읽혔다. 값은 `text-metric`(28px, tabular, 자간 -0.02em),
 * 라벨은 `text-label`(12px, muted)로 벌린다.
 *
 * ★배경은 **파인 면**(`surface-sunken`)이다. 카드 안에 또 카드(흰 배경 + 테두리)를 넣으면 배경이 같아
 * 테두리로만 구분되는데, 그게 화면이 "전부 같은 흰 상자"로 보이던 원인이었다.
 *
 * ★값은 **브랜드 초록**이다(제로웨이스트 테마). 제품 소개 페이지의 KPI 카드가 전부 큰 초록 숫자이고,
 * 그게 이 제품의 첫인상을 만드는 요소다. 초록은 여기서 "좋다"는 의미가 아니라 **브랜드 강조**이므로
 * 환불·감소 같은 값에도 그대로 쓴다 — 의미를 실어야 할 때는 호출부가 `tone` 을 넘긴다.
 * 대비는 `data-display.contrast.test.ts` 가 파인 면 위에서 AA 를 넘는지 고정한다.
 *
 * ★크기는 **뷰포트가 아니라 담긴 칸**을 보고 정한다(`@container`). 같은 타일이 4열 그리드에도, 넓은
 * 단독 칸에도 놓이므로 뷰포트 기준으로 키우면 좁은 칸에서 숫자가 줄바꿈된다. 부모에 `@container` 를
 * 붙여 두면 여기서 `@lg:` 가 그 칸의 폭을 본다.
 */
export function Stat({
  label,
  value,
  hint,
  visual,
  tone = "default",
  emphasis = false,
  variant = "tile",
  className,
}: {
  label: string;
  value: ReactNode;
  hint?: ReactNode;
  /**
   * 값 오른쪽의 미니 시각화(스파크라인·도넛). 숫자가 "얼마"를 말하고 이 그림이 "어느 방향인가"를 말한다.
   *
   * ★여기 들어가는 것은 **축·라벨이 없는 그림**이어야 한다. 작은 자리에 눈금을 넣으면 숫자와 그림이
   *   서로를 가린다 — 자세한 축이 필요하면 그건 KPI 가 아니라 차트 섹션의 일이다.
   */
  visual?: ReactNode;
  /** 값 자체의 의미색. 금액·수량은 default 로 두고, 경고성 수치에만 danger/warning 을 쓴다. */
  tone?: "default" | "danger" | "warning" | "success" | "muted";
  /** 그 묶음에서 **가장 중요한 하나**에만. 둘이면 강조가 사라진다. */
  emphasis?: boolean;
  /**
   * `tile`(기본) = **카드 안**에 들어가는 파인 면. `card` = 페이지 바탕에 **직접** 놓이는 떠 있는 카드.
   *
   * ★둘을 나눈 이유: 카드 안에 흰 카드를 넣으면 배경이 같아 테두리로만 구분되고(그게 "전부 같은 흰 상자"의
   *   원인이었다), 반대로 크림 바탕에 파인 면을 놓으면 배경보다 어두워 움푹 들어가 보인다. 같은 컴포넌트가
   *   어디에 놓이느냐로 표면이 달라져야 한다.
   */
  variant?: "tile" | "card";
  className?: string;
}) {
  return (
    // `<dt>`/`<dd>` 는 `<dl>` 의 자식이어야 유효하다 — 타일 하나가 한 쌍짜리 정의 목록이 된다.
    <dl
      className={cn(
        "flex min-w-0 flex-col gap-1 rounded-lg border px-4 py-3.5",
        variant === "tile" && "surface-sunken border-border/60",
        variant === "card" && "border-border bg-card shadow-[var(--shadow-card)]",
        emphasis && "ring-1 ring-primary/25",
        className,
      )}
    >
      {/*
        ★라벨과 값을 `<dt>`/`<dd>` 로 **연결**한다. `<p>` 두 개는 프로그램적 연관이 없어서 스크린 리더가
        "회사 부담" 과 "0원" 을 각각 떠도는 문단으로 읽는다 — 타일이 4개면 라벨 4개와 값 4개가 순서로만
        이어지고, 표처럼 훑을 수 없다. 같은 카드 아래쪽 `Row` 가 이미 `<dl>` 을 쓰고 있어 한 화면 안의
        불일치도 함께 사라진다.
      */}
      <dt className="text-label text-muted-foreground">{label}</dt>
      {/*
        값과 그림을 **한 줄에** 둔다(그림은 오른쪽). 세로로 쌓으면 4열 그리드에서 타일 높이가 제각각이 되고,
        높이를 맞추려 여백을 넣으면 숫자가 카드 위쪽에 떠 버린다.
      */}
      <div className="flex items-center justify-between gap-3">
        <dd
          className={cn(
            "tabular min-w-0 text-metric-sm @lg:text-metric",
            tone === "danger" && "text-danger",
            tone === "warning" && "text-warning",
            tone === "success" && "text-success",
            tone === "muted" && "text-muted-foreground",
            tone === "default" && "text-brand",
          )}
        >
          {value}
        </dd>
        {visual && <div className="shrink-0">{visual}</div>}
      </div>
      {/*
        ★`hint` 는 `<div>` 다(`<p>` 가 아니라). ReactNode 를 받는 자리라 호출부가 막대·배지 같은 블록
        요소를 넘길 수 있는데, `<p>` 안에 `<div>` 가 들어가면 **HTML 이 무효**라 브라우저가 태그를
        자동으로 닫아 버리고 React 는 하이드레이션 오류를 낸다(실제로 `ProgressMeter` 를 넘겼다가 겪었다).
        타이포는 클래스로 유지하므로 보이는 결과는 같다.
      */}
      {hint && <dd className="text-xs leading-snug text-muted-foreground">{hint}</dd>}
    </dl>
  );
}

/**
 * 비율 막대.
 *
 * ★그전에는 비율이 전부 **숫자쌍**이었다 — "200,000원 / 200,000원", "21 / 21 셀". 두 수를 읽고 나눠야
 * 상태를 알 수 있어서, 한눈에 보라고 만든 대시보드가 오히려 계산을 요구했다. 막대는 그 계산을 없앤다.
 *
 * `value`/`max` 는 **원시 값**을 받는다(포맷된 문자열이 아니라). 표시 문구는 호출부가 `label` 로
 * 넘긴다 — 여기서 포맷하면 화폐·인원·퍼센트마다 분기가 생긴다.
 */
export function ProgressMeter({
  value,
  max,
  label,
  caption,
  tone = "default",
  className,
  name,
}: {
  value: number;
  max: number;
  /** 막대 위 문구. 보통 왼쪽에 이름, 오른쪽에 값을 둔다. */
  label?: ReactNode;
  /** 막대 아래 보조 설명. */
  caption?: ReactNode;
  tone?: "default" | "warning" | "danger" | "success";
  className?: string;
  /**
   * ★막대가 **무엇의** 비율인지. 없으면 스크린 리더가 "진행률 표시줄 34%" 라고만 읽고 대상이 사라진다
   * (`label` 은 시각적 배치용 ReactNode 라 접근 가능한 이름으로 쓸 수 없다).
   */
  name?: string;
}) {
  // max 가 0이면 비율이 정의되지 않는다 — 0으로 나누지 않고 빈 막대로 둔다(NaN 이 화면에 새지 않게).
  const ratio = max > 0 ? Math.min(1, Math.max(0, value / max)) : 0;
  const percent = Math.round(ratio * 100);

  return (
    <div className={cn("flex flex-col gap-1.5", className)}>
      {label && <div className="flex flex-wrap items-baseline justify-between gap-x-3 text-sm">{label}</div>}
      <div
        className="progress-track h-2 w-full overflow-hidden rounded-full bg-line"
        role="progressbar"
        aria-label={name}
        aria-valuenow={percent}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuetext={name ? `${name} ${percent}%` : `${percent}%`}
      >
        <div
          className={cn(
            "h-full rounded-full transition-[width] duration-300",
            tone === "danger"
              ? "bg-danger"
              : tone === "warning"
                ? "bg-warning"
                : tone === "success"
                  ? "bg-success"
                  : "bg-primary",
          )}
          style={{ width: `${percent}%` }}
        />
      </div>
      {caption && <p className="text-xs leading-snug text-muted-foreground">{caption}</p>}
    </div>
  );
}

/**
 * 화면 맨 위의 제목 블록.
 *
 * ★그전에는 화면마다 `<h1 className="text-xl font-semibold">` 을 손으로 썼고, 그래서 화면별로 크기와
 * 여백이 조금씩 달랐다. 제목은 화면의 첫 신호라, 그게 흔들리면 앱 전체가 덜 다듬어져 보인다.
 */
export function PageHeader({
  title,
  description,
  action,
  breadcrumb,
  meta,
}: {
  title: ReactNode;
  description?: ReactNode;
  action?: ReactNode;
  /** 제목 위 한 줄(상위 목록으로 돌아가는 링크 등). */
  breadcrumb?: ReactNode;
  /** 제목 아래 식별 정보(슬러그·타임존 등). 설명과 달리 문장이 아니라 사실 나열이다. */
  meta?: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-2">
      {breadcrumb}
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 flex-col gap-1">
          <h1 className="text-display text-foreground">{title}</h1>
          {meta && <p className="text-sm text-muted-foreground">{meta}</p>}
        </div>
        {action && <div className="flex shrink-0 items-center gap-2">{action}</div>}
      </div>
      {description && (
        <p className="max-w-2xl text-sm leading-relaxed text-muted-foreground">{description}</p>
      )}
    </div>
  );
}

/**
 * 값이 없을 때 자리를 지키는 표시.
 *
 * ★그전에는 값 자리에 `—` 를 직접 썼다. 대시 하나만 남으면 **로딩 실패처럼** 보여서, 정상적으로
 * "아직 데이터가 없음"인 화면이 고장난 것처럼 읽혔다(예측 탭이 통째로 그 상태였다). 대시는 남기되
 * 흐리게 하고, 이유를 붙일 수 있게 한다.
 */
export function NoValue({ reason }: { reason?: string }) {
  return (
    <span className="text-faint" title={reason} aria-label={reason ?? "값 없음"}>
      —
    </span>
  );
}

/**
 * 상태 한 줄. "지금 이용 가능"처럼 **화면의 첫 질문에 답하는** 문장을 배경색과 함께 띄운다.
 *
 * ★본문 텍스트로 두면 묻힌다. 식권 화면에서 "지금은 아침 06:00~10:30 시간입니다"(= 지금 쓸 수 있다)가
 * 카드 맨 아래 회색 한 줄이었는데, 계산대 앞에서 가장 먼저 확인해야 하는 사실이 가장 눈에 안 띄었다.
 */
export function StatusLine({
  tone,
  icon,
  children,
}: {
  tone: "ok" | "warning" | "neutral";
  icon?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div
      className={cn(
        // ★`items-start` 다. `items-center` 로 두면 문장이 두 줄 이상일 때 아이콘이 **줄 사이로 내려가**
        //   무엇을 가리키는지 흐려진다(끼니창 미설정 안내가 실제로 두 줄이다).
        "flex items-start gap-2 rounded-lg px-3 py-2.5 text-sm",
        tone === "ok" && "bg-success-soft text-success",
        tone === "warning" && "bg-warning-soft text-warning",
        tone === "neutral" && "surface-sunken text-muted-foreground",
      )}
    >
      {/* 아이콘은 첫 줄 글자 높이에 맞춘다(`items-start` 라 그대로 두면 살짝 위로 뜬다). */}
      {icon && (
        <span aria-hidden className="mt-0.5 shrink-0 [&_svg]:size-4">
          {icon}
        </span>
      )}
      <span className="min-w-0">{children}</span>
    </div>
  );
}
