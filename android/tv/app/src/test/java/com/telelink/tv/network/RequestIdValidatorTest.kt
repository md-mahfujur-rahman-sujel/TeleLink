package com.telelink.tv.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestIdValidatorTest {
    @Test
    fun acceptsCanonicalUuidRequestIds() {
        assertTrue(RequestIdValidator.isValid("7b9f66a8-5f74-4ee1-9d34-98c143f29473"))
        assertTrue(RequestIdValidator.isValid("7B9F66A8-5F74-4EE1-9D34-98C143F29473"))
    }

    @Test
    fun rejectsMissingAndMalformedRequestIds() {
        assertFalse(RequestIdValidator.isValid(""))
        assertFalse(RequestIdValidator.isValid("request-1"))
        assertFalse(RequestIdValidator.isValid("7b9f66a85f744ee19d3498c143f29473"))
        assertFalse(RequestIdValidator.isValid("7b9f66a8-5f74-4ee1-9d34-98c143f2947z"))
    }
}
