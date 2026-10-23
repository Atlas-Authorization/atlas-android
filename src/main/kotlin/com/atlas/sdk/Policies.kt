package com.atlas.sdk

/*
 * Policy acceptance (terms of service, privacy, any policy) — the client half.
 *
 * Mirrors `@atlasauth/js` `policy-acceptance`: the wire types with tolerant
 * readers, a small state machine ([PolicyAcceptanceController]) behind a policy
 * UI, and the Frontend API calls (load the policies, accept them in a sign-up or
 * a held sign-in, accept from inside the app). This module is pure JVM, so it has
 * no prebuilt UI; draw the documents with your own toolkit from [AtlasPolicy.contentHtml]
 * (sanitised HTML) and drive the ticks through the controller.
 *
 * Display modes (a policy's `display`):
 *  - `checkbox`: a checkbox, combined into one sentence when several policies use it;
 *  - `scroll`: an unskippable dialog, accepted only after the end of the text is
 *    seen (or the text fits); declining cancels;
 *  - `link`: a clickwrap sentence under the submit button; submitting accepts.
 *
 * `scrolled_to_end` and `view_ms` are client-attested evidence the server records
 * beside the acceptance; neither is a security boundary.
 */

/** One policy's current version as the Frontend API serves it. */
data class AtlasPolicy(
    val key: String,
    val title: String,
    /** `checkbox`, `scroll` or `link`. */
    val display: String,
    val required: Boolean,
    /** `sign_up` or `sign_in_and_sign_up`. */
    val appliesTo: String,
    val versionId: String,
    val version: String,
    /** Sanitised HTML: what the user sees and what [contentSha256] covers. */
    val contentHtml: String?,
    val contentMarkdown: String?,
    val contentUrl: String?,
    val contentSha256: String,
    /** Epoch milliseconds. */
    val effectiveAt: Long?,
    val summaryOfChanges: String?,
    /** On a held sign-in's pending policy: whether an earlier version was accepted (so "what changed" applies). Null when unknown. */
    val reaccept: Boolean?,
) {
    /** Whether this policy has any content to show. */
    val hasContent: Boolean get() = contentHtml != null || contentMarkdown != null

    companion object {
        /** Read one policy from the wire (flat, or with a nested `version` / `current_version` object). */
        fun from(raw: JsonValue?): AtlasPolicy? {
            if (raw !is JsonValue.Obj) return null
            val nested = raw.obj("version") ?: raw.obj("current_version")
            fun str(v: JsonValue?): String? = (v as? JsonValue.Str)?.value?.takeIf { it.isNotEmpty() }
            fun pick(name: String): JsonValue? =
                raw.entries[name]?.takeIf { it !is JsonValue.Null } ?: nested?.entries?.get(name)
            val key = str(raw.entries["key"]) ?: str(raw.entries["policy"]) ?: return null
            val versionId = str(pick("version_id")) ?: str(nested?.entries?.get("id")) ?: return null
            val display = str(raw.entries["display"]).takeIf { it == "checkbox" || it == "scroll" || it == "link" } ?: "checkbox"
            val version = str(raw.entries["version"]) ?: str(nested?.entries?.get("version")) ?: ""
            fun ms(v: JsonValue?): Long? = when (v) {
                is JsonValue.Num -> v.value.toLong()
                is JsonValue.Str -> v.value.toLongOrNull()
                else -> null
            }
            return AtlasPolicy(
                key = key,
                title = str(raw.entries["title"]) ?: key,
                display = display,
                required = (raw.entries["required"] as? JsonValue.Bool)?.value != false,
                appliesTo = if (str(raw.entries["applies_to"]) == "sign_in_and_sign_up") "sign_in_and_sign_up" else "sign_up",
                versionId = versionId,
                version = version,
                contentHtml = str(pick("content_html")),
                contentMarkdown = str(pick("content_markdown")),
                contentUrl = str(pick("content_url")),
                contentSha256 = str(pick("content_sha256")) ?: "",
                effectiveAt = ms(pick("effective_at")),
                summaryOfChanges = str(pick("summary_of_changes")),
                reaccept = (raw.entries["reaccept"] as? JsonValue.Bool)?.value,
            )
        }

        /** Read a list of policies from any shape the API uses (`[..]`, `{policies}`, `{data}`). */
        fun listFrom(body: JsonValue?): List<AtlasPolicy> {
            val list = when (body) {
                is JsonValue.Arr -> body.items
                is JsonValue.Obj -> (body.arr("policies") ?: body.arr("data"))?.items
                else -> null
            } ?: return emptyList()
            return list.mapNotNull { from(it) }
        }
    }
}

