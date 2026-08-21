import { ApiError } from "@/lib/api";
import { messageOf } from "@/lib/useApi";

/**
 * 서버 errorCode → 한국어 안내.
 *
 * 서버의 `message` 는 두 종류다: ①`ErrorCode` 의 **영어 기본 문구**("Validation error") ②서비스가
 * 던지며 붙인 **구체적인 한국어 사유**("이미 이 매장의 담당자입니다"). 코드만 보고 우리 문구로
 * 갈아 끼우면 ②가 사라진다.
 *
 * ★그 사고가 실제로 났다: `VALIDATION_ERROR` 를 무조건 "입력값을 확인하세요."로 덮은 탓에, 서버가
 * 정확히 무엇이 잘못됐는지 알려 준 경우에도 화면은 그 문장만 보여줬다. 관리자는 어느 입력이 문제인지
 * 알 수 없어 **같은 값을 다시 넣고 다시 실패**했다(가맹 담당자 추가·클라이언트 등록에서 재현).
 * "친절한 문구"가 정보를 지운 셈이다.
 *
 * 그래서 두 갈래로 나눈다.
 */

/**
 * **우리 문구가 서버 문구보다 확실히 나은 코드.** 서버가 영어 상수만 주거나, 사용자가 다음에 무엇을
 * 해야 하는지가 코드로부터 일의적으로 정해지는 경우다.
 */
const OVERRIDE: Record<string, string> = {
  ADMIN_SELF_ACTION:
    "자기 자신은 정지하거나 관리자 역할을 해제할 수 없습니다(서버가 409 ADMIN_SELF_ACTION 으로 거부). 다른 플랫폼 관리자 계정으로 수행하세요.",
  IAM_POLICY_IMMUTABLE:
    "시스템 관리 정책은 수정·삭제할 수 없습니다(409). 동작을 바꾸려면 별도 정책을 만들어 부착하세요.",
  IAM_CONFLICT: "같은 이름의 IAM 리소스가 이미 있습니다. 다른 이름을 사용하세요.",
  // 서버가 저장 전에 되돌아올 수 있는지 실제로 평가한 결과다 — 무엇을 확인해야 하는지까지 말해 준다.
  IAM_LOCKOUT:
    "이 변경을 적용하면 IAM 정책을 관리할 수 있는 플랫폼 관리자가 아무도 남지 않아 되돌릴 방법이 없어집니다. 저장하지 않았습니다. 다른 관리자에게 IAM 권한이 남아 있는지 확인하거나, Deny 범위를 좁혀 주세요.",
  IAM_POLICY_NOT_FOUND: "정책을 찾을 수 없습니다. 목록을 새로고침해 주세요.",
  CLIENT_ID_ALREADY_EXISTS: "이미 사용 중인 client_id 입니다. 다른 값을 입력하세요.",
  CLIENT_NOT_CONFIDENTIAL:
    "공개 클라이언트에는 시크릿이 없습니다. 시크릿이 필요하면 기밀 클라이언트로 새로 등록하세요.",
  DOMAIN_ALREADY_CLAIMED: "이미 다른 조직이 검증한 도메인입니다. 소유 조직에서 먼저 해제해야 합니다.",
  /*
   * ★"플랫폼 관리자 계정인지 확인하세요"(서버 일반 문구)는 **이 화면에서는 사실일 수 없다.**
   * `/admin/**` 은 체인에서 이미 `hasRole("ADMIN")` 을 통과해야 도달하므로, 여기까지 와서 나는
   * 403 은 언제나 "IAM 정책이 거부했다"는 뜻이다. 실제로 자기 락아웃된 관리자가 그 문구를 보고
   * 계정 문제로 오해해 엉뚱한 곳을 뒤졌다. 복구 절차는 docs/iam-operations.md.
   */
  FORBIDDEN:
    "IAM 정책이 이 작업을 거부했습니다. 계정(또는 소속 그룹)에 부착된 명시적 Deny 정책 때문일 수 있습니다. 다른 플랫폼 관리자에게 해당 정책의 부착 해제를 요청하거나 docs/iam-operations.md 의 복구 절차를 따르세요.",
};

/**
 * ★**`serverDefault` 와 비교하려던 시도는 틀렸다**(적대 리뷰에서 잡혔다).
 *
 * 서버 `GlobalExceptionHandler` 는 응답을 만들 때 이미 `error.{CODE}` 를 **요청 로케일로 해석**한다 —
 * 즉 프런트가 받는 문구는 언제나 한국어 안내이지 `ErrorCode` 의 영어 상수가 아니다. 그래서
 * "영어 상수와 같으면 우리 문구로 대신한다"는 비교는 **한 번도 참이 되지 않고**, 그 갈래에 넣어 둔
 * 안내는 전부 죽은 코드였다(고쳤다고 믿은 IAM 거부 안내가 정확히 그 상태였다).
 *
 * 그래서 갈래를 다시 정리했다:
 * - 서버가 이미 구체적이거나 충분한 코드(VALIDATION_ERROR·NOT_FOUND 등) → **그대로 통과**시킨다.
 *   우리가 지어낼 수 있는 문구가 서버 것보다 나을 이유가 없다.
 * - 서버의 일반 문구가 **이 화면 맥락에서 사실과 어긋나는** 코드 → [OVERRIDE] 로 올린다.
 *   `FORBIDDEN` 이 그렇다: `/admin/**` 은 체인에서 이미 `hasRole("ADMIN")` 을 통과했으므로 여기서의
 *   거부는 언제나 IAM 정책 때문이고, 서버의 "관리자에게 문의" 는 이 자리에서 오답이다.
 */

/** 서버가 문구를 주지 못했을 때만 쓰는 최후 문장 — 빈 오류 표시(=아무 표시도 없음)를 막는다. */
const LAST_RESORT = "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";

export function adminErrorText(cause: unknown): string {
  if (cause instanceof ApiError) {
    const override = OVERRIDE[cause.errorCode];
    if (override) return override;
    // 서버 문구가 곧 사유다 — 지우거나 덮지 않는다(그게 이 파일이 고친 원래 결함이다).
    const serverMessage = cause.message?.trim() ?? "";
    return serverMessage.length > 0 ? serverMessage : LAST_RESORT;
  }
  return messageOf(cause);
}

export function isErrorCode(cause: unknown, code: string): boolean {
  return cause instanceof ApiError && cause.errorCode === code;
}
