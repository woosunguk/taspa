# syntax=docker/dockerfile:1
#
# taspa web 티어(Next.js) 컨테이너 이미지 — 멀티스테이지, non-root 실행.
#
#   deps    : npm ci 로 의존성만 설치(소스와 레이어 분리 → 소스만 바뀌면 재설치하지 않는다)
#   builder : next build → .next/standalone (자체 node_modules 없이 도는 최소 서버)
#   runner  : node 런타임 + standalone 산출물만. 빌드 도구도 전체 의존성 트리도 없다.
#
# ============================================================================
# ★빌드 방법 — 컨텍스트는 저장소 루트가 아니라 이 디렉터리다.
#
#   docker build -f web/Dockerfile -t taspa-web:<tag> \
#     --build-arg TASPA_ORIGIN=http://taspa-server:9100 \
#     ./web
#
# 저장소 루트를 컨텍스트로 주면 Docker 가 `web/.dockerignore` 를 읽지 않아 **`.env.local` 이 이미지에
# 들어간다**(거기에 POS 자격증명 실값이 있다). 컨텍스트를 옮기지 말 것.
#
# ============================================================================
# ★TASPA_ORIGIN 은 빌드 시각에 굳는다 — 런타임 env 로 바꿀 수 없다.
#
# `next.config.ts` 의 rewrites() 는 **빌드 중 한 번** 실행되고 그 결과(목적지 URL 문자열)가
# `.next/routes-manifest.json` 에 직렬화된다. standalone 서버는 그 매니페스트를 읽을 뿐 설정 파일을
# 다시 평가하지 않는다. 따라서 동일 오리진 프록시의 목적지는 **이미지에 박힌 값**이다.
#
#   확인:  docker run --rm taspa-web:<tag> \
#            node -e "console.log(require('./.next/routes-manifest.json').rewrites.beforeFiles[0].destination)"
#
# 그래서 이 값은 시크릿이 아니라 **환경 식별자**이며, 빌드 인자로 받는 것이 옳다(굽히는 게 정상인 값).
# 반대로 아래 POS 자격증명은 빌드 인자로 주면 안 된다 — ARG/ENV 는 이미지 레이어와 `docker history`
# 에 남아, 이미지를 pull 할 수 있는 누구나 읽는다.
#
# 런타임 스테이지에도 같은 값을 ENV 로 심는 이유: POS BFF(`lib/pos-terminal.ts`)는 이 변수를 **런타임에**
# 읽어 taspa 토큰 엔드포인트를 호출한다. 둘이 어긋나면 프록시는 A 를, 결제 중계는 B 를 보게 된다.
# 런타임에 덮어써도 프록시 목적지는 따라오지 않으므로, 오리진을 바꾸려면 **다시 빌드**해야 한다.
#
# ============================================================================
# ★시크릿 주입 — 런타임 env 로만.
#
#   docker run -d -p 3000:3000 \
#     -e POS_CLIENT_ID=... \
#     -e POS_CLIENT_SECRET=... \
#     -e POS_TERMINAL_KEY=... \
#     -e POS_SESSION_SECRET=... \
#     taspa-web:<tag>
#
# 오케스트레이터에서는 파일 마운트(Kubernetes Secret / docker compose `secrets:`)로 주고 env 로 노출한다.
# 값의 의미·발급 절차·회수 절차·엔트로피 요구사항은 `web/.env.example` 에 전부 적혀 있다.
#
# 주의(운영): POS_TERMINAL_KEY 가 약하면 서버는 **첫 요청 시점**에 등록 기능을 꺼 버린다(fail-closed).
# 즉 약한 키로도 컨테이너는 정상 기동하고 헬스체크도 통과한다 — 배포 후 `/pos` 를 한 번 열어
# 잠기지 않았는지 확인할 것.
# ============================================================================

# ---- deps stage ----
# alpine 을 쓸 수 있는 근거: package-lock.json 에 @next/swc-linux-{x64,arm64}-musl 이 들어 있다.
FROM node:24-alpine AS deps
WORKDIR /app
# next-swc 네이티브 바이너리가 glibc 심볼을 참조한다(musl 빌드여도 shim 이 필요하다).
RUN apk add --no-cache libc6-compat
# 락파일 기준 재현 가능한 설치. `npm install` 이 아니라 `npm ci` 여야 락파일이 조용히 갱신되지 않는다.
COPY package.json package-lock.json ./
RUN npm ci

# ---- builder stage ----
FROM node:24-alpine AS builder
WORKDIR /app
RUN apk add --no-cache libc6-compat
COPY --from=deps /app/node_modules ./node_modules
COPY . .

# 위 "TASPA_ORIGIN 은 빌드 시각에 굳는다" 참고. 기본값은 코드 기본값(`next.config.ts`)과 같지만,
# 컨테이너 안에서 localhost 는 **이 컨테이너 자신**이므로 실제 배포에서는 거의 항상 넘겨야 한다.
ARG TASPA_ORIGIN=http://localhost:9100
ENV TASPA_ORIGIN=${TASPA_ORIGIN}
# 이 값이 번들에 굳어, 런타임에 오리진이 조용히 갈라지면 POS BFF 가 첫 요청에서 크게 실패한다
# (lib/pos-terminal.ts 의 가드). 문서가 아니라 코드로 "오리진 변경 = 재빌드"를 집행한다.
ENV NEXT_PUBLIC_TASPA_BUILD_ORIGIN=${TASPA_ORIGIN}

ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

# ---- runtime stage ----
FROM node:24-alpine AS runner
WORKDIR /app

ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
# 0.0.0.0 이 아니면 컨테이너 밖에서 닿지 않는다(standalone server.js 기본은 localhost).
ENV HOSTNAME=0.0.0.0

# 프록시 목적지가 이미지에 박힌 값과 같도록 런타임에도 노출한다(위 주석 참고).
ARG TASPA_ORIGIN=http://localhost:9100
ENV TASPA_ORIGIN=${TASPA_ORIGIN}

RUN addgroup --system --gid 1001 taspa \
    && adduser --system --uid 1001 --ingroup taspa --home /app --shell /sbin/nologin taspa

# standalone 은 public/ 과 .next/static 을 복사하지 않는다(CDN 전제) — CDN 없이도 뜨도록 직접 넣는다.
COPY --from=builder --chown=taspa:taspa /app/public ./public
COPY --from=builder --chown=taspa:taspa /app/.next/standalone ./
COPY --from=builder --chown=taspa:taspa /app/.next/static ./.next/static

USER taspa
EXPOSE 3000

# 헬스체크는 **taspa 서버에 의존하지 않는 경로**여야 한다. `/` 는 클라이언트 컴포넌트라 SSR 이
# 외부 호출 없이 끝난다 — 서버 이미지가 집계 health 대신 liveness 프로브를 쓰는 것과 같은 이유로,
# 의존 서비스 장애가 이 티어의 재시작 루프로 번지지 않게 한다. wget 은 busybox 내장(추가 패키지 없음).
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:3000/ || exit 1

CMD ["node", "server.js"]
