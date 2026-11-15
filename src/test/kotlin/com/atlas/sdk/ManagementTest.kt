package com.atlas.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Billing, API keys, active organization, step-up and device-approval client calls. */
class ManagementTest {
    private fun client(t: FakeTransport, token: String = "jwt"): Pair<AtlasClient, InMemoryTokenStore> {
        val store = InMemoryTokenStore(AtlasSession("sess_1", token, "rt"))
        return AtlasClient("pk_test_123", "clerk.example.com", store, t, Dispatchers.Unconfined) to store
    }

    private fun body(r: HttpRequest): JsonValue = JsonValue.parse(r.body!!)

    private val plan = """{"id":"plan_pro","name":"Pro","slug":"pro","interval":"month","amount":1000,"currency":"usd","features":["SSO"],"audience":"user","annual_available":true,"annual_amount":"9600","trial_days":14,"promotion":{"label":"Launch","percent_off":50,"code":"LAUNCH"}}"""

    @Test fun plansDecodeAndSort() = runBlocking {
        val t = FakeTransport()
        t.enqueue(200, """{"data":[{"id":"b","name":"B","sort_order":2,"amount":0},{"id":"a","sort_order":1,"amount":"500"}]}""")
        val plans = client(t).first.billingPlans()
        assertEquals(listOf("a", "b"), plans.map { it.id })
        assertEquals(500.0, plans[0].amount)
        assertEquals("https://clerk.example.com/v1/client/billing/plans", t.recorded[0].url)
    }

    @Test fun priceViewAppliesPromotionAndAnnual() {
        val p = BillingPlan.from(JsonValue.parse(plan))
        val monthly = p.priceView("month", Locale.US)
        assertEquals(500L, monthly.amount)
        assertEquals("$10.00", monthly.originalPrice)
        assertEquals(14, monthly.trialDays)
        assertEquals("LAUNCH", monthly.promotionCode)
        val yearly = p.priceView("year", Locale.US)
        assertEquals("year", yearly.interval)
        assertEquals(4800L, yearly.amount)
        val expired = BillingPlan.from(JsonValue.parse(plan.replace("\"code\":\"LAUNCH\"", "\"code\":\"LAUNCH\",\"ends_at\":1000")))
        assertEquals(1000L, expired.priceView(nowMillis = 5000).amount)
    }

    @Test fun subscriptionOrgQueryAndAddons() = runBlocking {
        val t = FakeTransport()
        t.enqueue(200, """{"subject_type":"org","status":"active","id":"sub_1","cancel_at_period_end":true,"subscriptions":[{"id":"sub_1","status":"active"},{"id":"sub_2","status":"trialing","plan":{"id":"x","kind":"addon"}}]}""")
        val sub = client(t).first.billingSubscription("org_1")
        assertTrue(t.recorded[0].url.endsWith("/v1/client/billing/subscription?org_id=org_1"))
        assertTrue(sub.isLive)
        assertTrue(sub.cancelAtPeriodEnd)
        assertEquals(listOf("sub_2"), sub.liveAddons.map { it.id })
    }

    @Test fun neverSubscribedHasNullStatus() = runBlocking {
        val t = FakeTransport()
        t.enqueue(200, """{"subject_type":"user","status":null,"plan":null}""")
        val sub = client(t).first.billingSubscription()
        assertNull(sub.status)
        assertTrue(sub.all.isEmpty())
    }

