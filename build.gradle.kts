plugins {
    id("org.springframework.boot") version "3.4.4" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    kotlin("jvm") version "2.1.10" apply false
    kotlin("plugin.spring") version "2.1.10" apply false
    kotlin("plugin.jpa") version "2.1.10" apply false
    // 정적분석(CI 품질 게이트). detekt 는 1.23.8(최신 안정 1.x)까지도 Kotlin 2.0.21 까지만
    // 지원해 2.1.10 에서 "compiled with Kotlin 2.0.21 but is currently running with 2.1.10" 로
    // 즉시 실패한다(2.x 라인은 dev.detekt 로 이관되었고 2026-07 현재도 alpha 전용이라 배제) — 대신
    // Kotlin 컴파일러 버전에 종속되지 않는 ktlint 를 사용한다.
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
    // 커버리지 리포트(임계 게이트 없음 — 리포트 생성까지만). Kotlin 2.1.10 호환 확인됨.
    id("org.jetbrains.kotlinx.kover") version "0.9.1" apply false
}

subprojects {
    group = "com.taspa"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
        // OpenSAML 4.3.2(spring-security-saml2-service-provider 의 transitive 의존)는 Maven Central 에
        // 없다(404). Shibboleth 저장소를 추가해야 SAML 의존이 해석된다 — 누락 시 SAML 추가 순간 빌드 실패.
        maven { url = uri("https://build.shibboleth.net/maven/releases/") }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
