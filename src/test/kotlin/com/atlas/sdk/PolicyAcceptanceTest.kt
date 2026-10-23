package com.atlas.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Policy acceptance: wire readers, the controller, the flow calls. Offline, FakeTransport only. */
class PolicyAcceptanceTest {

    private fun client(transport: FakeTransport, store: TokenStore = InMemoryTokenStore()) = AtlasClient(
        publishableKey = "pk_test_pol",
        frontendApi = "clerk.example.com",
        tokenStore = store,
        transport = transport,
        dispatcher = Dispatchers.Unconfined,
    )

    private fun signedIn() = InMemoryTokenStore(AtlasSession(sessionId = "s", token = "j", refreshToken = "rt"))

    private fun body(request: HttpRequest): JsonValue = JsonValue.parse(request.body!!)

    private fun policy(
        key: String = "tos",
        display: String = "checkbox",
        required: Boolean = true,
        html: String? = "<p>Terms</p>",
    ) = AtlasPolicy(
        key = key, title = key.uppercase(), display = display, required = required, appliesTo = "sign_up",
        versionId = "polv_$key", version = "2026-10-24", contentHtml = html, contentMarkdown = null, contentUrl = null,
        contentSha256 = "sha_$key", effectiveAt = null, summaryOfChanges = null, reaccept = null,
    )

    private val policiesJson = """
        {"object":"list","data":[
          {"object":"policy","key":"tos","title":"Terms of Service","display":"checkbox","required":true,"applies_to":"sign_in_and_sign_up",
           "version_id":"polv_1","version":"2026-10-24","content_html":"<p>Hello</p>","content_sha256":"abc","effective_at":1790000000000,"summary_of_changes":null},
          {"object":"policy","key":"privacy","title":"Privacy","display":"scroll","required":true,"applies_to":"sign_up",
           "version_id":"polv_2","version":"3","content_markdown":"# Privacy","content_html":null,"content_sha256":"def"}
        ],"legacy_consent":{"required":false,"terms_url":"https://x/t","privacy_url":null}}
    """.trimIndent()

    @Test
    fun policiesAreReadFromTheWire() = runBlocking {
        val t = FakeTransport()
        t.enqueue(200, policiesJson)
        val res = client(t).policies(locale = "fr")
        assertEquals("https://clerk.example.com/v1/client/policies?locale=fr", t.recorded[0].url)
        assertEquals("GET", t.recorded[0].method)
        assertEquals(2, res.policies.size)
        val tos = res.policies[0]
        assertEquals("polv_1", tos.versionId)
        assertEquals(1790000000000L, tos.effectiveAt)
        assertEquals(1, res.signIn.size)
        assertEquals("scroll", res.policies[1].display)
        assertNull(res.policies[1].contentHtml)
        assertEquals("# Privacy", res.policies[1].contentMarkdown)
        assertEquals("https://x/t", res.legacyConsent?.termsUrl)
    }

    @Test
    fun nestedVersionAndPolicyKeyShapesAreAccepted() {
        val p = assertNotNull(
            AtlasPolicy.from(
                JsonValue.parse("""{"policy":"tos","title":"T","version":{"id":"polv_9","version":"v9","content_html":"<p>x</p>","content_sha256":"zz"}}"""),
            ),
        )
        assertEquals("tos", p.key)
        assertEquals("polv_9", p.versionId)
        assertEquals("v9", p.version)
        assertEquals("checkbox", p.display)
        assertNull(AtlasPolicy.from(JsonValue.parse("""{"title":"no key"}""")))
    }

    @Test
    fun controllerBuildsAcceptancesAndGatesTheSubmit() {
        var clock = 1_000L
        val c = PolicyAcceptanceController(
            listOf(policy("tos"), policy("privacy", "scroll"), policy("cookies", "link"), policy("news", required = false)),
            now = { clock },
        )
        assertFalse(c.canSubmit())
        c.setAllChecked(true)
        assertTrue(c.canSubmit())
        assertFalse(c.satisfied())
        assertEquals("privacy", c.pendingScroll()?.key)

        c.startClock("privacy")
        clock += 4_000
        c.stopClock("privacy")
        c.acceptScroll("privacy")
        assertTrue(c.satisfied())

        val byKey = c.build().associateBy { it.policy }
        assertEquals(setOf("tos", "privacy", "cookies", "news"), byKey.keys)
        assertEquals(true, byKey.getValue("privacy").scrolledToEnd)
        assertEquals(4_000L, byKey.getValue("privacy").viewMs)
        assertNull(byKey.getValue("tos").viewMs)
        assertEquals("link", byKey.getValue("cookies").display)
    }

    @Test
    fun ticksSurviveARefreshOfUnchangedVersionsOnly() {
        val c = PolicyAcceptanceController(listOf(policy("tos"), policy("privacy")))
        c.setAllChecked(true)
        c.setPolicies(listOf(policy("tos"), policy("privacy").copy(versionId = "polv_new")))
        assertTrue(c.isChecked("tos"))
        assertFalse(c.isChecked("privacy"))
    }

