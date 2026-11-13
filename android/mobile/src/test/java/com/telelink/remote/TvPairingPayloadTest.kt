package com.telelink.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TvPairingPayloadTest {
    private val fingerprint = "A".repeat(64)

    @Test
    fun parsesValidQrPayloadAndNormalizesFingerprint() {
        val payload = TvPairingPayload.parse(
            "telelink://pair?host=192.168.1.20&port=8765&fingerprint=${fingerprint.lowercase()}&pin=012345"
        )

        assertNotNull(payload)
        assertEquals("192.168.1.20", payload?.host)
        assertEquals(8765, payload?.port)
        assertEquals(fingerprint, payload?.fingerprint)
        assertEquals("012345", payload?.pin)
    }

    @Test
    fun acceptsMissingHostForDiscoveryFallback() {
        val payload = TvPairingPayload.parse(
            "telelink://pair?host=&port=8765&fingerprint=$fingerprint&pin=012345"
        )

        assertEquals("", payload?.host)
    }

    @Test
    fun rejectsWrongSchemeAndInvalidPort() {
        assertNull(
            TvPairingPayload.parse(
                "https://pair?host=192.168.1.20&port=8765&fingerprint=$fingerprint&pin=012345"
            )
        )
        assertNull(
            TvPairingPayload.parse(
                "telelink://pair?host=192.168.1.20&port=65536&fingerprint=$fingerprint&pin=012345"
            )
        )
    }

    @Test
    fun rejectsMalformedTrustMaterialAndUnsafeHost() {
        assertNull(
            TvPairingPayload.parse(
                "telelink://pair?host=192.168.1.20&port=8765&fingerprint=00&pin=012345"
            )
        )
        assertNull(
            TvPairingPayload.parse(
                "telelink://pair?host=example.com/path&port=8765&fingerprint=$fingerprint&pin=012345"
            )
        )
    }

    @Test
    fun rejectsDuplicateSecurityParameters() {
        assertNull(
            TvPairingPayload.parse(
                "telelink://pair?host=192.168.1.20&port=8765&fingerprint=$fingerprint&pin=012345&pin=999999"
            )
        )
    }
}
