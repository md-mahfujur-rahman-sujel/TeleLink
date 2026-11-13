
package com.telelink.tv.security

import android.os.SystemClock
import java.security.MessageDigest
import java.security.SecureRandom

class PairingManager(
    private val pinValidityMillis: Long = 5 * 60 * 1000L,
    private val monotonicMillis: () -> Long = SystemClock::elapsedRealtime,
    private val pinGenerator: () -> Int = { SecureRandom().nextInt(1_000_000) }
) {

    private var pairingSessionActive = false
    private var activePin: String? = null
    private var expiryTime: Long = 0L

    @Synchronized
    fun startSession(): String {
        pairingSessionActive = true
        return issuePin()
    }

    @Synchronized
    fun getOrRenewCurrentPin(): String? {
        if (!pairingSessionActive) {
            return null
        }

        if (isExpired()) {
            return issuePin()
        }

        return activePin
    }

    @Synchronized
    fun getCurrentPin(): String? {
        if (!pairingSessionActive || isExpired()) {
            clearActivePin()
            return null
        }

        return activePin
    }

    @Synchronized
    fun getRemainingValidityMillis(): Long {
        if (!pairingSessionActive || isExpired()) {
            clearActivePin()
            return 0L
        }

        return (expiryTime - monotonicMillis())
            .coerceAtLeast(0L)
    }

    /** Validates the current PIN and immediately issues a fresh one for the next phone. */
    @Synchronized
    fun verifyAndRotatePin(suppliedPin: String): Boolean {
        if (!pairingSessionActive || isExpired()) {
            clearActivePin()
            return false
        }

        val expectedPin = activePin ?: return false

        val valid = MessageDigest.isEqual(
            expectedPin.toByteArray(Charsets.UTF_8),
            suppliedPin.toByteArray(Charsets.UTF_8)
        )

        if (valid) {
            issuePin()
        }

        return valid
    }

    @Synchronized
    fun invalidatePin() {
        pairingSessionActive = false
        clearActivePin()
    }

    private fun issuePin(): String {
        val pin = pinGenerator().coerceIn(0, 999_999)
            .toString()
            .padStart(6, '0')

        activePin = pin
        expiryTime =
            monotonicMillis() + pinValidityMillis

        return pin
    }

    private fun clearActivePin() {
        activePin = null
        expiryTime = 0L
    }

    private fun isExpired(): Boolean {
        return activePin == null || monotonicMillis() >= expiryTime
    }
}
