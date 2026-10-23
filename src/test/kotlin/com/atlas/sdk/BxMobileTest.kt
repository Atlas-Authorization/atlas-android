package com.atlas.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Device id on account-creating starts, captcha codes, SMS MFA enrolment + TOTP QR, org audit log. */
class BxMobileTest {
    private fun client(transport: FakeTransport, store: TokenStore = InMemoryTokenStore()): AtlasClient =
        AtlasClient("pk_test_bx", "clerk.example.com", store, transport, Dispatchers.Unconfined).also {
            it.deviceIdProvider = { "d_test" }
        }

    private fun body(request: HttpRequest): JsonValue = JsonValue.parse(request.body!!)

    @Test
    fun deviceIdRidesOnAccountCreatingStartsOnly() = runBlocking {
        val t = FakeTransport()
        t.enqueue(201, """{"id":"sua_1","status":"needs_email_verification"}""")
        t.enqueue(201, """{"id":"sia_1","status":"needs_first_factor","supported_first_factors":["password"]}""")
        t.enqueue(200, """{"id":"sia_o","status":"needs_first_factor","authorization_url":"https://idp.example/auth"}""")
        val c = client(t)
        c.beginSignUp("a@b.com", "pw")
        assertEquals("d_test", body(t.recorded[0]).string("device_id"))
        c.beginSignIn("a@b.com")
        assertNull(body(t.recorded[1]).string("device_id"))
        c.oauthAuthorizeUrl("google", "app://cb")
        assertEquals("d_test", body(t.recorded[2]).string("device_id"))
        assertEquals("""{"device_id":"mine"}""", DeviceId.stamp("POST", "/v1/client/sign_ups", """{"device_id":"mine"}""") { "x" })
        assertTrue(DeviceId.fresh().startsWith("d_"))
        assertEquals(DeviceId.forProcess(), DeviceId.forProcess())
    }

    @Test
    fun captchaCodesAreTyped() {
        val e = AtlasError.Api(400, listOf(AtlasErrorItem("CAPTCHA_REQUIRED", "Solve it.")))
        assertEquals(AtlasErrorCode.CAPTCHA_REQUIRED, e.knownCode)
        assertTrue(e.isCaptchaRequired)
    }

    @Test
    fun smsMfaEnrollmentAndTotpQr() = runBlocking {
        val t = FakeTransport()
        t.enqueue(201, """{"id":"sia_m","status":"needs_mfa_enrollment"}""")
        t.enqueue(201, """{"factor_id":"fac_s","strategy":"phone_code","phone":"+1*******0123"}""")
        t.enqueue(200, """{"id":"sia_m","status":"complete","ticket":"tk_m","backup_codes":["aaaa"]}""")
        val flow = client(t).beginSignIn("a@b.com")
        val enrollment = flow.prepareSmsMfaEnrollment("+14155550123")
        assertEquals("phone_code", body(t.recorded[1]).string("strategy"))
        assertEquals("+14155550123", body(t.recorded[1]).string("phone"))
        assertEquals("phone_code", enrollment.strategy)
        assertEquals("", enrollment.secret)
        assertEquals("+1*******0123", enrollment.phone)
        val codes = flow.attemptSmsMfaEnrollment(enrollment.factorId, "123456")
        assertEquals(listOf("aaaa"), codes)
        assertEquals("123456", body(t.recorded[2]).string("code"))
        assertNull(body(t.recorded[2]).arr("codes"))

        val totp = MfaEnrollment.from(JsonValue.parse("""{"factor_id":"f","secret":"S","uri":"otpauth://x","qr_code":"data:image/gif;base64,R0lGODlh"}"""))
        assertEquals("data:image/gif;base64,R0lGODlh", totp.qrCode)
        assertTrue((totp.qrImageBytes?.size ?: 0) > 0)
        assertNull(MfaEnrollment.from(JsonValue.parse("""{"factor_id":"f","secret":"S","qr_code":"javascript:x"}""")).qrCode)
    }

    @Test
    fun organizationAuditLog() = runBlocking {
        val t = FakeTransport()
        t.enqueue(200, """{"object":"list","data":[{"object":"audit_log","id":"al_1","action":"member.added","actor":{"type":"user","id":"u_1"},"target":{"type":"user","id":"u_2"},"organization_id":"org_1","created_at":1}],"has_more":true,"next_cursor":"al_1"}""")
        val store = InMemoryTokenStore(AtlasSession("sess_1", "jwt", "rt"))
        val page = client(t, store).organizationAuditLog("org_1", limit = 10, action = "member.")
        assertEquals("https://clerk.example.com/v1/client/organizations/org_1/audit_logs?limit=10&action=member.", t.recorded[0].url)
        assertEquals("member.added", page.data.first().action)
        assertEquals("u_1", page.data.first().actor.id)
        assertEquals("al_1", page.nextCursor)
        assertTrue(page.hasMore)
    }
}