    @Test
    fun readingClockRecordsViewMsWhileTheDocumentIsOpen() {
        var clock = 0L
        val c = PolicyAcceptanceController(listOf(policy("tos")), now = { clock })
        c.openDocument("tos")
        clock += 2_500
        c.closeDocument()
        c.setChecked("tos", true)
        assertEquals(2_500L, c.build().single().viewMs)
    }

    @Test
    fun beginSignUpSendsPolicyAcceptancesAndOmitsThemWhenNone() = runBlocking {
        val t = FakeTransport()
        t.enqueue(201, """{"id":"sua_1","status":"needs_email_verification"}""")
        t.enqueue(201, """{"id":"sua_2","status":"needs_email_verification"}""")
        val c = PolicyAcceptanceController(listOf(policy("tos")))
        c.setAllChecked(true)
        val atlas = client(t)
        atlas.beginSignUp("a@b.co", "pw", policyAcceptances = c.build())
        val sent = assertNotNull(body(t.recorded[0]).arr("policy_acceptances")).items.single()
        assertEquals("tos", sent.string("policy"))
        assertEquals("polv_tos", sent.string("version_id"))
        assertEquals("sha_tos", sent.string("content_sha256"))
        assertEquals("checkbox", sent.string("display"))
        atlas.beginSignUp("a@b.co", "pw")
        assertNull(body(t.recorded[1]).arr("policy_acceptances"))
    }

    @Test
    fun a422RefusalIsRecognisedAndReloadsThePolicies() = runBlocking {
        val t = FakeTransport()
        t.enqueue(422, """{"errors":[{"code":"POLICY_ACCEPTANCE_REQUIRED","message":"Accept the terms."}]}""")
        val atlas = client(t)
        val failure = assertFailsWith<AtlasException> { atlas.beginSignUp("a@b.co", "pw") }
        assertTrue(failure.isPolicyAcceptanceRequired)

        t.enqueue(200, policiesJson)
        val c = PolicyAcceptanceController()
        assertTrue(atlas.refreshPoliciesOnRequired(c, failure))
        assertEquals(2, c.policies.size)
        assertFalse(atlas.refreshPoliciesOnRequired(c, AtlasException(AtlasError.Api(400, listOf(AtlasErrorItem("VALIDATION_FAILED", "x"))))))
    }

    @Test
    fun heldAttemptMapsToAcceptPoliciesAndContinuesWithTheToken() = runBlocking {
        val t = FakeTransport()
        t.enqueue(201, """{"id":"sia_1","status":"needs_first_factor","supported_first_factors":["password"]}""")
        t.enqueue(
            200,
            """{"id":"sia_1","status":"needs_policy_acceptance","policy_acceptance_token":"pat_9",
               "pending_policies":[{"policy":"tos","title":"Terms","display":"scroll","version_id":"polv_1","version":"2","content_sha256":"h","reaccept":true,"summary_of_changes":"New clause"}]}""",
        )
        t.enqueue(200, """{"id":"sia_1","status":"complete","ticket":"tk_1"}""")
        val flow = client(t).beginSignIn("a@b.co")
        val step = flow.attemptPassword("pw")
        val held = step as SignInStep.AcceptPolicies
        val p = held.policies.single()
        assertEquals("scroll", p.display)
        assertEquals(true, p.reaccept)
        assertEquals("New clause", p.summaryOfChanges)
        assertFalse(p.hasContent)

        val done = flow.acceptPolicies(listOf(PolicyAcceptance("tos", "polv_1", "h", "scroll", scrolledToEnd = true, viewMs = 1200)))
        assertEquals(SignInStep.Done(null), done)
        val req = t.recorded[2]
        assertEquals("https://clerk.example.com/v1/client/sign_ins/sia_1/accept_policies", req.url)
        val b = body(req)
        assertEquals("pat_9", b.string("policy_acceptance_token"))
        assertNull(b.string("poll_secret"))
        val sent = assertNotNull(b.arr("policy_acceptances")).items.single()
        assertEquals(true, sent.bool("scrolled_to_end"))
        assertEquals(1200L, sent.long("view_ms"))
    }

    @Test
    fun policyControllerFillsInContentTheAttemptOnlyListed() = runBlocking {
        val attempt = SignInAttempt.from(
            JsonValue.parse(
                """{"id":"sia_1","status":"needs_policy_acceptance","policy_acceptance_token":"t",
                   "pending_policies":[{"policy":"tos","title":"Terms","display":"scroll","version_id":"polv_1","version":"2","content_sha256":"h","reaccept":true}]}""",
            ),
        )
        val t = FakeTransport()
        t.enqueue(200, policiesJson)
        val c = client(t).policyController(attempt)
        val p = c.policies.single()
        assertEquals("<p>Hello</p>", p.contentHtml)
        assertEquals("scroll", p.display)
        assertEquals("polv_1", p.versionId)
        assertEquals("Terms", p.title)
        assertTrue(p.required)
    }

