package com.taspa.server.forecast

/**
 * 예측이 쓸 **신호 선택**.
 *
 * ## 저장과 실험의 이중 구조
 *
 * 가맹 그레인은 매장별로 **저장**된다(V41 `merchant_forecast_settings` — 매장이 찾아낸 최적 조합이
 * 새로고침마다 사라지면 실험의 결론을 쓸 수 없다). "저장하면 누가 언제 켰는지 모른다"는 원래 우려는
 * 감사 로그(`MERCHANT_FORECAST_SETTINGS_UPDATED`)가 답한다. 쿼리 파라미터는 여전히 저장값을 **요청
 * 한 번만** 덮는다([SignalOverrides]) — 백테스트로 조합을 비교할 때 쓰는 실험 경로다.
 *
 * ## 기본값은 "지금 운영 중인 것"
 *
 * 설정 행이 없고 파라미터도 없으면 이 클래스의 기본값 = 도입 전과 같은 동작이다.
 */
data class ForecastSignals(
    /** 재실 인원 비율 보정(전주 대비 인원 변화). 끄면 전주 실적을 그대로 쓴다. */
    val headcountAdjust: Boolean = true,
    /**
     * 하루 단위 부재(연차·반차·출장)를 재실 모수에서 뺀다. `headcountAdjust` 가 꺼져 있으면 무의미하다
     * (모수 자체를 안 쓰므로) — 화면이 그 사실을 알려야 한다.
     */
    val absenceAware: Boolean = true,
    /** 휴일 인지: 휴일 여부가 타깃과 같은 과거만 basis 로 쓴다. 끄면 모든 과거를 같은 날로 취급한다. */
    val holidayAware: Boolean = true,
    /**
     * 사내 행사 인지. 휴일과 **같은 메커니즘**이되 별개의 날 종류다 — 행사일은 식수가 **늘 수도 줄 수도**
     * 있어 평일과 섞으면 양쪽이 오염된다. 기본 꺼짐: 대부분의 조직은 행사 캘린더를 아직 넣지 않았고,
     * 빈 캘린더로 켜면 아무 효과 없이 basis 후보만 줄어들 수 있다.
     */
    val eventAware: Boolean = false,
    /**
     * 메뉴 신호(가맹 그레인 전용): 타깃일 식단의 **카테고리**(특식·면류 등)와 같은 카테고리였던 과거만
     * basis 로 **우선**한다. 휴일과 달리 배제가 아니라 선호다 — 같은 카테고리 표본이 없으면 전체로
     * 되돌아간다(메뉴 효과는 휴일만큼 강하지 않아 표본을 다 버리면 잃는 것이 더 크다).
     * 배율(특식 = +20% 같은 값)을 **지어내지 않는다** — 같은 카테고리의 실측을 그대로 쓰는 것이 전부다.
     */
    val menuAware: Boolean = false,
    /**
     * 당일 보정(nowcast): 오늘 셀의 예측을 **이미 나간 인분**으로 하한한다(점심에 40인분이 나갔는데
     * 예측이 35 면 그 숫자는 이미 틀린 것이 확정이다). 예측이 없는 셀(NO_DATA)은 숫자를 만들지 않고
     * `soFar` 로만 노출한다 — 부분값을 예측으로 위장하지 않는다.
     */
    val nowcast: Boolean = true,
    /** 홀드아웃 채점으로 방법을 자동 선택. null 이면 배포 설정을 따른다. */
    val methodSelection: Boolean? = null,
) {
    companion object {
        /** 쿼리 파라미터 파싱 — 미전송(null)은 기본값 유지다(전송한 것만 바꾼다). */
        fun of(
            headcountAdjust: Boolean?,
            absenceAware: Boolean?,
            holidayAware: Boolean?,
            eventAware: Boolean?,
            methodSelection: Boolean?,
            menuAware: Boolean? = null,
            nowcast: Boolean? = null,
        ): ForecastSignals =
            SignalOverrides(headcountAdjust, absenceAware, holidayAware, eventAware, menuAware, nowcast, methodSelection)
                .applyTo(ForecastSignals())
    }
}

/**
 * 쿼리 파라미터로 온 **부분 덮어쓰기**. 미전송(null)은 base(저장 설정 또는 기본값)를 유지한다 —
 * `eventAware=true` 하나만 실험하는 요청이 저장해 둔 다른 스위치를 기본값으로 되돌리면 안 된다.
 */
data class SignalOverrides(
    val headcountAdjust: Boolean? = null,
    val absenceAware: Boolean? = null,
    val holidayAware: Boolean? = null,
    val eventAware: Boolean? = null,
    val menuAware: Boolean? = null,
    val nowcast: Boolean? = null,
    val methodSelection: Boolean? = null,
) {
    fun isEmpty(): Boolean =
        headcountAdjust == null &&
            absenceAware == null &&
            holidayAware == null &&
            eventAware == null &&
            menuAware == null &&
            nowcast == null &&
            methodSelection == null

    fun applyTo(base: ForecastSignals): ForecastSignals =
        ForecastSignals(
            headcountAdjust = headcountAdjust ?: base.headcountAdjust,
            absenceAware = absenceAware ?: base.absenceAware,
            holidayAware = holidayAware ?: base.holidayAware,
            eventAware = eventAware ?: base.eventAware,
            menuAware = menuAware ?: base.menuAware,
            nowcast = nowcast ?: base.nowcast,
            methodSelection = methodSelection ?: base.methodSelection,
        )
}
