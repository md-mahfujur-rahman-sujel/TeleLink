package com.telelink.remote

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class TvApp(val packageName: String, val name: String)

class TvRemoteClient(
    private val target: TvPairingPayload,
    private val onStatus: (String) -> Unit,
    private val onPaired: (Boolean, String, String?, String?) -> Unit,
    private val onApps: (List<TvApp>) -> Unit
) : WebSocketClient(URI(target.endpoint)) {

    // Requests whose successful result should not repaint the UI (rapid key presses and swipes).
    private val quietRequests: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        setSocketFactory(PinnedTlsSocketFactory.create(target.fingerprint))
        // Send small command frames immediately instead of letting Nagle's algorithm batch them
        // (this alone removes tens of milliseconds of lag per key press on Wi-Fi).
        isTcpNoDelay = true
        // Ping every 30 s: detects a dead connection sooner than the 60 s default, yet tolerates
        // the short network pauses a phone has when its screen turns off.
        connectionLostTimeout = 30
    }

    override fun onOpen(handshake: ServerHandshake?) {
        onStatus("Secure connection established. Authenticating…")
        val authentication = if (target.deviceId != null && target.token != null) {
            JSONObject()
                .put("type", "AUTH")
                .put("deviceId", target.deviceId)
                .put("token", target.token)
        } else {
            JSONObject()
                .put("type", "PAIR")
                .put("pin", target.pin)
                .put("deviceName", android.os.Build.MODEL ?: "Android phone")
        }
        send(authentication.toString())
    }

    override fun onMessage(message: String?) {
        if (message == null) return
        try {
            val json = JSONObject(message)
            when (json.optString("type")) {
                "PAIR_RESULT" -> onPaired(
                    json.optBoolean("success"),
                    json.optString("message", "Pairing response received"),
                    json.optString("deviceId").takeIf { it.isNotBlank() },
                    json.optString("token").takeIf { it.isNotBlank() }
                )
                "AUTH_RESULT" -> onPaired(
                    json.optBoolean("success"),
                    json.optString("message", "Authentication response received"),
                    target.deviceId,
                    target.token
                )
                "COMMAND_RESULT" -> {
                    val requestId = json.optString("requestId")
                    val quiet = requestId.isNotEmpty() && quietRequests.remove(requestId)
                    val data = json.optJSONObject("data")
                    val appArray = data?.optJSONArray("apps")
                    if (appArray != null) {
                        val apps = (0 until appArray.length()).mapNotNull { index ->
                            val app = appArray.optJSONObject(index) ?: return@mapNotNull null
                            val packageName = app.optString("packageName")
                            if (packageName.isBlank()) null
                            else TvApp(packageName, app.optString("name", packageName))
                        }
                        onApps(apps)
                    }
                    if (!(quiet && json.optBoolean("success"))) {
                        onStatus(json.optString(
                            "message",
                            if (json.optBoolean("success")) "Command completed" else "Command failed"
                        ))
                    }
                }
                "ERROR" -> onStatus(json.optString("message", "TV returned an error"))
            }
        } catch (_: Exception) {
            onStatus("Received an invalid response from the TV")
        }
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        onStatus(webSocketCloseStatus(code, reason))
    }

    override fun onError(exception: Exception?) {
        onStatus(exception?.message?.let { "Connection failed: $it" } ?: "Connection failed")
    }

    fun sendCommand(type: String, payload: JSONObject = JSONObject()) {
        if (!isOpen) {
            onStatus("Connect and pair with a TV first")
            return
        }
        val requestId = UUID.randomUUID().toString()
        payload.put("requestId", requestId)
        payload.put("type", type)
        if (type in QUIET_COMMANDS) {
            if (quietRequests.size > 128) quietRequests.clear()
            quietRequests.add(requestId)
        }
        send(payload.toString())
    }

    private companion object {
        val QUIET_COMMANDS = setOf("KEY_PRESS", "TOUCH_GESTURE")
    }
}
