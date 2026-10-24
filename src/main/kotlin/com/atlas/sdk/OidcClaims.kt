package com.atlas.sdk

/** OIDC scope names for the token-exchange and sign-in helpers. */
object AtlasScopes {
    const val OPENID = "openid"
    const val PROFILE = "profile"
    const val EMAIL = "email"
    const val OFFLINE_ACCESS = "offline_access"

    /** Adds the `orgs` claim — `[{ id, slug, role }]`, at most 20 — to the ID token and userinfo. */
    const val ORGANIZATIONS = "organizations"

    /** [base] plus `organizations` (once), for helpers that take a scope list. */
    fun withOrganizations(base: List<String> = listOf(OPENID, PROFILE, EMAIL)): List<String> =
        if (ORGANIZATIONS in base) base else base + ORGANIZATIONS
}

/** One entry of the `orgs` claim (`organizations` scope). */
data class OidcOrganization(val id: String, val slug: String?, val role: String?)

/** The enterprise SSO connection a sign-in came through (`sso_*` claims). */
data class OidcSso(
    val connectionId: String,
    val tenantId: String?,
    val issuer: String?,
    val domain: String?,
)

/**
 * The organization and enterprise-SSO claims of an Atlas ID token, access token or
 * userinfo response.
 *
 * - `org_id`, `org_slug`, `org_role`, `org_permissions`: the organization the
 *   sign-in applies to (absent when none applies).
 * - `orgs`: every organization, with the `organizations` scope.
 * - `sso_connection_id`, `sso_tenant_id`, `sso_issuer`, `sso_domain`: present only
 *   for sign-ins through an enterprise SSO connection.
 */
data class OidcClaims(
    val orgId: String? = null,
    val orgSlug: String? = null,
    val orgRole: String? = null,
    val orgPermissions: List<String> = emptyList(),
    val orgs: List<OidcOrganization> = emptyList(),
    val sso: OidcSso? = null,
) {
    companion object {
        /** Read the claims off a decoded claims object (userinfo, introspection, or a JWT payload). */
        fun fromClaims(claims: JsonValue): OidcClaims {
            val permissions = claims.arr("org_permissions")?.items.orEmpty()
                .mapNotNull { (it as? JsonValue.Str)?.value }
            val orgs = claims.arr("orgs")?.items.orEmpty().mapNotNull { element ->
                val id = element.string("id") ?: return@mapNotNull null
                OidcOrganization(id, element.string("slug"), element.string("role"))
            }
            val sso = claims.string("sso_connection_id")?.let {
                OidcSso(it, claims.string("sso_tenant_id"), claims.string("sso_issuer"), claims.string("sso_domain"))
            }
            return OidcClaims(
                orgId = claims.string("org_id"),
                orgSlug = claims.string("org_slug"),
                orgRole = claims.string("org_role"),
                orgPermissions = permissions,
                orgs = orgs,
                sso = sso,
            )
        }

        /**
         * Read the claims off a JWT's payload. The signature is NOT verified here —
         * use this for display and routing on a token your app just received from
         * Atlas, never to authorize on a token that arrived from elsewhere. Returns
         * null for something that is not a JWT.
         */
        fun fromJwt(jwt: String): OidcClaims? {
            val parts = jwt.split('.')
            if (parts.size < 2) return null
            val payload = runCatching {
                java.util.Base64.getUrlDecoder().decode(parts[1].trimEnd('='))
            }.getOrNull() ?: return null
            val obj = runCatching { JsonValue.parse(String(payload, Charsets.UTF_8)) }.getOrNull() ?: return null
            if (obj !is JsonValue.Obj) return null
            return fromClaims(obj)
        }
    }
}