    @Test fun checkoutPortalAndManageBodies() = runBlocking {
        val t = FakeTransport()
        val (c, _) = client(t)
        t.enqueue(200, """{"url":"https://checkout.example/s/1"}""")
        assertEquals("https://checkout.example/s/1", c.billingCheckout("plan_pro", "app://ok", "app://no", "org_1", "year", "LAUNCH"))
        val b = body(t.recorded[0])
        assertEquals("plan_pro", b.string("plan_id"))
        assertEquals("org_1", b.string("org_id"))
        assertEquals("year", b.string("interval"))
        assertEquals("LAUNCH", b.string("coupon_code"))
        t.enqueue(200, """{"url":"https://portal.example/p"}""")
        c.billingPortal("app://back", subscriptionId = "sub 2")
        assertEquals("sub 2", body(t.recorded[1]).string("subscription_id"))
        val sub = """{"subject_type":"user","status":"active"}"""
        t.enqueue(200, sub); c.billingCancel(immediately = true)
        assertTrue(t.recorded[2].url.endsWith("/v1/client/billing/subscription/cancel"))
        assertEquals(true, body(t.recorded[2]).bool("immediately"))
        t.enqueue(200, sub); c.billingRestore(subscriptionId = "sub_2")
        assertTrue(t.recorded[3].url.endsWith("/v1/client/billing/subscriptions/sub_2/restore"))
        t.enqueue(200, sub); c.billingChangePlan("plan_b", atPeriodEnd = true)
        assertTrue(t.recorded[4].url.endsWith("/v1/client/billing/subscription/change"))
        assertEquals(true, body(t.recorded[4]).bool("schedule_at_period_end"))
    }

    @Test fun invoicesCardCheckAndCoupon() = runBlocking {
        val t = FakeTransport()
        val (c, _) = client(t)
        t.enqueue(200, """{"data":[{"id":"in_1","number":"A-1","status":"paid","amount_due":1000,"currency":"usd","created_at":1700000000000}]}""")
        assertEquals("A-1", c.billingInvoices().first().number)
        t.enqueue(200, """{"payment_method":{"brand":"visa","last4":"4242","exp_month":4,"exp_year":2030}}""")
        assertEquals("4242", c.billingPaymentMethod()?.last4)
        t.enqueue(200, """{"payment_method":null}""")
        assertNull(c.billingPaymentMethod())
        t.enqueue(200, """{"feature":"seats","allowed":true,"remaining":3}""")
        assertTrue(c.billingCheck("seats", 2).allowed)
        assertTrue(t.recorded[3].url.endsWith("/v1/client/billing/check?feature=seats&required=2"))
        t.enqueue(200, """{"valid":true,"discount":{"percent_off":20,"duration":"repeating","duration_months":3}}""")
        val check = c.billingValidateCoupon("plan_pro", " LAUNCH ")
        assertEquals(3, check.durationMonths)
        assertEquals("LAUNCH", body(t.recorded[4]).string("code"))
    }

    private val key = """{"id":"ak_1","subject_type":"user","subject_id":"user_1","name":"CI","prefix":"ak_live_abcd","scopes":["calls:read"],"created_at":1700000000000}"""

    @Test fun apiKeyListCreateRenameRevoke() = runBlocking {
        val t = FakeTransport()
        val (c, _) = client(t)
        t.enqueue(200, """{"data":[$key],"allowed_scopes":["calls:read","calls:write"],"max_active_keys":5,"active_count":1}""")
        val list = c.listApiKeys("org_1", bound = true)
        assertTrue(t.recorded[0].url.endsWith("/v1/client/me/api_keys?organization_id=org_1&subject_type=user"))
        assertEquals(5, list.maxActiveKeys)
        assertTrue(list.keys[0].isActive())
        t.enqueue(201, key.dropLast(1) + ""","secret":"ak_live_secret"}""")
        val created = c.createApiKey("CI", expiresInSeconds = 86_400, scopes = listOf("calls:read"), organizationId = "org_1")
        assertEquals("ak_live_secret", created.secret)
        val b = body(t.recorded[1])
        assertEquals(86_400.0, b.number("expires_in_seconds"))
        assertEquals("org_1", b.string("organization_id"))
        assertNull(b.number("expires_at"))
        t.enqueue(200, key); c.renameApiKey("ak_1", null)
        assertEquals("PATCH", t.recorded[2].method)
        assertTrue(t.recorded[2].body!!.contains("\"name\":null"))
        t.enqueue(200, """{"id":"ak_1","revoked":true}""")
        c.revokeApiKey("ak_1")
        assertTrue(t.recorded[3].url.endsWith("/v1/client/me/api_keys/ak_1/revoke"))
    }

    @Test fun staleSessionSurfacesStepUp() = runBlocking {
        val t = FakeTransport()
        t.enqueue(403, """{"errors":[{"code":"STEP_UP_REQUIRED","message":"Re-authenticate"}]}""")
        val e = assertFailsWith<AtlasException> { client(t).first.createApiKey() }
        assertTrue(e.error.isStepUpRequired)
    }