/** The policies a held attempt is waiting on (`pending_policies`). */
fun pendingPoliciesOf(attempt: SignInAttempt?): List<AtlasPolicy> =
    attempt?.pendingPolicies.orEmpty().mapNotNull { AtlasPolicy.from(it) }

/** The legacy single legal checkbox (send `consent = true` on sign-up), when the instance has one. */
data class AtlasLegacyConsent(val required: Boolean, val termsUrl: String?, val privacyUrl: String?)

/** `GET /v1/client/policies`: the current version of every policy. */
data class AtlasPolicies(val policies: List<AtlasPolicy>, val legacyConsent: AtlasLegacyConsent?) {
    /** Only the policies a sign-in has to show. */
    val signIn: List<AtlasPolicy> get() = policies.filter { it.appliesTo == "sign_in_and_sign_up" }
}

/** A signed-in user's standing on one policy (`GET /v1/client/me/policies`). */
data class AtlasPolicyStanding(
    val key: String,
    val title: String,
    val acceptedVersion: String?,
    val currentVersion: String?,
    val needsReaccept: Boolean,
    /** Epoch milliseconds: sign-in still works until then. */
    val graceEndsAt: Long?,
    /** The next sign-in will stop for this policy. */
    val blocking: Boolean,
) {
    companion object {
        fun listFrom(body: JsonValue?): List<AtlasPolicyStanding> {
            val list = when (body) {
                is JsonValue.Arr -> body.items
                is JsonValue.Obj -> (body.arr("policies") ?: body.arr("data"))?.items
                else -> null
            } ?: return emptyList()
            return list.mapNotNull { item ->
                val key = item.string("key") ?: item.string("policy") ?: return@mapNotNull null
                AtlasPolicyStanding(
                    key = key,
                    title = item.string("title") ?: key,
                    acceptedVersion = item.string("accepted_version"),
                    currentVersion = item.string("current_version"),
                    needsReaccept = item.bool("needs_reaccept") == true,
                    graceEndsAt = item.long("grace_ends_at"),
                    blocking = item.bool("blocking") == true,
                )
            }
        }
    }
}

/** One entry of `policy_acceptances`. */
data class PolicyAcceptance(
    val policy: String,
    val versionId: String,
    val contentSha256: String,
    val display: String? = null,
    val scrolledToEnd: Boolean? = null,
    val viewMs: Long? = null,
    val locale: String? = null,
) {
    fun toJson(): JsonValue {
        val m = LinkedHashMap<String, JsonValue>()
        m["policy"] = JsonValue.Str(policy)
        m["version_id"] = JsonValue.Str(versionId)
        m["content_sha256"] = JsonValue.Str(contentSha256)
        display?.let { m["display"] = JsonValue.Str(it) }
        scrolledToEnd?.let { m["scrolled_to_end"] = JsonValue.Bool(it) }
        viewMs?.let { m["view_ms"] = JsonValue.Num(it.toDouble()) }
        locale?.let { m["locale"] = JsonValue.Str(it) }
        return JsonValue.Obj(m)
    }
}

/** The `policy_acceptances` JSON array, or null for none (so [jsonBody] omits it). */
internal fun policyAcceptancesJson(acceptances: List<PolicyAcceptance>?): JsonValue? =
    if (acceptances.isNullOrEmpty()) null else JsonValue.Arr(acceptances.map { it.toJson() })

/**
 * The `accept_policies` request body: the acceptances plus the proof of the held
 * attempt, the one-time `policy_acceptance_token` (else the attempt's
 * `poll_secret`). The attempt id alone is never enough.
 */
internal fun acceptPoliciesBody(
    acceptances: List<PolicyAcceptance>,
    policyToken: String?,
    pollSecret: String? = null,
): String = jsonBody(
    "policy_acceptances" to (policyAcceptancesJson(acceptances) ?: JsonValue.Arr(emptyList())),
    "policy_acceptance_token" to jstr(policyToken?.takeIf { it.isNotEmpty() }),
    "poll_secret" to jstr(if (policyToken.isNullOrEmpty()) pollSecret?.takeIf { it.isNotEmpty() } else null),
)

/** Whether the server refused with "accept the policies" (422 `POLICY_ACCEPTANCE_REQUIRED`). */
val AtlasError.isPolicyAcceptanceRequired: Boolean
    get() = (this as? AtlasError.Api)?.errors?.any { it.code == "POLICY_ACCEPTANCE_REQUIRED" } == true

/** [AtlasError.isPolicyAcceptanceRequired] for the thrown [AtlasException]. */
val AtlasException.isPolicyAcceptanceRequired: Boolean
    get() = error.isPolicyAcceptanceRequired

