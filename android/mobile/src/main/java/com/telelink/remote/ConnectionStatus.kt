package com.telelink.remote

internal fun isDisconnectedConnectionStatus(value: String): Boolean =
    value.startsWith("Disconnected from TV") || value.startsWith("Connection failed")

internal fun webSocketCloseStatus(code: Int, reason: String?): String {
    val safeReason = reason
        ?.filterNot(Char::isISOControl)
        ?.trim()
        ?.take(160)
        .orEmpty()

    return when {
        safeReason.equals("Device revoked", ignoreCase = true) ->
            "Disconnected from TV: this phone was removed from paired devices"
        safeReason.equals("Receiver stopped", ignoreCase = true) ->
            "Disconnected from TV: the receiver was stopped"
        code == 1008 -> "Disconnected from TV: pairing or access was rejected"
        code == 1013 -> "Disconnected from TV: the receiver is busy; try again shortly"
        safeReason.isNotEmpty() -> "Disconnected from TV: $safeReason"
        else -> "Disconnected from TV"
    }
}
