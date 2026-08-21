package com.taspa.server.session

import com.taspa.server.common.http.RequestClientInfo
import jakarta.servlet.http.HttpServletRequest

/**
 * 세션 목록 표시용 클라이언트 메타(IP·브라우저 라벨) 세션 속성 헬퍼.
 * StepUp/PendingAuth 와 같은 패턴 — SecurityContext 밖(세션 속성)에만 존재하며,
 * 로그인/재인증(완전 인증 수립) 시점에만 기록한다.
 */
object SessionMetadata {
    const val CLIENT_IP_KEY = "TASPA_CLIENT_IP"
    const val USER_AGENT_KEY = "TASPA_USER_AGENT"

    fun record(request: HttpServletRequest) {
        val session = request.getSession(true)
        session.setAttribute(CLIENT_IP_KEY, RequestClientInfo.ip(request))
        session.setAttribute(USER_AGENT_KEY, RequestClientInfo.uaLabel(request))
    }
}
