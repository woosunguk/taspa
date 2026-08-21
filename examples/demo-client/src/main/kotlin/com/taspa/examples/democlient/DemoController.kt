package com.taspa.examples.democlient

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class DemoController {

    @GetMapping("/")
    fun home(@AuthenticationPrincipal user: OidcUser?): String =
        if (user != null) "redirect:/me" else "home"

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal user: OidcUser, model: Model): String {
        model.addAttribute("name", user.fullName)
        model.addAttribute("email", user.email)
        model.addAttribute("emailVerified", user.emailVerified)
        model.addAttribute("preferredUsername", user.preferredUsername)
        // sub 은 안정적 UUID(users.id) — 이메일이 아니다. 계정 키로 저장할 값은 sub.
        model.addAttribute("sub", user.subject)
        model.addAttribute("idTokenClaims", user.idToken.claims)
        return "me"
    }
}