/** What a redirect, custom-tab or browser callback URL carries about an attempt. */
data class AtlasRedirectResult(
    val attemptId: String?,
    val status: String?,
    /** `__atlas_ticket`: a completed attempt's one-time ticket. */
    val ticket: String?,
    /** `__atlas_policy_token`: the proof that goes with `accept_policies` (only with `needs_policy_acceptance`). */
    val policyToken: String?,
) {
    /** Whether the attempt is held until policies are accepted. */
    val needsPolicyAcceptance: Boolean get() = status == "needs_policy_acceptance"

    companion object {
        /** Read the `__atlas_*` parameters of a callback URL (query or fragment); null when it carries none. */
        fun parse(url: String): AtlasRedirectResult? {
            val q = url.substringAfter('?', "").substringBefore('#')
            val f = url.substringAfter('#', "")
            val params = HashMap<String, String>()
            for (part in (f.split('&') + q.split('&'))) {
                if (part.isEmpty()) continue
                val k = part.substringBefore('=')
                val v = part.substringAfter('=', "")
                if (k.startsWith("__atlas_")) {
                    params[k] = runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
                }
            }
            val attempt = params["__atlas_attempt"]
            val status = params["__atlas_status"]
            val ticket = params["__atlas_ticket"]
            if (attempt == null && status == null && ticket == null) return null
            val token = if (status == "needs_policy_acceptance") params["__atlas_policy_token"] else null
            return AtlasRedirectResult(attempt, status, ticket, token)
        }
    }
}

// ---- Controller -----------------------------------------------------------

/**
 * The state behind a policy UI. Subscribe for changes; read [canSubmit] to enable
 * the submit button and [build] for the request body. State is kept per version
 * id, so a refreshed list (after a 422) keeps the ticks the user already made on
 * versions that did not change.
 *
 * Not thread-safe: use it from the thread that drives the UI.
 */
