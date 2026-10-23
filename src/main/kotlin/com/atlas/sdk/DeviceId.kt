package com.atlas.sdk

import java.util.UUID

/**
 * The stable device id the SDK sends on every start that can create an account
 * (email sign-up, social / id_token sign-ins), so the instance's
 * accounts-per-device abuse limit can count devices rather than networks — the
 * native peer of the JS SDK's `device_id`. Password and passwordless sign-ins
 * never create an account and do not send it.
 *
 * The id is random (`d_` + a UUID) and carries no hardware identifier.
 * [AtlasClient.deviceIdProvider] defaults to an id stable for the process; supply
 * one that reads your own persistent storage to keep it across launches.
 */
object DeviceId {
    /** The routes that carry `device_id` (the JS SDK's `DEVICE_ID_ROUTES`). */
    val ROUTES: Set<String> = setOf(
        "/v1/client/sign_ups",
        "/v1/client/sign_ins/oauth",
        "/v1/client/sign_ins/sso",
        "/v1/client/sign_ins/saml",
        "/v1/client/sign_ins/discourse",
        "/v1/client/sign_ins/id_token",
        "/v1/client/sign_ins/external_jwt",
        "/v1/client/sign_ins/ldap",
        "/v1/oauth/telegram",
        "/v1/oauth/siwe/verify",
        "/v1/oauth/siws/verify",
        "/v1/oauth/siwf/verify",
    )

    /** A fresh random device id. */
    fun fresh(): String = "d_" + UUID.randomUUID().toString().replace("-", "")

    private val processId: String by lazy { fresh() }

    /** An id stable for this process. */
    fun forProcess(): String = processId

    /** [body] with `device_id` added when [path] is a device-id route and the body lacks one. */
    internal fun stamp(method: String, path: String, body: String?, id: () -> String?): String? {
        if (method != "POST" || body == null || path.substringBefore('?') !in ROUTES) return body
        val obj = runCatching { JsonValue.parse(body) as? JsonValue.Obj }.getOrNull() ?: return body
        if ("device_id" in obj.entries) return body
        val value = id() ?: return body
        return encodeJsonValue(JsonValue.Obj(LinkedHashMap(obj.entries).apply { put("device_id", JsonValue.Str(value)) }))
    }
}
