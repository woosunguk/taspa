#!/usr/bin/env bash
# 배포 리허설 — prod 이미지를 실제로 띄워 사람이 밟는 경로를 끝까지 밟아 본다.
#
# 릴리스 전에 한 번 돌린다. 이 스크립트가 없던 동안 실제로 놓친 것들:
#   - 필수 환경변수가 하나 없으면 배포자가 받는 것이 자동설정 스택트레이스였다(원인이 묻힌다).
#   - 공개 JSON 가입 API 가 인증 코드를 보내지 않아, 그 경로로 가입한 사람은 오지 않는 메일을 기다렸다.
#   - docker-compose 의 `app` 서비스가 주석과 달리 prod 로 떠서 기동조차 못 했다.
# 셋 다 단위·통합 테스트와 e2e 를 모두 통과한 상태였다 — dev 프로파일에서는 드러나지 않는 형태였기 때문이다.
#
# 사용: deploy/rehearsal/run.sh            (이미지까지 빌드)
#       SKIP_BUILD=1 deploy/rehearsal/run.sh
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
NET=taspa-rehearsal-net
PG=taspa-rehearsal-pg
MAILC=taspa-rehearsal-mail
APP=taspa-rehearsal-app
WEB=taspa-rehearsal-web
SERVER_IMAGE=taspa-server:rehearsal
WEB_IMAGE=taspa-web:rehearsal
DB_PW='R3hearsal-Only-Pw-2026'
APP_PORT=9110
WEB_PORT=3010
MAIL_PORT=8125
FAILED=0

step() { printf '\n\033[1m== %s\033[0m\n' "$1"; }
fail() { echo "  FAIL $1"; FAILED=1; }
ok() { echo "  OK   $1"; }
expect() { if [ "$2" = "$3" ]; then ok "$1 ($2)"; else fail "$1 — 기대 $3, 실제 $2"; fi; }

cleanup() {
  step "정리"
  docker rm -f "$APP" "$WEB" "$PG" "$MAILC" >/dev/null 2>&1
  docker network rm "$NET" >/dev/null 2>&1
  echo "  리허설 컨테이너 제거 완료(개발용 컨테이너는 건드리지 않는다)"
}
trap cleanup EXIT

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  step "이미지 빌드"
  docker build -t "$SERVER_IMAGE" -f "$ROOT/Dockerfile" "$ROOT" >/dev/null || { fail "server 이미지 빌드"; exit 1; }
  docker build -t "$WEB_IMAGE" --build-arg "TASPA_ORIGIN=http://$APP:9100" \
    -f "$ROOT/web/Dockerfile" "$ROOT/web" >/dev/null || { fail "web 이미지 빌드"; exit 1; }
  ok "두 이미지 빌드"
fi