    @Test
    fun aResumedRedirectShowsEveryRequiredPolicyAndContinuesWithItsToken() = runBlocking {
        val r = assertNotNull(
            AtlasRedirectResult.parse("myapp://cb?__atlas_attempt=sia_7&__atlas_status=needs_policy_acceptance&__atlas_policy_token=pat%2B1#x"),
        )
        assertEquals("sia_7", r.attemptId)
        assertTrue(r.needsPolicyAcceptance)
        assertEquals("pat+1", r.policyToken)
        assertNull(AtlasRedirectResult.parse("myapp://cb?foo=bar"))
        assertNull(assertNotNull(AtlasRedirectResult.parse("myapp://cb?__atlas_attempt=a&__atlas_status=complete&__atlas_policy_token=x")).policyToken)

        val t = FakeTransport()
        t.enqueue(200, policiesJson)
        t.enqueue(200, """{"id":"sia_7","status":"complete","ticket":"tk"}""")
        val atlas = client(t)
        val flow = atlas.resumeSignIn(r)
        assertTrue(flow.step is SignInStep.AcceptPolicies)
        assertEquals(listOf("tos", "privacy"), atlas.policyController(flow.attempt).policies.map { it.key })
        flow.acceptPolicies(listOf(PolicyAcceptance("tos", "v", "h")))
        assertEquals("https://clerk.example.com/v1/client/sign_ins/sia_7/accept_policies", t.recorded[1].url)
        assertEquals("pat+1", body(t.recorded[1]).string("policy_acceptance_token"))
    }

    @Test
    fun passwordResetHeldAtPoliciesContinues() = runBlocking {
        val t = FakeTransport()
        t.enqueue(201, """{"id":"pr_1","status":"needs_new_password"}""")
        t.enqueue(200, """{"id":"pr_1","status":"needs_policy_acceptance","policy_acceptance_token":"pat_r"}""")
        t.enqueue(200, """{"id":"pr_1","status":"complete","ticket":"tk"}""")
        val flow = client(t).beginPasswordReset("a@b.co")
        assertTrue(flow.setNewPassword("newpass") is PasswordResetStep.AcceptPolicies)
        flow.acceptPolicies(listOf(PolicyAcceptance("tos", "v", "h")))
        assertEquals("https://clerk.example.com/v1/client/sign_ins/pr_1/accept_policies", t.recorded[2].url)
        assertEquals("pat_r", body(t.recorded[2]).string("policy_acceptance_token"))
    }

    @Test
    fun myPoliciesAndInAppAcceptanceUseTheSessionRoutes() = runBlocking {
        val t = FakeTransport()
        val atlas = client(t, signedIn())
        t.enqueue(
            200,
            """{"object":"list","needs_acceptance":true,"blocking":false,"data":[
              {"policy":"tos","title":"Terms","accepted_version":"1","current_version":"2","needs_reaccept":true,"grace_ends_at":1790000000000,"blocking":false},
              {"policy":"privacy","title":"Privacy","needs_reaccept":false}]}""",
        )
        val mine = atlas.myPolicies()
        assertEquals("https://clerk.example.com/v1/client/me/policies", t.recorded[0].url)
        assertTrue(t.recorded[0].headers["Cookie"]!!.contains("rt"))
        assertTrue(mine[0].needsReaccept)
        assertEquals(1790000000000L, mine[0].graceEndsAt)
        assertFalse(mine[1].needsReaccept)

        t.enqueue(200, "{}")
        atlas.acceptMyPolicies(listOf(PolicyAcceptance("tos", "polv_2", "h", "scroll", scrolledToEnd = true, viewMs = 5000)))
        assertEquals("https://clerk.example.com/v1/client/me/policies/accept", t.recorded[1].url)
        val sent = assertNotNull(body(t.recorded[1]).arr("policy_acceptances")).items.single()
        assertEquals(true, sent.bool("scrolled_to_end"))
        assertEquals(5000L, sent.long("view_ms"))
    }

    @Test
    fun gateBuildsAControllerForOnlyTheStalePolicies() = runBlocking {
        val t = FakeTransport()
        val gate = PolicyGate(client(t, signedIn()))
        t.enqueue(200, """{"data":[{"policy":"tos","title":"Terms","needs_reaccept":true,"grace_ends_at":1790000000000},{"policy":"privacy","needs_reaccept":false}]}""")
        gate.load()
        assertEquals(1790000000000L, gate.graceEndsAt)
        t.enqueue(200, policiesJson)
        val c = gate.begin()
        assertEquals(listOf("tos"), c.policies.map { it.key })
        assertTrue(c.policies.single().required)
    }

    @Test
    fun dateAndScrollHelpers() {
        assertEquals("2026-10-24", formatPolicyDate(1_792_800_000_000L))
        assertEquals("", formatPolicyDate(null))
        assertTrue(isPolicyScrolledToEnd(0, 0))
        assertTrue(isPolicyScrolledToEnd(98, 100))
        assertFalse(isPolicyScrolledToEnd(10, 100))
        assertFalse(isPolicyScrolledToEnd(0, Int.MAX_VALUE))
    }
}
