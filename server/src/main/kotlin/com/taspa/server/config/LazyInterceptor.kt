package com.taspa.server.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.servlet.HandlerInterceptor

/**
 * 실제 인터셉터를 **첫 요청 시점에** 꺼내 쓰는 얇은 위임자.
 *
 * `WebMvcConfigurer` 는 컨텍스트 초기화의 매우 이른 단계에 만들어진다. 여기서 저장소·서비스를 물고 있는
 * 인터셉터를 직접 주입하면 그 빈 그래프가 통째로 조기 초기화되어, `@Transactional`·보안 어드바이스의
 * 자동프록시 대상에서 빠질 수 있다(프록시 생성기가 아직 등록되기 전이라 원본 빈이 그대로 배선된다).
 * 그 결과는 조용하다 — 기동은 성공하고 트랜잭션만 안 걸린다.
 *
 * 등록 시점에는 이 껍데기만 있으면 되고, 실제 조회는 요청 처리 때 일어나므로 그 함정을 피한다.
 */
class LazyInterceptor(
    private val provider: ObjectProvider<out HandlerInterceptor>,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean = provider.getObject().preHandle(request, response, handler)
}
