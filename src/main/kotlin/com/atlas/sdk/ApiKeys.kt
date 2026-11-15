package com.atlas.sdk

import java.net.URLEncoder

/**
 * End-user API key self-service over `/v1/client/me/api_keys`: the signed-in
 * user's own `ak_` keys, or (with `organizationId`) an organization's. The feature
 * is off until the application enables it (a 403 on the list). Creating a key
 * needs a recent re-authentication: a stale session throws an [AtlasException]
 * whose `error.isStepUpRequired` is true; call [stepUp], then create again.
 */

/** An API key as its owner sees it. The secret is never part of it. */
data class UserApiKey(
    val id: String,
    /** `user` or `organization`. */
    val subjectType: String,
    val subjectId: String,
    val name: String?,
    /** The public `ak_` head: recognises a key, cannot be used as one. */
    val prefix: String,
    val type: String?,
    val scopes: List<String>?,
    val lastUsedAt: Double?,
    val expiresAt: Double?,
    val revokedAt: Double?,
    /** Why it was revoked when the owner did not. */
    val revokedReason: String?,
    val organizationId: String?,
    val createdAt: Double,
) {
    /** Whether the key still works at [nowMillis]. */
    fun isActive(nowMillis: Long = System.currentTimeMillis()): Boolean =
        revokedAt == null && (expiresAt == null || expiresAt > nowMillis)

    companion object {
        fun from(json: JsonValue) = UserApiKey(
            id = json.string("id") ?: throw AtlasException(AtlasError.Decoding("API key has no id.")),
            subjectType = json.string("subject_type") ?: "user",
            subjectId = json.string("subject_id") ?: "",
            name = json.string("name"),
            prefix = json.string("prefix") ?: "",
            type = json.string("type"),
            scopes = json.arr("scopes")?.items?.mapNotNull { (it as? JsonValue.Str)?.value },
            lastUsedAt = json.number("last_used_at"),
            expiresAt = json.number("expires_at"),
            revokedAt = json.number("revoked_at"),
            revokedReason = json.string("revoked_reason"),
            organizationId = json.string("organization_id"),
            createdAt = json.number("created_at") ?: 0.0,
        )
    }
}

/** The create response: the key plus its one-time [secret], shown exactly once. */
data class CreatedUserApiKey(val key: UserApiKey, val secret: String)

/** The user's keys with the limits of the instance. */
data class UserApiKeyList(
    val keys: List<UserApiKey>,
    /** The scopes a new key may carry. */
    val allowedScopes: List<String>,
    /** The most active keys this user (or organization) may hold; 0 when unknown. */
    val maxActiveKeys: Int,
    val activeCount: Int,
)

private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/**
 * The user's API keys, or an organization's with [organizationId]. With [bound],
 * your own keys bound to that organization instead.
 */
suspend fun AtlasClient.listApiKeys(organizationId: String? = null, type: String? = null, bound: Boolean = false): UserApiKeyList {
    val q = buildList {
        if (organizationId != null) add("organization_id=${enc(organizationId)}")
        if (bound && organizationId != null) add("subject_type=user")
        if (type != null) add("type=${enc(type)}")
    }.joinToString("&")
    val j = authedRequest("GET", "/v1/client/me/api_keys" + if (q.isEmpty()) "" else "?$q", null)
    return UserApiKeyList(
        keys = j.arr("data")?.items.orEmpty().map { UserApiKey.from(it) },
        allowedScopes = j.arr("allowed_scopes")?.items?.mapNotNull { (it as? JsonValue.Str)?.value } ?: emptyList(),
        maxActiveKeys = j.number("max_active_keys")?.toInt() ?: 0,
        activeCount = j.number("active_count")?.toInt() ?: 0,
    )
}

/**
 * Create a key. [expiresInSeconds] and [expiresAt] (epoch milliseconds) are
 * mutually exclusive. [organizationId] creates the organization's key; with
 * [bindToOrganization], your own key bound to that organization.
 */
suspend fun AtlasClient.createApiKey(
    name: String? = null,
    expiresInSeconds: Long? = null,
    expiresAt: Long? = null,
    scopes: List<String>? = null,
    organizationId: String? = null,
    bindToOrganization: Boolean = false,
    type: String? = null,
): CreatedUserApiKey {
    val j = authedRequest(
        "POST",
        "/v1/client/me/api_keys",
        jsonBody(
            "name" to jstr(name), "expires_in_seconds" to jnum(expiresInSeconds), "expires_at" to jnum(expiresAt),
            "scopes" to jarr(scopes), "organization_id" to jstr(organizationId),
            "subject_type" to jstr(if (bindToOrganization) "user" else null), "type" to jstr(type),
        ),
    )
    return CreatedUserApiKey(
        UserApiKey.from(j),
        j.string("secret") ?: throw AtlasException(AtlasError.Decoding("The server returned no key secret.")),
    )
}

/** Rename a key; null clears the name. */
suspend fun AtlasClient.renameApiKey(id: String, name: String?): UserApiKey =
    UserApiKey.from(authedRequest("PATCH", "/v1/client/me/api_keys/${enc(id)}", jsonBody("name" to (jstr(name) ?: JsonValue.Null))))

/** Revoke a key. Immediate and permanent. */
suspend fun AtlasClient.revokeApiKey(id: String) {
    authedRequest("POST", "/v1/client/me/api_keys/${enc(id)}/revoke", null)
}

