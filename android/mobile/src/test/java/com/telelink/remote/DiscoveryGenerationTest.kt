package com.telelink.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryGenerationTest {
    @Test
    fun newerSearchMakesLateCallbacksFromPriorSearchStale() {
        val generations = DiscoveryGeneration()
        val firstSearch = generations.begin()
        val secondSearch = generations.begin()

        assertFalse(generations.isCurrent(firstSearch))
        assertTrue(generations.isCurrent(secondSearch))
    }

    @Test
    fun stoppingSearchInvalidatesOutstandingCallbacks() {
        val generations = DiscoveryGeneration()
        val activeSearch = generations.begin()
        generations.invalidate()

        assertFalse(generations.isCurrent(activeSearch))
    }
}
