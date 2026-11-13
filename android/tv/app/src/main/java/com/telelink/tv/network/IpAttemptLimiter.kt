package com.telelink.tv.network

/** Bounded per-IP pairing throttling using monotonic elapsed time. */
internal class IpAttemptLimiter(
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val maxFailedAttempts: Int = 5,
    private val attemptWindowMillis: Long = 10 * 60 * 1000L,
    private val blockDurationMillis: Long = 10 * 60 * 1000L,
    private val maxTrackedClientIps: Int = 2048
) {
    private data class AttemptState(
        var attempts: Int,
        val windowStartedAt: Long,
        var blockedUntil: Long = 0L
    )

    private val lock = Any()
    private val attemptsByIp = HashMap<String, AttemptState>()

    init {
        require(maxFailedAttempts > 0)
        require(attemptWindowMillis > 0)
        require(blockDurationMillis > 0)
        require(maxTrackedClientIps > 0)
    }

    fun isBlocked(clientIp: String): Boolean = synchronized(lock) {
        val state = attemptsByIp[clientIp] ?: return@synchronized false
        val now = monotonicMillis()
        if (isExpired(state, now)) {
            attemptsByIp.remove(clientIp)
            false
        } else {
            state.blockedUntil > now
        }
    }

    /** Returns true when this failure reaches the blocking threshold. */
    fun recordFailedAttempt(clientIp: String): Boolean = synchronized(lock) {
        val now = monotonicMillis()
        var state = attemptsByIp[clientIp]
        if (state == null || isExpired(state, now)) {
            state?.let { attemptsByIp.remove(clientIp, it) }
            attemptsByIp.entries.removeIf { (_, attempt) -> isExpired(attempt, now) }
            if (attemptsByIp.size >= maxTrackedClientIps) {
                attemptsByIp.minByOrNull { it.value.windowStartedAt }
                    ?.let { attemptsByIp.remove(it.key, it.value) }
            }
            state = AttemptState(attempts = 0, windowStartedAt = now)
            attemptsByIp[clientIp] = state
        }

        state.attempts++
        if (state.attempts < maxFailedAttempts) return@synchronized false
        state.blockedUntil = now + blockDurationMillis
        true
    }

    fun clear(clientIp: String) {
        synchronized(lock) {
            attemptsByIp.remove(clientIp)
        }
    }

    private fun isExpired(state: AttemptState, now: Long): Boolean =
        now - state.windowStartedAt >= attemptWindowMillis ||
            (state.blockedUntil != 0L && now >= state.blockedUntil)
}
