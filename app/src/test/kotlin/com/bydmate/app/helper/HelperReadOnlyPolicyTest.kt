package com.bydmate.app.helper

import com.bydmate.app.data.autoservice.FidRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperReadOnlyPolicyTest {
    @Test fun `N9 permits reads and binder lifecycle only`() {
        val allowed = setOf(
            HelperBinderProtocol.TX_PING,
            HelperBinderProtocol.TX_READ,
            HelperBinderProtocol.TX_READ_BATCH,
            HelperBinderProtocol.TX_GET_VERSION,
            HelperBinderProtocol.TX_REGISTER_CLIENT,
        )
        // Includes all current protocol verbs and future unrecognized slots.
        for (code in 0..100) {
            assertEquals("helper code $code", code in allowed, helperTransactionAllowed(code, true))
        }
    }

    @Test fun `generic reads cannot carry setter transaction codes`() {
        for (tx in 0..30) {
            val readTx = tx == FidRegistry.TX_GET_INT || tx == FidRegistry.TX_GET_FLOAT
            assertEquals("autoservice tx $tx", readTx, helperAutoserviceAllowed(tx, false, true))
            assertFalse(helperAutoserviceAllowed(tx, true, true))
        }
    }

    @Test fun `standard builds retain their existing protocol`() {
        assertTrue(helperTransactionAllowed(HelperBinderProtocol.TX_WRITE, false))
        assertTrue(helperTransactionAllowed(HelperBinderProtocol.TX_HUD_SDK, false))
        assertTrue(helperAutoserviceAllowed(6, true, false))
    }

    @Test fun `parallel variant cannot target official helper resources`() {
        val official = helperRuntimeName("com.bydmate.app")
        val n9 = helperRuntimeName("com.bydmate.app.n9test")
        val n9v2 = helperRuntimeName("com.bydmate.app.n9test2")
        assertEquals("bydmate_helper", official)
        assertEquals("bydmate_n9test", n9)
        assertEquals("bydmate_n9test2", n9v2)
        assertEquals(3, setOf(official, n9, n9v2).size)
        assertTrue(n9v2.length <= 15)
        assertNotEquals(official, n9)
        assertTrue(n9.length <= 15)
        assertNotEquals(n9, helperRuntimeName("com.bydmate.app.other"))
    }
}
