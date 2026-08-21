package com.taspa.server.scim

import com.taspa.server.scim.dto.CORE_USER_SCHEMA
import com.taspa.server.scim.dto.ENTERPRISE_USER_SCHEMA
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * SCIM 디스커버리(RFC 7644 §4) — 프로비저너가 기능 협상에 쓰는 정적 최소 응답.
 * patch/filter 지원, bulk/sort/etag 미지원을 정직하게 광고한다. Groups 리소스는 범위 밖이라 미광고.
 * 같은 베어러 체인 뒤에 있으므로 인가(ScimAuthorization)도 동일하게 요구한다(익명 노출면 없음).
 */
@RestController
@RequestMapping("/scim/v2", produces = [ScimMediaType.SCIM_JSON, "application/json"])
class ScimDiscoveryController(
    private val scimAuthorization: ScimAuthorization,
    @Value("\${taspa.issuer-uri}") private val issuerUri: String,
) {
    @GetMapping("/ServiceProviderConfig")
    fun serviceProviderConfig(authentication: Authentication?): ResponseEntity<Map<String, Any>> {
        scimAuthorization.authorize(authentication)
        return ResponseEntity.ok(
            mapOf(
                "schemas" to listOf("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"),
                "patch" to mapOf("supported" to true),
                "filter" to mapOf("supported" to true, "maxResults" to 200),
                "bulk" to mapOf("supported" to false, "maxOperations" to 0, "maxPayloadSize" to 0),
                "sort" to mapOf("supported" to false),
                "etag" to mapOf("supported" to false),
                "changePassword" to mapOf("supported" to false),
                "authenticationSchemes" to
                    listOf(
                        mapOf(
                            "type" to "oauthbearertoken",
                            "name" to "OAuth Bearer Token",
                            "description" to "OAuth2 client_credentials bearer token with org.scim scope",
                        ),
                    ),
                "meta" to
                    mapOf(
                        "resourceType" to "ServiceProviderConfig",
                        "location" to "$issuerUri/scim/v2/ServiceProviderConfig",
                    ),
            ),
        )
    }

    @GetMapping("/ResourceTypes")
    fun resourceTypes(authentication: Authentication?): ResponseEntity<Map<String, Any>> {
        scimAuthorization.authorize(authentication)
        val userType =
            mapOf(
                "schemas" to listOf("urn:ietf:params:scim:schemas:core:2.0:ResourceType"),
                "id" to "User",
                "name" to "User",
                "endpoint" to "/Users",
                "schema" to CORE_USER_SCHEMA,
                "schemaExtensions" to listOf(mapOf("schema" to ENTERPRISE_USER_SCHEMA, "required" to false)),
                "meta" to mapOf("resourceType" to "ResourceType", "location" to "$issuerUri/scim/v2/ResourceTypes/User"),
            )
        return ResponseEntity.ok(listResponse(listOf(userType)))
    }

    @GetMapping("/Schemas")
    fun schemas(authentication: Authentication?): ResponseEntity<Map<String, Any>> {
        scimAuthorization.authorize(authentication)
        val core =
            mapOf(
                "id" to CORE_USER_SCHEMA,
                "name" to "User",
                "description" to "User Account (minimal subset)",
                "attributes" to
                    listOf(
                        stringAttribute("userName", required = true),
                        stringAttribute("displayName", required = false),
                        stringAttribute("externalId", required = false),
                        mapOf("name" to "active", "type" to "boolean", "multiValued" to false, "required" to false),
                    ),
                "meta" to mapOf("resourceType" to "Schema", "location" to "$issuerUri/scim/v2/Schemas/$CORE_USER_SCHEMA"),
            )
        val enterprise =
            mapOf(
                "id" to ENTERPRISE_USER_SCHEMA,
                "name" to "EnterpriseUser",
                "description" to "Enterprise User Extension (minimal subset)",
                "attributes" to
                    listOf(
                        stringAttribute("employeeNumber", required = false),
                        stringAttribute("title", required = false),
                        stringAttribute("department", required = false),
                    ),
                "meta" to mapOf("resourceType" to "Schema", "location" to "$issuerUri/scim/v2/Schemas/$ENTERPRISE_USER_SCHEMA"),
            )
        return ResponseEntity.ok(listResponse(listOf(core, enterprise)))
    }

    private fun listResponse(resources: List<Map<String, Any>>): Map<String, Any> =
        mapOf(
            "schemas" to listOf("urn:ietf:params:scim:api:messages:2.0:ListResponse"),
            "totalResults" to resources.size,
            "startIndex" to 1,
            "itemsPerPage" to resources.size,
            "Resources" to resources,
        )

    private fun stringAttribute(
        name: String,
        required: Boolean,
    ): Map<String, Any> = mapOf("name" to name, "type" to "string", "multiValued" to false, "required" to required)
}
