# taspa web

taspa 의 사용자 화면 티어(Next.js). 직원 식권(`/meal`), 조직 관리 콘솔(`/console`), 플랫폼 관리(`/admin`),
계정(`/account`), 가맹 관리자(`/merchant`), 계산대 단말(`/pos`)을 제공한다.

인증 전략(동일 오리진 프록시)·UI 규약은 저장소 루트 `CLAUDE.md` 의 "웹 프런트엔드" 절을 따른다.
Next.js 자체는 학습 데이터와 다를 수 있으니 `node_modules/next/dist/docs/` 를 먼저 볼 것(`AGENTS.md`).

## 개발

```bash
cp .env.example .env.local   # 값 채우기
npm install
npm run dev                  # http://localhost:3000
```

taspa 서버(:9100)가 함께 떠 있어야 한다 — `/api/**`·`/login` 등은 이 앱이 그쪽으로 프록시한다.

## 배포

컨테이너 이미지 하나로 배포한다. **빌드·실행 절차와 그 이유는 `Dockerfile` 상단 주석이 정본**이며,
여기서는 요약만 적는다.

```bash
# 빌드 — 컨텍스트는 저장소 루트가 아니라 `./web` 이다(`web/.dockerignore` 가 적용되는 유일한 방법).
docker build -f web/Dockerfile -t taspa-web:<tag> \
  --build-arg TASPA_ORIGIN=http://taspa-server:9100 \
  ./web

# 실행 — 시크릿은 런타임 env 로만 주입한다.
docker run -d -p 3000:3000 \
  -e POS_CLIENT_ID=... -e POS_CLIENT_SECRET=... \
  -e POS_TERMINAL_KEY=... -e POS_SESSION_SECRET=... \
  taspa-web:<tag>
```

### 반드시 알고 있어야 하는 두 가지

**1. `TASPA_ORIGIN` 은 빌드 시각에 굳는다.** `next.config.ts` 의 `rewrites()` 는 빌드 중 한 번 실행되고
결과가 `.next/routes-manifest.json` 에 직렬화된다. 런타임 env 로 바꿔도 프록시 목적지는 따라오지 않는다
(POS BFF 만 런타임 값을 읽으므로, 덮어쓰면 프록시와 결제 중계가 서로 다른 서버를 보게 된다).
**환경이 다르면 다시 빌드한다.** 확인:

```bash
docker run --rm --entrypoint node taspa-web:<tag> \
  -e "console.log(require('/app/.next/routes-manifest.json').rewrites.beforeFiles[0].destination)"
```

**2. 시크릿은 빌드 인자로 넘기지 않는다.** `ARG`/`ENV` 는 이미지 레이어와 `docker history` 에 남아,
이미지를 pull 할 수 있는 누구나 읽는다. 이 티어가 쥔 `POS_CLIENT_SECRET`·`POS_TERMINAL_KEY` 는
그 매장의 결제 승인·취소 권한 그 자체다. `.env.local` 이 이미지에 들어가지 않는 것은
`.dockerignore` 가 보장하며, CI 의 `web-docker-build` 잡이 매 빌드마다 검사한다.

값의 의미·발급 절차·유출 시 회수 절차·등록 키 엔트로피 요구사항은 `.env.example` 에 있다.

> 배포 후 `/pos` 를 한 번 열어 볼 것. `POS_TERMINAL_KEY` 가 약하면 서버는 등록 기능을 꺼 버리는데
> (fail-closed), 그 판정이 기동이 아니라 **첫 요청 시점**이라 컨테이너는 정상 기동하고 헬스체크도
> 통과한다. 화면이 사유를 알려 준다.

## CI

`.github/workflows/ci.yml`

- `web-build` — `npm ci` → `npx tsc --noEmit` → `npx next build` (서버 잡과 병렬)
- `web-docker-build` — 이미지 빌드 + **이미지에 `.env*` 파일이 없는지 단언**
