package com.taspa.server.admin.dto

/** 수정 가능 항목만 담는다 — client_id·유형(공개/기밀)·grant type 은 불변. */
data class ClientUpdateRequest(
    val clientName: String,
    val redirectUris: List<String> = emptyList(),
    val postLogoutRedirectUris: List<String> = emptyList(),
    val scopes: List<String> = emptyList(),
    /**
     * 선언 역할 이름. **null 이면 미전송 → 기존 값 유지**, 빈 목록이면 선언 해제(=`roles` 미발급).
     * 둘을 구분하지 않으면 이름을 건드릴 의사가 없던 수정 요청이 선언을 통째로 지운다.
     */
    val roleNames: List<String>? = null,
)
