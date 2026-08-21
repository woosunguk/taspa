package com.taspa.server.support

import jakarta.servlet.http.Cookie
import org.springframework.session.Session
import org.springframework.session.SessionRepository
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import java.util.Base64

/**
 * Spring Session(JDBC) 하의 브라우저 세션 시뮬레이터 — MockHttpSession 공유 패턴의 대체(B-3).
 *
 * springSessionRepositoryFilter 가 적용되면 MockMvc 에 전달한 MockHttpSession 은 무시되고
 * 요청마다 새 세션이 만들어진다. 실제 브라우저처럼 응답의 SESSION 쿠키를 캡처해 후속 요청에
 * 재전송하는 방식으로 세션을 이어간다(쿠키 값 = 세션 ID 의 표준 Base64 — DefaultCookieSerializer).
 */
class WebSession(
    private val mockMvc: MockMvc,
    private val sessionRepository: SessionRepository<out Session>,
) {
    private var cookie: Cookie? = null

    /** 세션 쿠키를 실어 요청을 수행하고, 응답이 세션 쿠키를 갱신하면(생성/교체/만료) 반영한다. */
    fun perform(builder: MockHttpServletRequestBuilder): ResultActions {
        cookie?.let { builder.cookie(it) }
        val actions = mockMvc.perform(builder)
        actions.andReturn().response.getCookie(COOKIE_NAME)?.let { updated ->
            cookie = if (updated.maxAge == 0) null else updated
        }
        return actions
    }

    /** 현재 세션 ID(쿠키 Base64 디코딩). 세션이 없으면 null. */
    fun sessionId(): String? = cookie?.let { String(Base64.getDecoder().decode(it.value)) }

    /**
     * 세션 속성 직접 조작(시간 경과 시뮬 등) — 저장소에 바로 쓴다.
     * MockHttpSession.setAttribute 직접 조작 패턴의 대체.
     */
    fun setAttribute(
        name: String,
        value: Any,
    ) {
        val id = requireNotNull(sessionId()) { "세션 쿠키가 없습니다 — 먼저 세션을 만드는 요청을 수행하세요" }

        @Suppress("UNCHECKED_CAST")
        val repository = sessionRepository as SessionRepository<Session>
        val session = requireNotNull(repository.findById(id)) { "저장소에 세션이 없습니다: $id" }
        session.setAttribute(name, value)
        repository.save(session)
    }

    /**
     * 요청 없이 저장소에 세션을 만들어 쿠키를 확보한다 —
     * `MockHttpSession().apply { setAttribute(...) }` 패턴의 대체.
     */
    fun prime(vararg attributes: Pair<String, Any>): WebSession {
        @Suppress("UNCHECKED_CAST")
        val repository = sessionRepository as SessionRepository<Session>
        val session = repository.createSession()
        attributes.forEach { (name, value) -> session.setAttribute(name, value) }
        repository.save(session)
        cookie = Cookie(COOKIE_NAME, Base64.getEncoder().encodeToString(session.id.toByteArray()))
        return this
    }

    companion object {
        const val COOKIE_NAME = "SESSION"
    }
}
