# 소셜 로그인 설정 가이드 (구글 · 카카오 · 네이버)

taspa 는 클라이언트 자격 증명이 **환경변수로 주어진 공급자만** 조건부로 등록한다
(`federation/SocialClientRegistrations`). 아무 것도 설정하지 않으면 소셜 버튼이 노출되지 않고
서버는 정상 기동한다 — yaml 방식(`spring.security.oauth2.client.registration`)은 client-id 가
비면 기동이 실패하므로 사용하지 않는다.

## 환경변수

| 공급자 | 환경변수 |
|--------|----------|
| 구글 | `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` |
| 카카오 | `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET` |
| 네이버 | `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET` |

쌍(ID+SECRET)이 모두 있어야 해당 공급자가 등록된다.

```bash
KAKAO_CLIENT_ID=... KAKAO_CLIENT_SECRET=... ./gradlew :server:bootRun
```

## Redirect URI (공통)

공급자 콘솔에 등록할 콜백 주소는 Spring Security oauth2Login 표준 패턴이다.

```
{baseUrl}/login/oauth2/code/{registrationId}
```

로컬 기준:

- 구글: `http://localhost:9100/login/oauth2/code/google`
- 카카오: `http://localhost:9100/login/oauth2/code/kakao`
- 네이버: `http://localhost:9100/login/oauth2/code/naver`

## 공급자별 콘솔 등록 절차

### 구글 (Google Cloud Console)

1. https://console.cloud.google.com → 프로젝트 생성 → "API 및 서비스 > OAuth 동의 화면" 구성
   (범위: `openid`, `email`, `profile`).
2. "사용자 인증 정보 > 사용자 인증 정보 만들기 > OAuth 클라이언트 ID" — 유형 "웹 애플리케이션".
3. "승인된 리디렉션 URI" 에 위 redirect URI 등록.
4. 발급된 클라이언트 ID/보안 비밀 → `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET`.

구글은 OIDC 로 동작하며(`CommonOAuth2Provider.GOOGLE` 내장 — provider URI 설정 불필요),
id_token 의 `email`, `email_verified` 클레임을 신뢰한다.

### 카카오 (Kakao Developers)

1. https://developers.kakao.com → "내 애플리케이션 > 애플리케이션 추가".
2. "제품 설정 > 카카오 로그인" 활성화, "Redirect URI" 에 위 주소 등록.
3. "카카오 로그인 > 동의항목": `account_email`(카카오계정 이메일), `profile_nickname`,
   `profile_image` 동의 설정. 이메일은 비즈 앱 전환 후 필수 동의로 설정 가능 — 아니면 사용자가
   동의를 거부할 수 있고, 그 경우 taspa 는 이메일 입력·확인 게이트(`/login/social-email`)로 처리한다.
4. "제품 설정 > 카카오 로그인 > 보안" 에서 Client Secret 생성·활성화.
5. "앱 설정 > 앱 키" 의 **REST API 키** → `KAKAO_CLIENT_ID`, 생성한 Client Secret → `KAKAO_CLIENT_SECRET`.

카카오는 순수 OAuth2 방식으로 붙는다(OIDC 미사용 — issuer 디스커버리 기동 시 HTTP 호출 회피,
이메일 검증 판단은 `/v2/user/me` 의 `is_email_valid && is_email_verified` 가 명확).
토큰 요청은 `client_secret_post` 방식이다.

### 네이버 (NAVER Developers)

1. https://developers.naver.com/apps → "애플리케이션 등록", 사용 API "네이버 로그인".
2. 제공 정보 선택: 이메일 주소, 이름(또는 별명), 프로필 사진.
3. "서비스 URL" 과 "네이버 로그인 Callback URL" 에 위 redirect URI 등록.
4. 클라이언트 ID/시크릿 → `NAVER_CLIENT_ID`/`NAVER_CLIENT_SECRET`.

네이버는 OIDC 를 지원하지 않으며 userinfo(`/v1/nid/me`) 응답이 `response` 아래 중첩이라
`federation/FederatedOAuth2UserService` 가 평탄화한다. **이메일 검증 플래그가 없으므로 네이버
이메일은 항상 미검증으로 취급**되어 자동 연결 대상이 아니다(아래 정책 참고).

## 계정 연결 정책 (Keycloak/Auth0 모델)

콜백에서 `FederatedLoginSuccessHandler` 가 분기한다.

1. 이미 연결된 소셜 신원 → 그 사용자로 게이트 판정(MFA 등) 후 로그인.
2. 미연결 + 같은 이메일의 로컬 계정 존재:
   - 공급자 이메일 검증됨 **그리고** 로컬 이메일 검증됨 → 자동 연결.
     (한쪽이라도 미검증인 계정에 자동 연결하면 계정 선점 탈취가 가능하다 — better-auth 사례)
   - 아니면(네이버 전부 포함) → `/login/link-confirm` 에서 이메일 코드로 본인 확인 후 연결.
3. 미연결 + 로컬 계정 없음 → 신규 계정 생성(비밀번호 없음). 공급자 미검증 이메일이면 기존
   이메일 인증 게이트를 통과해야 한다.
4. 이메일 미제공(카카오 동의 거부) → `/login/social-email` 에서 이메일 입력·코드 확인 후 계정 생성.

소셜 로그인도 **로컬 MFA 게이트를 그대로 적용**한다(Auth0/Okta 모델). 패스키만 자체 2요소라 생략.

## 계정 페이지 연결/해제

- 연결: `/account` 의 "연결된 계정" 섹션 → 공급자 버튼(`/account/federations/link/{provider}`)
  → 공급자 인증 후 연결만 수행하고 `/account?linked=1` 로 복귀.
- 해제: `DELETE /api/federations/{provider}`. 비밀번호·패스키·다른 소셜 연결이 하나도 남지 않으면
  409(`LAST_LOGIN_METHOD`)로 거부한다. 해제 시 알림 메일을 발송한다.

## 브랜딩 주의

로그인 버튼은 중립 텍스트 버튼("Google로 계속하기" 등)이다. 공급자 브랜드 로고/버튼 에셋은
각사 브랜드 가이드라인 준수가 필요하므로 저장소에 커밋하지 않는다 — 실배포 시 공식 에셋으로 교체할 것.

## 테스트

`federation/SocialLoginFlowIntegrationTest` 가 WireMock 으로 카카오/네이버 token·userinfo 를 스텁해
전체 플로우(자동 연결 / link-confirm / social-email / unlink 정책 / passwordless 폼 로그인 차단)를
검증한다. 공급자 URI 는 `taspa.social.{kakao|naver}.{authorization-uri|token-uri|user-info-uri}`
프로퍼티로 교체 가능하다(테스트 전용 — 운영 기본값은 실제 공급자 주소).
