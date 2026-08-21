package com.taspa.server.domain.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    // 이메일은 자기서비스 이메일 변경(selfservice/EmailChangeService)으로 바뀔 수 있어 var 이다.
    // sub 은 users.id(UUID)로 안정화돼 있어(TokenCustomizerConfig) 이메일 변경이 OIDC 신원을 깨지 않는다.
    @Column(name = "email", nullable = false, unique = true)
    var email: String,
    /** 소셜 전용 계정은 비밀번호가 없다(NULL). 폼 로그인은 LoginUserDetailsService 의 더미 해시로 항상 실패한다. */
    @Column(name = "password_hash")
    var passwordHash: String? = null,
    @Column(name = "display_name", length = 100)
    var displayName: String? = null,
    @Column(name = "email_verified", nullable = false)
    var emailVerified: Boolean = false,
    @Column(name = "mfa_enabled", nullable = false)
    var mfaEnabled: Boolean = false,
    @Column(name = "mfa_secret_encrypted", length = 512)
    var mfaSecretEncrypted: String? = null,
    @Column(name = "status", nullable = false)
    var status: String = UserStatus.ACTIVE.name,
    @Column(name = "role", nullable = false, length = 16)
    var role: String = UserRole.USER.name,
    @Column(name = "failed_login_attempts", nullable = false)
    var failedLoginAttempts: Int = 0,
    @Column(name = "locked_until")
    var lockedUntil: Instant? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun onPreUpdate() {
        updatedAt = Instant.now()
    }

    companion object {
        /**
         * 이메일 길이 상한. users.email 은 VARCHAR(255)지만 이메일이 Spring Session 의
         * PRINCIPAL_NAME(VARCHAR(100), 공식 스키마 그대로)에 principal 로 인덱싱되므로,
         * 100자를 넘는 이메일 계정은 로그인(세션 커밋) 시점에 SQL 오류로 영구 로그인 불가가 된다.
         * 모든 계정 생성 경로(가입/소셜)는 이 상한을 강제해야 한다.
         */
        const val MAX_EMAIL_LENGTH = 100
    }
}