step "필수 환경변수 누락은 읽을 수 있는 한 개의 오류다"
# ★"기동이 실패한다"만 확인하면 부족하다 — 예전에도 실패는 했다. 문제는 **실패하는 방식**이었다.
OUT=$(docker run --rm -e DB_URL=jdbc:postgresql://x/y "$SERVER_IMAGE" 2>&1)
if grep -q "prod 프로파일 필수 환경변수가 설정되지 않았습니다" <<<"$OUT"; then
  ok "누락 목록을 한 번에 안내"
else
  fail "안내 대신 스프링 오류가 나온다"
fi
if grep -q "MailSenderAutoConfiguration" <<<"$OUT"; then
  fail "옛 실패 형태(자동설정 스택트레이스)가 그대로다"
else
  ok "자동설정 스택트레이스 아님"
fi

step "인프라 기동"
docker network create "$NET" >/dev/null 2>&1
docker run -d --name "$PG" --network "$NET" \
  -e POSTGRES_DB=taspa -e POSTGRES_USER=taspa_app -e POSTGRES_PASSWORD="$DB_PW" \
  postgres:16-alpine >/dev/null
docker run -d --name "$MAILC" --network "$NET" -p "$MAIL_PORT:8025" axllent/mailpit:v1.20 >/dev/null
for _ in $(seq 1 30); do
  docker exec "$PG" pg_isready -U taspa_app -d taspa >/dev/null 2>&1 && break
  sleep 1
done
ok "빈 Postgres + Mailpit"

step "prod 프로파일 기동(생성한 시크릿, 빈 DB)"
MFA_KEY=$(openssl rand -base64 32)
JWK_KEY=$(openssl rand -base64 32)
METRICS_PW=$(openssl rand -base64 24)

# ★★환경변수 **집합은 배포 매니페스트에서 가져온다**. 여기서 `-e` 목록을 손으로 적으면, 검증하는 것이
#   배포될 산출물이 아니라 리허설이 지어낸 조합이 된다 — 실제로 그래서 매니페스트의 `${VAR:-}` 결함
#   (선택 변수를 빈 문자열로 정의해 스프링 폴백을 무력화 → 문서대로 배포하면 기동 불가)을 이 리허설이
#   구조적으로 못 봤다. 이제 `docker compose config` 가 렌더한 server 서비스의 환경을 그대로 쓰고,
#   값만 리허설용으로 덮는다. **어떤 변수를 정의하는가**는 매니페스트가 정한다.
COMPOSE_ENV=$(
  docker compose --env-file "$ROOT/deploy/.env.prod.example" \
    -f "$ROOT/deploy/docker-compose.prod.yml" config --format json 2>/dev/null |
    python3 -c '
import json, sys
svc = json.load(sys.stdin)["services"]["server"]["environment"]
# null = 매니페스트가 "값 없으면 정의하지 않는다"고 선언한 선택 변수 → 여기서도 넘기지 않는다.
for k, v in svc.items():
    if v is not None:
        print(k)
'
)
if [ -z "$COMPOSE_ENV" ]; then
  fail "배포 매니페스트에서 환경변수 목록을 읽지 못했다"
  exit 1
fi
ok "매니페스트가 정의하는 서버 환경변수 $(wc -l <<<"$COMPOSE_ENV" | tr -d ' ')개"

# 매니페스트가 정의하는 변수만, 리허설용 실제 값으로 채운다.
declare -a ENV_ARGS=()
while read -r KEY; do
  [ -z "$KEY" ] && continue
  case "$KEY" in
  DB_URL) VAL="jdbc:postgresql://$PG:5432/taspa" ;;
  DB_USERNAME) VAL="taspa_app" ;;
  DB_PASSWORD) VAL="$DB_PW" ;;
  MAIL_HOST) VAL="$MAILC" ;;
  MAIL_USERNAME | MAIL_PASSWORD) VAL="" ;;
  TASPA_TRUSTED_PROXIES) VAL='172\.\d{1,3}\.\d{1,3}\.\d{1,3}' ;;
  TASPA_ISSUER_URI) VAL="https://auth.taspa.example" ;;
  TASPA_WEBAUTHN_RP_ID) VAL="taspa.example" ;;
  TASPA_WEBAUTHN_ALLOWED_ORIGINS) VAL="https://auth.taspa.example,https://app.taspa.example" ;;
  MFA_ENCRYPTION_KEY) VAL="$MFA_KEY" ;;
  TASPA_JWK_ENCRYPTION_KEY) VAL="$JWK_KEY" ;;
  *)
    fail "매니페스트에 리허설이 모르는 변수가 있다: $KEY (run.sh 에 값을 추가할 것)"
    continue
    ;;
  esac
  ENV_ARGS+=("-e" "$KEY=$VAL")
done <<<"$COMPOSE_ENV"

docker run -d --name "$APP" --network "$NET" -p "$APP_PORT:9100" \
  "${ENV_ARGS[@]}" \
  -e MAIL_PORT=1025 -e MAIL_SMTP_AUTH=false -e MAIL_SMTP_STARTTLS=false \
  -e TASPA_METRICS_SCRAPE_PASSWORD="$METRICS_PW" \
  -e TASPA_ADMIN_EMAILS=rehearsal-admin@taspa.example \
  "$SERVER_IMAGE" >/dev/null
for _ in $(seq 1 60); do
  [ "$(docker inspect -f '{{.State.Health.Status}}' "$APP" 2>/dev/null)" = healthy ] && break
  sleep 2
done
if [ "$(docker inspect -f '{{.State.Health.Status}}' "$APP" 2>/dev/null)" = healthy ]; then
  ok "healthy"
