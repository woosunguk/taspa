/**
 * POS 단말 화면의 서버 계약 미러 + 계산원 언어로의 번역.
 *
 * 필드 이름은 taspa DTO 와 1:1 이다(`meal/dto/MealDtos.kt` 의 `RedeemResponse`) — 추측해서 짓지
 * 않는다. 이름이 어긋나면 화면은 조용히 `undefined` 를 "0원"으로 보여주고, 계산원은 받아야 할 돈을
 * 받지 않는다.
 */

/** POST /api/pos/redeem · /api/pos/void 성공 응답 = taspa `RedeemResponse`. */
export interface RedeemResponse {
  authId: string;
  /** **조직(회사) 부담액.** 이름과 달리 결제 총액이 아니다 — 총액은 단말이 입력한 금액이다. */
  approvedAmountMinor: number;
  /** 한도를 넘어 분리 승인된 **개인 부담액** — 계산원이 손님에게 추가로 받아야 하는 돈. */
  selfPaidMinor: number;
  mealWindow: string;
  status: string;

  /**
   * 이번 환불에서 각자에게 돌아간 금액 — **환불 응답에만** 온다(승인·취소는 없음).
   *
   * `selfRefundedMinor` 가 계산원이 손님에게 현금으로 돌려줄 금액이다. 환불 후의 `selfPaidMinor` 로
   * 대신할 수 없다 — 그건 "앞으로 받을 금액"이지 "지금 돌려줄 금액"이 아니다(손님은 이미 옛 금액을 냈다).
   */
  orgRefundedMinor?: number | null;
  selfRefundedMinor?: number | null;

  /**
   * 이 거래에 귀속된 메뉴 이름. null 은 "기록 안 됨" — 그 끼니에 식단이 없거나, 코너가 여럿인데
   * 단말이 고르지 않았거나, 보낸 menuId 가 그 끼니 메뉴가 아니었다. 결제와는 무관하다(분석 축).
   */
  menuName?: string | null;
}

/** GET /api/pos/menus 응답 = taspa `MerchantMenusResponse`. 지금 끼니의 배식 코너 목록. */
export interface PosMenusResponse {
  mealWindow: string;
  menuDate: string;
  menus: Array<{ menuId: string; name: string; category: string; corner: string | null }>;
}

/** 오류 응답 — taspa `{errorCode, message}` 와 단말 BFF 가 만드는 코드가 같은 형태로 온다. */
export interface ApiErrorBody {
  errorCode: string;
  message: string;
}

/** 화면에 누적되는 승인 1건. 새로고침하면 사라진다(장부는 taspa 가 갖는다 — 여기는 참고용). */
export interface Approval {
  authId: string;
  /** 계산원이 입력한 결제 총액. 서버는 총액을 돌려주지 않으므로 요청값을 그대로 보관한다. */
  totalMinor: number;
  orgPaidMinor: number;
  selfPaidMinor: number;
  mealWindow: string;
  /** 환불 누계. 부분 환불이 있으면 회사·개인 부담이 다시 계산돼 위 두 값도 함께 바뀐다. */
  refundedMinor: number;
  /**
   * 직전 환불에서 **손님에게 현금으로 돌려줄 금액**(서버가 정한 분담).
   *
   * 환불 후의 `selfPaidMinor` 로 대신할 수 없다 — 그건 "앞으로 받을 금액"이지 "지금 돌려줄 금액"이
   * 아니다(손님은 이미 옛 금액을 냈다). 화면에서 유추하지 않는 이유는 분담 결정이 서버 몫이기 때문이다.
   */
  cashBackMinor: number | null;
  /** 취소되거나 전액 환불되면 VOIDED 로 바뀐다. */
  status: string;
  at: number;
}

/** 서버 enum 라벨(domain/consumption/ConsumptionEnums.kt). 모르는 값은 원문 그대로 — 숨기지 않는다. */
export function mealWindowLabel(value: string): string {
  if (value === "BREAKFAST") return "아침";
  if (value === "LUNCH") return "점심";
  if (value === "DINNER") return "저녁";
  return value;
}

/** 금액은 KRW 원 단위 정수다(minor = 원). */
export function formatWon(minor: number): string {
  return `${minor.toLocaleString("ko-KR")}원`;
}

const TIME = new Intl.DateTimeFormat("ko-KR", { hour: "2-digit", minute: "2-digit", second: "2-digit" });

export function formatTime(ms: number): string {
  return TIME.format(new Date(ms));
}

/**
 * 계산원에게 보여줄 거절 안내.
 *
 * `title` 은 무슨 일이 일어났는지, `guidance` 는 **지금 손님에게 뭐라고 말하고 무엇을 하면 되는지**다.
 * 이 화면에서 가장 흔한 실패(만료·이미 사용됨)는 손님이 QR 을 다시 발급하면 바로 해결되는데,
 * "Bad Request" 같은 문구만 띄우면 계산대가 멈추고 매장이 본사에 전화한다.
 */
export interface Decline {
  title: string;
  guidance: string;
  /** 같은 QR·금액으로 다시 시도해 볼 만한가(일시적 실패인가). */
  retryable: boolean;
  /** 이 QR 은 이미 죽었는가 — 새 QR 을 받아야 한다. */
  needsNewQr: boolean;
}

