package com.telelink.remote

internal class DiscoveryGeneration {
    @Volatile
    private var current = 0L

    @Synchronized
    fun begin(): Long = ++current

    @Synchronized
    fun invalidate() {
        current++
    }

    fun isCurrent(generation: Long): Boolean = current == generation

    fun current(): Long = current
}
