"""배포 리허설의 사용자 흐름 검증 — prod 이미지에 대고 실제로 가입·로그인·관리까지 밟는다.

사용: python3 checks.py <base-url> <mailpit-url> <email> [--admin]

왜 e2e 로 안 되는가: Playwright e2e 는 dev 프로파일(데모 클라이언트 시딩·localhost issuer·약한 시크릿)을
전제한다. prod 이미지에서만 드러나는 문제 — 필수 환경변수 강제, 리버스 프록시 뒤 리다이렉트, 구조화
로깅, 시크릿 강도 — 는 그 스위트가 구조적으로 밟지 않는다. 이 스크립트가 그 구간을 맡는다.
"""

import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import http.cookiejar

BASE = sys.argv[1].rstrip("/")
MAIL = sys.argv[2].rstrip("/")
EMAIL = sys.argv[3]
ADMIN = "--admin" in sys.argv
PW = "Rehearsal-Flow-Pw-2026!"

jar = http.cookiejar.CookieJar()
op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
failures = []


def req(path, data=None, json_body=None, headers=None):
    url = path if path.startswith("http") else BASE + path
    h = dict(headers or {})
    body = None
    if json_body is not None:
        body = json.dumps(json_body).encode()
        h["Content-Type"] = "application/json"
    elif data is not None:
        body = urllib.parse.urlencode(data).encode()
    try:
        with op.open(urllib.request.Request(url, data=body, headers=h)) as r:
            return r.status, r.geturl(), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.url, e.read().decode("utf-8", "replace")


def csrf_field(html):
    m = re.search(r'name="_csrf"\s+value="([^"]+)"', html)
    return m.group(1) if m else None


def check(label, cond, detail=""):
    print(("  OK   " if cond else "  FAIL ") + label + ((" — " + detail) if detail else ""))
    if not cond:
        failures.append(label)


def latest_code():
    """이 실행의 수신자에게 온 메일에서 6자리 코드를 꺼낸다.

    ★**수신자를 고정하고, 코드를 찾을 때까지 재시도한다.** 예전에는 메일함이 비었을 때만 폴링을
    반복하고 메시지가 하나라도 있으면 `messages[0]` 을 그대로 읽었다 — 같은 mailpit 을 공유하는
    2·3번째 실행에서는 **직전 실행의 메일**을 즉시 집어 들고, 방금 온 메일을 기다리지 않는다.
    그러면 코드가 안 맞아 흐름이 깨지거나(운이 나쁘면) 엉뚱한 코드로 통과한 것처럼 보인다.
    """
    query = urllib.parse.quote(f"to:{EMAIL}")
    for _ in range(15):
        msgs = json.load(urllib.request.urlopen(f"{MAIL}/api/v1/search?query={query}"))
        for m in msgs.get("messages", []):
            d = json.load(urllib.request.urlopen(MAIL + f"/api/v1/message/{m['ID']}"))
            hit = re.search(r"\b(\d{6})\b", d.get("Text") or d.get("HTML") or "")
            if hit:
                return hit.group(1)
        time.sleep(1)
    return None


"""
--admin 은 **이미 가입·인증을 마친 계정으로 다시 로그인**하는 모드다(플랫폼 관리자 승격은 기동 시점에
일어나고 역할은 로그인 시점에 세션에 굳으므로, 재기동 후 한 번 더 로그인해야 반영된다).
그래서 가입·게이트 단계를 건너뛴다 — 같은 이메일로 다시 가입하면 409 다.
"""
if not ADMIN:
    print(f"[{BASE}] 1) 가입 — 공개 JSON API")
    s, _, b = req("/api/accounts/signup", json_body={"email": EMAIL, "password": PW, "displayName": "리허설"})
    check("201 Created", s == 201, f"status={s} {b[:120]}")

    # ★가입 경로마다 코드 발송이 갈리면 사용자는 "보냈다"는 화면 앞에서 오지 않는 메일을 기다린다.
    #   한동안 서버 렌더링 /signup 만 발송하고 이 API 는 하지 않았다(AccountService KDoc).
    print("2) 가입 즉시 인증 코드 메일")
    code = latest_code()
    check("메일 1통 + 6자리 코드", code is not None, f"code={code}")

