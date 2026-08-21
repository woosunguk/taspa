package com.taspa.server.stepup

// 민감 작업(step-up) 표시: 최근 재인증(auth_time 이 max-age 이내)이 없으면
// HTML 요청은 /reauth 로 리다이렉트, API 요청(/api/ 이하)은 401 REAUTH_REQUIRED 로 거절된다.
// 신뢰 기기 쿠키는 step-up 을 면제하지 않는다.
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequireRecentAuth
