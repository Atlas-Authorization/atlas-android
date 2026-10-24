package com.atlas.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Integration `getToken`, the OIDC org / SSO claims, and the 2026-10 error codes. */
class IntegrationsTest {
    private fun client(transport: FakeTransport, signedIn: Boolean = true): AtlasClient {
        val store = if (signedIn) InMemoryTokenStore(AtlasSession("sess_1", "jwt_1", "rt_1")) else InMemoryTokenStore()
        return AtlasClient("atlas_test_pk_abc", "clerk.example.com", store, transport, Dispatchers.Unconfined)
    }

    private fun header(request: HttpRequest, name: String): String? =
        request.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    @Test
    fun getTokenPostsToTheSessionIntegrationRoute() = runBlocking {
        val t = FakeTransport()
        t.enqueue(200, """{"jwt":"t.t.t","expires_in":300,"integration":"chatly","audience":"chatly"}""")
        val token = client(t).getToken("chatly")
        assertNotNull(token)
        assertEquals("t.t.t", token.jwt)
        assertEquals(300, token.expiresIn)
        assertEquals("chatly", token.audience)
        val req = t.recorded[0]
        assertEquals("POST", req.method)
        assertEquals("https://clerk.example.com/v1/client/sessions/sess_1/integration_tokens/chatly", req.url)
        assertEquals("atlas_test_pk_abc", header(req, "x-publishable-key"))
        assertTrue(header(req, "Cookie")!!.contains("__atlas_rt=rt_1"))
    }

    @Test
    fun getTokenCarriesTheForumulateSpace() = runBlocking {
        val t = FakeTransport()
        t.enqueue(200, """{"jwt":"j","expires_in":60,"integration":"forumulate","audience":"a","org_id":"o1","space_id":"s 1","redeem_url":"https://f.example/redeem"}""")
        val token = client(t).getToken("forumulate", spaceId = "s 1")!!
        assertEquals("o1", token.orgId)
        assertEquals("https://f.example/redeem", token.redeemUrl)
        assertTrue(t.recorded[0].url.endsWith("/integration_tokens/forumulate?space_id=s%201"))
    }

    @Test
    fun getTokenIsNullWhenTheIntegrationIsNotEnabled() = runBlocking {
        val t = FakeTransport()
        t.enqueue(404, """{"errors":[{"code":"NOT_FOUND","message":"nope"}]}""")
        assertNull(client(t).getToken("chatly"))
    }

    @Test
    fun getTokenThrowsOnOtherRefusalsAndWithoutASession() = runBlocking {
        val t = FakeTransport()
        t.enqueue(401, """{"errors":[{"code":"UNAUTHENTICATED","message":"no"}]}""")
        val failure = assertFailsWith<AtlasException> { client(t).getToken("chatly") }
        assertEquals(401, failure.error.status)
        val missing = assertFailsWith<AtlasException> { client(t, signedIn = false).getToken("chatly") }
        assertEquals(AtlasError.NotSignedIn, missing.error)
        assertEquals(1, t.recorded.size)
    }

    @Test
    fun atlasIdTokenSendsBearerOriginAndKeyInQueryWithoutCookies() = runBlocking {
        val t = FakeTransport()
        t.enqueue(200, """{"jwt":"j","expires_in":300,"integration":"referalli","audience":"r","issuer":"https://atlasauth.net","jwks_uri":"https://atlasauth.net/jwks"}""")
        val token = client(t).getAtlasIdToken("referalli", "at_123", origin = "https://app.example")!!
        assertEquals("https://atlasauth.net", token.issuer)
        assertEquals("https://atlasauth.net/jwks", token.jwksUri)
        val req = t.recorded[0]
        assertTrue(req.url.endsWith("/v1/client/integrations/referalli/atlas_id_tokens?publishable_key=atlas_test_pk_abc"))
        assertEquals("Bearer at_123", header(req, "Authorization"))
        assertEquals("https://app.example", header(req, "Origin"))
        assertNull(header(req, "Cookie"))
    }

    @Test
    fun atlasIdTokenUsesDpopWhenAProofIsGiven() = runBlocking {
        val t = FakeTransport()
        t.enqueue(403, """{"errors":[{"code":"ORIGIN_NOT_ALLOWED","message":"origin"}]}""")
        val failure = assertFailsWith<AtlasException> { client(t).getAtlasIdToken("referalli", "at_1", dpopProof = "proof") }
        assertTrue(failure.error.isOriginNotAllowed)
        assertEquals("DPoP at_1", header(t.recorded[0], "Authorization"))
        assertEquals("proof", header(t.recorded[0], "DPoP"))
    }

