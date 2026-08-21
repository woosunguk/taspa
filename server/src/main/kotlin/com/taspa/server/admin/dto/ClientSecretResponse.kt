package com.taspa.server.admin.dto

/** clientSecret 은 등록/재발급 응답에서 단 1회만 내려간다(저장은 bcrypt 해시). 목록/조회에는 없다. */
data class ClientSecretResponse(
    val client: AdminClientView,
    val clientSecret: String?,
)
