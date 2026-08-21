package com.taspa.server.config.i18n

import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.servlet.LocaleResolver
import java.util.Locale

/**
 * 컨트롤러·서비스가 모델(`error`/`info` 등)에 주입하거나 예외 메시지로 던지는 **사용자 노출 문자열**을
 * 요청 로케일로 해석하는 헬퍼. 템플릿의 `#{}` 와 달리, 서버가 값을 먼저 문자열로 확정해 모델에 담는
 * 경로(예: `th:text="${error}"`)를 i18n 하기 위한 것이다.
 *
 * 로케일 결정은 **우리 [LocaleResolver](CookieLocaleResolver) 를 직접 물어** ?lang= 쿠키 선택을 반영한다.
 * DispatcherServlet 은 `LocaleContextHolder` 에 리졸버 로케일이 아니라 `request.getLocale()`
 * (Accept-Language) 를 바인딩하므로, 그 홀더가 아니라 리졸버를 신뢰한다.
 * 요청 스레드 밖(테스트 등)에서는 홀더로 폴백한다.
 */
@Component
class MessageResolver(
    private val messageSource: MessageSource,
    private val localeResolver: LocaleResolver,
) {
    fun get(
        code: String,
        vararg args: Any?,
    ): String = messageSource.getMessage(code, if (args.isEmpty()) null else args, currentLocale())

    private fun currentLocale(): Locale {
        val request =
            (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
                ?: return LocaleContextHolder.getLocale()
        return localeResolver.resolveLocale(request)
    }
}
