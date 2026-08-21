plugins {
    `java-library`
    // ★외부 프로젝트가 이 스타터를 쓸 수 있게 하는 유일한 조건.
    //
    // 그전에는 발행 설정이 **아예 없어서**, 연동 가이드가 1순위로 권하는 방법(`implementation
    // ("com.taspa:taspa-spring-boot-starter:<version>")`)이 taspa 저장소 **안에서만** 성립했다
    // (`project(":client:spring-boot-starter")`). 다른 팀은 문서대로 따라 하다 의존성 해석 실패로
    // 막히고, 그게 왜인지는 문서 어디에도 없었다 — 스타터를 만든 목적이 그 지점에서 사라진다.
    `maven-publish`
    id("io.spring.dependency-management")
    kotlin("jvm")
    kotlin("plugin.spring")
}

/**
 * 아티팩트 이름은 **`taspa-spring-boot-starter`** 하나로 고정한다(디렉터리 이름
 * `spring-boot-starter` 를 그대로 쓰면 `com.taspa:spring-boot-starter` 가 되어 문서와 어긋난다).
 * 문서·빌드·발행이 같은 이름을 말하지 않으면, 복사해 붙인 좌표가 조용히 해석되지 않는다.
 */
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "taspa-spring-boot-starter"
            from(components["java"])
            pom {
                name.set("taspa Spring Boot Starter")
                description.set("taspa IdP 연동용 Resource Server 자동설정(JWT 검증 + 조직 역할 권한 매핑)")
            }
        }
    }
    repositories {
        /*
         * 사내 저장소 주소는 환경마다 다르므로 환경변수로 받는다. 없으면 `publishToMavenLocal` 만
         * 동작한다 — 배포 파이프라인이 없는 개발자도 `~/.m2` 로 즉시 시험해 볼 수 있다.
         */
        val url = System.getenv("TASPA_MAVEN_REPO_URL")
        if (!url.isNullOrBlank()) {
            maven {
                setUrl(url)
                credentials {
                    username = System.getenv("TASPA_MAVEN_USERNAME")
                    password = System.getenv("TASPA_MAVEN_PASSWORD")
                }
            }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.4.4")
    }
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

// 이 모듈에는 Spring 컨텍스트가 필요한 테스트가 없다(순수 판정 로직) — Docker 도 DB 도 쓰지 않는다.
tasks.withType<Test> { useJUnitPlatform() }
