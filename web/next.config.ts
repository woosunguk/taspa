import type { NextConfig } from "next";

/**
 * taspa 인증 서버 오리진. 개발 기본값은 로컬 dev 서버(:9100).
 * 배포에서는 리버스 프록시가 같은 오리진에 taspa 를 붙이거나, 이 값으로 내부 주소를 지정한다.
 */
const TASPA_ORIGIN = process.env.TASPA_ORIGIN ?? "http://localhost:9100";

/**
 * **`/api` 프록시의 유일한 예외** — `/api/pos/**` 는 이 Next 서버가 소유한다(POS 단말 BFF).
 *
 * 이유: POS 승인은 taspa 의 `/api/merchant/**` STATELESS 베어러 체인이 받는데, 그 자격증명
 * (가맹 client secret)은 **브라우저에 둘 수 없다**. 그래서 Next 서버가 secret 을 들고
 * client_credentials 토큰을 받아 대신 호출한다(`app/api/pos` 아래의 Route Handler 들).
 *
 * `beforeFiles` 는 파일시스템 라우트보다 **먼저** 도는 게 존재 이유라(서버 소유 경로를 앱이 실수로
 * 가로채지 못하게 한다), 예외를 두지 않으면 우리 Route Handler 는 영원히 실행되지 않고 요청이
 * 그대로 taspa 로 새어 나가 401 이 된다. 예외는 소스 패턴에 **부정 전방탐색**으로 박아 둔다 —
 * 순서에 의존하는 별도 항목보다 규칙이 한 곳에 남는다.
 *
 * `pos` **세그먼트 전체**가 일치할 때만 제외된다(`/api/possible/x` 는 그대로 프록시).
 */
const POS_BFF_SEGMENT = "pos";

/** taspa 가 계속 소유하는 경로 — 이 앱은 프록시만 한다(라우트로 가로채지 않는다). */
const PROXIED_PREFIXES = [
  "/login", // 서버 렌더링 로그인 플로우(identifier-first·MFA·패스키·소셜 게이트)
  "/oauth2", // 인가 코드 흐름·동의·JWKS
  "/webauthn", // 패스키 등록/어서션 엔드포인트
  "/password-reset",
  "/saml2",
  /*
   * ★**메일 링크가 착지하는 경로는 반드시 여기 있어야 한다.**
   *
   * `/orgs/invite/accept` 는 조직 초대 메일의 수락 링크다(서버 렌더링 — `OrgInvitationAcceptController`).
   * 이게 빠져 있던 동안, 배포 문서가 지시하는 구성(`TASPA_PUBLIC_BASE_URL` = SPA 도메인)에서 초대 메일을
   * 클릭한 직원이 **Next 기본 404**(영문 한 줄, 브랜딩도 되돌아갈 링크도 없음)에 착지했다.
   * 조직에 사람을 넣는 자율 경로는 초대뿐이라 온보딩이 그 자리에서 멈추는데, 발송은 성공했으므로
   * 서버 로그에도 흔적이 없다 — 조직관리자도 운영자도 원인을 알 수 없는 형태다.
   *
   * 링크 base 는 비밀번호 재설정·매직링크·초대가 **한 키를 공유**한다(`taspa.org-invitation.base-url`
   * 과 `taspa.password-reset.base-url` 모두 `TASPA_PUBLIC_BASE_URL`). 앞의 둘은 여기 있었고 초대만
   * 빠져 있었다 — 의도가 아니라 누락이다.
   */
  "/orgs", // 조직 초대 수락(메일 링크 착지점)
  /*
   * ★`/reauth` 는 **접두**로도 넣는다. 정확 일치만 두면 서버가 소유하고 재인증 화면이 직접 링크하는
   * 하위 경로 `/reauth/social/{provider}`(소셜로 재인증) 가 SPA 오리진에서 404 가 된다 —
   * step-up 이 필요한 순간에 막다른 길이 되는 형태라, 정확히 이 감사가 고치려던 종류의 결함이다.
   */
  "/reauth",
  "/css", // 서버 렌더링 화면이 참조하는 정적 자원
  "/js",
];

