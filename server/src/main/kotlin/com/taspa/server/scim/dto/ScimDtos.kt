package com.taspa.server.scim.dto

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/** RFC 7643 enterprise 확장 스키마 URN — Azure AD/Okta 프로비저너가 HR 속성을 싣는 네임스페이스. */
const val ENTERPRISE_USER_SCHEMA = "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User"
const val CORE_USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User"
const val LIST_RESPONSE_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:ListResponse"
const val PATCH_OP_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:PatchOp"

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScimName(
    val formatted: String? = null,
    val givenName: String? = null,
    val familyName: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScimEmail(
    val value: String? = null,
    val type: String? = null,
    val primary: Boolean? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScimEnterpriseUser(
    val employeeNumber: String? = null,
    val title: String? = null,
    val department: String? = null,
)

/** POST/PUT /Users 요청 본문(실용적 최소 서브셋). 미지원 속성은 무해하게 무시한다(Azure AD 호환). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ScimUserRequest(
    val schemas: List<String>? = null,
    val userName: String? = null,
    val externalId: String? = null,
    val displayName: String? = null,
    val name: ScimName? = null,
    val emails: List<ScimEmail>? = null,
    val active: Boolean? = null,
    @JsonProperty(ENTERPRISE_USER_SCHEMA)
    val enterprise: ScimEnterpriseUser? = null,
) {
    /** displayName 우선, 없으면 name.formatted — POST 매핑 규약(과제 정의). */
    fun resolvedDisplayName(): String? =
        displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: name?.formatted?.trim()?.takeIf { it.isNotEmpty() }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ScimMeta(
    val resourceType: String,
    val location: String,
)

/** SCIM User 표현(응답 전용). password·내부 필드(비밀번호 해시·MFA·역할 등)는 절대 싣지 않는다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ScimUserResponse(
    val schemas: List<String>,
    val id: String,
    val externalId: String?,
    val userName: String,
    val displayName: String?,
    val active: Boolean,
    val emails: List<ScimEmail>,
    @JsonProperty(ENTERPRISE_USER_SCHEMA)
    val enterprise: ScimEnterpriseUser?,
    val meta: ScimMeta,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ScimListResponse(
    val schemas: List<String> = listOf(LIST_RESPONSE_SCHEMA),
    val totalResults: Int,
    val startIndex: Int,
    val itemsPerPage: Int,
    @JsonProperty("Resources")
    val resources: List<ScimUserResponse>,
)

/** RFC 7644 PatchOp — Azure AD 는 "Operations" 대문자로 보낸다(JsonAlias 수용). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ScimPatchRequest(
    val schemas: List<String>? = null,
    @JsonProperty("Operations")
    @JsonAlias("operations")
    val operations: List<ScimPatchOperation> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScimPatchOperation(
    val op: String? = null,
    val path: String? = null,
    val value: Any? = null,
)
