package com.taspa.server.scim

import com.taspa.server.scim.dto.ScimListResponse
import com.taspa.server.scim.dto.ScimPatchRequest
import com.taspa.server.scim.dto.ScimUserRequest
import com.taspa.server.scim.dto.ScimUserResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * SCIM 2.0 Users 엔드포인트(RFC 7644 실용적 최소 — Azure AD/Okta 프로비저너 서브셋). 조직 3c.
 * 전용 베어러 체인(/scim/v2 이하 — SecurityConfig.scimApiSecurityFilterChain)에서 인증되고,
 * **테넌트는 URL 이 아니라 토큰의 org_id 클레임**이 앵커다(ScimAuthorization). Groups 는 범위 밖.
 * 요청 본문은 application/scim+json·application/json 모두 수용(*+json 컨버터 기본 지원),
 * 응답은 application/scim+json.
 */
@RestController
@RequestMapping("/scim/v2/Users", produces = [ScimMediaType.SCIM_JSON, "application/json"])
class ScimUserController(
    private val scimAuthorization: ScimAuthorization,
    private val scimUserService: ScimUserService,
) {
    @GetMapping
    fun list(
        authentication: Authentication?,
        @RequestParam(required = false) filter: String?,
        @RequestParam(required = false) startIndex: Int?,
        @RequestParam(required = false) count: Int?,
    ): ResponseEntity<ScimListResponse> {
        val ctx = scimAuthorization.authorize(authentication)
        return ResponseEntity.ok(scimUserService.list(ctx, filter, startIndex, count))
    }

    @GetMapping("/{id}")
    fun get(
        authentication: Authentication?,
        @PathVariable id: String,
    ): ResponseEntity<ScimUserResponse> {
        val ctx = scimAuthorization.authorize(authentication)
        return ResponseEntity.ok(scimUserService.get(ctx, id))
    }

    @PostMapping
    fun create(
        authentication: Authentication?,
        @RequestBody request: ScimUserRequest,
    ): ResponseEntity<ScimUserResponse> {
        val ctx = scimAuthorization.authorize(authentication)
        val created = scimUserService.create(ctx, request)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .header("Location", created.meta.location)
            .body(created)
    }

    @PutMapping("/{id}")
    fun replace(
        authentication: Authentication?,
        @PathVariable id: String,
        @RequestBody request: ScimUserRequest,
    ): ResponseEntity<ScimUserResponse> {
        val ctx = scimAuthorization.authorize(authentication)
        return ResponseEntity.ok(scimUserService.replace(ctx, id, request))
    }

    @PatchMapping("/{id}")
    fun patch(
        authentication: Authentication?,
        @PathVariable id: String,
        @RequestBody request: ScimPatchRequest,
    ): ResponseEntity<ScimUserResponse> {
        val ctx = scimAuthorization.authorize(authentication)
        return ResponseEntity.ok(scimUserService.patch(ctx, id, request))
    }

    @DeleteMapping("/{id}")
    fun delete(
        authentication: Authentication?,
        @PathVariable id: String,
    ): ResponseEntity<Void> {
        val ctx = scimAuthorization.authorize(authentication)
        scimUserService.delete(ctx, id)
        return ResponseEntity.noContent().build()
    }
}
