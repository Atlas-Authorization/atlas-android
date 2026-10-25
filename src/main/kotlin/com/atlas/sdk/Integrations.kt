package com.atlas.sdk

import java.net.URLEncoder

/**
 * A short-lived token for a configured identity integration — what
 * [AtlasClient.getToken] resolves. Any service that verifies the user with a JWT
 * (the built-in presets `chatly`, `forumulate`, `cookiemunch`, `referalli`, or a
 * custom integration id) accepts it as the user's identity.
 */
data class IntegrationToken(
    val jwt: String,
    val expiresIn: Int,
    /** The integration id it was minted for (a preset or a custom id). */
    val integration: String,
    /** The token's `aud`. */
    val audience: String,
    /** Forumulate preset: the organization the reader belongs to. */
    val orgId: String? = null,
    /** Forumulate preset: the space the token is for. */
    val spaceId: String? = null,
    /** Forumulate preset: where the reader's browser redeems the token. */
    val redeemUrl: String? = null,
)

/** An integration token for a Sign in with Atlas user (see [AtlasClient.getAtlasIdToken]). */
data class AtlasIdIntegrationToken(
    val jwt: String,
    val expiresIn: Int,
    val integration: String,
    val audience: String,
    /** The Sign in with Atlas issuer: the token's `iss`. */
    val issuer: String,
    /** Where the receiving service fetches the keys to verify it. */
    val jwksUri: String,
)

private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/** The query string for a per-call audience (and any extra parameters): `?a=b&...`, or empty. */
internal fun integrationQuery(
    spaceId: String? = null,
    cbid: String? = null,
    audience: String? = null,
    audienceParams: Map<String, String> = emptyMap(),
): String {
    val parts = ArrayList<String>()
    if (!spaceId.isNullOrEmpty()) parts.add("space_id=${enc(spaceId)}")
    for (key in audienceParams.keys.sorted()) {
        val value = audienceParams[key]
        if (!value.isNullOrEmpty()) parts.add("${enc(key)}=${enc(value)}")
    }
    if (!cbid.isNullOrEmpty()) parts.add("cbid=${enc(cbid)}")
    if (!audience.isNullOrEmpty()) parts.add("audience=${enc(audience)}")
    return if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
}

/**
 * `getToken({ for })` for the signed-in session
 * (`POST /v1/client/sessions/:id/integration_tokens/:integration`): a short-lived
 * token for a configured identity integration. The stored refresh cookie is
 * presented, never rotated.
 *
 * Returns null when the integration is not enabled (404). A refusal such as 401
 * or 403 throws [AtlasException].
 *
 * @param integration the integration id (a preset such as `chatly`, or a custom id).
 * @param spaceId Forumulate preset only: the space to mint the reader token for.
 * @param cbid the per-call audience value for an integration whose `per_call_audience` is keyed on `cbid`.
 * @param audience the full audience to request; it must match the integration's per-call audience template or allow-list.
 * @param audienceParams extra named values for a per-call audience template (for example `mapOf("tenant" to "t2")`).
 *
 * An integration with a per-call audience mints a token only when the call supplies
 * a matching value; otherwise no token is minted.
 */
suspend fun AtlasClient.getToken(
    integration: String,
    spaceId: String? = null,
    cbid: String? = null,
    audience: String? = null,
    audienceParams: Map<String, String> = emptyMap(),
): IntegrationToken? {
    val stored = tokenStore.load() ?: throw AtlasException(AtlasError.NotSignedIn)
    val query = integrationQuery(spaceId, cbid, audience, audienceParams)
    val json = try {
        authedRequest(
            "POST",
            "/v1/client/sessions/${enc(stored.sessionId)}/integration_tokens/${enc(integration)}$query",
            null,
        )
    } catch (e: AtlasException) {
        if ((e.error as? AtlasError.Api)?.httpStatus == 404) return null
        throw e
    }
    val jwt = json.string("jwt") ?: throw AtlasException(AtlasError.Decoding("The server returned no jwt."))
    return IntegrationToken(
        jwt = jwt,
        expiresIn = json.number("expires_in")?.toInt() ?: 0,
        integration = json.string("integration") ?: integration,
        audience = json.string("audience") ?: "",
        orgId = json.string("org_id"),
        spaceId = json.string("space_id"),
        redeemUrl = json.string("redeem_url"),
    )
}

/**
 * `getToken({ for })` for a user who signed in with Sign in with Atlas (not a
 * session of this instance), from an app with no server of its own
 * (`POST /v1/client/integrations/:integration/atlas_id_tokens`).
 *
 * The user's Sign in with Atlas access token goes as `Authorization: Bearer`, or
 * as `DPoP` with [dpopProof] when the token is DPoP-bound. Atlas checks the
 * request's `Origin` against the web origins of the Sign in with Atlas app the
 * token was issued to; a native app that shares that app's registration passes
 * one of those origins as [origin]. No cookie is sent.
 *
 * Returns null when the integration is not enabled for Sign in with Atlas users
 * (404). Other refusals — 401 for a token Atlas does not accept, 403
 * `ORIGIN_NOT_ALLOWED` — throw [AtlasException].
 */
suspend fun AtlasClient.getAtlasIdToken(
    integration: String,
    accessToken: String,
    origin: String? = null,
    dpopProof: String? = null,
    cbid: String? = null,
    audience: String? = null,
    audienceParams: Map<String, String> = emptyMap(),
): AtlasIdIntegrationToken? {
    val headers = LinkedHashMap<String, String>()
    if (dpopProof != null) {
        headers["Authorization"] = "DPoP $accessToken"
        headers["DPoP"] = dpopProof
    } else {
        headers["Authorization"] = "Bearer $accessToken"
    }
    if (!origin.isNullOrEmpty()) headers["Origin"] = origin
    val json = try {
        headerRequest(
            "POST",
            "/v1/client/integrations/${enc(integration)}/atlas_id_tokens?publishable_key=${enc(publishableKey)}" +
                integrationQuery(cbid = cbid, audience = audience, audienceParams = audienceParams).replaceFirst("?", "&"),
            null,
            headers,
        )
    } catch (e: AtlasException) {
        if ((e.error as? AtlasError.Api)?.httpStatus == 404) return null
        throw e
    }
    val jwt = json.string("jwt") ?: throw AtlasException(AtlasError.Decoding("The server returned no jwt."))
    return AtlasIdIntegrationToken(
        jwt = jwt,
        expiresIn = json.number("expires_in")?.toInt() ?: 0,
        integration = json.string("integration") ?: integration,
        audience = json.string("audience") ?: "",
        issuer = json.string("issuer") ?: "",
        jwksUri = json.string("jwks_uri") ?: "",
    )
}
