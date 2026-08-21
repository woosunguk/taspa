package com.taspa.server.config

import com.taspa.server.iam.AdminSurface
import com.taspa.server.iam.PlatformAuthorizationInterceptor
import com.taspa.server.stepup.RecentAuthInterceptor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor

@Configuration
class WebMvcConfig(
    private val recentAuthInterceptor: RecentAuthInterceptor,
    private val localeChangeInterceptor: LocaleChangeInterceptor,
    // WebMvcConfigurer 는 아주 이른 시점에 인스턴스화된다. 인가 인터셉터는 저장소·서비스를 물고 있어
    // 직접 주입하면 그 빈 그래프가 조기 초기화되고 자동프록시 대상에서 빠질 수 있다 — 지연 조회한다.
    private val platformAuthorizationInterceptor: ObjectProvider<PlatformAuthorizationInterceptor>,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        // ?lang= 파라미터로 로케일을 전환한다(쿠키에 영속). 다른 인터셉터보다 먼저 로케일을 확정한다.
        registry.addInterceptor(localeChangeInterceptor)
        registry.addInterceptor(recentAuthInterceptor)
        // ★반드시 recentAuthInterceptor **뒤**. 이 순서가 CSRF → step-up → 인가 라는 계층 순서를 만든다.
        //   앞에 두면 재인증이 만료된 관리자 요청이 401 REAUTH_REQUIRED 대신 403 으로 나가고, 화면은
        //   "재인증하면 됩니다" 대신 "권한 없음"을 보여 준다. 오늘은 관리자가 엔진에서 항상 통과하므로
        //   순서를 바꿔도 **어떤 기존 테스트도 실패하지 않는다** — 잠복 결함이라 순서 자체를 고정한다.
        registry
            .addInterceptor(LazyInterceptor(platformAuthorizationInterceptor))
            .addPathPatterns(*AdminSurface.PATH_PATTERNS)
    }
}
