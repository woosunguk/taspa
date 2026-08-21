package com.taspa.server.iam

import com.taspa.server.domain.iam.IamPolicy
import com.taspa.server.domain.iam.IamPolicyRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 시스템 관리(system-managed) 정책 시딩. AWS 의 "AWS managed policy" 대응 — 관리 콘솔에서 principal 에
 * 바로 부착할 수 있는 불변 시작 정책. 이름(전역)으로 멱등 upsert 한다.
 *
 * org 스코프 정책은 구체 org UUID 를 박아야 테넌시가 안전하므로(변수 템플릿의 교차테넌트 함정 회피,
 * LegacyPolicyBridge 참조) 여기서 전역 시드하지 않는다 — 조직 정책은 콘솔에서 org 별 구체 리소스로 생성한다.
 */
@Configuration
class SystemPolicySeeder {
    private val log = LoggerFactory.getLogger(SystemPolicySeeder::class.java)

    @Bean
    fun iamSystemPolicySeeder(
        policyRepository: IamPolicyRepository,
        parser: PolicyParser,
    ): ApplicationRunner =
        ApplicationRunner {
            SYSTEM_POLICIES.forEach { (name, description, document) ->
                val existing = policyRepository.findByNameAndOrgIdIsNull(name)
                // 시드 문서도 저장 정책과 동일한 검증을 통과해야 한다(SYSTEM_POLICIES 편집 시 회귀 방지).
                parser.validate(document)
                val json = parser.serialize(document)
                if (existing == null) {
                    policyRepository.save(
                        IamPolicy(name = name, orgId = null, description = description, document = json, systemManaged = true),
                    )
                    log.info("seeded system IAM policy: {}", name)
                } else if (existing.systemManaged && existing.document != json) {
                    // 시스템 정책 정의가 바뀌면 문서만 동기화(이름/부착은 유지).
                    existing.document = json
                    existing.description = description
                    policyRepository.save(existing)
                    log.info("updated system IAM policy: {}", name)
                }
            }
        }

    private companion object {
        val PLATFORM_ADMIN =
            PolicyDocument(
                PolicyParser.DEFAULT_VERSION,
                listOf(Statement(sid = "FullAccess", effect = Effect.ALLOW, actions = listOf("*"), resources = listOf("*"))),
            )

        val SYSTEM_POLICIES: List<Triple<String, String, PolicyDocument>> =
            listOf(
                Triple("PlatformAdminAccess", "플랫폼 전권(모든 action·모든 리소스)", PLATFORM_ADMIN),
            )
    }
}
