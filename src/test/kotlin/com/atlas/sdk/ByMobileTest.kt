package com.atlas.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Upstream / SAML logout on sign-out and device approval from a signed-in app. */
class ByMobileTest {
    private fun client(transport: FakeTransport, store: TokenStore = InMemoryTokenStore()): AtlasClient =
        AtlasClient("pk_test_by", "clerk.example.com", store, transport, Dispatchers.Unconfined)

    private fun signedIn() = InMemoryTokenStore().also { it.save(AtlasSession("sess_1", "jwt", "rt")) }

    private fun body(request: HttpRequest): JsonValue = JsonValue.parse(request.body!!)

    @Test
    fun signOutFollowsTheLogoutUrl(): Unit = runBlocking {
        val t = FakeTransport()
        t.enqueue(200, """{"object":"session","upstream_logout_url":"https://idp.example.com/logout?x=1","saml_logout_url":"http://insecure.example.com/slo"}""")
        val store = signedIn()
        val c = client(t, store)
        val opened = mutableListOf<String>()
        c.logoutUrlOpener = { opened += it }
        val result = c.signOut(returnUrl = "https://app.example.com/bye")
        val req = t.recorded.single()
        assertEquals("https://clerk.example.com/v1/client/sessions/sess_1/revoke", req.url)
        assertEquals("https://app.example.com/bye", body(req).string("return_url"))
        assertTrue(req.headers["Cookie"]!!.contains("__session=jwt"))
        assertEquals("https://idp.example.com/logout?x=1", result.upstreamLogoutUrl)
        assertNull(result.samlLogoutUrl, "a non-https URL is dropped")
        assertEquals(listOf("https://idp.example.com/logout?x=1"), opened)
        assertNull(store.load())

        val t2 = FakeTransport()
        t2.enqueue(200, """{"saml_logout_url":"https://clerk.example.com/v1/sso/saml/sso_1/slo/start?request=a"}""")
        val c2 = client(t2, signedIn())
        c2.logoutUrlOpener = { fail("must not open when followLogoutUrl is false") }
        assertEquals("https://clerk.example.com/v1/sso/saml/sso_1/slo/start?request=a", c2.signOut(followLogoutUrl = false).logoutUrl)
    }

    @Test
    fun deviceApprovalLookupApproveDeny(): Unit = runBlocking {
        val t = FakeTransport()
        val da = """{"object":"device_authorization","id":"da_1","user_code":"WDJB-MJHT","status":"pending","client":{"client_id":"oc_tv","name":"TV","first_party":true},"scopes":[{"scope":"openid","description":"Sign you in"}],"audience":null,"requires_step_up":true,"created_at":1,"expires_at":2}"""
        t.enqueue(200, da)
        t.enqueue(200, da.replace("pending", "approved"))
        t.enqueue(200, da.replace("pending", "denied"))
        t.enqueue(403, """{"errors":[{"code":"STEP_UP_REQUIRED","message":"Re-authenticate."}]}""")
        val c = client(t, signedIn())
        val auth = c.lookupDeviceAuthorization(" wdjb mjht ")
        assertEquals("https://clerk.example.com/v1/client/me/device_authorizations/lookup", t.recorded[0].url)
        assertEquals("WDJB-MJHT", body(t.recorded[0]).string("user_code"))
        assertEquals("TV", auth.clientName)
        assertTrue(auth.requiresStepUp)
        assertEquals("Sign you in", auth.scopes.single().description)
        assertEquals("approved", c.approveDeviceAuthorization(auth).status)
        assertEquals("https://clerk.example.com/v1/client/me/device_authorizations/da_1/approve", t.recorded[1].url)
        assertEquals("denied", c.denyDeviceAuthorization(auth).status)
        assertEquals("https://clerk.example.com/v1/client/me/device_authorizations/da_1/deny", t.recorded[2].url)
        val e = assertFailsWith<AtlasException> { c.approveDeviceAuthorization(auth) }
        assertEquals("STEP_UP_REQUIRED", e.error.code)
        assertEquals("abc", formatUserCode("abc"))
        assertEquals("WDJB-MJHT", userCodeFromUrl("https://example.com/device?user_code=wdjbmjht"))
        assertNull(userCodeFromUrl("nope"))
        assertFailsWith<AtlasException> { client(FakeTransport()).lookupDeviceAuthorization("WDJB-MJHT") }
        Unit
    }
}
