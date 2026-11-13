package com.telelink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteLaunchAuthorizationsTest {
    @Test
    fun authorizationIsOneTimeAndBoundToItsToken() {
        val authorizations = RemoteLaunchAuthorizations()
        val request = AuthorizedRemoteLaunch("OPEN_URL", "https://example.com", integrated = true)
        val token = authorizations.authorize(request)

        assertNull(authorizations.consume("forged-token"))
        assertEquals(request, authorizations.consume(token))
        assertNull(authorizations.consume(token))
    }

    @Test
    fun revokeAndReplacementInvalidatePreviousTokens() {
        val authorizations = RemoteLaunchAuthorizations()
        val oldToken = authorizations.authorize(AuthorizedRemoteLaunch("LAUNCH_APP", "old", false))
        authorizations.revoke(oldToken)
        assertNull(authorizations.consume(oldToken))

        val replacedToken = authorizations.authorize(AuthorizedRemoteLaunch("LAUNCH_APP", "first", false))
        val currentRequest = AuthorizedRemoteLaunch("LAUNCH_APP", "second", false)
        val currentToken = authorizations.authorize(currentRequest)
        assertNull(authorizations.consume(replacedToken))
        assertEquals(currentRequest, authorizations.consume(currentToken))
    }
}
