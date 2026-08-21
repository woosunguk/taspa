plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.jlleitschuh.gradle.ktlint")
    id("org.jetbrains.kotlinx.kover")
}

kotlin {
    jvmToolchain(21)
}

/*
 * 정적분석(ktlint) — **baseline 방식**.
 *
 * 기존 코드에 위반이 7천여 건 있어 한동안 `ignoreFailures=true` 로 두었는데, 그러면 게이트가 장식이
 * 된다: 리포트는 쌓이지만 **새 위반을 아무도 막지 않는다**. 반대로 전면 자동수정(`ktlintFormat`)은
 * 수천 줄을 한꺼번에 갈아 리뷰 diff 를 덮어 실제 변경을 가린다(web 쪽에서 실제로 그 대가를 치렀다).
 *
 * 그래서 baseline 을 쓴다 — 기존 위반은 그 파일에 기록해 유예하고, **그 뒤에 생긴 위반만** 빌드를
 * 깨뜨린다. 새 코드는 처음부터 규칙을 지키고, 옛 코드는 손댈 때 자연히 정리된다.
 *
 * 운용:
 *   - 새 위반 확인: `./gradlew :server:ktlintCheck`
 *   - 내 코드만 자동수정: `./gradlew :server:ktlintFormat`
 *   - ★baseline 재생성(`ktlintGenerateBaseline`)은 **기존 위반을 새로 사면하는 행위**다. 지금 실패하는
 *     것을 통과시키려고 돌리면 게이트가 다시 장식이 된다 — 규칙을 의도적으로 바꿀 때만 재생성할 것.
 */
ktlint {
    ignoreFailures.set(false)
    baseline.set(file("config/ktlint/baseline.xml"))
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.HTML)
    }
}

// 커버리지 리포트(임계 게이트 없음). CI 가 koverXmlReport/koverHtmlReport 산출물을 아티팩트로 업로드한다.
kover {
    reports {
        total {
            xml { onCheck = false }
            html { onCheck = false }
        }
    }
}

dependencies {
    // Core
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // 프로덕션 메트릭 스크레이핑(/actuator/prometheus) — prod 프로파일에서만 노출한다.
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-mail")

    // OAuth2 / OIDC Authorization Server (Boot-managed version)
    implementation("org.springframework.boot:spring-boot-starter-oauth2-authorization-server")

    // OAuth2 Resource Server(Phase 0) — 조직 캘린더 조회 API 를 M2M(client_credentials + calendar.read)
    // 베어러 JWT 로 보호하기 위해 필요하다. 전용 SecurityFilterChain(/api/orgs/**)에서만 활성화하고
    // JwtDecoder 를 명시 주입하므로 Boot 의 resource-server 자동구성(issuer/jwk-set-uri 프로퍼티 요구)은
    // 개입하지 않는다(조건 미충족으로 back-off).
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // 소셜 로그인(구글·카카오·네이버) — oauth2Login 클라이언트
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")

    // 기업 SSO(Stage E) — SAML 2.0 SP(saml2Login). Boot 3.4.4 BOM → Spring Security 6.4.4,
    // OpenSAML 4.3.2 가 transitive(compile)로 딸려온다. OpenSAML 4.3.2 는 Maven Central 에 없어
    // 루트 build.gradle.kts 의 subprojects repositories 에 Shibboleth 저장소가 필요하다.
    implementation("org.springframework.security:spring-security-saml2-service-provider")

    // 세션 JDBC 영속화(재시작 생존 + 원격 세션 관리). 스키마는 Flyway(V9)가 관리한다.
    implementation("org.springframework.session:spring-session-jdbc")

    // TOTP (MFA)
    implementation("dev.samstevens.totp:totp:1.7.1")

    // WebAuthn (Passkey) — Spring Security 6.4 webAuthn DSL 의 런타임 의존성.
    // Boot BOM 이 관리하지 않으므로 명시하지 않으면 NoClassDefFoundError 가 발생한다.
    implementation("com.webauthn4j:webauthn4j-core:0.28.6.RELEASE")

    // Database
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // iCalendar(RFC 5545) 파싱(Phase 0-E). biweekly 는 경량·간결하며 RRULE 확장(getDateIterator)을 제공한다.
    implementation("net.sf.biweekly:biweekly:0.6.8")

    // API Docs
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.5")

    // Kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation("com.ninja-squad:springmockk:4.0.2")
    // 소셜 로그인 플로우 테스트용 공급자(token/userinfo) 스텁. standalone 셰이딩 버전은
    // Spring Boot 3.x 의 Jetty/서블릿 API 와 충돌하지 않는다.
    testImplementation("org.wiremock:wiremock-standalone:3.10.0")
}

tasks.withType<Test>().configureEach {
    // Testcontainers + 최신 Docker 데몬(API >= 1.44, 예: colima) 대응.
    // docker-java 기본 API 버전(1.32)이 거부되므로 하한을 지정한다(docker-java 는 시스템 프로퍼티 api.version 을 읽는다).
    val apiVersion = System.getProperty("api.version") ?: System.getenv("DOCKER_API_VERSION") ?: "1.44"
    systemProperty("api.version", apiVersion)
    environment("DOCKER_API_VERSION", apiVersion)
    // Ryuk 리소스 리퍼가 컨테이너 내부 표준 소켓 경로(/var/run/docker.sock)를 마운트하도록 한다(colima VM 대응).
    environment(
        "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE",
        System.getenv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE") ?: "/var/run/docker.sock",
    )
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("taspa-server.jar")
}

tasks.named<Jar>("jar") {
    enabled = false
}