    @Test
    fun atlasIdTokenIsNullOn404() = runBlocking {
        val t = FakeTransport()
        t.enqueue(404, """{"errors":[{"code":"NOT_FOUND","message":"n"}]}""")
        assertNull(client(t).getAtlasIdToken("x", "t"))
    }

    @Test
    fun insufficientScopeReadsTheRequiredScope() {
        val body = """{"errors":[{"code":"INSUFFICIENT_SCOPE","message":"m","meta":{"required_scope":"webhooks:write"},"docs_url":"https://atlasauth.net/errors/INSUFFICIENT_SCOPE"}]}"""
        val e = AtlasError.fromResponse(403, body)
        assertTrue(e.isInsufficientScope)
        assertEquals("webhooks:write", e.requiredScope)
        assertEquals("https://atlasauth.net/errors/INSUFFICIENT_SCOPE", e.errors[0].docsUrl)
        // The old spelling is still tolerated.
        val legacy = AtlasError.Api(403, listOf(AtlasErrorItem("SCOPE_MISSING", "m")))
        assertTrue(legacy.isInsufficientScope)
        assertNull(legacy.requiredScope)
    }

    @Test
    fun stepUpAndOriginNotAllowedAreTyped() {
        val step = AtlasError.Api(403, listOf(AtlasErrorItem("STEP_UP_REQUIRED", "m")))
        assertTrue(step.isStepUpRequired)
        assertEquals(false, step.isOriginNotAllowed)
        assertEquals(AtlasErrorCode.STEP_UP_REQUIRED, step.knownCode)
        assertTrue(AtlasError.Api(403, listOf(AtlasErrorItem("ORIGIN_NOT_ALLOWED", "m"))).isOriginNotAllowed)
    }

    @Test
    fun routeNotFoundCarriesSuggestions() {
        val body = """{"errors":[{"code":"ROUTE_NOT_FOUND","message":"m","did_you_mean":["/v1/webhook_endpoints"],"allowed_methods":["GET","POST"]}]}"""
        val e = AtlasError.fromResponse(404, body)
        assertTrue(e.isRouteNotFound)
        assertEquals(listOf("/v1/webhook_endpoints"), e.didYouMean)
        assertEquals(listOf("GET", "POST"), e.allowedMethods)
        assertTrue(AtlasError.Api(404, listOf(AtlasErrorItem("NOT_FOUND", "m"))).didYouMean.isEmpty())
    }

    @Test
    fun oidcClaimsReadOrgAndSsoClaims() {
        val claims = JsonValue.parse(
            """{"org_id":"org_1","org_slug":"acme","org_role":"admin","org_permissions":["a:b","c:d"],
               "orgs":[{"id":"org_1","slug":"acme","role":"admin"},{"id":"org_2","slug":"b","role":"member"},{"slug":"skipped"}],
               "sso_connection_id":"sso_9","sso_tenant_id":"tid","sso_issuer":"https://login.example","sso_domain":"acme.com"}""",
        )
        val c = OidcClaims.fromClaims(claims)
        assertEquals("org_1", c.orgId)
        assertEquals("admin", c.orgRole)
        assertEquals(listOf("a:b", "c:d"), c.orgPermissions)
        assertEquals(2, c.orgs.size)
        assertEquals("member", c.orgs[1].role)
        assertEquals("sso_9", c.sso?.connectionId)
        assertEquals("acme.com", c.sso?.domain)
        val none = OidcClaims.fromClaims(JsonValue.parse("""{"sub":"u"}"""))
        assertNull(none.orgId)
        assertNull(none.sso)
        assertTrue(none.orgs.isEmpty())
    }

    @Test
    fun oidcClaimsFromJwtAndScopes() {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"org_id":"o","sso_connection_id":"s"}""".toByteArray())
        val c = OidcClaims.fromJwt("e30.$payload.sig")!!
        assertEquals("o", c.orgId)
        assertEquals("s", c.sso?.connectionId)
        assertNull(OidcClaims.fromJwt("not-a-jwt"))
        assertEquals(listOf("openid", "organizations"), AtlasScopes.withOrganizations(listOf("openid")))
        assertEquals(listOf("openid", "organizations"), AtlasScopes.withOrganizations(listOf("openid", "organizations")))
    }
}
