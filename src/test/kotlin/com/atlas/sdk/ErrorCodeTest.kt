package com.atlas.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ErrorCodeTest {
    private val message = "This app can't send email right now. Try another sign-in method or contact the app owner."

    @Test
    fun emailDeliveryNotConfiguredIsTypedAndKeepsTheServerMessage() {
        val error = AtlasError.fromResponse(
            422,
            """{"errors":[{"code":"EMAIL_DELIVERY_NOT_CONFIGURED","message":"$message","meta":{"fix":"Configure an email provider first."}}]}""",
        )
        assertEquals(422, error.status)
        assertEquals(AtlasErrorCode.EMAIL_DELIVERY_NOT_CONFIGURED, error.knownCode)
        assertTrue(error.isEmailDeliveryNotConfigured)
        assertEquals(message, error.message)
        assertEquals(message, AtlasException(error).message)
    }

    @Test
    fun unknownAndLocalErrorsHaveNoKnownCode() {
        val unknown = AtlasError.fromResponse(400, """{"errors":[{"code":"SOMETHING_NEW","message":"x"}]}""")
        assertEquals("SOMETHING_NEW", unknown.code)
        assertNull(unknown.knownCode)
        assertFalse(unknown.isEmailDeliveryNotConfigured)
        assertNull(AtlasError.Transport("offline").knownCode)
        assertNull(AtlasErrorCode.fromWire(null))
    }
}
