#!/usr/bin/env python3
"""데모용 과거 실적 생성기 — 식수 예측을 **측정 가능하게** 만드는 것이 목적이다.

예측 알고리즘은 D-7~D-28 실적을 본다. 실적이 없으면 모든 셀이 NO_DATA 라 어떤 규칙을
넣어도 개선을 측정할 수 없다(설계 문서 §9: "각 단계는 이전 베이스라인을 유의하게 이겨야").

★소비 이벤트만 넣으면 그 달 정합성 대사에 countDrift 가 생긴다(대사는 소비 이벤트를
  source 구분 없이 센다). 그래서 **거래 + 소비이벤트 + 원장 3종을 함께** 만든다:
    meal_transactions 1행  ↔  consumption_events 1행(source=payment, external_id=auth_id)
    ↔ ledger_entries 1행 + ledger_postings 2행(ORG_RECEIVABLE +, MERCHANT_PAYABLE −, 합 0)
  적용 후 /api/orgs/{org}/reconciliation 로 월별 drift 0 을 확인할 것.

★모든 식별자에 'sim-' 접두를 붙인다 — 롤백이 한 줄로 끝나야 한다(파일 하단 주석 참조).
★난수 시드 고정 + UUID5 결정론 → 재실행하면 정확히 같은 데이터(ON CONFLICT DO NOTHING 로 멱등).

사용: python3 deploy/demo-history.py | docker compose exec -T postgres psql -U taspa -d taspa -v ON_ERROR_STOP=1 -f -
"""
import random
import uuid
from datetime import date, datetime, timedelta

