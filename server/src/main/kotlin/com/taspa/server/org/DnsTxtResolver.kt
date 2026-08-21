package com.taspa.server.org

import org.springframework.stereotype.Component
import java.util.Hashtable
import javax.naming.Context
import javax.naming.directory.InitialDirContext

/**
 * DNS TXT 조회 추상화 — 도메인 소유 자가검증(`_taspa-verify.<domain>`)에 사용한다.
 * 인터페이스로 분리해 통합 테스트에서 @MockkBean 으로 대체한다(실 DNS 왕복 금지).
 */
interface DnsTxtResolver {
    /**
     * name 의 TXT 레코드 문자열 목록을 반환한다. 레코드가 없으면 빈 목록,
     * 조회 실패(NXDOMAIN·타임아웃 등)는 예외를 던진다 — 호출부(OrgDomainService)가 400 으로 수렴시킨다.
     */
    fun lookupTxt(name: String): List<String>
}

/**
 * JDK 내장 JNDI DNS 구현(외부 라이브러리 금지 제약). 시스템 기본 리졸버("dns:")를 사용하고
 * 타임아웃을 **네임서버당 3초·재시도 1회**로 묶는다 — JNDI 타임아웃은 서버당 적용이라 시스템에
 * 리졸버가 여러 개 구성돼 있으면 실패 시 최악 3초 × 서버 수(통상 2~3개 = 6~9초)까지 걸릴 수 있다.
 * 그래서 호출부(OrgDomainService.verify·OrgDomainReverifyJob)는 이 조회를 DB 트랜잭션 밖에서 수행한다.
 */
@Component
class JndiDnsTxtResolver : DnsTxtResolver {
    override fun lookupTxt(name: String): List<String> {
        val env =
            Hashtable<String, String>().apply {
                put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory")
                put(Context.PROVIDER_URL, "dns:")
                put("com.sun.jndi.dns.timeout.initial", "3000")
                put("com.sun.jndi.dns.timeout.retries", "1")
            }
        val ctx = InitialDirContext(env)
        try {
            val attribute = ctx.getAttributes(name, arrayOf("TXT")).get("TXT") ?: return emptyList()
            val values = mutableListOf<String>()
            val all = attribute.all
            while (all.hasMore()) {
                // JNDI 는 긴 TXT 를 따옴표로 감싼 조각 연결로 줄 수 있어 앞뒤 따옴표를 걷어낸다.
                values.add(
                    all
                        .next()
                        .toString()
                        .trim()
                        .trim('"'),
                )
            }
            return values
        } finally {
            ctx.close()
        }
    }
}
