package com.telelink.tv.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingManagerTest {
    private var now = 10_000L
    private val generatedPins = ArrayDeque(listOf(42, 123456, 789012, 3))
    private val manager = PairingManager(
        pinValidityMillis = 5_000L,
        monotonicMillis = { now },
        pinGenerator = { generatedPins.removeFirst() }
    )

    @Test
    fun startIssuesSixDigitPinAndReportsRemainingTime() {
        assertEquals("000042", manager.startSession())
        assertEquals("000042", manager.getCurrentPin())
        assertEquals(5_000L, manager.getRemainingValidityMillis())
    }

    @Test
    fun successfulPairingConsumesPinAndRotatesIt() {
        val firstPin = manager.startSession()

        assertTrue(manager.verifyAndRotatePin(firstPin))
        assertEquals("123456", manager.getCurrentPin())
        assertFalse(manager.verifyAndRotatePin(firstPin))
        assertTrue(manager.verifyAndRotatePin("123456"))
        assertEquals("789012", manager.getCurrentPin())
    }

    @Test
    fun invalidPinDoesNotConsumeCurrentPin() {
        manager.startSession()

        assertFalse(manager.verifyAndRotatePin("654321"))
        assertEquals("000042", manager.getCurrentPin())
    }

    @Test
    fun expiredPinIsRejectedAndCanBeRenewedWhileSessionIsActive() {
        manager.startSession()
        now += 5_001L

        assertFalse(manager.verifyAndRotatePin("000042"))
        assertNull(manager.getCurrentPin())
        assertEquals("123456", manager.getOrRenewCurrentPin())
    }

    @Test
    fun invalidatedSessionCannotRenewOrAcceptPin() {
        val pin = manager.startSession()
        manager.invalidatePin()

        assertFalse(manager.verifyAndRotatePin(pin))
        assertNull(manager.getOrRenewCurrentPin())
        assertNull(manager.getCurrentPin())
        assertEquals(0L, manager.getRemainingValidityMillis())
    }
}