/**
 * errorCode 로 분기한다(HTTP 상태가 아니라). 상태코드는 여러 사유가 공유하지만
 * (400: 무효·만료, 422: 시간대·횟수), errorCode 는 사유마다 하나뿐이라 안내가 섞이지 않는다.
 */
export function declineOf(error: ApiErrorBody): Decline {
  switch (error.errorCode) {
    case "QR_TOKEN_INVALID":
      return {
        title: "읽을 수 없는 QR 입니다",
        guidance: "손님 화면의 QR 이 맞는지 확인하고, 앱에서 새로 발급받아 다시 보여 달라고 안내하세요.",
        retryable: false,
        needsNewQr: true,
      };
    case "QR_TOKEN_EXPIRED":
      return {
        title: "QR 이 만료됐습니다",
        guidance: "식권 QR 은 발급 후 1분만 유효합니다. 손님에게 앱에서 다시 발급받아 달라고 안내하세요.",
        retryable: false,
        needsNewQr: true,
      };
    case "QR_TOKEN_ALREADY_USED":
      return {
        title: "이미 사용된 QR 입니다",
        guidance:
          "결제가 이미 끝났을 수 있습니다. 아래 최근 승인 내역을 먼저 확인하고, 승인 기록이 없으면 새 QR 을 요청하세요.",
        retryable: false,
        needsNewQr: true,
      };
    case "MEAL_WINDOW_CLOSED":
      return {
        title: "지금은 식대 이용 시간이 아닙니다",
        guidance:
          "손님 회사가 정한 아침·점심·저녁 시간대에만 식권이 사용됩니다. 이번 결제는 손님이 직접 결제해야 합니다.",
        retryable: false,
        needsNewQr: false,
      };
    case "DAILY_MEAL_LIMIT":
      return {
        title: "오늘 이용 횟수를 모두 썼습니다",
        guidance:
          "손님이 오늘 사용할 수 있는 식권 횟수를 이미 채웠습니다. 이번 결제는 손님이 직접 결제해야 합니다.",
        retryable: false,
        needsNewQr: false,
      };
    case "MERCHANT_SUSPENDED":
      return {
        title: "이 매장은 지금 식권 결제를 쓸 수 없습니다",
        guidance: "매장 상태가 정지되어 있습니다. 손님에게는 다른 결제 수단을 안내하고, 본사에 문의하세요.",
        retryable: false,
        needsNewQr: false,
      };
    case "FORBIDDEN":
      return {
        title: "이 손님은 식권을 쓸 수 없습니다",
        guidance:
          "회사 소속이 해지됐거나 계정이 정지된 상태입니다. 손님에게 회사 담당자 확인을 안내하고, 이번 결제는 직접 결제로 진행하세요.",
        retryable: false,
        needsNewQr: false,
      };
    case "NOT_FOUND":
      return {
        title: "거래를 찾을 수 없습니다",
        guidance: "이미 취소됐거나 이 매장의 거래가 아닙니다. 최근 승인 내역을 확인하세요.",
        retryable: false,
        needsNewQr: false,
      };
    case "VALIDATION_ERROR":
      return {
        title: "입력값을 확인하세요",
        guidance: "금액과 QR 을 다시 확인한 뒤 시도하세요.",
        retryable: true,
        needsNewQr: false,
      };
    case "TERMINAL_NOT_CONFIGURED":
      return {
        title: "단말이 등록되지 않았습니다",
        guidance: "이 단말에 매장 자격증명이 설정되지 않았습니다. 관리자에게 설정을 요청하세요.",
        retryable: false,
        needsNewQr: false,
      };
    case "TERMINAL_UNAUTHORIZED":
      // 단말 등록이 만료됐다(유휴 7일·절대 90일). 재시도는 **영원히 성공하지 않으므로** 권하지 않는다 —
      // 계산원이 줄을 세워 둔 채 같은 버튼을 누르는 것을 막고, 등록 화면으로 가라고 말한다.
      return {
        title: "단말 등록이 만료되었습니다",
        guidance:
          "화면을 새로고침한 뒤 등록 키를 다시 입력하세요. 그 전까지는 다른 결제 수단으로 진행하세요.",
        retryable: false,
        needsNewQr: false,
      };
    case "TERMINAL_RATE_LIMITED":
      return {
        title: "잠시 후 다시 시도하세요",
        guidance: "단말 등록 시도가 일시적으로 제한되었습니다. 잠깐 기다린 뒤 다시 시도하세요.",
        retryable: true,
        needsNewQr: false,
      };
    case "TERMINAL_UPSTREAM_ERROR":
      return {
        title: "서버와 통신하지 못했습니다",
        guidance:
          "승인 여부가 확정되지 않았습니다. 같은 QR·금액으로 한 번 더 시도하세요(중복 승인되지 않습니다). 계속 실패하면 다른 결제 수단으로 진행하세요.",
        retryable: true,
        needsNewQr: false,
      };
    default:
      return {
        title: "결제를 승인하지 못했습니다",
        guidance: "잠시 후 다시 시도하고, 계속 실패하면 다른 결제 수단으로 진행하세요.",
        retryable: true,
        needsNewQr: false,
      };
  }
}
