package com.telelink.tv.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiverFramePolicyTest {
    @Test
    fun configuresTheWebSocketDraftToEnforceTheMessageLimitBeforeAssembly() {
        assertEquals(16 * 1024, receiverWebSocketDraft().maxFrameSize)
    }
}
