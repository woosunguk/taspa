/* 계정 화면 전용 표시 헬퍼. 서버가 주는 원값(Instant ISO 문자열·method 코드)을 사람이 읽는 한국어로 바꾼다. */

const DATE_TIME = new Intl.DateTimeFormat("ko-KR", {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
});

const DATE_ONLY = new Intl.DateTimeFormat("ko-KR", {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});

/** 서버 Instant(ISO-8601) → "2026. 07. 25. 14:03". null 은 "-"(값 없음을 0 처럼 보이게 하지 않는다). */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return "-";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "-" : DATE_TIME.format(date);
}

export function formatDate(value: string | null | undefined): string {
  if (!value) return "-";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "-" : DATE_ONLY.format(date);
}

/** "3일 전"처럼 대략적인 경과. 정확한 시각은 title 로 함께 노출해 둘 다 잃지 않게 한다. */
export function relativeFromNow(value: string | null | undefined): string {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "-";

  const diffMs = Date.now() - date.getTime();
  const minutes = Math.round(diffMs / 60000);
  if (minutes < 1) return "방금 전";
  if (minutes < 60) return `${minutes}분 전`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours}시간 전`;
  const days = Math.round(hours / 24);
  if (days < 30) return `${days}일 전`;
  return formatDate(value);
}

/**
 * login_events.method 라벨. 서버는 password / mfa / passkey / social:{provider} / magic 을 그대로 준다.
 * 모르는 값은 감추지 않고 원문을 보여준다 — 새 로그인 수단이 추가돼도 기록이 비어 보이지 않게.
 */
export function loginMethodLabel(method: string): string {
  switch (method) {
    case "password":
      return "비밀번호";
    case "mfa":
      return "비밀번호 + 2단계 인증";
    case "passkey":
      return "패스키";
    case "magic":
      return "매직 링크";
    default:
      break;
  }
  if (method.startsWith("social:")) {
    return `${socialLabel(method.slice("social:".length))} 로그인`;
  }
  return method;
}

/** 소셜 공급자 표시명 — 서버 `SocialProviders.label` 과 같은 규칙(기업 SSO 접두사 포함). */
export function socialLabel(provider: string): string {
  switch (provider) {
    case "google":
      return "Google";
    case "kakao":
      return "카카오";
    case "naver":
      return "네이버";
    default:
      break;
  }
  if (provider.startsWith("saml:")) return `${provider.slice(5)} (SAML)`;
  if (provider.startsWith("oidc:")) return `${provider.slice(5)} (OIDC)`;
  return provider;
}