ORG = "d0000000-0000-4000-8000-000000000001"
SITE = "d0000000-0000-4000-8000-000000000002"
MERCHANT = "6dc75701-36b5-4a3f-8ee5-5b6d2484685f"
DEPTS = {  # 부서 → (uuid, 인원)
    "개발팀": ("d0000000-0000-4000-8000-000000000010", 12),
    "플랫폼파트": ("d0000000-0000-4000-8000-000000000012", 10),
    "경영지원팀": ("d0000000-0000-4000-8000-000000000011", 18),
}
NS = uuid.UUID("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

WINDOW_START = date(2026, 5, 25)   # 월요일
WINDOW_END = date(2026, 8, 24)     # 데모 기준 어제(완결일). 여기서 끊기면 예측 화면에 "데이터 절벽"이
                                   # 생겨 정확도 지표가 실제보다 나쁘게 보인다(실측: 매장 WAPE 30%).
AMOUNT = 12_000                    # 조직 부담(개인부담 0) — 1식 한도 안이라 분담이 단순하다
KST_OFFSET = timedelta(hours=9)

# 회사 휴무일(전사). 국가 공휴일을 임의로 지어내지 않는다 — 조직이 선언하는 휴무가
# 이 기능(HolidayCalendar)의 실제 사용처다. 목요일·금요일로 흩어 요일 효과와 겹치지 않게 한다.
HOLIDAYS = {
    date(2026, 6, 11): "창립기념일",
    date(2026, 7, 17): "전사 워크숍",
    date(2026, 8, 13): "하계 휴무",
}

# 요일 계수 — 금요일 급감은 구내식당의 보편적 패턴이다(외식·재택).
DOW_LUNCH = {0: 1.06, 1: 1.02, 2: 0.99, 3: 1.00, 4: 0.80}
DOW_DINNER = {0: 1.10, 1: 1.05, 2: 1.00, 3: 0.95, 4: 0.45}
P_LUNCH, P_DINNER = 0.55, 0.11
HOLIDAY_FACTOR = 0.08              # 당직 식사 — 휴일에도 0 이 아니다

rnd = random.Random(20260824)


def u5(key: str) -> str:
    return str(uuid.uuid5(NS, key))


def names():
    fam = ["김", "이", "박", "최", "정", "강", "조", "윤", "장", "임"]
    giv = ["민준", "서연", "도윤", "지우", "예준", "하윤", "주원", "지민", "건우", "수아",
           "우진", "다은", "현우", "채원", "지호", "가은", "준서", "유나", "성민", "소율"]
    out, i = [], 0
    for f in fam:
        for g in giv:
            out.append(f + g)
            i += 1
    rnd.shuffle(out)
    return out


NAMES = names()

# ── 직원 명단 ────────────────────────────────────────────────────────────────
# 30명은 창 시작 전 입사(안정 모수), 10명은 창 안에서 입사 → 재직인원이 증가한다.
# 그래야 예측의 재실보정(SEASONAL_NAIVE_ADJUSTED)이 실제로 발동한다(비율 1.0 이면 무의미).
employees = []
seq = 0
for dept, (dept_id, count) in DEPTS.items():
    for _ in range(count):
        seq += 1
        if seq <= 30:
            hire = WINDOW_START - timedelta(days=rnd.randint(120, 900))
        else:
            hire = WINDOW_START + timedelta(days=rnd.randint(3, 80))
        employees.append({
            "n": seq,
            "id": u5(f"taspa-demo:emp:{seq}"),
            "email": f"emp{seq:02d}@taspa.example",
            "name": NAMES[seq - 1],
            "dept": dept,
            "dept_id": dept_id,
            "hire": hire,
            # 2명은 창 중간에 퇴사 → 이력 복원(SCD)과 재직 모수 감소를 실제로 겪게 한다.
            "left": WINDOW_START + timedelta(days=rnd.randint(30, 70)) if seq in (7, 23) else None,
        })


def active_on(d: date):
    return [e for e in employees if e["hire"] <= d and (e["left"] is None or e["left"] > d)]


def q(s: str) -> str:
    return "'" + s.replace("'", "''") + "'"


def ts(d: date, hour_kst: int, minute: int = 0) -> str:
    """org-로컬 벽시계 → UTC timestamp 리터럴. 컬럼이 timestamp without time zone(=UTC 인스턴트)이다."""
    return q((datetime(d.year, d.month, d.day, hour_kst, minute) - KST_OFFSET).isoformat(sep=" "))


out = ["BEGIN;", ""]

# ── 직원 계정·멤버십·이력 ────────────────────────────────────────────────────
out.append("-- 직원 40명. 비밀번호 해시는 기존 데모 계정에서 복사한다(문서화된 TaspaDemo!2026 과 항상 일치).")
rows = []
for e in employees:
    rows.append(
        f"({q(e['id'])}, {q(e['email'])}, (select password_hash from users where email='staff@taspa.example'),"
        f" true, 'ACTIVE', 0, {ts(e['hire'], 9)}, now(), {q(e['name'])}, false, 'USER')"
    )
out.append("INSERT INTO users (id, email, password_hash, email_verified, status, failed_login_attempts,"
           " created_at, updated_at, display_name, mfa_enabled, role) VALUES")
out.append(",\n".join(rows) + "\nON CONFLICT DO NOTHING;\n")

rows = []
for e in employees:
    status = "TERMINATED" if e["left"] else "EMPLOYED"
    mstatus = "SUSPENDED" if e["left"] else "ACTIVE"
    rows.append(
        f"({q(u5('m:' + e['id']))}, {q(ORG)}, {q(e['id'])}, 'MEMBER', {q(e['dept'])}, {q(mstatus)},"
        f" {ts(e['hire'], 9)}, {q(e['dept_id'])}, {q(SITE)}, {q('EMP-%04d' % (1000 + e['n']))},"
        f" '사원', 'FULL_TIME', {q(e['hire'].isoformat())}, {q(status)})"
    )
out.append("INSERT INTO org_memberships (id, org_id, user_id, role, department, status, joined_at,"
           " department_id, site_id, employee_id, job_title, employment_type, hire_date, employment_status) VALUES")
out.append(",\n".join(rows) + "\nON CONFLICT DO NOTHING;\n")

out.append("-- 멤버십 SCD. recorded_at 이 과거여야 예측의 재실보정이 basis 주차 인원을 복원한다.")
rows = []
for e in employees:
    rows.append(
        f"({q(u5('h:join:' + e['id']))}, {q(ORG)}, {q(e['id'])}, 'MEMBER', {q(e['dept_id'])}, {q(SITE)},"
        f" 'FULL_TIME', 'EMPLOYED', '사원', 'JOINED', {ts(e['hire'], 9)}, NULL)"
    )
    if e["left"]:
        rows.append(
            f"({q(u5('h:left:' + e['id']))}, {q(ORG)}, {q(e['id'])}, 'MEMBER', {q(e['dept_id'])}, {q(SITE)},"
            f" 'FULL_TIME', 'TERMINATED', '사원', 'ATTRIBUTES_UPDATED', {ts(e['left'], 9)}, NULL)"
        )
out.append("INSERT INTO org_membership_history (id, org_id, user_id, role, department_id, site_id,"
           " employment_type, employment_status, job_title, change_type, recorded_at, recorded_by) VALUES")
out.append(",\n".join(rows) + "\nON CONFLICT DO NOTHING;\n")

# ── 휴무일 캘린더 ────────────────────────────────────────────────────────────
out.append("-- 휴일 인지의 입력. all_day=true AND (feed.type='HOLIDAY' OR category='HOLIDAY') 만 휴일로 본다")
out.append("-- (요약 텍스트로 추측하지 않는다 — 조직관리자의 명시 선언만 믿는다).")
feed = u5("feed:holiday")
out.append(f"INSERT INTO calendar_feeds (id, org_id, name, type, source_url, enabled, last_synced_at,"
           f" last_sync_status, created_at) VALUES ({q(feed)}, {q(ORG)}, '전사 휴무일', 'HOLIDAY', NULL,"
           f" true, now(), 'OK', now()) ON CONFLICT DO NOTHING;")
rows = []
for d, label in HOLIDAYS.items():
    # all-day 는 instant 가 아니라 달력 날짜다 — 파서가 UTC 벽시계로 고정하므로 UTC 자정으로 쓴다.
    # DTEND 는 배타(하루 휴일 = 다음날 자정).
    rows.append(
        f"({q(u5('cal:' + d.isoformat()))}, {q(ORG)}, {q(feed)}, {q('sim-holiday-' + d.isoformat())},"
        f" {q(label)}, 'HOLIDAY', {q(d.isoformat() + ' 00:00:00')},"
        f" {q((d + timedelta(days=1)).isoformat() + ' 00:00:00')}, true, 'FEED', now())"
    )
out.append("INSERT INTO calendar_events (id, org_id, feed_id, uid, summary, category, starts_at, ends_at,"
           " all_day, source, created_at) VALUES")
out.append(",\n".join(rows) + "\nON CONFLICT DO NOTHING;\n")

# ── 식사 실적 ────────────────────────────────────────────────────────────────
tx, ev, le, lp = [], [], [], []
d = WINDOW_START
total = 0
while d <= WINDOW_END:
    if d.weekday() < 5:  # 주말은 구내식당 미운영
        hc = len(active_on(d))
        holiday = d in HOLIDAYS
        for window, hour, base, dow in (("LUNCH", 12, P_LUNCH, DOW_LUNCH), ("DINNER", 18, P_DINNER, DOW_DINNER)):
            factor = dow[d.weekday()] * (HOLIDAY_FACTOR if holiday else 1.0)
            n = max(0, round(hc * base * factor * rnd.gauss(1.0, 0.06)))
            if n == 0:
                continue
            for e in rnd.sample(active_on(d), min(n, hc)):
                total += 1
                key = f"{d.isoformat()}:{window}:{e['n']}"
                auth = "sim-" + u5("auth:" + key)
                txid, evid, leid = u5("tx:" + key), u5("ev:" + key), u5("le:" + key)
                at = ts(d, hour, rnd.randint(0, 45))
                tx.append(f"({q(txid)}, {q(auth)}, {q(ORG)}, {q(e['id'])}, {q(MERCHANT)}, {AMOUNT}, 0,"
                          f" {q(window)}, 'APPROVED', {q('sim-pos-' + str(total))}, {at}, NULL, 0)")
                ev.append(f"({q(evid)}, 'payment', {q(auth)}, {q(ORG)}, {q(e['id'])}, {q(MERCHANT)},"
                          f" {q(window)}, NULL, 1, 'CONFIRMED', {at}, now(), {q(SITE)})")
                le.append(f"({q(leid)}, {q(ORG)}, 'REDEEM', {q(txid)}, NULL, {q(MERCHANT)}, {q(e['id'])},"
                          f" 0, {at}, now())")
                lp.append(f"({q(u5('lpr:' + key))}, {q(leid)}, {q(ORG)}, 'ORG_RECEIVABLE', {AMOUNT},"
                          f" {q(MERCHANT)}, {q(e['id'])}, {at})")
                lp.append(f"({q(u5('lpm:' + key))}, {q(leid)}, {q(ORG)}, 'MERCHANT_PAYABLE', {-AMOUNT},"
                          f" {q(MERCHANT)}, {q(e['id'])}, {at})")
    d += timedelta(days=1)


def batched(header: str, rows: list, tail: str = "ON CONFLICT DO NOTHING;", size: int = 400):
    for i in range(0, len(rows), size):
        out.append(header + " VALUES")
        out.append(",\n".join(rows[i:i + size]) + "\n" + tail + "\n")


out.append(f"-- 식사 실적 {total} 건 ({WINDOW_START} ~ {WINDOW_END}, 평일만).")
batched("INSERT INTO meal_transactions (id, auth_id, org_id, user_id, merchant_id, amount_minor,"
        " self_paid_minor, meal_window, status, pos_txn_id, approved_at, voided_at, refunded_minor)", tx)
batched("INSERT INTO consumption_events (id, source, external_id, org_id, user_sub, merchant_id,"
        " meal_window, menu_ref, quantity, status, occurred_at, created_at, site_id)", ev)
batched("INSERT INTO ledger_entries (id, org_id, entry_type, transaction_id, refund_id, merchant_id,"
        " user_id, self_paid_minor, occurred_at, created_at)", le)
batched("INSERT INTO ledger_postings (id, entry_id, org_id, account, amount_minor, merchant_id,"
        " user_id, occurred_at)", lp)

out.append("COMMIT;")
out.append(f"-- 생성: 직원 {len(employees)}명, 식사 {total}건, 휴무일 {len(HOLIDAYS)}일")
out.append("-- 롤백: DELETE FROM meal_transactions WHERE auth_id LIKE 'sim-%';")
out.append("--       DELETE FROM consumption_events WHERE external_id LIKE 'sim-%';")
out.append("--       (원장은 transaction_id FK CASCADE 가 아니므로 함께 지울 것 — 파일 하단 참고)")
print("\n".join(out))