/**
 * 하위 경로가 없는 단일 페이지들.
 *
 * `/activate`·`/activated` 는 OAuth2 Device Authorization Grant 의 사용자 검증 URI 다
 * (`SecurityConfig` 의 `verificationUri("/activate")`). TV·CLI 가 화면에 띄우는 주소라 사용자가
 * **손으로 입력**하는데, 공개 도메인이 웹 티어면 그 주소가 404 였다.
 */
const PROXIED_EXACT = ["/login", "/logout", "/signup", "/reauth", "/error", "/activate", "/activated"];

/**
 * `/account` **페이지는 SPA 가 소유**하지만, 그 아래 소셜 연결 **시작** 경로만은 서버가 소유한다.
 *
 * 이유: `GET /account/federations/link/{provider}` 는 단순 링크가 아니라 세션에 `SocialLinkIntent`
 * 마커를 심은 뒤 `/oauth2/authorization/{provider}` 로 리다이렉트하는 서버 핸들러다. 성공 핸들러는
 * 이 마커로만 "이미 로그인한 세션의 연결 추가"를 식별한다(oauth2Login 필터가 SecurityContext 를
 * 교체한 뒤라 다른 방법이 없다). SPA 가 이 경로를 가로채면 소셜 계정 연결이 조용히 로그인 플로우가 된다.
 * 서버는 완료 후 `/account?linked=1` 또는 `/account?linkError=...` 로 되돌려 보내고, 그 화면은 SPA 가 받는다.
 */
const PROXIED_ACCOUNT_SUBPATH = "/account/federations/link/:provider";

/**
 * **동일 오리진 프록시 — 이 앱의 인증 전략.**
 *
 * taspa 의 `/api/orgs/**`·`/api/admin/**` 은 세션 쿠키로 인증하고, 컨트롤러가 사용자 위임 베어러 토큰을
 * **의도적으로 거부**한다(confused-deputy 차단 — 위임 토큰은 동의 경계 안에서만 쓰인다). 따라서 다른
 * 오리진의 SPA 가 액세스 토큰으로 이 API 들을 호출하면 설계상 403 이다.
 *
 * 그래서 브라우저에게 이 앱과 taspa 를 **한 오리진으로 보이게** 만든다. 세션 쿠키·step-up 재인증·
 * CSRF 토큰이 서버가 기대하는 그대로 흐르고, 어렵게 세운 서버 인가 모델을 한 줄도 손대지 않는다.
 *
 * `beforeFiles` 를 쓰는 이유: 배열 반환형은 파일시스템 라우트를 **먼저** 확인하므로, 앱에 실수로
 * `/login` 같은 라우트가 생기면 서버 소유 경로를 가로채게 된다. beforeFiles 는 그 사고를 구조적으로 막는다.
 */
const nextConfig: NextConfig = {
  /**
   * 컨테이너 배포용 최소 산출물(`.next/standalone`) — `node_modules` 없이 `node server.js` 로 뜬다.
   *
   * 이 티어는 POS 결제 중계 자격증명(`POS_CLIENT_SECRET`·`POS_TERMINAL_KEY`)을 쥐고 있다. 런타임
   * 이미지에 빌드 도구와 전체 의존성 트리를 남기지 않는 쪽이 그 비밀에 닿을 수 있는 표면을 줄인다.
   * 자세한 배포 절차는 `web/Dockerfile` 주석.
   */
  output: "standalone",

  async rewrites() {
    return {
      beforeFiles: [
        // 콘솔·계정·관리 API (세션 쿠키 인증) — `/api/pos/**` 만 제외하고 전부 taspa 가 처리한다.
        {
          source: `/api/:path((?!${POS_BFF_SEGMENT}$|${POS_BFF_SEGMENT}/).*)`,
          destination: `${TASPA_ORIGIN}/api/:path`,
        },
        ...PROXIED_PREFIXES.map((prefix) => ({
          source: `${prefix}/:path*`,
          destination: `${TASPA_ORIGIN}${prefix}/:path*`,
        })),
        ...PROXIED_EXACT.map((path) => ({
          source: path,
          destination: `${TASPA_ORIGIN}${path}`,
        })),
        {
          source: PROXIED_ACCOUNT_SUBPATH,
          destination: `${TASPA_ORIGIN}${PROXIED_ACCOUNT_SUBPATH}`,
        },
      ],
      afterFiles: [],
      fallback: [],
    };
  },
};

export default nextConfig;
