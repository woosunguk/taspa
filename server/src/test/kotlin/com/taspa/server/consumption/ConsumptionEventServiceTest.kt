package com.taspa.server.consumption

import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.consumption.dto.ConsumptionEventWriteRequest
import com.taspa.server.domain.consumption.ConsumptionEventRepository
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mail.javamail.JavaMailSender
import java.time.Instant
import java.util.UUID

/**
 * 소비 이벤트 수집/집계 서비스(Phase 0ب-C) 단위 통합 테스트 — 멱등 적재(중복 external_id no-op),
 * 배치, VOIDED 제외 집계, 집계 정확성, 교차 테넌시 적재 거부.
 */
class ConsumptionEventServiceTest : IntegrationTestBase() {
    @Autowired lateinit var service: ConsumptionEventService

    @Autowired lateinit var eventRepository: ConsumptionEventRepository

    @Autowired lateinit var organizationRepository: OrganizationRepository

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private lateinit var orgA: UUID
    private lateinit var orgB: UUID

    private val from = Instant.parse("2026-08-01T00:00:00Z")
    private val to = Instant.parse("2026-09-01T00:00:00Z")

    @BeforeEach
    fun setUp() {
        eventRepository.deleteAll()
        organizationRepository.deleteAll()
        orgA = organizationRepository.save(Organization(slug = "c-a", name = "C A")).id!!
        orgB = organizationRepository.save(Organization(slug = "c-b", name = "C B")).id!!
    }

    private fun req(
        externalId: String,
        window: String = "LUNCH",
        source: String = "payment",
        quantity: Int = 1,
        status: String? = null,
        menu: String? = null,
        at: String = "2026-08-10T12:00:00Z",
    ) = ConsumptionEventWriteRequest(
        source = source,
        externalId = externalId,
        mealWindow = window,
        quantity = quantity,
        status = status,
        menuRef = menu,
        occurredAt = Instant.parse(at),
    )

    @Test
    fun `멱등 적재 - 같은 external_id 재전송은 insert 하지 않고 갱신한다`() {
        val first = service.ingest(orgA, listOf(req("ext-1", quantity = 1)))
        assertThat(first.inserted).isEqualTo(1)
        assertThat(first.updated).isEqualTo(0)
        assertThat(eventRepository.count()).isEqualTo(1)

        // 같은 (source, external_id) 재전송 — no-op/갱신(insert 아님).
        val second = service.ingest(orgA, listOf(req("ext-1", quantity = 3)))
        assertThat(second.inserted).isEqualTo(0)
        assertThat(second.updated).isEqualTo(1)
        assertThat(eventRepository.count()).isEqualTo(1)
        assertThat(eventRepository.findByOrgIdAndSourceAndExternalId(orgA, "payment", "ext-1")!!.quantity).isEqualTo(3)
    }

    @Test
    fun `배치 적재 - 여러 건과 배치 내 중복 external_id 를 한 건으로 접는다`() {
        val result =
            service.ingest(
                orgA,
                listOf(req("b-1"), req("b-2"), req("b-1", quantity = 5)),
            )
        assertThat(result.received).isEqualTo(3)
        // b-1 은 배치 내 중복이므로 최종 1건, b-2 1건 → 2건 insert.
        assertThat(result.inserted).isEqualTo(2)
        assertThat(eventRepository.count()).isEqualTo(2)
        assertThat(eventRepository.findByOrgIdAndSourceAndExternalId(orgA, "payment", "b-1")!!.quantity).isEqualTo(5)
    }

    @Test
    fun `집계 - VOIDED 는 제외되고 date x meal_window 로 카운트된다`() {
        service.ingest(
            orgA,
            listOf(
                req("a", window = "LUNCH", at = "2026-08-10T12:00:00Z", quantity = 2),
                req("b", window = "LUNCH", at = "2026-08-10T12:30:00Z", quantity = 1),
                req("c", window = "DINNER", at = "2026-08-10T18:00:00Z", quantity = 1),
                req("d", window = "LUNCH", at = "2026-08-11T12:00:00Z", quantity = 1),
                req("void", window = "LUNCH", at = "2026-08-10T12:45:00Z", status = "VOIDED", quantity = 9),
            ),
        )
        val rows = service.aggregate(orgA, from, to, includeMenu = false)
        // (08-10 LUNCH): a+b = 2건, 수량 3 (void 제외). (08-10 DINNER): 1건. (08-11 LUNCH): 1건.
        val lunch0810 = rows.single { it.date.toString() == "2026-08-10" && it.mealWindow == "LUNCH" }
        assertThat(lunch0810.count).isEqualTo(2)
        assertThat(lunch0810.quantity).isEqualTo(3)
        val dinner0810 = rows.single { it.date.toString() == "2026-08-10" && it.mealWindow == "DINNER" }
        assertThat(dinner0810.count).isEqualTo(1)
        val lunch0811 = rows.single { it.date.toString() == "2026-08-11" && it.mealWindow == "LUNCH" }
        assertThat(lunch0811.count).isEqualTo(1)
        // VOIDED 는 어느 버킷에도 들어가지 않는다.
        assertThat(rows.sumOf { it.count }).isEqualTo(4)
    }

