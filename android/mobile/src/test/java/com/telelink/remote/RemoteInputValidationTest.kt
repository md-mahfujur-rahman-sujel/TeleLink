package com.telelink.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteInputValidationTest {
    @Test
    fun acceptsValidHttpAndHttpsUrls() {
        assertNull(validateOpenUrl("https://example.com/watch?id=1"))
        assertNull(validateOpenUrl("http://192.168.1.10:8080/video"))
        assertNull(validateOpenUrl("HTTPS://EXAMPLE.COM/watch"))
    }

    @Test
    fun rejectsUnsupportedSchemesAndMissingHosts() {
        assertEquals("Enter a valid HTTP or HTTPS URL", validateOpenUrl("ftp://example.com/file"))
        assertEquals("Enter a valid HTTP or HTTPS URL", validateOpenUrl("https:///missing-host"))
        assertEquals("Enter a valid HTTP or HTTPS URL", validateOpenUrl("javascript:alert(1)"))
    }

    @Test
    fun rejectsMalformedAndOversizedUrls() {
        assertEquals("Enter a valid HTTP or HTTPS URL", validateOpenUrl("https://example.com:bad"))
        assertEquals("URL is too long", validateOpenUrl("https://example.com/" + "a".repeat(4096)))
    }
}
