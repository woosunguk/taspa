package com.taspa.server.common.exception

import com.taspa.server.config.i18n.MessageResolver

/**
 * `AuthException` → 사용자에게 보일 문구.
 *
 * ★규칙은 하나다: **서비스가 구체적인 사유를 붙였으면 그것이 답이고, 기본 상수 그대로면 로케일 문구로
 * 해석한다.** 이 판정이 JSON 경로([GlobalExceptionHandler])에만 있고 **HTML 화면 컨트롤러에는 없어서**,
 * 같은 예외가 API 에서는 한국어로, 화면에서는 영문("Password reset token has expired")으로 보였다 —
 * 비밀번호 재설정·가입 화면이 그 상태였다. 판정을 한 곳에 두면 다음 화면이 같은 실수를 반복하지 않는다.
 *
 * 해석 실패(키 누락·로케일 확인 불가)는 ErrorCode 의 기본 문구로 낙하한다 — 문구를 못 고르는 것이
 * 화면 전체를 500 으로 만들어서는 안 된다.
 */
fun MessageResolver.userMessageFor(ex: AuthException): String =
    if (ex.message != ex.errorCode.message) {
        ex.message ?: ex.errorCode.message
    } else {
        runCatching { get("error.${ex.errorCode.name}") }.getOrDefault(ex.errorCode.message)
    }
