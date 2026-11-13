package com.telelink.remote

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.util.UUID

data class TvApp(val packageName: String, val name: String)

class TvRemoteClient(
    private val target: TvPairingPayload,
    private val onStatus: (String) -> Unit,
    private val onPaired: (Boolean, String, String?, String?) -> Unit,
    private val onApps: (List<TvApp>) -> Unit
) : WebSocketClient(URI(target.endpoint)) {

    init {
        setSocketFactory(PinnedTlsSocketFactory.create(target.fingerprint))
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
                    onStatus(json.optString(
                        "message",
                        if (json.optBoolean("success")) "Command completed" else "Command failed"
                    ))
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
        payload.put("requestId", UUID.randomUUID().toString())
        payload.put("type", type)
        send(payload.toString())
    }
}