/**
 * Switch the active organization for the current session
 * (`POST /v1/client/sessions/:id/touch`). Pass null for the personal workspace.
 * The re-minted JWT is persisted so later calls carry the new active-org claim.
 * Returns the new active organization id.
 */
suspend fun AtlasClient.setActiveOrganization(organizationId: String?): String? {
    val stored = tokenStore.load() ?: throw AtlasException(AtlasError.NotSignedIn)
    val j = authedRequest(
        "POST",
        "/v1/client/sessions/${enc(stored.sessionId)}/touch",
        jsonBody("active_organization_id" to (jstr(organizationId) ?: JsonValue.Null)),
    )
    // The touch response carries no new refresh token: keep the stored one.
    tokenStore.save(AtlasSession(j.string("id") ?: stored.sessionId, j.string("jwt") ?: stored.token, stored.refreshToken))
    return j.string("last_active_organization_id")
}

/**
 * The active organization id carried by the stored session token (`org_id`
 * claim), or null for the personal workspace or when signed out. Offline.
 */
fun AtlasClient.activeOrganizationId(): String? {
    val token = runCatching { tokenStore.load() }.getOrNull()?.token ?: return null
    return OidcClaims.fromJwt(token)?.orgId
}

/**
 * Step-up re-authentication (`POST /v1/client/me/step_up`): re-prove one
 * credential (`password`, `totp`, `backup_code`, `email_code`, `phone_code`)
 * before a sensitive action such as creating an API key. The fresh session token
 * is persisted. A pending push/duo answer (202) returns without one.
 */
suspend fun AtlasClient.stepUp(strategy: String, password: String? = null, code: String? = null) {
    val stored = tokenStore.load() ?: throw AtlasException(AtlasError.NotSignedIn)
    val j = authedRequest(
        "POST",
        "/v1/client/me/step_up",
        jsonBody("strategy" to jstr(strategy), "password" to jstr(password), "code" to jstr(code)),
    )
    persistStepUp(stored, j)
}

/** Persist the fresh session token a step-up answers with; false when it carried none (a push still pending, 202). */
internal fun AtlasClient.persistStepUp(stored: AtlasSession, j: JsonValue): Boolean {
    val jwt = j.string("jwt") ?: return false
    tokenStore.save(AtlasSession(j.string("id") ?: stored.sessionId, jwt, stored.refreshToken))
    return true
}

/**
 * Send the signed-in session a push step-up prompt (`POST /v1/client/me/step_up/push`;
 * 429 `LOCKED` while prompts are paused after denials). Returns the number to show
 * on this screen; the user taps it on their device. Then call [stepUpPush] until it returns true.
 */
suspend fun AtlasClient.beginPushStepUp(): String? =
    authedRequest("POST", "/v1/client/me/step_up/push", "{}").string("number_match")

/** Ask whether the user answered the push prompt: true once they approved (the fresh token is persisted), false while pending. */
suspend fun AtlasClient.stepUpPush(): Boolean {
    val stored = tokenStore.load() ?: throw AtlasException(AtlasError.NotSignedIn)
    return persistStepUp(stored, authedRequest("POST", "/v1/client/me/step_up", jsonBody("strategy" to jstr("push"))))
}

/** The `POST /me/step_up` body for a passkey assertion given the begin options and the platform's WebAuthn response JSON. */
internal fun stepUpPasskeyBody(beginJson: String, authenticationResponseJson: String): String {
    val begin = JsonValue.parse(beginJson)
    val assertion = JsonValue.parse(authenticationResponseJson)
    val response = assertion.obj("response") ?: throw AtlasException(AtlasError.Decoding("The authentication response had no \"response\" object."))
    fun need(v: String?, what: String) = v ?: throw AtlasException(AtlasError.Decoding("The passkey data had no $what."))
    return jsonBody(
        "strategy" to jstr("passkey"),
        "challenge" to jstr(need(begin.string("challenge"), "challenge")),
        "credential_id" to jstr(need(assertion.string("rawId") ?: assertion.string("id"), "credential id")),
        "authenticator_data" to jstr(need(response.string("authenticatorData"), "authenticatorData")),
        "client_data_json" to jstr(need(response.string("clientDataJSON"), "clientDataJSON")),
        "signature" to jstr(need(response.string("signature"), "signature")),
    )
}

/**
 * Step up with a passkey: `POST /v1/client/me/step_up/passkey` for the options,
 * then [getAssertion] runs the platform ceremony and returns the WebAuthn
 * authentication response JSON, then the assertion goes to `POST /v1/client/me/step_up`.
 * This SDK has no Android dependency, so plug Credential Manager in here:
 * `GetPublicKeyCredentialOption(options)` -> `PublicKeyCredential.authenticationResponseJson`.
 * The fresh session token is persisted.
 */
suspend fun AtlasClient.stepUpWithPasskey(getAssertion: suspend (optionsJson: String) -> String) {
    val stored = tokenStore.load() ?: throw AtlasException(AtlasError.NotSignedIn)
    val begin = authedRequest("POST", "/v1/client/me/step_up/passkey", "{}")
    val responseJson = getAssertion(encodeJsonValue(begin))
    persistStepUp(stored, authedRequest("POST", "/v1/client/me/step_up", stepUpPasskeyBody(encodeJsonValue(begin), responseJson)))
}