    @Test fun stepUpPersistsFreshJwt() = runBlocking {
        val t = FakeTransport()
        t.enqueue(200, """{"id":"sess_1","jwt":"jwt_fresh"}""")
        val (c, store) = client(t)
        c.stepUp("password", password = "pw")
        assertEquals("password", body(t.recorded[0]).string("strategy"))
        assertEquals("jwt_fresh", store.load()?.token)
        assertEquals("rt", store.load()?.refreshToken)
    }

    @Test fun setActiveOrganizationPersistsRotatedJwt() = runBlocking {
        val t = FakeTransport()
        val (c, store) = client(t)
        t.enqueue(200, """{"id":"sess_1","jwt":"jwt_rot","last_active_organization_id":"org_1"}""")
        assertEquals("org_1", c.setActiveOrganization("org_1"))
        assertTrue(t.recorded[0].url.endsWith("/v1/client/sessions/sess_1/touch"))
        assertEquals("org_1", body(t.recorded[0]).string("active_organization_id"))
        assertEquals("jwt_rot", store.load()?.token)
        t.enqueue(200, """{"id":"sess_1","jwt":"j2","last_active_organization_id":null}""")
        assertNull(c.setActiveOrganization(null))
        assertTrue(t.recorded[1].body!!.contains("\"active_organization_id\":null"))
    }

    @Test fun activeOrganizationFromTokenClaim() {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"org_id":"org_9"}""".toByteArray())
        assertEquals("org_9", client(FakeTransport(), "h.$payload.s").first.activeOrganizationId())
        assertNull(client(FakeTransport(), "opaque").first.activeOrganizationId())
    }
}

class StepUpAndTransportTest {
    private fun client(t: FakeTransport): Pair<AtlasClient, InMemoryTokenStore> {
        val store = InMemoryTokenStore(AtlasSession("sess_1", "jwt", "rt"))
        return AtlasClient("pk_test_123", "clerk.example.com", store, t, Dispatchers.Unconfined) to store
    }

    @Test fun pushPendingThenApproved() = runBlocking {
        val t = FakeTransport()
        val (c, store) = client(t)
        t.enqueue(200, """{"number_match":"42"}""")
        assertEquals("42", c.beginPushStepUp())
        t.enqueue(202, """{"status":"pending"}""")
        assertTrue(!c.stepUpPush())
        assertEquals("jwt", store.load()?.token)
        t.enqueue(200, """{"id":"sess_1","jwt":"jwt_push"}""")
        assertTrue(c.stepUpPush())
        assertEquals("jwt_push", store.load()?.token)
    }

    @Test fun passkeyStepUpSendsAssertionWithoutHandle() = runBlocking {
        val t = FakeTransport()
        val (c, store) = client(t)
        t.enqueue(200, """{"challenge":"ch","handle":"h","rpId":"clerk.example.com"}""")
        t.enqueue(200, """{"id":"sess_1","jwt":"jwt_pk"}""")
        c.stepUpWithPasskey { options ->
            assertTrue(options.contains("\"challenge\":\"ch\""))
            """{"id":"cred","rawId":"cred","response":{"authenticatorData":"ad","clientDataJSON":"cd","signature":"sig"}}"""
        }
        val b = JsonValue.parse(t.recorded[1].body!!)
        assertEquals("passkey", b.string("strategy"))
        assertEquals("sig", b.string("signature"))
        assertNull(b.string("handle"))
        assertEquals("jwt_pk", store.load()?.token)
    }

    @Test fun patchWorksOverARealConnection() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/x") { ex ->
            val body = "${ex.requestMethod}:${String(ex.requestBody.readBytes())}".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val r = UrlConnectionTransport().execute(
                HttpRequest("PATCH", "http://127.0.0.1:${server.address.port}/x", mapOf("content-type" to "application/json"), "{\"a\":1}"),
            )
            assertEquals("PATCH:{\"a\":1}", r.body)
        } finally {
            server.stop(0)
        }
    }
}
