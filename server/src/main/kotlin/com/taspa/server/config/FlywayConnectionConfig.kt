package com.taspa.server.config

import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer
import org.springframework.boot.autoconfigure.flyway.FlywayProperties
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Flyway 를 **앱 커넥션 풀에서 떼어 낸다** — 마이그레이션 전용 커넥션을 Flyway 가 직접 열게 한다.
 *
 * ★왜 필요한가 (둘 다 실측으로 확인한 함정이다)
 *
 * 1) 마이그레이션에 세션 타임아웃이 상속되면 안 된다.
 *    `application.yml` 은 요청 경로를 지키려고 pgjdbc 시작 패킷 `options` 로 `lock_timeout=3s`·
 *    `idle_in_transaction_session_timeout=60s` 를 건다. 기본 구성에서 Flyway 는 **앱 DataSource 를 그대로
 *    쓰므로** 그 값을 상속하는데, `CREATE INDEX CONCURRENTLY`(V30~)는 두 값 모두에 죽는다: CIC 는 인덱스
 *    빌드 전체가 하나의 statement 이고, 두 번의 테이블 스캔 사이에 동시 트랜잭션의 가상 XID 를 **기다린다**
 *    (=잠금 획득 → lock_timeout 적용). 실패의 성질도 최악이다 — CIC 는 트랜잭션 밖이라 롤백이 없어
 *    **INVALID 인덱스**를 남기고, `IF NOT EXISTS` 는 이름만 보므로 재실행이 그걸 조용히 건너뛴다.
 *
 * 2) 그렇다고 `spring.flyway.init-sqls: SET lock_timeout = 0` 로 되돌리면 **풀이 오염된다.**
 *    Flyway 가 빌린 커넥션은 풀로 돌아가고 평범한 `SET` 은 그 물리 커넥션의 수명 내내 남는다 →
 *    풀의 일부가 타임아웃 없는 커넥션이 되어 방어선이 확률적으로 사라진다.
 *    (실측: 이 구성에서 `OrgInvitationServiceIntegrationTest` 의 lock_timeout 단언이 0 을 봤다.)
 *
 * 그래서 SET 으로 되돌리는 대신 **애초에 다른 커넥션을 쓴다.** Flyway 는 자체 DriverDataSource 로
 * url/user/password 만 가지고 접속하므로 `options` 가 실리지 않고(타임아웃 없음), 풀도 건드리지 않는다.
 * 마이그레이션은 기동 시 한 번뿐이라 별도 접속 비용은 무시할 수 있다.
 *
 * ★`ALTER ROLE/DATABASE ... SET statement_timeout` 로는 이 문제를 풀 수 없다 — 그 방식은 같은 role/DB 의
 *   **모든** 커넥션에 붙어 별도 `flyway migrate` 잡까지 오염시킨다.
 *
 * FlywayAutoConfiguration 은 커스터마이저를 데이터소스 구성 **뒤**에 적용하므로 이 재지정이 이긴다.
 * `spring.flyway.url` 로 마이그레이션 접속을 명시한 환경에서는 그 설정을 존중해 아무것도 하지 않는다.
 */
@Configuration
class FlywayConnectionConfig {
    @Bean
    fun flywayDedicatedConnectionCustomizer(
        dataSourceProperties: DataSourceProperties,
        flywayProperties: FlywayProperties,
    ): FlywayConfigurationCustomizer =
        FlywayConfigurationCustomizer { configuration ->
            if (flywayProperties.url != null) return@FlywayConfigurationCustomizer
            configuration.dataSource(
                dataSourceProperties.determineUrl(),
                dataSourceProperties.determineUsername(),
                dataSourceProperties.determinePassword(),
            )
        }
}
