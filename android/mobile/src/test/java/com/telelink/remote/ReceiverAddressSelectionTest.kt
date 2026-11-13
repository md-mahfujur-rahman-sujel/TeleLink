package com.telelink.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class ReceiverAddressSelectionTest {
    @Test
    fun prefersTheIpv4AddressTheTvReceiverBinds() {
        val ipv6 = InetAddress.getByName("2001:db8::1")
        val ipv4 = InetAddress.getByName("192.168.1.20")

        assertEquals(ipv4, selectReceiverAddress(listOf(ipv6, ipv4)))
    }

    @Test
    fun ignoresIpv6OnlyAdvertisementsTheReceiverCannotAccept() {
        val ipv6 = InetAddress.getByName("2001:db8::1")

        assertNull(selectReceiverAddress(listOf(ipv6)))
    }
}
