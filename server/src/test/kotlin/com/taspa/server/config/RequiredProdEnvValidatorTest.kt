package com.taspa.server.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.context.annotation.Configuration
import org.springframework.mock.env.MockEnvironment
import java.io.PrintWriter
import java.io.StringWriter

/**
 * prod 필수 환경변수 누락이 **읽을 수 있는 한 개의 오류**로 보고되는지.
 *
 * ★이 검증기가 없던 동안 `MAIL_HOST` 하나만 빠져도 배포자가 받는 것은
 * `Error processing condition on MailSenderAutoConfiguration` 로 시작하는 스택트레이스였다 —
 * 진짜 원인은 맨 아래 `Caused by` 한 줄에 묻히고, 나머지 누락 변수는 **하나도 알려주지 않는다**
 * (고칠 때마다 다음 변수에서 다시 죽는다).
 */
class RequiredProdEnvValidatorTest {
    private val validator = RequiredProdEnvValidator()
    private val application = SpringApplication()

    private fun envWith(vararg present: Pair<String, String>) =
        MockEnvironment().apply {
            setActiveProfiles("prod")
            present.forEach { (k, v) -> setProperty(k, v) }
        }

    @Test
    fun `prod 가 아니면 아무 것도 검사하지 않는다`() {
        val env = MockEnvironment().apply { setActiveProfiles("dev") }
        validator.postProcessEnvironment(env, application) // 예외 없음
    }

    @Test
    fun `누락된 변수를 한 번에 모두 알려준다`() {
        assertThatThrownBy { validator.postProcessEnvironment(envWith(), application) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("MAIL_HOST")
            .hasMessageContaining("TASPA_ISSUER_URI")
            .hasMessageContaining("MFA_ENCRYPTION_KEY")
            // 개수를 함께 말한다 — "하나 고치면 다음 것에서 또 죽는" 경험을 없애는 것이 목적이다.
            .hasMessageContaining("10개")
    }

    @Test
    fun `빈 문자열은 미설정과 같게 다룬다`() {
        val env = envWith(*REQUIRED_ALL.map { it to "x" }.toTypedArray())
        env.setProperty("MAIL_HOST", "   ")
        assertThatThrownBy { validator.postProcessEnvironment(env, application) }
            .hasMessageContaining("MAIL_HOST")
    }

    @Test
    fun `전부 설정되면 통과한다(대조군)`() {
        val env = envWith(*REQUIRED_ALL.map { it to "x" }.toTypedArray())
        validator.postProcessEnvironment(env, application) // 예외 없음
        assertThat(env.getProperty("MAIL_HOST")).isEqualTo("x")
    }

    /**
     * ★**목록이 `application-prod.yml` 과 어긋나면 실패한다** — 이 테스트가 이 검증기의 값어치를 지킨다.
     *
     * 목록이 빠뜨린 변수는 예전과 똑같이 자동설정 스택트레이스로 실패하고(검증기가 있으나 마나),
     * 반대로 yml 이 기본값을 갖게 된 변수를 목록에 남겨 두면 **필요 없는 주입을 강요**한다.
     * 둘 다 조용히 어긋나는 종류라 주석이나 규약으로는 못 막는다.
     */
    @Test
    fun `필수 목록이 application-prod_yml 의 기본값 없는 플레이스홀더와 정확히 일치한다`() {
        val yml =
            javaClass.classLoader.getResource("application-prod.yml")
                ?: error("application-prod.yml 을 클래스패스에서 찾지 못했습니다")
        val declared =
            yml
                .readText()
                .lineSequence()
                .filterNot { it.trimStart().startsWith("#") } // 주석 속 예시 문구(${ENV})는 대상이 아니다
                .flatMap { DEFAULTLESS.findAll(it) }
                .map { it.groupValues[1] }
                .toSet()

        assertThat(RequiredProdEnvValidator.REQUIRED.map { it.name })
            .containsExactlyInAnyOrderElementsOf(declared)
    }

    /**
     * ★**등록이 실제로 먹히는지** — 클래스가 아무리 옳아도 발견되지 않으면 아무 일도 하지 않는다.
     *
     * `EnvironmentPostProcessor` 는 Boot 3.4 에서도 `META-INF/spring.factories` 로만 발견된다.
     * 자동설정에 쓰는 `.imports` 방식으로 등록하면 **조용히 무시**된다 — 실제로 그렇게 만들었다가
     * 컨테이너에서 예전 그대로의 자동설정 스택트레이스를 받았다. 단위 테스트는 그 실수를 통과시킨다.
     *
     * 그래서 실제 `SpringApplication` 을 prod 로 띄워 본다. 우리 검증기는 컨텍스트 refresh **전**에
     * 던지므로 DB·메일 접속 없이 즉시 끝난다.
     */
    @Test
    fun `prod 로 기동하면 스프링 오류가 아니라 이 안내가 먼저 나온다`() {
        val app =
            SpringApplication(EmptyConfig::class.java).apply {
                webApplicationType = WebApplicationType.NONE
                setAdditionalProfiles("prod")
                setDefaultProperties(mapOf("spring.main.banner-mode" to "off"))
            }

        val failure = catchThrowable { app.run() }
        assertThat(failure).isNotNull()
        val trace = StringWriter().also { failure.printStackTrace(PrintWriter(it)) }.toString()
        assertThat(trace).contains("prod 프로파일 필수 환경변수가 설정되지 않았습니다")
        // 자동설정이 먼저 터지던 옛 실패 형태가 아니어야 한다 — 그게 이 검증기의 존재 이유다.
        assertThat(trace).doesNotContain("MailSenderAutoConfiguration")
    }

    @Configuration(proxyBeanMethods = false)
    class EmptyConfig

    private companion object {
        /** `${VAR}` — 기본값(`:`)이 붙지 않은 것만. */
        val DEFAULTLESS = Regex("""\$\{([A-Z][A-Z0-9_]*)}""")

        val REQUIRED_ALL = RequiredProdEnvValidator.REQUIRED.map { it.name }
    }
}
