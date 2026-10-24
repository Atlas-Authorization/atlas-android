package com.atlas.sdk

/**
 * One item of the §9.1 error envelope: `{ errors: [{ code, message, param?, meta? }] }`.
 * `message` is written for humans by the API and surfaced verbatim; `param`
 * attaches it to a field for inline form rendering.
 */
data class AtlasErrorItem(
    val code: String,
    val message: String,
    val param: String? = null,
    /** Structured detail some errors carry (e.g. `required_scope` on `INSUFFICIENT_SCOPE`). */
    val meta: JsonValue.Obj? = null,
    /** `ROUTE_NOT_FOUND` only: the closest documented paths. */
    val didYouMean: List<String> = emptyList(),
    /** `ROUTE_NOT_FOUND` only: the methods the path does support. */
    val allowedMethods: List<String> = emptyList(),
    /** Where the error code is documented (`https://atlasauth.net/errors/<code>`). */
    val docsUrl: String? = null,
)

/**
 * Every failure the SDK surfaces, as one sealed type so a caller has exactly one
 * thing to catch (wrapped in [AtlasException] where a throwable is needed).
 *
 * - [Api] — the server's §9.1 envelope with the HTTP status.
 * - [Transport] — a network/IO failure.
 * - [Decoding] — a malformed body; contract drift worth distinguishing.
 * - [NotSignedIn] — raised locally when an authenticated call has no session.
 */
sealed class AtlasError {
    data class Api(val httpStatus: Int, val errors: List<AtlasErrorItem>) : AtlasError()
    data class Transport(val detail: String) : AtlasError()
    data class Decoding(val detail: String) : AtlasError()
    data object NotSignedIn : AtlasError()

    /** The first server error code — the value most callers branch on. */
    val code: String?
        get() = (this as? Api)?.errors?.firstOrNull()?.code

    /** HTTP status for an [Api] error; null otherwise. */
    val status: Int?
        get() = (this as? Api)?.httpStatus

    /** The first server error code as a typed [AtlasErrorCode], when the SDK knows it. */
    val knownCode: AtlasErrorCode?
        get() = AtlasErrorCode.fromWire(code)

    /**
     * The app cannot send email (422 `EMAIL_DELIVERY_NOT_CONFIGURED`); nothing was
     * sent. [message] is the server's end-user wording — show it as-is and offer
     * another sign-in method.
     */
    val isEmailDeliveryNotConfigured: Boolean
        get() = knownCode == AtlasErrorCode.EMAIL_DELIVERY_NOT_CONFIGURED

    /**
     * The step needs a captcha (`CAPTCHA_REQUIRED`) or the one sent was refused
     * (`CAPTCHA_FAILED`): run the captcha and retry with its token.
     */
    val isCaptchaRequired: Boolean
        get() = knownCode == AtlasErrorCode.CAPTCHA_REQUIRED || knownCode == AtlasErrorCode.CAPTCHA_FAILED

    /**
     * 403 `INSUFFICIENT_SCOPE`: the key or token lacks a scope. The older
     * `SCOPE_MISSING` spelling is still tolerated. [requiredScope] names the scope.
     */
    val isInsufficientScope: Boolean
        get() = knownCode == AtlasErrorCode.INSUFFICIENT_SCOPE || knownCode == AtlasErrorCode.SCOPE_MISSING

    /** The scope an `INSUFFICIENT_SCOPE` refusal asks for (`meta.required_scope`); null otherwise. */
    val requiredScope: String?
        get() = (this as? Api)?.errors?.firstNotNullOfOrNull { it.meta?.string("required_scope") }

    /** 403 `STEP_UP_REQUIRED`: a recent re-authentication (MFA) is needed; run it and retry. */
    val isStepUpRequired: Boolean
        get() = knownCode == AtlasErrorCode.STEP_UP_REQUIRED

    /** 403 `ORIGIN_NOT_ALLOWED`: the request came from an origin the key or app does not allow. */
    val isOriginNotAllowed: Boolean
        get() = knownCode == AtlasErrorCode.ORIGIN_NOT_ALLOWED

    /** 404 `ROUTE_NOT_FOUND`: no such method + path (a missing object is `NOT_FOUND`). */
    val isRouteNotFound: Boolean
        get() = knownCode == AtlasErrorCode.ROUTE_NOT_FOUND

    /** `ROUTE_NOT_FOUND`: the closest documented paths; empty for any other error. */
    val didYouMean: List<String>
        get() = (this as? Api)?.errors?.firstOrNull { it.code == AtlasErrorCode.ROUTE_NOT_FOUND.wire }
            ?.didYouMean.orEmpty()

