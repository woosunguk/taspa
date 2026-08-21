package com.taspa.server.meal

import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.meal.MealPolicy
import com.taspa.server.domain.meal.MealPolicyOverrideRepository
import com.taspa.server.domain.meal.MealPolicyRepository
import com.taspa.server.domain.meal.MealPolicyRevisionRepository
import com.taspa.server.domain.org.Department
import com.taspa.server.domain.org.DepartmentRepository
import com.taspa.server.domain.org.OrgMembership
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrgRole
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.org.Site
import com.taspa.server.domain.org.SiteRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.http.MediaType
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID

/**
 * 부서·사업장 식대 정책 재정의 통합 테스트.
 *
 * 이 슬라이스의 약속은 하나다: **재정의는 필드 단위이고, 화면과 계산대가 같은 값을 본다.**
 * 그래서 여기서 잠그는 것은 병합 우선순위(부서 가장 가까운 조상 > 사업장 > 조직 > 코드 기본값),
 * 재정의하지 않은 필드의 상속, 그리고 그 결과가 **자격 조회에 실제로 나타나는가**다.
 */
class MealPolicyOverrideIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var departmentRepository: DepartmentRepository

    @Autowired lateinit var siteRepository: SiteRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var policyRepository: MealPolicyRepository

    @Autowired lateinit var overrideRepository: MealPolicyOverrideRepository

    @Autowired lateinit var revisionRepository: MealPolicyRevisionRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val password = "SecureP@ssw0rd123"
    private lateinit var orgId: UUID
    private lateinit var admin: User
    private lateinit var rootDept: Department
    private lateinit var childDept: Department
    private lateinit var site: Site

    @BeforeEach
    fun setUp() {
        overrideRepository.deleteAll()
        revisionRepository.deleteAll()
        policyRepository.deleteAll()
        membershipRepository.deleteAll()
        // 자기참조 CASCADE 라 행단위 deleteAll 은 StaleState 가 된다(CLAUDE.md 규약).
        departmentRepository.deleteAllInBatch()
        siteRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()
        every { mailSender.send(any<SimpleMailMessage>()) } just Runs

        orgId =
            organizationRepository
                .save(
                    Organization(slug = "mpo", name = "재정의 테스트", timezone = "Asia/Seoul"),
                ).id!!
        // 조직 기본값을 명시 저장 — 재정의가 "무엇을 덮는가"가 분명해야 단언이 의미를 갖는다.
        policyRepository.save(
            MealPolicy(orgId = orgId, perMealLimitMinor = 12000, dailyMealCount = 1, monthlyCapMinor = 200000),
        )
        rootDept = departmentRepository.save(Department(orgId = orgId, name = "본부"))
        childDept = departmentRepository.save(Department(orgId = orgId, parentId = rootDept.id, name = "개발팀"))
        site = siteRepository.save(Site(orgId = orgId, name = "판교", timezone = "Asia/Seoul"))

        admin = saveUser("mpo-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgId, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
    }

    @Test
    fun `부서 재정의는 지정한 필드만 덮고 나머지는 조직값을 물려받는다`() {
        // ★이 상속이 이 설계의 핵심이다. 전체 복제였다면 조직이 한도를 올렸을 때 이 부서만 옛 값에
        //   남는데, 아무도 눈치채지 못한다.
        createOverride(childDept.id!!, """"perMealLimitMinor":18000""").andExpect(status().isCreated)

        val preview = previewOf("DEPARTMENT", childDept.id!!)
        preview
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.perMealLimitMinor").value(18000))
            .andExpect(jsonPath("$.sources.PER_MEAL_LIMIT").value("DEPARTMENT"))
            .andExpect(jsonPath("$.sourceLabels.PER_MEAL_LIMIT").value("개발팀"))
            // 재정의하지 않은 필드는 조직값 그대로 — 여기가 "필드 단위"의 증거다.
            .andExpect(jsonPath("$.dailyMealCount").value(1))
            .andExpect(jsonPath("$.sources.DAILY_MEAL_COUNT").value("ORG"))
            .andExpect(jsonPath("$.monthlyCapMinor").value(200000))
    }

    @Test
    fun `★가장 가까운 조상이 이긴다 (개발팀 재정의가 본부 재정의를 덮는다)`() {
        createOverride(rootDept.id!!, """"perMealLimitMinor":15000,"dailyMealCount":2""").andExpect(status().isCreated)
        createOverride(childDept.id!!, """"perMealLimitMinor":18000""").andExpect(status().isCreated)

        previewOf("DEPARTMENT", childDept.id!!)
            .andExpect(jsonPath("$.perMealLimitMinor").value(18000))
            .andExpect(jsonPath("$.sourceLabels.PER_MEAL_LIMIT").value("개발팀"))
            // 개발팀이 재정의하지 않은 dailyMealCount 는 **본부**에서 온다(조직 기본값이 아니라).
            .andExpect(jsonPath("$.dailyMealCount").value(2))
            .andExpect(jsonPath("$.sources.DAILY_MEAL_COUNT").value("DEPARTMENT"))
            .andExpect(jsonPath("$.sourceLabels.DAILY_MEAL_COUNT").value("본부"))
    }

    @Test
    fun `조직 기본값을 올리면 재정의하지 않은 필드는 자동으로 따라 오른다`() {
        createOverride(childDept.id!!, """"perMealLimitMinor":18000""").andExpect(status().isCreated)

        login(admin.email)
            .perform(
                put("/api/orgs/{orgId}/meal-policy", orgId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"perMealLimitMinor":13000,"dailyMealCount":3,"monthlyCapMinor":250000,
                    "breakfastStart":"07:00","breakfastEnd":"10:00","lunchStart":"11:00","lunchEnd":"14:00",
                    "dinnerStart":"17:00","dinnerEnd":"21:00"}""",
                    ).with(csrf()),
            ).andExpect(status().isOk)

        previewOf("DEPARTMENT", childDept.id!!)
            // 부서가 재정의한 값은 그대로.
            .andExpect(jsonPath("$.perMealLimitMinor").value(18000))
            // 재정의하지 않은 값은 인상분을 물려받는다 — 손으로 따라 고칠 필요가 없다.
            .andExpect(jsonPath("$.dailyMealCount").value(3))
            .andExpect(jsonPath("$.monthlyCapMinor").value(250000))
            .andExpect(jsonPath("$.lunchStart").value("11:00"))
    }

    @Test
    fun `부서 재정의가 사업장 재정의를 이긴다`() {
        createOverride(childDept.id!!, """"perMealLimitMinor":18000""").andExpect(status().isCreated)
        createSiteOverride(site.id!!, """"perMealLimitMinor":9000,"monthlyCapMinor":100000""")
            .andExpect(status().isCreated)

        val member = saveUser("mpo-both@example.com")
        val membership =
            membershipRepository.save(
                OrgMembership(orgId = orgId, userId = member.id!!, role = OrgRole.MEMBER.name),
            )
        membership.departmentId = childDept.id
        membership.siteId = site.id
        membershipRepository.save(membership)

        // 자격 조회는 멤버의 두 축을 모두 넘긴다 — 부서가 이기고, 부서가 안 정한 월 한도는 사업장에서 온다.
        login(member.email)
            .perform(get("/api/meal/entitlement").param("orgId", orgId.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.perMealLimitMinor").value(18000))
            .andExpect(jsonPath("$.perMealLimitSource").value("DEPARTMENT"))
            .andExpect(jsonPath("$.monthlyCapMinor").value(100000))
            .andExpect(jsonPath("$.monthlyCapSource").value("SITE"))
    }

    @Test
    fun `★재정의가 자격 조회(=승인 판정 입력)에 즉시 반영된다`() {
        val member = saveUser("mpo-member@example.com")
        val membership =
            membershipRepository.save(
                OrgMembership(orgId = orgId, userId = member.id!!, role = OrgRole.MEMBER.name),
            )
        membership.departmentId = childDept.id
        membershipRepository.save(membership)

        val session = login(member.email)
        session
            .perform(get("/api/meal/entitlement").param("orgId", orgId.toString()))
            .andExpect(jsonPath("$.perMealLimitMinor").value(12000))
            .andExpect(jsonPath("$.perMealLimitSource").value("ORG"))

        createOverride(childDept.id!!, """"perMealLimitMinor":18000,"dailyMealCount":3""")
            .andExpect(status().isCreated)

        session
            .perform(get("/api/meal/entitlement").param("orgId", orgId.toString()))
            .andExpect(jsonPath("$.perMealLimitMinor").value(18000))
            .andExpect(jsonPath("$.perMealLimitSource").value("DEPARTMENT"))
            .andExpect(jsonPath("$.dailyMealCount").value(3))
    }

    @Test
    fun `부서·사업장이 없는 멤버는 조직 기본값을 그대로 받는다(도입 전과 동일)`() {
        createOverride(childDept.id!!, """"perMealLimitMinor":18000""").andExpect(status().isCreated)
        val member = saveUser("mpo-unassigned@example.com")
        membershipRepository.save(OrgMembership(orgId = orgId, userId = member.id!!, role = OrgRole.MEMBER.name))

        login(member.email)
            .perform(get("/api/meal/entitlement").param("orgId", orgId.toString()))
            .andExpect(jsonPath("$.perMealLimitMinor").value(12000))
            .andExpect(jsonPath("$.perMealLimitSource").value("ORG"))
    }

    @Test
    fun `기간 한정 재정의는 기간 안에서만 상시 재정의를 이긴다`() {
        createOverride(childDept.id!!, """"perMealLimitMinor":18000""").andExpect(status().isCreated)
        val today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
        createOverride(
            childDept.id!!,
            """"perMealLimitMinor":30000,"effectiveFrom":"$today","effectiveTo":"$today","reason":"연말 회식"""",
        ).andExpect(status().isCreated)

        // 오늘은 기간 한정이 이긴다.
        previewOf("DEPARTMENT", childDept.id!!).andExpect(jsonPath("$.perMealLimitMinor").value(30000))
        // 기간 밖에서는 상시 재정의로 돌아간다.
        login(admin.email)
            .perform(
                get("/api/orgs/{orgId}/meal-policy/preview", orgId)
                    .param("scopeType", "DEPARTMENT")
                    .param("scopeId", childDept.id.toString())
                    .param("onDate", today.plusDays(7).toString()),
            ).andExpect(jsonPath("$.perMealLimitMinor").value(18000))
    }

    @Test
    fun `같은 대상에 상시 재정의를 두 번 만들 수 없다`() {
        createOverride(childDept.id!!, """"perMealLimitMinor":18000""").andExpect(status().isCreated)
        createOverride(childDept.id!!, """"perMealLimitMinor":19000""")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("MEAL_POLICY_OVERRIDE_EXISTS"))
    }

    @Test
    fun `아무것도 재정의하지 않는 항목은 거절된다`() {
        // 있어도 해석에 영향이 없어 "설정했는데 안 바뀐다"만 만든다.
        createOverride(childDept.id!!, """"reason":"의미 없음"""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
    }

    @Test
    fun `끼니창은 시작만 재정의할 수 없다(쌍이 원자 단위)`() {
        // 한쪽만 받으면 상위값과 짝이 맞지 않아 그 끼니가 조용히 사라진다.
        createOverride(childDept.id!!, """"lunchStart":"12:00"""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("점심")))
    }

    @Test
    fun `자정을 넘는 끼니창 재정의는 거절된다`() {
        createOverride(childDept.id!!, """"dinnerStart":"22:00","dinnerEnd":"02:00"""")
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `타 조직 부서에는 재정의를 붙일 수 없다`() {
        val otherOrg = organizationRepository.save(Organization(slug = "mpo-other", name = "다른 조직")).id!!
        val otherDept = departmentRepository.save(Department(orgId = otherOrg, name = "남의 부서"))

        createOverride(otherDept.id!!, """"perMealLimitMinor":18000""")
            .andExpect(status().isNotFound)
        assertThat(overrideRepository.count()).isZero()
    }

    @Test
    fun `일반 멤버는 재정의를 보지도 만들지도 못한다`() {
        val member = saveUser("mpo-plain@example.com")
        membershipRepository.save(OrgMembership(orgId = orgId, userId = member.id!!, role = OrgRole.MEMBER.name))

        val session = login(member.email)
        session.perform(get("/api/orgs/{orgId}/meal-policy/overrides", orgId)).andExpect(status().isForbidden)
        session
            .perform(
                post("/api/orgs/{orgId}/meal-policy/overrides", orgId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"scopeType":"DEPARTMENT","scopeId":"${childDept.id}","perMealLimitMinor":18000}""")
                    .with(csrf()),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `수정·삭제가 이력에 남고, 삭제 이력은 직전 값을 담는다`() {
        val body =
            createOverride(childDept.id!!, """"perMealLimitMinor":18000""")
                .andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        val id = UUID.fromString(Regex("\"id\":\"([^\"]+)\"").find(body)!!.groupValues[1])

        login(admin.email)
            .perform(
                put("/api/orgs/{orgId}/meal-policy/overrides/{id}", orgId, id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"scopeType":"DEPARTMENT","scopeId":"${childDept.id}","perMealLimitMinor":21000}""")
                    .with(csrf()),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.perMealLimitMinor").value(21000))

        login(admin.email)
            .perform(
                delete("/api/orgs/{orgId}/meal-policy/overrides/{id}", orgId, id).with(csrf()),
            ).andExpect(status().isNoContent)

        val revisions = revisionRepository.findByOrgIdOrderByRecordedAtDesc(orgId, PageRequest.of(0, 10))
        assertThat(revisions.map { it.changeType }).containsExactly("REMOVED", "UPDATED", "CREATED")
        // ★삭제 이력은 **직전 값**이어야 한다 — 지운 뒤에 만들면 그 값을 알 수 없다.
        assertThat(revisions.first().document).contains("21000")
        // 부서 이름 스냅샷 — 그 부서가 삭제된 뒤에도 이력이 무엇을 가리켰는지 읽힌다.
        assertThat(revisions.first().scopeLabel).isEqualTo("개발팀")
        assertThat(overrideRepository.count()).isZero()
    }

    @Test
    fun `부서를 삭제하면 그 재정의도 함께 사라진다(죽은 노드가 돈을 쓰지 않는다)`() {
        createOverride(childDept.id!!, """"perMealLimitMinor":18000""").andExpect(status().isCreated)
        assertThat(overrideRepository.count()).isEqualTo(1)

        departmentRepository.deleteById(childDept.id!!)
        departmentRepository.flush()

        assertThat(overrideRepository.count()).isZero()
        // 이력은 남는다 — live 는 정합, 이력은 불변이라는 두 테이블의 역할 차이다.
        assertThat(revisionRepository.count()).isEqualTo(1)
    }

    // ---- helpers ----

    private fun createOverride(
        departmentId: UUID,
        fields: String,
    ) = login(admin.email).perform(
        post("/api/orgs/{orgId}/meal-policy/overrides", orgId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"scopeType":"DEPARTMENT","scopeId":"$departmentId",$fields}""")
            .with(csrf()),
    )

    private fun createSiteOverride(
        siteId: UUID,
        fields: String,
    ) = login(admin.email).perform(
        post("/api/orgs/{orgId}/meal-policy/overrides", orgId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"scopeType":"SITE","scopeId":"$siteId",$fields}""")
            .with(csrf()),
    )

    private fun previewOf(
        scopeType: String,
        scopeId: UUID,
    ) = login(admin.email).perform(
        get("/api/orgs/{orgId}/meal-policy/preview", orgId)
            .param("scopeType", scopeType)
            .param("scopeId", scopeId.toString()),
    )

    private fun saveUser(email: String): User =
        userRepository.save(
            User(email = email, passwordHash = passwordEncoder.encode(password), emailVerified = true),
        )

    private fun login(email: String): WebSession {
        val session = webSession()
        session.perform(post("/login/identifier").param("email", email).with(csrf()))
        session
            .perform(post("/login/password").param("username", email).param("password", password).with(csrf()))
            .andExpect(status().is3xxRedirection)
        return session
    }
}
