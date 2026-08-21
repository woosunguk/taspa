package com.taspa.server.login

import com.taspa.server.credential.AccountLockoutService
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.domain.user.UserStatus
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.util.UUID
import org.springframework.security.core.userdetails.User as SpringUser

@Service
class LoginUserDetailsService(
    private val userRepository: UserRepository,
    private val accountLockoutService: AccountLockoutService,
    private val passwordEncoder: PasswordEncoder,
) : UserDetailsService {
    /**
     * 소셜 전용 계정(password_hash NULL) 대비 더미 bcrypt 해시. 기동 시 임의 값으로 1회 생성하므로
     * 어떤 입력과도 일치할 수 없고, 실제 bcrypt 검증 비용을 유지해 폼 로그인 실패가
     * 타이밍으로 "소셜 전용 계정" 여부를 드러내지 않는다. 실패 메시지도 기존 일반 메시지를 그대로 탄다.
     */
    private val dummyPasswordHash: String by lazy { passwordEncoder.encode(UUID.randomUUID().toString()) }

    override fun loadUserByUsername(username: String): UserDetails {
        val user =
            userRepository.findByEmail(username)
                ?: throw UsernameNotFoundException("User not found with email: $username")
        // 만료된 잠금은 여기서 자동 해제된다(카운터는 보존 — 리스크 신호 입력, AccountLockoutService KDoc).
        val locked = accountLockoutService.isAccountLocked(user)
        return SpringUser
            .builder()
            .username(user.email)
            .password(user.passwordHash ?: dummyPasswordHash)
            // ADMIN 도 항상 ROLE_USER 를 함께 가진다 — 일반 화면/API 접근이 역할에 따라 갈리지 않는다.
            .authorities(
                buildList {
                    add(SimpleGrantedAuthority("ROLE_USER"))
                    if (user.role == UserRole.ADMIN.name) {
                        add(SimpleGrantedAuthority("ROLE_ADMIN"))
                    }
                },
            ).accountLocked(locked)
            .disabled(user.status != UserStatus.ACTIVE.name)
            .build()
    }
}