    /** `ROUTE_NOT_FOUND`: the methods the path does support; empty for any other error. */
    val allowedMethods: List<String>
        get() = (this as? Api)?.errors?.firstOrNull { it.code == AtlasErrorCode.ROUTE_NOT_FOUND.wire }
            ?.allowedMethods.orEmpty()

    /** A human-readable message, always non-null so it can go straight to UI. */
    val message: String
        get() = when (this) {
            is Api -> errors.firstOrNull()?.message ?: "The request failed (HTTP $status)."
            is Transport -> detail
            is Decoding -> detail
            NotSignedIn -> "You must be signed in."
        }

    companion object {
        /**
         * Decode the envelope from a non-2xx body. Falls back to a synthetic item
         * when the body is not the expected shape (a proxy page, an empty 500), so
         * a caller always gets a code to branch on rather than a parse crash.
         */
        fun fromResponse(status: Int, body: String): Api {
            val items = try {
                val root = JsonValue.parse(body)
                (root.arr("errors")?.items ?: emptyList()).mapNotNull { item ->
                    val code = item.string("code")
                    val message = item.string("message")
                    if (code != null && message != null) {
                        AtlasErrorItem(
                            code = code,
                            message = message,
                            param = item.string("param"),
                            meta = item.obj("meta"),
                            didYouMean = item.stringList("did_you_mean"),
                            allowedMethods = item.stringList("allowed_methods"),
                            docsUrl = item.string("docs_url"),
                        )
                    } else null
                }
            } catch (_: Exception) {
                emptyList()
            }
            return if (items.isNotEmpty()) {
                Api(status, items)
            } else {
                Api(status, listOf(AtlasErrorItem("unexpected", "The request failed (HTTP $status).")))
            }
        }
    }
}

/**
 * Server error codes the SDK names. [wire] is the code on the wire;
 * [AtlasError.code] still carries every code, known or not.
 */
enum class AtlasErrorCode(val wire: String) {
    /**
     * 422 — a flow would send an email (code, link, reset, verification) but the
     * app has no way to deliver it. Refused before anything is issued, so a form
     * never says "we sent you a code" when nothing will arrive.
     */
    EMAIL_DELIVERY_NOT_CONFIGURED("EMAIL_DELIVERY_NOT_CONFIGURED"),
    VALIDATION_FAILED("VALIDATION_FAILED"),
    RATE_LIMITED("RATE_LIMITED"),
    IDENTIFIER_NOT_FOUND("IDENTIFIER_NOT_FOUND"),
    IDENTIFIER_EXISTS("IDENTIFIER_EXISTS"),
    VERIFICATION_EXPIRED("VERIFICATION_EXPIRED"),
    VERIFICATION_FAILED("VERIFICATION_FAILED"),
    EMAIL_NOT_VERIFIED("EMAIL_NOT_VERIFIED"),
    TOO_MANY_ATTEMPTS("TOO_MANY_ATTEMPTS"),
    PASSWORD_BREACHED("PASSWORD_BREACHED"),
    PASSWORD_POLICY("PASSWORD_POLICY"),
    LOCKED("LOCKED"),

    /** 400 — the instance challenges this step and no `captcha_token` was sent. */
    CAPTCHA_REQUIRED("CAPTCHA_REQUIRED"),

    /** 400 — the `captcha_token` sent was refused. */
    CAPTCHA_FAILED("CAPTCHA_FAILED"),

    /** 403 — the step needs a challenge but no captcha provider is configured. */
    CHALLENGE_UNAVAILABLE("CHALLENGE_UNAVAILABLE"),

    /** 403 — the key or token lacks a scope; `meta.required_scope` names it. */
    INSUFFICIENT_SCOPE("INSUFFICIENT_SCOPE"),

    /** The pre-2026-10 spelling of [INSUFFICIENT_SCOPE]; still tolerated. */
    SCOPE_MISSING("SCOPE_MISSING"),

    /** 403 — the request origin is not one the key or app allows. */
    ORIGIN_NOT_ALLOWED("ORIGIN_NOT_ALLOWED"),

    /** 403 — a recent MFA / re-authentication is required first. */
    STEP_UP_REQUIRED("STEP_UP_REQUIRED"),

    /** 404 — no such method + path; carries `did_you_mean` / `allowed_methods`. */
    ROUTE_NOT_FOUND("ROUTE_NOT_FOUND"),
    ;

    companion object {
        /** The known code for a wire value, or null. */
        fun fromWire(code: String?): AtlasErrorCode? = entries.firstOrNull { it.wire == code }
    }
}

/** The throwable carrier for an [AtlasError]. */
class AtlasException(val error: AtlasError) : Exception(error.message)

private fun JsonValue.stringList(key: String): List<String> =
    arr(key)?.items?.mapNotNull { (it as? JsonValue.Str)?.value } ?: emptyList()