else
  fail "기동 실패"
  docker logs "$APP" 2>&1 | tail -30
  exit 1
fi

step "스키마·디스커버리·헬스"
MIGR=$(docker exec "$PG" psql -U taspa_app -d taspa -tAc "select count(*) from flyway_schema_history where success" | tr -d ' ')
BAD=$(docker exec "$PG" psql -U taspa_app -d taspa -tAc "select count(*) from flyway_schema_history where not success" | tr -d ' ')
ok "마이그레이션 $MIGR 건 적용"
expect "실패한 마이그레이션 수" "$BAD" "0"
ISS=$(curl -s "http://localhost:$APP_PORT/.well-known/openid-configuration" | python3 -c 'import sys,json;print(json.load(sys.stdin)["issuer"])')
expect "디스커버리 issuer" "$ISS" "https://auth.taspa.example"
expect "liveness" "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$APP_PORT/actuator/health/liveness")" "200"
expect "readiness" "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$APP_PORT/actuator/health/readiness")" "200"

step "보안 헤더 / 메트릭"
HDR=$(curl -s -D- -o /dev/null -H 'X-Forwarded-Proto: https' "http://localhost:$APP_PORT/login")
grep -qi 'content-security-policy' <<<"$HDR" && ok "CSP" || fail "CSP 없음"
# HSTS 는 요청이 https 로 **보일 때만** 나간다 — 신뢰 프록시 설정이 실제로 먹는지의 관측 가능한 증거다.
grep -qi 'strict-transport-security' <<<"$HDR" && ok "HSTS(신뢰 프록시 XFP 반영)" || fail "HSTS 없음"
expect "메트릭 무인증" "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$APP_PORT/actuator/prometheus")" "401"
expect "메트릭 인증" "$(curl -s -o /dev/null -w '%{http_code}' -u "metrics:$METRICS_PW" "http://localhost:$APP_PORT/actuator/prometheus")" "200"

step "사용자 흐름 — 서버 직접(관리자)"
python3 "$ROOT/deploy/rehearsal/checks.py" "http://localhost:$APP_PORT" "http://localhost:$MAIL_PORT" \
  rehearsal-admin@taspa.example || FAILED=1
# 역할은 로그인 시점에 세션에 굳으므로, 부트스트랩 승격을 반영하려면 재기동 후 다시 로그인해야 한다.
# ★로그 정적 검사의 기준선도 여기다 — 첫 기동의 "admin bootstrap: account not found" WARN 은
#   그 시점엔 계정이 실제로 없었으니 **옳은 경고**다. 재기동 이후 로그만 본다.
#   ★끝의 `Z` 를 빼지 말 것 — docker 는 타임존 없는 타임스탬프를 **로컬 시각**으로 읽는다. KST 에서
#   `date -u` 값을 그대로 넘기면 9시간 전으로 해석돼 --since 가 사실상 무효가 되고, 첫 기동의
#   "account not found" WARN 이 그대로 잡혀 **정상 리허설이 실패로 뒤집힌다**(실제로 그렇게 실패했다).
RESTART_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
docker restart "$APP" >/dev/null
for _ in $(seq 1 60); do
  [ "$(docker inspect -f '{{.State.Health.Status}}' "$APP" 2>/dev/null)" = healthy ] && break
  sleep 2
done
# ★로그를 변수에 받아서 검사한다. `docker logs | grep -q` 는 **`set -o pipefail` 과 함께 쓰면 안 된다** —
#   grep -q 는 첫 매치에서 즉시 끝나 파이프를 닫고, 그러면 docker logs 가 SIGPIPE(141)로 죽어서
#   **찾았는데도 파이프라인 종료코드가 실패**가 된다. 실제로 이 리허설이 두 번 연속 "graceful shutdown
#   흔적 없음"으로 거짓 실패했다(프로브로 재현: 같은 컨테이너에서 grep -c 로는 2줄이 나온다).
#   거짓 실패는 거짓 통과만큼 나쁘다 — 사람이 이 스크립트의 실패를 무시하게 만든다.
APP_LOGS=$(docker logs "$APP" 2>&1)
if grep -q "Graceful shutdown complete" <<<"$APP_LOGS"; then ok "graceful shutdown"; else fail "graceful shutdown 흔적 없음"; fi
python3 "$ROOT/deploy/rehearsal/checks.py" "http://localhost:$APP_PORT" "http://localhost:$MAIL_PORT" \
  rehearsal-admin@taspa.example --admin || FAILED=1

