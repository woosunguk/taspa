package com.taspa.server.config

import com.taspa.server.support.IntegrationTestBase
import jakarta.servlet.http.Cookie
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 실제 DispatcherServlet(LocaleChangeInterceptor → SupportedLocaleCookieResolver → Thymeleaf) 을 통과하는
 * 로케일 렌더 통합 테스트 — 번들만 보던 I18nMessagesTest 와 달리 **런타임 en 렌더**와 **미지원 로케일**
 * 실제 렌더까지 검증한다.
 *
 * - TASPA_LOCALE=en 쿠키 → /login 이 영문으로 렌더된다(재방문자 경로).
 * - 쿠키 없음 → 한국어 기준선(기존 단언 무손상).
 * - TASPA_LOCALE=fr(미지원) → 500/??key?? 없이 ko 로 클램프되어 정상 렌더.
 * - ?lang=en / ?lang=fr → 쿠키에 각각 en / (클램프된)ko 로 저장.
 */
class I18nRenderIntegrationTest : IntegrationTestBase() {
    @Test
    fun `en cookie renders the login page in English`() {
        mockMvc
            .perform(get("/login").cookie(Cookie("TASPA_LOCALE", "en")))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Continue with your taspa account")))
            .andExpect(content().string(not(containsString("taspa 계정으로 계속하기"))))
    }

    @Test
    fun `no locale cookie renders the Korean baseline`() {
        mockMvc
            .perform(get("/login"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("taspa 계정으로 계속하기")))
    }

    @Test
    fun `unsupported locale cookie is clamped to Korean and still renders`() {
        mockMvc
            .perform(get("/login").cookie(Cookie("TASPA_LOCALE", "fr")))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("taspa 계정으로 계속하기")))
            .andExpect(content().string(not(containsString("??login"))))
    }

    @Test
    fun `lang=en persists en to the locale cookie`() {
        mockMvc
            .perform(get("/login").param("lang", "en"))
            .andExpect(status().isOk)
            .andExpect(cookie().value("TASPA_LOCALE", "en"))
    }

    @Test
    fun `lang=fr is clamped and persists ko to the locale cookie`() {
        mockMvc
            .perform(get("/login").param("lang", "fr"))
            .andExpect(status().isOk)
            .andExpect(cookie().value("TASPA_LOCALE", "ko"))
    }
}
