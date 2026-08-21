package com.taspa.server.mail

import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.i18n.TimeZoneAwareLocaleContext
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Service
class MailService(
    private val mailSender: JavaMailSender,
    private val messageSource: MessageSource,
) {
    companion object {
        private val TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss zzz").withZone(ZoneId.systemDefault())

        /**
         * 금액 표시. `MessageFormat` 의 숫자 포맷에 맡기지 않고 여기서 만든다 — properties 안의
         * `{2,number}` 는 로케일마다 구분자가 달라지고, 회계 문의가 오갈 문장의 숫자가 발송 환경에
         * 따라 달라지면 안 된다.
         */
        private val AMOUNT_FORMAT: java.text.NumberFormat =
            java.text.NumberFormat.getIntegerInstance(Locale.KOREA)

        /** 요청 로케일이 바인딩되지 않은 발송 경로(로그인 알림/@Async)의 기본 로케일. */
        private val DEFAULT_LOCALE: Locale = Locale.KOREAN
    }

    /**
     * 메일 본문 로케일 — **우리 LocaleResolver(CookieLocaleResolver)가 해석·바인딩한 컨텍스트만**
     * 신뢰하고, 지원 로케일(ko/en)로 클램프한다. 그 컨텍스트는 `TimeZoneAwareLocaleContext` 다.
     *
     * 컨트롤러 경로(매직 링크·비밀번호 재설정·이메일 변경 등)는 DispatcherServlet 이 우리 리졸버로
     * LocaleContext 를 바인딩하므로 사용자가 ?lang= 로 고른 로케일이 그대로 반영된다.
     *
     * 반면 로그인 알림/리스크 메일은 Spring Security 필터 체인(DispatcherServlet 진입 전)에서
     * 발송되어, 그 시점의 바인딩은 RequestContextFilter 의 `SimpleLocaleContext(request.getLocale())`
     * 이다(사용자 선택이 아닌 브라우저/서블릿 기본 로케일 — 테스트 MockMvc 에선 en). 이는 신뢰하지
     * 않고 앱 기본 로케일(ko)로 결정적으로 폴백한다 → 기존 메일 텍스트 단언(정확 국문) 무손상.
     */
    private fun currentLocale(): Locale {
        val ctx = LocaleContextHolder.getLocaleContext()
        val locale = (ctx as? TimeZoneAwareLocaleContext)?.locale ?: DEFAULT_LOCALE
        return if (locale.language == "en") Locale.ENGLISH else Locale.KOREAN
    }

    private fun subject(
        key: String,
        locale: Locale,
    ): String = messageSource.getMessage(key, null, locale)

    private fun body(
        key: String,
        args: Array<Any?>,
        locale: Locale,
    ): String = messageSource.getMessage(key, args, locale)

    fun sendVerificationCode(
        email: String,
        code: String,
    ) {
        val locale = currentLocale()
        val message =
            SimpleMailMessage().apply {
                setTo(email)
                subject = subject("mail.verify.subject", locale)
                text = body("mail.verify.body", arrayOf(code), locale)
            }
        mailSender.send(message)
    }

    /** 이메일 변경 완료 시 이전(옛) 주소로 보내는 통지 — 계정 탈취 조기 감지용. */
    fun sendEmailChangedNotice(
        oldEmail: String,
        newEmail: String,
    ) {
        val locale = currentLocale()
        val message =
            SimpleMailMessage().apply {
                setTo(oldEmail)
                subject = subject("mail.emailChanged.subject", locale)
                text = body("mail.emailChanged.body", arrayOf(newEmail), locale)
            }
        mailSender.send(message)
    }

    fun sendFederationUnlinkedNotice(
        email: String,
        providerLabel: String,
    ) {
        val locale = currentLocale()
        val message =
            SimpleMailMessage().apply {
                setTo(email)
                subject = subject("mail.federationUnlinked.subject", locale)
                text = body("mail.federationUnlinked.body", arrayOf(providerLabel), locale)
            }
        mailSender.send(message)
    }

    /**
     * 새 로그인(신규 기기) 알림 — B-2. 로그인 성공 경로에서 호출되므로 @Async 로 요청 스레드와
     * 분리한다(SMTP 지연이 로그인 응답을 지연시키지 않게). test 프로파일은 동기 실행(AsyncConfig).
     */
    @Async("mailTaskExecutor")
    fun sendNewLoginNotice(
        email: String,
        uaLabel: String,
        ip: String,
        occurredAt: Instant,
    ) {
        val locale = currentLocale()
        val message =
            SimpleMailMessage().apply {
                setTo(email)
                subject = subject("mail.newLogin.subject", locale)
                text = body("mail.newLogin.body", arrayOf(uaLabel, ip, TIME_FORMAT.format(occurredAt)), locale)
            }
        mailSender.send(message)
    }

    /**
     * 고위험 로그인 보안 경고(리스크 HIGH) — 새 로그인 알림과 별도의 차단 안내 문구.
     * 로그인 경로 인라인 호출이므로 @Async(요청 스레드 분리). test 프로파일은 동기 실행(AsyncConfig).
     */
    @Async("mailTaskExecutor")
    fun sendHighRiskLoginAlert(
        email: String,
        uaLabel: String,
        ip: String,
        occurredAt: Instant,
    ) {
        val locale = currentLocale()
        val message =
            SimpleMailMessage().apply {
                setTo(email)
                subject = subject("mail.highRisk.subject", locale)
                text = body("mail.highRisk.body", arrayOf(uaLabel, ip, TIME_FORMAT.format(occurredAt)), locale)
            }
        mailSender.send(message)
    }

    /** 매직 링크(이메일 로그인) — B-4. */
    fun sendMagicLink(
        email: String,
        magicLink: String,
        expiryMinutes: Long,
    ) {
        val locale = currentLocale()
        val message =
            SimpleMailMessage().apply {
                setTo(email)
                subject = subject("mail.magicLink.subject", locale)
                text = body("mail.magicLink.body", arrayOf(magicLink, expiryMinutes), locale)
            }
        mailSender.send(message)
    }

    fun sendPasswordResetLink(
        email: String,
        resetLink: String,
    ) {
        val locale = currentLocale()
        val message =
            SimpleMailMessage().apply {
                setTo(email)
                subject = subject("mail.passwordReset.subject", locale)
                text = body("mail.passwordReset.body", arrayOf(resetLink), locale)
            }
        mailSender.send(message)
    }

    /**
     * 조직 초대 메일 — 관리자/조직관리자가 이메일로 초대할 때 발송한다. 수락 링크(원문 토큰 포함)와
     * 조직명·만료 시각을 담는다. 원문 토큰은 이 메일과 URL 로만 노출된다(DB 에는 해시만 저장).
     */
    fun sendOrgInvitation(
        email: String,
        orgName: String,
        acceptUrl: String,
        expiresAt: Instant,
    ) {
        val locale = currentLocale()
        val message =
            SimpleMailMessage().apply {
                setTo(email)
                subject = subject("mail.orgInvite.subject", locale)
                text =
                    body(
                        "mail.orgInvite.body",
                        arrayOf(orgName, acceptUrl, TIME_FORMAT.format(expiresAt)),
                        locale,
                    )
            }
        mailSender.send(message)
    }

    /**
     * 월 청구서 초안 준비 알림 — 자동 생성 잡이 만든 초안을 **조직관리자가 알게** 한다.
     *
     * 이 메일이 없으면 자동 생성은 아무 일도 하지 않은 것과 같다: 청구서는 만들어졌지만 그 사실을
     * 아는 사람이 없고, 관리자는 여전히 "이번 달 청구서를 만들어야 하나" 를 스스로 기억해야 한다.
     * 확정 링크가 아니라 **콘솔 링크**를 보낸다 — 확정은 숫자를 확인한 뒤의 판단이라 메일 한 번의
     * 클릭으로 일어나서는 안 된다.
     */
    fun sendInvoiceDraftReady(
        email: String,
        orgName: String,
        period: String,
        subtotalMinor: Long,
        txnCount: Int,
        consoleUrl: String,
    ) {
        val locale = currentLocale()
        val message =
            SimpleMailMessage().apply {
                setTo(email)
                subject = subject("mail.invoiceDraft.subject", locale)
                text =
                    body(
                        "mail.invoiceDraft.body",
                        arrayOf(orgName, period, AMOUNT_FORMAT.format(subtotalMinor), txnCount, consoleUrl),
                        locale,
                    )
            }
        mailSender.send(message)
    }

    /**
     * 가맹 담당자로 지정됐음을 당사자에게 알린다.
     *
     * ★그전에는 **아무 통지도 없었다.** 플랫폼 운영자가 담당자를 지정해도 사장이 그 사실을 알 방법은
     * "우연히 로그인해서 헤더에 없던 메뉴가 생긴 것을 보는 것"뿐이었다 — 실제로는 운영자가 따로
     * 전화나 메신저로 알려야 했고, 그 절차는 제품 밖에 있으니 빠뜨려도 아무 흔적이 없다.
     * (조직 초대는 메일을 보내는데 가맹만 없었다 — 규칙이 아니라 누락이다.)
     */
    fun sendMerchantAdminGranted(
        email: String,
        merchantName: String,
        consoleUrl: String,
        merchantActive: Boolean,
    ) {
        val locale = currentLocale()
        val message =
            SimpleMailMessage().apply {
                setTo(email)
                subject = subject("mail.merchantAdmin.subject", locale)
                text =
                    body(
                        // 매장이 아직 활성화 전이면 "지금 들어가면 안 보인다"를 **미리** 말한다 —
                        // 그러지 않으면 메일을 받고 들어갔다가 빈 화면을 보고 권한 문제로 오해한다.
                        if (merchantActive) "mail.merchantAdmin.body" else "mail.merchantAdmin.bodyPending",
                        arrayOf(merchantName, consoleUrl),
                        locale,
                    )
            }
        mailSender.send(message)
    }
}
