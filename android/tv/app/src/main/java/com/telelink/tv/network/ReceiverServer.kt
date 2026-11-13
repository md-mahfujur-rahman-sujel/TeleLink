
package com.telelink.tv.network

import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.DefaultSSLWebSocketServerFactory
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import com.telelink.tv.security.PairedDevice
import com.telelink.tv.security.PairingCredential

private const val MAX_RECEIVER_MESSAGE_BYTES = 16 * 1024

internal fun receiverWebSocketDraft(): Draft_6455 =
    Draft_6455(emptyList(), MAX_RECEIVER_MESSAGE_BYTES)

class ReceiverServer(
    private val port: Int = 8765,
    private val onPairingRequested: (pin: String, deviceName: String) -> PairingCredential?,
    private val onPairedDeviceCreated: (PairedDevice) -> Unit,
    private val onPairedDeviceAuthentication: (deviceId: String, token: String) -> Boolean,
    private val onStatusChanged: (String) -> Unit,
    private val onCommandReceived: (WebSocket, JSONObject) -> Unit,
    private val onAuthenticatedClientCountChanged: (Int) -> Unit = {}
) {

    @Volatile
    private var server: TeleLinkWebSocketServer? = null

    val listeningPort: Int
        get() = server?.port ?: port

    fun revokeDevice(deviceId: String) {
        server?.revokeDevice(deviceId)
    }

    fun isAuthenticated(connection: WebSocket): Boolean =
        server?.isAuthenticated(connection) == true

    @Synchronized
    fun start(sslContext: SSLContext) {
        if (server != null) {
            onStatusChanged("Receiver already started")
            return
        }

        try {
            lateinit var newServer: TeleLinkWebSocketServer

            newServer = TeleLinkWebSocketServer(
                port = port,
                onPairingRequested = onPairingRequested,
                onPairedDeviceCreated = onPairedDeviceCreated,
                onPairedDeviceAuthentication = onPairedDeviceAuthentication,
                onStatusChanged = onStatusChanged,
                onCommandReceived = onCommandReceived,
                onAuthenticatedClientCountChanged = onAuthenticatedClientCountChanged,
                sslContext = sslContext,
                onFatalError = { message ->
                    synchronized(this@ReceiverServer) {
                        if (server === newServer) {
                            server = null
                        }
                    }
                    onStatusChanged("Start failed: $message")
                }
            )

            server = newServer
            newServer.start()

        } catch (exception: Exception) {
            server = null
            onStatusChanged("Start failed: ${exception.message}")
        }
    }

    @Synchronized
    fun stop(status: String = "Receiver stopped") {
        val currentServer = server ?: return

        server = null

        try {
            currentServer.stop(1000, "Receiver stopped")
            onStatusChanged(status)
        } catch (exception: Exception) {
            onStatusChanged("Stop failed: ${exception.message}")
        }
    }

    private class TeleLinkWebSocketServer(
        port: Int,
        private val onPairingRequested: (String, String) -> PairingCredential?,
        private val onPairedDeviceCreated: (PairedDevice) -> Unit,
        private val onPairedDeviceAuthentication: (String, String) -> Boolean,
        private val onStatusChanged: (String) -> Unit,
        private val onCommandReceived: (WebSocket, JSONObject) -> Unit,
        private val onAuthenticatedClientCountChanged: (Int) -> Unit,
        sslContext: SSLContext,
        private val onFatalError: (String) -> Unit
    ) : WebSocketServer(
        InetSocketAddress("0.0.0.0", port),
        2,
        listOf(receiverWebSocketDraft())
    ) {

        init {
            setWebSocketFactory(DefaultSSLWebSocketServerFactory(sslContext))
            setMaxPendingConnections(MAX_PENDING_CONNECTIONS)
        }

        private val authenticatedClients = ConcurrentHashMap<WebSocket, String>()
        private val authenticationLock = Any()
        private val pendingConnections = HashSet<WebSocket>()
        private val attemptLimiter = IpAttemptLimiter()

        companion object {
            private const val MAX_PENDING_CONNECTIONS = 16
        }

        override fun onStart() {
            onStatusChanged("Receiver listening on port $port")
        }

        override fun onOpen(
            connection: WebSocket,
            handshake: ClientHandshake
        ) {
            val admitted = synchronized(pendingConnections) {
                if (pendingConnections.size >= MAX_PENDING_CONNECTIONS) false
                else pendingConnections.add(connection)
            }
            if (!admitted) {
                connection.close(1013, "Too many clients are waiting to pair")
                return
            }
            onStatusChanged("A device connected. Pairing required.")
        }

        private fun getClientIp(connection: WebSocket): String {
            return connection.remoteSocketAddress
                ?.address
                ?.hostAddress
                ?: "unknown"
        }

        fun revokeDevice(deviceId: String) {
            val removed = synchronized(authenticationLock) {
                val connections = authenticatedClients.entries
                    .filter { it.value == deviceId }
                    .map { it.key }
                connections.forEach { connection ->
                    if (authenticatedClients.remove(connection, deviceId)) {
                        connection.close(1008, "Device revoked")
                    }
                }
                connections.isNotEmpty()
            }
            if (removed) onAuthenticatedClientCountChanged(connectedDeviceCount())
        }

        fun isAuthenticated(connection: WebSocket): Boolean = synchronized(authenticationLock) {
            connection.isOpen && authenticatedClients.containsKey(connection)
        }

        override fun onMessage(
            connection: WebSocket,
            message: String
        ) {
            try {
                if (message.toByteArray(Charsets.UTF_8).size > MAX_RECEIVER_MESSAGE_BYTES) {
                    connection.send(
                        JSONObject().put("type", "ERROR")
                            .put("message", "Message is too large")
                            .toString()
                    )
                    connection.close(1009, "Message too large")
                    return
                }
                val json = JSONObject(message)
                val type = json.optString("type")

                if (!authenticatedClients.containsKey(connection)) {
                    val clientIp = getClientIp(connection)

                    if (attemptLimiter.isBlocked(clientIp)) {
                        connection.close(
                            1008,
                            "Too many failed pairing attempts"
                        )
                        return
                    }

                    when (type) {
                        "PAIR" -> {
                            val credential = try {
                                onPairingRequested(
                                    json.optString("pin"),
                                    json.optString("deviceName", "Android phone")
                                )
                            } catch (_: Exception) {
                                reportPairingStorageFailure(connection)
                                return
                            }
                            if (credential == null) {
                                sendPairingFailure(connection, clientIp)
                                return
                            }

                            if (!registerAuthenticatedClient(connection, credential.device.id)) return
                            attemptLimiter.clear(clientIp)
                            onPairedDeviceCreated(credential.device)
                                onAuthenticatedClientCountChanged(connectedDeviceCount())
                            connection.send(
                                JSONObject()
                                    .put("type", "PAIR_RESULT")
                                    .put("success", true)
                                    .put("message", "Paired successfully")
                                    .put("deviceId", credential.device.id)
                                    .put("deviceName", credential.device.name)
                                    .put("token", credential.token)
                                    .toString()
                            )
                            onStatusChanged("Device paired successfully")
                            return
                        }

                        "AUTH" -> {
                            val deviceId = json.optString("deviceId")
                            val token = json.optString("token")
                            val authenticated = try {
                                synchronized(authenticationLock) {
                                    onPairedDeviceAuthentication(deviceId, token) &&
                                        registerAuthenticatedClient(connection, deviceId)
                                }
                            } catch (_: Exception) {
                                reportPairingStorageFailure(connection)
                                return
                            }
                            if (!authenticated) {
                                sendPairingFailure(connection, clientIp)
                                return
                            }
                            attemptLimiter.clear(clientIp)
                            onAuthenticatedClientCountChanged(connectedDeviceCount())
                            connection.send(
                                JSONObject()
                                    .put("type", "AUTH_RESULT")
                                    .put("success", true)
                                    .put("message", "Reconnected successfully")
                                    .toString()
                            )
                            return
                        }

                        else -> {
                            connection.send(
                                JSONObject()
                                    .put("type", "ERROR")
                                    .put("message", "Pairing or authentication required")
                                    .toString()
                            )
                            return
                        }
                    }
                }

                if (!RequestIdValidator.isValid(json.optString("requestId"))) {
                    connection.send(
                        JSONObject()
                            .put("type", "ERROR")
                            .put("message", "A valid requestId is required")
                            .toString()
                    )
                    return
                }

                when (type) {
                    "PING" -> {
                        connection.send(
                            JSONObject()
                                .put("type", "PONG")
                                .put("timestamp", System.currentTimeMillis())
                                .toString()
                        )
                    }

                    "TEXT_INPUT",
                    "OPEN_URL",
                    "KEY_PRESS",
                    "MEDIA_PLAY",
                    "MEDIA_PAUSE",
                    "MEDIA_SEEK",
                    "MEDIA_CONTROL",
                    "TOUCH_GESTURE",
                    "SHARE_TEXT",
                    "LAUNCH_APP",
                    "LIST_APPS",
                    "GET_DEVICE_STATUS" -> {
                        onCommandReceived(connection, json)
                    }

                    else -> {
                        connection.send(
                            JSONObject()
                                .put("type", "ERROR")
                                .put("message", "Unsupported command")
                                .toString()
                        )
                    }
                }

            } catch (exception: Exception) {
                if (connection.isOpen) {
                    connection.send(
                        JSONObject()
                            .put("type", "ERROR")
                            .put("message", "Invalid message")
                            .toString()
                    )
                }
            }
        }

        override fun onMessage(connection: WebSocket, message: ByteBuffer) {
            if (!connection.isOpen) return
            connection.send(
                JSONObject()
                    .put("type", "ERROR")
                    .put("message", "Binary messages are not supported")
                    .toString()
            )
            connection.close(1003, "Binary messages are not supported")
        }

        override fun onClose(
            connection: WebSocket,
            code: Int,
            reason: String,
            remote: Boolean
        ) {
            val wasAuthenticated = synchronized(connection) {
                synchronized(pendingConnections) { pendingConnections.remove(connection) }
                authenticatedClients.remove(connection) != null
            }
            if (wasAuthenticated) {
                onAuthenticatedClientCountChanged(connectedDeviceCount())
            }
            onStatusChanged("Device disconnected")
        }

        override fun onError(
            connection: WebSocket?,
            exception: Exception
        ) {
            val message = exception.message ?: "Unknown error"

            if (connection == null) {
                onFatalError(message)
            } else {
                onStatusChanged("Client connection error: $message")
            }
        }

        private fun sendPairingFailure(connection: WebSocket, clientIp: String) {
            val blocked = attemptLimiter.recordFailedAttempt(clientIp)
            connection.send(
                JSONObject()
                    .put("type", "PAIR_RESULT")
                    .put("success", false)
                    .put(
                        "message",
                        if (blocked) "Too many attempts. Try again later."
                        else "Invalid PIN or device credential"
                    )
                    .toString()
            )
            if (blocked) connection.close(1008, "Too many failed pairing attempts")
        }

        private fun reportPairingStorageFailure(connection: WebSocket) {
            onStatusChanged("Pairing unavailable: saved device data could not be read")
            if (!connection.isOpen) return
            connection.send(
                JSONObject()
                    .put("type", "ERROR")
                    .put("message", "TV could not access saved pairing data. Restart TeleLink TV; if the problem continues, clear its app data and pair again.")
                    .toString()
            )
        }

        private fun registerAuthenticatedClient(connection: WebSocket, deviceId: String): Boolean =
            synchronized(authenticationLock) {
                synchronized(connection) {
                    if (!connection.isOpen) return@synchronized false
                    synchronized(pendingConnections) { pendingConnections.remove(connection) }
                    authenticatedClients[connection] = deviceId
                    true
                }
            }

        private fun connectedDeviceCount(): Int = authenticatedClients.values.toSet().size
    }
}
