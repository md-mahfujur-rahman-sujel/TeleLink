package com.telelink.tv.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpAttemptLimiterTest {
    private var now = 0L

    @Test
    fun blocksAtThresholdAndUnblocksAfterBlockDuration() {
        val limiter = IpAttemptLimiter(
            monotonicMillis = { now },
            maxFailedAttempts = 3,
            attemptWindowMillis = 100L,
            blockDurationMillis = 50L
        )

        assertFalse(limiter.recordFailedAttempt("192.0.2.1"))
        assertFalse(limiter.recordFailedAttempt("192.0.2.1"))
        assertTrue(limiter.recordFailedAttempt("192.0.2.1"))
        assertTrue(limiter.isBlocked("192.0.2.1"))

        now += 50L
        assertFalse(limiter.isBlocked("192.0.2.1"))
    }

    @Test
    fun expiredAttemptWindowStartsANewCount() {
        val limiter = IpAttemptLimiter(
            monotonicMillis = { now },
            maxFailedAttempts = 2,
            attemptWindowMillis = 100L,
            blockDurationMillis = 100L
        )

        assertFalse(limiter.recordFailedAttempt("192.0.2.2"))
        now += 100L
        assertFalse(limiter.recordFailedAttempt("192.0.2.2"))
        assertTrue(limiter.recordFailedAttempt("192.0.2.2"))
    }

    @Test
    fun successfulPairingClearsFailureHistory() {
        val limiter = IpAttemptLimiter(
            monotonicMillis = { now },
            maxFailedAttempts = 2,
            attemptWindowMillis = 100L,
            blockDurationMillis = 100L
        )

        assertFalse(limiter.recordFailedAttempt("192.0.2.3"))
        limiter.clear("192.0.2.3")
        assertFalse(limiter.recordFailedAttempt("192.0.2.3"))
        assertFalse(limiter.isBlocked("192.0.2.3"))
    }

    @Test
    fun evictsOldestClientWhenTrackingCapacityIsReached() {
        val limiter = IpAttemptLimiter(
            monotonicMillis = { now },
            maxFailedAttempts = 2,
            attemptWindowMillis = 1_000L,
            blockDurationMillis = 1_000L,
            maxTrackedClientIps = 2
        )

        assertFalse(limiter.recordFailedAttempt("192.0.2.10"))
        now += 1L
        assertFalse(limiter.recordFailedAttempt("192.0.2.11"))
        now += 1L
        assertFalse(limiter.recordFailedAttempt("192.0.2.12"))

        // The oldest address was evicted; its next failure starts a fresh window.
        assertFalse(limiter.recordFailedAttempt("192.0.2.10"))
    }
}
