package com.taspa.server.account

import com.taspa.server.account.dto.AccountResponse
import com.taspa.server.account.dto.SignupRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/accounts")
class AccountController(
    private val accountService: AccountService,
) {
    @PostMapping("/signup")
    fun signup(
        @Valid @RequestBody request: SignupRequest,
    ): ResponseEntity<AccountResponse> {
        val user = accountService.signup(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(user))
    }
}
