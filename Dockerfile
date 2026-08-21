# syntax=docker/dockerfile:1
# taspa 인증 서버 컨테이너 이미지(멀티스테이지).
#  - build: JDK 21 + Gradle 래퍼로 :server:bootJar 산출
#  - runtime: slim JRE 21, non-root 유저, EXPOSE 9100, HEALTHCHECK

# ---- Build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 래퍼/빌드 스크립트 먼저 복사해 의존성 레이어를 소스와 분리(캐시 효율).
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY server/build.gradle.kts server/build.gradle.kts
COPY client ./client
COPY examples ./examples
RUN chmod +x gradlew && ./gradlew --no-daemon :server:dependencies || true

# 소스 복사 후 실제 bootJar 빌드(테스트는 이미지 빌드에서 제외 — CI 파이프라인에서 수행,
# .github/workflows/ci.yml 의 build-and-test 잡이 ./gradlew build 로 515개 통합테스트를 실행한다).
COPY server ./server
RUN ./gradlew --no-daemon clean :server:bootJar -x test

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system taspa \
    && useradd --system --gid taspa --home-dir /app --shell /usr/sbin/nologin taspa
WORKDIR /app
COPY --from=build /workspace/server/build/libs/taspa-server.jar app.jar
USER taspa
EXPOSE 9100

# ★prod 프로파일을 이미지 기본값으로 고정한다. 이 한 줄이 없으면 default 프로파일로 기동해
# CSP/HSTS·rate limit·graceful shutdown·SMTP 타임아웃·XFF 신뢰·issuer https 검증이 전부 꺼진 채로
# "정상 기동"한다(ProductionSafetyValidator 는 @Profile("prod") 라 생성조차 되지 않는다).
# 다른 프로파일로 띄우려면 실행 시 -e SPRING_PROFILES_ACTIVE=... 로 덮어쓴다.
ENV SPRING_PROFILES_ACTIVE=prod

# 컨테이너 메모리에 맞춘 힙 + OOM 시 즉시 종료(좀비 JVM 방지 — 오케스트레이터가 교체하게 한다).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp"

# 컨테이너 헬스체크 — **liveness 프로브**를 쓴다(익명 허용, application-prod.yml 의 probes).
# 집계 /actuator/health 를 쓰면 mail 기여자(spring-boot-starter-mail 자동 등록)가 포함돼
# SMTP 장애가 컨테이너 재시작으로 번진다 — 인증 서버가 메일 서버에 물려 죽는 구조를 피한다.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:9100/actuator/health/liveness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
