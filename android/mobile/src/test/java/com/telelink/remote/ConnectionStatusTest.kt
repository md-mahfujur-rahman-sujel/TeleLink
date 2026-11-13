package com.telelink.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionStatusTest {
    @Test
    fun explainsWhenThePhoneWasRevoked() {
        assertEquals(
            "Disconnected from TV: this phone was removed from paired devices",
            webSocketCloseStatus(1008, "Device revoked")
        )
    }

    @Test
    fun explainsWhenTheReceiverStops() {
        assertEquals(
            "Disconnected from TV: the receiver was stopped",
            webSocketCloseStatus(1001, "Receiver stopped")
        )
    }

    @Test
    fun reportsBusyReceiverAndBoundsUntrustedReasons() {
        assertEquals(
            "Disconnected from TV: the receiver is busy; try again shortly",
            webSocketCloseStatus(1013, "Too many clients are waiting to pair")
        )
        val status = webSocketCloseStatus(1000, "x".repeat(300))
        assertEquals(160, status.substringAfter("Disconnected from TV: ").length)
    }

    @Test
    fun recognizesAllDisconnectReasonVariantsForConnectionState() {
        assertEquals(true, isDisconnectedConnectionStatus("Disconnected from TV"))
        assertEquals(
            true,
            isDisconnectedConnectionStatus("Disconnected from TV: this phone was removed from paired devices")
        )
        assertEquals(true, isDisconnectedConnectionStatus("Connection failed: timeout"))
        assertEquals(false, isDisconnectedConnectionStatus("Secure connection established"))
    }
}
