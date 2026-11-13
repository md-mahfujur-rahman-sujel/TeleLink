package com.telelink.remote

import java.net.URI

internal fun validateOpenUrl(value: String): String? {
    val url = value.trim()
    if (url.length > MAX_URL_LENGTH) return "URL is too long"
    val uri = runCatching { URI(url) }.getOrNull()
    if (uri?.scheme?.lowercase() !in setOf("http", "https") || uri?.host.isNullOrBlank()) {
        return "Enter a valid HTTP or HTTPS URL"
    }
    return null
}

private const val MAX_URL_LENGTH = 4096