class PolicyAcceptanceController(
    policies: List<AtlasPolicy> = emptyList(),
    /** One checkbox for every checkbox-mode policy (default) or one each. */
    val combined: Boolean = true,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private class Entry(
        var checked: Boolean = false,
        var scrolledToEnd: Boolean = false,
        var viewMs: Long = 0,
        var openedAt: Long? = null,
    )

    private var list: List<AtlasPolicy> = emptyList()
    private val entries = HashMap<String, Entry>()
    private var open: String? = null
    private val listeners = LinkedHashSet<() -> Unit>()

    init {
        setPolicies(policies)
    }

    /** Be told when anything changes. Returns the function that stops listening. */
    fun subscribe(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    private fun emit() {
        for (l in listeners.toList()) l()
    }

    private fun entry(key: String): Entry {
        val id = list.firstOrNull { it.key == key }?.versionId ?: key
        return entries.getOrPut(id) { Entry() }
    }

    val policies: List<AtlasPolicy> get() = list

    /** The document open in the viewer, or null. */
    val viewing: AtlasPolicy? get() = list.firstOrNull { it.key == open }

    /** Replace the policies (e.g. after a refresh); ticks on unchanged versions survive. */
    fun setPolicies(policies: List<AtlasPolicy>) {
        list = policies
        if (open != null && policies.none { it.key == open }) open = null
        emit()
    }

    fun checkboxPolicies(): List<AtlasPolicy> = list.filter { it.display == "checkbox" }
    fun scrollPolicies(): List<AtlasPolicy> = list.filter { it.display == "scroll" }
    fun linkPolicies(): List<AtlasPolicy> = list.filter { it.display == "link" }

    fun isChecked(key: String): Boolean {
        val p = list.firstOrNull { it.key == key } ?: return false
        // A clickwrap sentence is accepted by submitting.
        if (p.display == "link") return true
        return entry(key).checked
    }

    fun setChecked(key: String, checked: Boolean) {
        entry(key).checked = checked
        emit()
    }

    /** Whether every checkbox-mode policy is ticked (the combined box). */
    val combinedChecked: Boolean
        get() = checkboxPolicies().let { boxes -> boxes.isNotEmpty() && boxes.all { isChecked(it.key) } }

    /** Tick or untick every checkbox-mode policy at once (the combined box). */
    fun setAllChecked(checked: Boolean) {
        for (p in checkboxPolicies()) entry(p.key).checked = checked
        emit()
    }

    /** The user accepted a scroll-mode policy in its dialog. */
    fun acceptScroll(key: String) {
        val e = entry(key)
        e.checked = true
        e.scrolledToEnd = true
        emit()
    }

    /** The first scroll-mode policy still to be read and accepted, or null. */
    fun pendingScroll(): AtlasPolicy? = scrollPolicies().firstOrNull { !isChecked(it.key) }

    /** Open a document in the viewer and start its reading clock. */
    fun openDocument(key: String) {
        if (open != null) closeDocument()
        if (list.none { it.key == key }) return
        open = key
        entry(key).openedAt = now()
        emit()
    }

    fun closeDocument() {
        val k = open ?: return
        stopClock(k)
        open = null
        emit()
    }

    /** Start the reading clock for a policy shown some other way (a dialog). */
    fun startClock(key: String) {
        val e = entry(key)
        if (e.openedAt == null) e.openedAt = now()
    }

    fun stopClock(key: String) {
        val e = entry(key)
        val at = e.openedAt ?: return
        e.viewMs += maxOf(0L, now() - at)
        e.openedAt = null
    }

    /** The end of the document was reached while reading [key]. */
    fun markScrolledToEnd(key: String) {
        val e = entry(key)
        if (!e.scrolledToEnd) {
            e.scrolledToEnd = true
            emit()
        }
    }

    fun hasScrolledToEnd(key: String): Boolean = entry(key).scrolledToEnd

    fun viewMs(key: String): Long {
        val e = entry(key)
        val running = e.openedAt?.let { maxOf(0L, now() - it) } ?: 0L
        return e.viewMs + running
    }

    /** The required policies still unaccepted. */
    fun missing(): List<AtlasPolicy> = list.filter { it.required && !isChecked(it.key) }

    /** Whether every required policy is accepted. */
    fun satisfied(): Boolean = missing().isEmpty()

    /** Whether the submit button may be enabled: scroll-mode policies are read on submit, so they do not block it. */
    fun canSubmit(): Boolean = list.all { !it.required || it.display == "scroll" || isChecked(it.key) }

    /** The `policy_acceptances` body: every accepted policy (and every clickwrap one). */
    fun build(locale: String? = null): List<PolicyAcceptance> {
        val out = ArrayList<PolicyAcceptance>()
        for (p in list) {
            if (!isChecked(p.key)) continue
            val e = entry(p.key)
            val ms = viewMs(p.key)
            out.add(
                PolicyAcceptance(
                    policy = p.key,
                    versionId = p.versionId,
                    contentSha256 = p.contentSha256,
                    display = p.display,
                    scrolledToEnd = if (p.display == "scroll" || e.scrolledToEnd) e.scrolledToEnd else null,
                    viewMs = if (ms > 0) ms else null,
                    locale = locale,
                ),
            )
        }
        return out
    }
}

// ---- Client calls -----------------------------------------------------------

private fun localeQuery(locale: String?): String =
    if (locale.isNullOrEmpty()) "" else "?locale=" + java.net.URLEncoder.encode(locale, "UTF-8")

/** `GET /v1/client/policies`: the current version of every policy (publishable key only). */
suspend fun AtlasClient.policies(locale: String? = null): AtlasPolicies {
    val root = clientRequest("GET", "/v1/client/policies${localeQuery(locale)}", null)
    val legacy = root.obj("legacy_consent")?.let {
        AtlasLegacyConsent(
            required = it.bool("required") == true,
            termsUrl = it.string("terms_url"),
            privacyUrl = it.string("privacy_url"),
        )
    }
    return AtlasPolicies(AtlasPolicy.listFrom(root), legacy)
}

/** `GET /v1/client/me/policies`: the signed-in user's standing on every policy. */
suspend fun AtlasClient.myPolicies(): List<AtlasPolicyStanding> =
    AtlasPolicyStanding.listFrom(authedRequest("GET", "/v1/client/me/policies", null))

/** `POST /v1/client/me/policies/accept`: accept from inside the app (a banner during a grace window). */
suspend fun AtlasClient.acceptMyPolicies(acceptances: List<PolicyAcceptance>) {
    authedRequest(
        "POST",
        "/v1/client/me/policies/accept",
        jsonBody("policy_acceptances" to JsonValue.Arr(acceptances.map { it.toJson() })),
    )
}

/**
 * A controller for the policies a sign-up must show: every policy with a published
 * version. Required ones block the submit; optional ones may stay unticked.
 */
suspend fun AtlasClient.signUpPolicyController(
    locale: String? = null,
    combined: Boolean = true,
): PolicyAcceptanceController = PolicyAcceptanceController(policies(locale).policies, combined)

/**
 * A controller for the policies a held attempt is waiting on, with their content
 * filled in from `GET /v1/client/policies` when the attempt only listed them. An
 * attempt resumed from a redirect carries no list: every required policy is shown
 * and the server checks each acceptance.
 */
suspend fun AtlasClient.policyController(
    attempt: SignInAttempt?,
    locale: String? = null,
    combined: Boolean = true,
): PolicyAcceptanceController {
    var pending = pendingPoliciesOf(attempt)
    if (pending.isEmpty()) {
        return PolicyAcceptanceController(policies(locale).policies.filter { it.required }, combined)
    }
    if (pending.any { !it.hasContent }) {
        val all = policies(locale).policies
        pending = pending.map { p ->
            val full = all.firstOrNull { it.key == p.key } ?: return@map p
            full.copy(
                title = p.title.takeIf { it != p.key } ?: full.title,
                display = p.display,
                versionId = p.versionId,
                version = p.version.ifEmpty { full.version },
                contentHtml = p.contentHtml ?: full.contentHtml,
                contentMarkdown = p.contentMarkdown ?: full.contentMarkdown,
                contentSha256 = p.contentSha256.ifEmpty { full.contentSha256 },
                summaryOfChanges = p.summaryOfChanges ?: full.summaryOfChanges,
                reaccept = p.reaccept,
            )
        }
    }
    // A sign-in acceptance is always required of the user, whatever the policy says elsewhere.
    return PolicyAcceptanceController(pending.map { it.copy(required = true) }, combined)
}

/**
 * After the server answered 422 `POLICY_ACCEPTANCE_REQUIRED` (a policy was
 * published or changed while the form was open): reload the policies into the
 * controller so the form re-shows what is needed now. Returns whether [error] was
 * that refusal.
 */
suspend fun AtlasClient.refreshPoliciesOnRequired(
    controller: PolicyAcceptanceController,
    error: AtlasException,
    locale: String? = null,
): Boolean {
    if (!error.isPolicyAcceptanceRequired) return false
    val fresh = policies(locale).policies
    if (fresh.isNotEmpty()) controller.setPolicies(fresh)
    return true
}

/**
 * Resume a sign-in the app was handed mid-stream: an OAuth, SSO or magic-link
 * redirect that came back `needs_policy_acceptance` (parse the callback URL with
 * [AtlasRedirectResult.parse]). The flow is seeded with the attempt id, its status
 * and the policy token the redirect carried, so [SignInFlow.acceptPolicies] can
 * continue it. Show the policies with [policyController].
 */
fun AtlasClient.resumeSignIn(redirect: AtlasRedirectResult): SignInFlow {
    val id = redirect.attemptId
        ?: throw AtlasException(AtlasError.Decoding("The redirect carries no attempt id."))
    return SignInFlow(
        this,
        SignInAttempt(
            id = id,
            status = redirect.status ?: "needs_policy_acceptance",
            identifier = null,
            supportedFirstFactors = null,
            authorizationUrl = null,
            createdSessionId = null,
            ticket = null,
            policyAcceptanceToken = redirect.policyToken,
        ),
    )
}

/**
 * In-app re-acceptance for a signed-in user: loads the standing, builds a
 * controller for the policies that need accepting, and posts the result.
 */
class PolicyGate(private val client: AtlasClient, private val locale: String? = null) {
    /** The policies that need re-acceptance (empty when none, or before [load]). */
    var pending: List<AtlasPolicyStanding> = emptyList()
        private set
    var loaded: Boolean = false
        private set

    suspend fun load() {
        pending = client.myPolicies().filter { it.needsReaccept }
        loaded = true
    }

    /** The soonest end of grace among the pending policies (epoch ms), or null. */
    val graceEndsAt: Long? get() = pending.mapNotNull { it.graceEndsAt }.minOrNull()

    /** A controller over the pending policies' current versions, all required. */
    suspend fun begin(): PolicyAcceptanceController {
        val wanted = pending.map { it.key }.toSet()
        val all = client.policies(locale).policies.filter { it.key in wanted }
        return PolicyAcceptanceController(all.map { it.copy(required = true) })
    }

    suspend fun accept(controller: PolicyAcceptanceController) {
        client.acceptMyPolicies(controller.build(locale))
        pending = emptyList()
    }
}

/** `2026-10-24` for an epoch-millisecond time, or an empty string. */
fun formatPolicyDate(epochMs: Long?): String {
    if (epochMs == null) return ""
    val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
    f.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return f.format(java.util.Date(epochMs))
}

/**
 * Whether a vertical scroller has reached its end. [maxValue] is the furthest
 * scroll offset (0 when the content fits, so a short document counts as read).
 */
fun isPolicyScrolledToEnd(value: Int, maxValue: Int, slack: Int = 2): Boolean =
    maxValue != Int.MAX_VALUE && value >= maxValue - slack