print(f"[{BASE}] 3) 로그인")
s, _, html = req("/login")
tok = csrf_field(html)
s, url, html = req("/login/identifier", {"email": EMAIL, "_csrf": tok})
check("비밀번호 화면", url.endswith("/login/password"), url)
tok = csrf_field(html) or tok
s, url, html = req("/login/password", {"username": EMAIL, "password": PW, "_csrf": tok})

if ADMIN:
    # 이미 인증된 계정 — 게이트 없이 바로 통과해야 한다.
    check("게이트 없이 계정 화면", url.startswith(BASE) and "/account" in url, url)
else:
    check("이메일 인증 게이트", url.endswith("/login/verify-email"), url)
    tok = csrf_field(html) or tok

    # 부분 인증을 SecurityContext 에 넣지 않는다는 불변식의 관측 가능한 형태.
    print("4) 부분 인증 상태에서는 세션 API 가 닫혀 있다")
    s, _, b = req("/api/account/me", headers={"Accept": "application/json"})
    check("401 UNAUTHENTICATED", s == 401 and "UNAUTHENTICATED" in b, f"status={s}")

    print("5) 코드 제출 → 완전 인증")
    s, url, html = req("/login/verify-email", {"code": code, "_csrf": tok})
    # 프록시 뒤에서는 이 착지점이 프런트 오리진이어야 한다(server.tomcat.use-relative-redirects).
    check("계정 화면 착지(같은 오리진)", url.startswith(BASE) and "/account" in url, url)

print("6) 세션 API")
s, _, b = req("/api/account/me", headers={"Accept": "application/json"})
me = json.loads(b) if s == 200 else {}
check("200 + 내 이메일", me.get("email") == EMAIL, f"status={s} {b[:160]}")

print("7) CSRF 토큰 발급(SPA 계약)")
s, _, b = req("/api/csrf", headers={"Accept": "application/json"})
csrf = json.loads(b) if s == 200 else {}
check("200 + token", "token" in csrf, f"status={s} {b[:120]}")

if not ADMIN:
    print("8) 일반 사용자는 관리 콘솔에 닿지 않는다")
    s, _, b = req("/api/admin/orgs", headers={"Accept": "application/json"})
    check("403", s == 403, f"status={s}")
else:
    print("8) 플랫폼 관리자 — 조회·쓰기·CSRF 거절·감사")
    check("platformAdmin=true", me.get("platformAdmin") is True, str(me.get("platformAdmin")))
    s, _, b = req("/api/admin/orgs", headers={"Accept": "application/json"})
    check("GET 200", s == 200, f"status={s} {b[:120]}")
    s, _, b = req(
        "/api/admin/orgs",
        json_body={"slug": "rehearsal-co", "name": "리허설 주식회사", "timezone": "Asia/Seoul"},
        # 앞 단계가 실패했으면 여기서 죽지 않고 그 실패만 보고되게 한다(스택트레이스가 결과를 가린다).
        headers={csrf.get("headerName", "X-CSRF-TOKEN"): csrf.get("token", ""), "Accept": "application/json"},
    )
    check("POST 201", s == 201, f"status={s} {b[:160]}")
    s, _, b = req(
        "/api/admin/orgs",
        json_body={"slug": "no-csrf", "name": "거절되어야 함"},
        headers={"Accept": "application/json"},
    )
    check("CSRF 없는 쓰기 403", s == 403, f"status={s}")
    s, _, b = req("/api/admin/audit?limit=10", headers={"Accept": "application/json"})
    check("감사 기록 ADMIN_ORG_CREATED", "ADMIN_ORG_CREATED" in b, b[:160])

print()
print("실패:", failures if failures else "없음")
sys.exit(1 if failures else 0)