    @Test
    fun `집계 - menu 그룹핑은 menu_ref 축을 추가한다`() {
        service.ingest(
            orgA,
            listOf(
                req("m1", window = "LUNCH", menu = "bibimbap", at = "2026-08-10T12:00:00Z"),
                req("m2", window = "LUNCH", menu = "bibimbap", at = "2026-08-10T12:10:00Z"),
                req("m3", window = "LUNCH", menu = "kimchi-stew", at = "2026-08-10T12:20:00Z"),
            ),
        )
        val rows = service.aggregate(orgA, from, to, includeMenu = true)
        assertThat(rows.single { it.menuRef == "bibimbap" }.count).isEqualTo(2)
        assertThat(rows.single { it.menuRef == "kimchi-stew" }.count).isEqualTo(1)
    }

    @Test
    fun `멱등키는 org 범위다 - 다른 org 가 같은 external_id 를 써도 충돌하지 않고 독립 적재된다`() {
        // pos·manual·import 의 external_id 는 org-로컬로만 유일하다(영수증번호 등). 전역 UNIQUE 였다면 뒤에
        // 적재한 조직이 403/충돌로 유실됐지만, org-범위 멱등키에서는 각 조직이 독립적으로 자기 행을 갖는다.
        val a = service.ingest(orgA, listOf(req("shared", source = "pos")))
        val b = service.ingest(orgB, listOf(req("shared", source = "pos")))
        assertThat(a.inserted).isEqualTo(1)
        assertThat(b.inserted).isEqualTo(1)
        assertThat(eventRepository.count()).isEqualTo(2)
        assertThat(eventRepository.findByOrgIdAndSourceAndExternalId(orgA, "pos", "shared")!!.orgId).isEqualTo(orgA)
        assertThat(eventRepository.findByOrgIdAndSourceAndExternalId(orgB, "pos", "shared")!!.orgId).isEqualTo(orgB)
    }

    @Test
    fun `멱등 갱신은 full-replace 다 - 재전송이 생략한 선택 필드는 비워진다`() {
        val user = UUID.randomUUID()
        service.ingest(orgA, listOf(req("fr-1", menu = "bibimbap").copy(userSub = user)))
        val first = eventRepository.findByOrgIdAndSourceAndExternalId(orgA, "payment", "fr-1")!!
        assertThat(first.userSub).isEqualTo(user)
        assertThat(first.menuRef).isEqualTo("bibimbap")
        // 같은 멱등키로 선택 필드를 생략해 재전송 → 전체 상태 대체이므로 userSub·menuRef 가 null 로 비워진다.
        service.ingest(orgA, listOf(req("fr-1")))
        val replaced = eventRepository.findByOrgIdAndSourceAndExternalId(orgA, "payment", "fr-1")!!
        assertThat(replaced.userSub).isNull()
        assertThat(replaced.menuRef).isNull()
    }

    @Test
    fun `집계 date 버킷은 org 로컬 타임존으로 앵커링된다 - KST 이른 아침은 전날로 새지 않는다`() {
        // KST(UTC+9) 조직: 2026-08-11 07:30 KST 아침식사 = 2026-08-10T22:30Z 저장. UTC 절단이면 08-10 으로
        // 오귀속되지만 org-로컬(Asia/Seoul) 앵커링이면 08-11 버킷이어야 한다.
        val kst =
            organizationRepository
                .save(
                    Organization(slug = "c-kst", name = "C KST", timezone = "Asia/Seoul"),
                ).id!!
        service.ingest(kst, listOf(req("k-1", window = "BREAKFAST", at = "2026-08-10T22:30:00Z")))
        val rows = service.aggregate(kst, from, to, includeMenu = false)
        val bucket = rows.single()
        assertThat(bucket.date.toString()).isEqualTo("2026-08-11")
        assertThat(bucket.mealWindow).isEqualTo("BREAKFAST")
        assertThat(bucket.count).isEqualTo(1)
    }

    @Test
    fun `집계 date 버킷 - UTC 조직은 UTC 로 절단한다(회귀 고정)`() {
        // 기본 타임존 UTC 조직: 22:30Z 는 그대로 08-10 버킷(로컬 변환 없음).
        service.ingest(orgA, listOf(req("u-1", window = "DINNER", at = "2026-08-10T22:30:00Z")))
        val rows = service.aggregate(orgA, from, to, includeMenu = false)
        assertThat(rows.single().date.toString()).isEqualTo("2026-08-10")
    }

    @Test
    fun `집계는 org 로 격리된다 - 타 org 이벤트는 포함되지 않는다`() {
        service.ingest(orgA, listOf(req("a1", at = "2026-08-10T12:00:00Z")))
        service.ingest(orgB, listOf(req("b1", at = "2026-08-10T12:00:00Z")))
        assertThat(service.aggregate(orgA, from, to, includeMenu = false).sumOf { it.count }).isEqualTo(1)
        assertThat(service.aggregate(orgB, from, to, includeMenu = false).sumOf { it.count }).isEqualTo(1)
    }
}
