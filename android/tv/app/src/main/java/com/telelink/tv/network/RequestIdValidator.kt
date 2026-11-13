package com.telelink.tv.network

internal object RequestIdValidator {
    private val canonicalUuid = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    )

    fun isValid(requestId: String): Boolean = canonicalUuid.matches(requestId)
}
