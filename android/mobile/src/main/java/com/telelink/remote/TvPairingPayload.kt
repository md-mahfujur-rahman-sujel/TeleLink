package com.telelink.remote

import java.net.URI
import java.net.URLDecoder

data class TvPairingPayload(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val pin: String,
    val deviceId: String? = null,
    val token: String? = null
) {
    val endpoint: String
        get() = "wss://${if (host.contains(':') && !host.startsWith('[')) "[$host]" else host}:$port"

    companion object {
        fun parse(raw: String): TvPairingPayload? = try {
            val uri = URI(raw)
            if (uri.scheme != "telelink" || uri.host != "pair") return null

            val parameters = linkedMapOf<String, String>()
            for (part in uri.rawQuery.orEmpty().split('&')) {
                if (part.isEmpty()) continue
                val separator = part.indexOf('=')
                val rawName = if (separator < 0) part else part.substring(0, separator)
                val rawValue = if (separator < 0) "" else part.substring(separator + 1)
                val name = URLDecoder.decode(rawName, "UTF-8")
                val value = URLDecoder.decode(rawValue, "UTF-8")
                if (parameters.putIfAbsent(name, value) != null) return null
            }

            val host = parameters["host"]?.trim().orEmpty()
            val port = parameters["port"]?.toIntOrNull() ?: return null
            val fingerprint = parameters["fingerprint"]?.uppercase().orEmpty()
            val pin = parameters["pin"].orEmpty()

            if ((host.isNotBlank() &&
                    (!host.matches(Regex("[A-Za-z0-9.-]{1,253}")) ||
                        host.startsWith('.') || host.endsWith('.') || host.contains(".."))) ||
                port !in 1..65535 ||
                !fingerprint.matches(Regex("[A-F0-9]{64}")) ||
                !pin.matches(Regex("[0-9]{6}"))
            ) {
                null
            } else {
                TvPairingPayload(host, port, fingerprint, pin)
            }
        } catch (_: Exception) {
            null
        }
    }
}