step "사용자 흐름 — web 이미지의 동일 오리진 프록시 경유"
docker run -d --name "$WEB" --network "$NET" -p "$WEB_PORT:3000" \
  -e "TASPA_ORIGIN=http://$APP:9100" "$WEB_IMAGE" >/dev/null
for _ in $(seq 1 45); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$WEB_PORT/")" = "200" ] && break
  sleep 2
done
python3 "$ROOT/deploy/rehearsal/checks.py" "http://localhost:$WEB_PORT" "http://localhost:$MAIL_PORT" \
  spa-user@taspa.example || FAILED=1

step "rate limit"
# ★반드시 사용자 흐름 **뒤**에 온다. 버킷은 (IP, 엔드포인트그룹)당이고 리허설은 전부 같은 IP 라,
#   먼저 소진하면 그 다음 로그인이 429 로 막혀 흐름 검증이 통째로 거짓 실패한다(순서를 바꾸지 말 것).
LAST=""
for _ in $(seq 1 25); do
  LAST=$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://localhost:$APP_PORT/login/identifier" -d 'email=nobody@taspa.example')
done
expect "로그인 rate limit" "$LAST" "429"

step "기동 로그: 구조화 + WARN·ERROR 0"
# 상시 WARN 은 그 자체로 비용이다 — 사람이 WARN 을 훑지 않는 습관이 들고 진짜 경고가 묻힌다.
#
# ★**몇 줄을 실제로 파싱했는지 함께 센다.** 예전엔 비-JSON 줄을 전부 버리고 WARN 개수만 봤는데,
#   그러면 로그가 ECS JSON 이 아니게 되는 순간(구조화 로깅 설정이 깨지거나 평문으로 되돌아가면)
#   이 게이트가 **무조건 초록불**이 된다 — 0줄을 검사하고 "이상 없음"이라고 말한다. 이 저장소의 전역
#   순회들이 `scanned`/`failed` 를 함께 내려보내는 것과 같은 규약이다.
LOG_STATS=$(docker logs --since "$RESTART_AT" "$APP" 2>&1 | python3 -c '
import sys, json
parsed = unparsed = 0
noisy = []
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    try:
        e = json.loads(line)
    except Exception:
        unparsed += 1
        continue
    parsed += 1
    if e.get("log.level") in ("WARN", "ERROR"):
        noisy.append(e.get("log.logger", "").split(".")[-1] + " | " + e.get("message", "")[:160])
print("%d %d %d" % (parsed, unparsed, len(noisy)))
for n in noisy:
    print("   " + n)
')
# ★첫 줄에 세 수를 **위치로** 싣는다. 이름표(`PARSED=`)를 붙여 sed 로 뽑으면 안 된다 —
#   `.*PARSED=\(...\)` 의 탐욕 매칭이 `UNPARSED=` 안의 "PARSED=" 를 집어 **다른 값을 읽는다**.
#   실제로 그렇게 써서 정상 실행이 "검사한 로그가 0줄"이라는 거짓 실패를 냈다(값은 UNPARSED 의 0 이었다).
read -r PARSED UNPARSED NOISY <<<"$(head -1 <<<"$LOG_STATS")"
if [ "${PARSED:-0}" -gt 0 ]; then ok "구조화 로그 $PARSED 줄 검사"; else fail "검사한 로그가 0줄이다(게이트가 아무것도 증명하지 못한다)"; fi
expect "평문 혼입" "${UNPARSED:-0}" "0"
if [ "${NOISY:-0}" = "0" ]; then ok "WARN/ERROR 없음"; else tail -n +2 <<<"$LOG_STATS"; fail "기동 로그에 WARN/ERROR $NOISY 건"; fi

step "결과"
if [ "$FAILED" = "0" ]; then echo "  전부 통과 — 배포 가능"; else echo "  실패 있음"; fi
exit "$FAILED"
