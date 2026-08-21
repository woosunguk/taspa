package com.taspa.server.domain.user

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface UserRepository : JpaRepository<User, UUID> {
    /**
     * 이메일 조회는 대소문자를 구분하지 않는다 — 소셜 공급자 클레임('Victim@Gmail.com')과
     * 로컬 가입 이메일('victim@gmail.com')이 대소문자만 달라 같은 사람의 계정이 둘로 갈라지는 것을
     * 막는다. 저장 시에는 항상 소문자로 정규화한다(AccountService.signup / FederationService.createSocialUser).
     */
    @Query("select u from User u where lower(u.email) = lower(:email)")
    fun findByEmail(
        @Param("email") email: String,
    ): User?

    @Query("select count(u) > 0 from User u where lower(u.email) = lower(:email)")
    fun existsByEmail(
        @Param("email") email: String,
    ): Boolean

    /**
     * 관리 콘솔 사용자 검색(이메일 부분일치, 대소문자 비구분).
     * 호출자는 검색어의 LIKE 와일드카드(%, _)와 이스케이프 문자(!)를 이스케이프해서 넘겨야 한다
     * (AdminUserService.escapeLike) — 안 하면 '_' 가 임의 1문자, '%' 가 전체 일치로 해석돼
     * 부분일치 결과가 틀어진다. ESCAPE '!' 라 Postgres 기본 이스케이프(역슬래시)도 리터럴로 취급된다.
     */
    @Query(
        "select u from User u where lower(u.email) like concat('%', lower(:email), '%') escape '!' " +
            "order by u.createdAt desc",
    )
    fun searchByEmailContains(
        @Param("email") email: String,
        pageable: Pageable,
    ): List<User>

    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): List<User>

    /**
     * 사용자 행 잠금(PESSIMISTIC_WRITE) 조회 — 로그인 수단 추가/삭제(소셜 해제, 패스키 삭제)의
     * "마지막 로그인 수단" 검증을 직렬화해 TOCTOU 로 수단 0개 계정이 생기는 것을 막는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    fun findByIdForUpdate(
        @Param("id") id: UUID,
    ): User?

    /**
     * 특정 역할·상태의 사용자 id 만. `IamLockoutGuard` 가 IAM 편집마다 호출하므로 **전체 사용자를
     * 적재하면 안 된다** — 플랫폼 관리자는 소수인데 users 는 전 고객사 임직원이다.
     */
    @Query("select u.id from User u where u.role = :role and u.status = :status")
    fun findIdsByRoleAndStatus(
        @Param("role") role: String,
        @Param("status") status: String,
    ): List<UUID>
}
