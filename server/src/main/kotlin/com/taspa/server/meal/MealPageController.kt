package com.taspa.server.meal

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/**
 * 직원 식권 페이지 — default 체인 anyRequest().authenticated() 로 보호된다(모든 로그인 사용자).
 * 데이터는 페이지 JS 가 /api/orgs/memberships(조직 선택)·/api/meal/qr(발급)·/api/meal/transactions
 * (이력)로 채운다. QR 화상화는 벤더링 정적 JS(/js/qrcode.js — 외부 CDN 없음)가 수행한다.
 */
@Controller
class MealPageController {
    @GetMapping("/meal")
    fun meal(): String = "meal"
}
