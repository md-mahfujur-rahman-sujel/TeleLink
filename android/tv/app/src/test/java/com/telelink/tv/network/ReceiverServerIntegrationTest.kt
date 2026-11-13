package com.telelink.tv.network

import com.telelink.tv.security.PairedDevice
import com.telelink.tv.security.PairingCredential
import org.java_websocket.WebSocket
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class ReceiverServerIntegrationTest {
    @Test
    fun secureReceiverPairsRoutesCommandsRevokesAndRejectsMalformedClients() {
        val serverTls = createServerTlsContext()
        val clientTls = createTestClientTlsContext()
        val savedDevices = ConcurrentHashMap<String, String>()
        val commandCount = AtomicInteger()
        val commandReceived = CountDownLatch(2)
        val authenticatedConnections = CopyOnWriteArrayList<WebSocket>()
        val serverStatuses = CopyOnWriteArrayList<String>()
        val server = ReceiverServer(
            port = ServerSocket(0).use { it.localPort },
            onPairingRequested = { pin, deviceName ->
                if (pin != "123456") null
                else PairingCredential(PairedDevice("phone-1", deviceName, 1L), "opaque-token")
            },
            onPairedDeviceCreated = { savedDevices[it.id] = "opaque-token" },
            onPairedDeviceAuthentication = { id, token -> savedDevices[id] == token },
            onStatusChanged = { serverStatuses += it },
            onCommandReceived = { connection, _ ->
                authenticatedConnections += connection
                commandCount.incrementAndGet()
                commandReceived.countDown()
            }
        )
        val clients = mutableListOf<RawTlsWebSocketClient>()

        try {
            server.start(serverTls)
            val port = server.listeningPort

            val paired = connect(clientTls, port, clients, serverStatuses)
            paired.sendText(
                JSONObject()
                    .put("type", "KEY_PRESS")
                    .put("key", "LEFT")
                    .put("requestId", "b60fd11b-2886-45b7-90f8-2f7eac123456")
                    .toString()
            )
            val unauthorized = JSONObject(paired.readText())
            assertEquals("ERROR", unauthorized.optString("type"))
            assertEquals("Pairing or authentication required", unauthorized.optString("message"))

            paired.sendText(JSONObject().put("type", "PAIR").put("pin", "123456").toString())
            val pairResult = JSONObject(paired.readText())
            assertEquals("PAIR_RESULT", pairResult.optString("type"))
            assertTrue(pairResult.optBoolean("success"))
            assertEquals("opaque-token", pairResult.optString("token"))

            paired.sendText(
                JSONObject()
                    .put("type", "KEY_PRESS")
                    .put("key", "LEFT")
                    .put("requestId", "invalid-id")
                    .toString()
            )
            val invalidRequest = JSONObject(paired.readText())
            assertEquals("ERROR", invalidRequest.optString("type"))
            assertEquals("A valid requestId is required", invalidRequest.optString("message"))
            assertEquals(0, commandCount.get())

            paired.sendText(
                JSONObject()
                    .put("type", "KEY_PRESS")
                    .put("key", "LEFT")
                    .put("requestId", "b60fd11b-2886-45b7-90f8-2f7eac123456")
                    .toString()
            )
            val reconnected = connect(clientTls, port, clients, serverStatuses)
            reconnected.sendText(
                JSONObject()
                    .put("type", "AUTH")
                    .put("deviceId", "phone-1")
                    .put("token", "opaque-token")
                    .toString()
            )
            val authResult = JSONObject(reconnected.readText())
            assertEquals("AUTH_RESULT", authResult.optString("type"))
            assertTrue(authResult.optBoolean("success"))

            reconnected.sendText(
                JSONObject()
                    .put("type", "KEY_PRESS")
                    .put("key", "RIGHT")
                    .put("requestId", "e712a616-22a7-4a5f-8eaa-c02b00abc123")
                    .toString()
            )
            assertTrue(commandReceived.await(5, TimeUnit.SECONDS))
            assertEquals(2, commandCount.get())
            assertTrue(server.isAuthenticated(authenticatedConnections[0]))
            assertTrue(server.isAuthenticated(authenticatedConnections[1]))

            savedDevices.remove("phone-1")
            server.revokeDevice("phone-1")
            assertFalse(server.isAuthenticated(authenticatedConnections[0]))
            assertFalse(server.isAuthenticated(authenticatedConnections[1]))
            assertEquals(1008, paired.readCloseCode())
            assertEquals(1008, reconnected.readCloseCode())

            val revoked = connect(clientTls, port, clients, serverStatuses)
            revoked.sendText(
                JSONObject()
                    .put("type", "AUTH")
                    .put("deviceId", "phone-1")
                    .put("token", "opaque-token")
                    .toString()
            )
            val revokedResult = JSONObject(revoked.readText())
            assertEquals("PAIR_RESULT", revokedResult.optString("type"))
            assertFalse(revokedResult.optBoolean("success"))

            val malformed = connect(clientTls, port, clients, serverStatuses)
            malformed.sendText("{")
            assertEquals("ERROR", JSONObject(malformed.readText()).optString("type"))

            val binary = connect(clientTls, port, clients, serverStatuses)
            binary.sendBinary(byteArrayOf(1, 2, 3))
            assertEquals("ERROR", JSONObject(binary.readText()).optString("type"))
            assertEquals(1003, binary.readCloseCode())

            val oversized = connect(clientTls, port, clients, serverStatuses)
            oversized.sendText("x".repeat(17 * 1024))
            assertEquals(1009, oversized.readCloseCode())

            val wrongPin = connect(clientTls, port, clients, serverStatuses)
            repeat(4) { attempt ->
                wrongPin.sendText(JSONObject().put("type", "PAIR").put("pin", "000000").toString())
                val failure = JSONObject(wrongPin.readText())
                assertFalse(failure.optBoolean("success"))
                if (attempt == 3) {
                    assertTrue(failure.optString("message").contains("Too many attempts"))
                    assertEquals(1008, wrongPin.readCloseCode())
                }
            }

            val permissionRevokedStatus =
                "Local network permission revoked; allow it in Settings before restarting"
            server.stop(permissionRevokedStatus)
            assertEquals(permissionRevokedStatus, serverStatuses.last())
        } finally {
            clients.forEach(RawTlsWebSocketClient::close)
            server.stop()
        }
    }

    private fun connect(
        tls: SSLContext,
        port: Int,
        clients: MutableList<RawTlsWebSocketClient>,
        serverStatuses: List<String>
    ): RawTlsWebSocketClient = try {
        RawTlsWebSocketClient(tls.socketFactory.createSocket("127.0.0.1", port) as SSLSocket).also {
        clients += it
        it.handshake()
        }
    } catch (exception: Exception) {
        throw AssertionError("Could not connect to receiver; statuses: $serverStatuses", exception)
    }

    private fun createServerTlsContext(): SSLContext {
        val directory = Files.createTempDirectory("telelink-receiver-test-").toFile()
        val keystore = File(directory, "server.p12")
        val javaHome = File(requireNotNull(System.getProperty("java.home")))
        val keytoolName = if (System.getProperty("os.name")?.startsWith("Windows", true) == true) {
            "keytool.exe"
        } else {
            "keytool"
        }
        val process = ProcessBuilder(
            File(javaHome, "bin/$keytoolName").absolutePath,
            "-genkeypair", "-alias", "telelink-test", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "2", "-storetype", "PKCS12", "-keystore", keystore.absolutePath,
            "-storepass", "test-password", "-keypass", "test-password",
            "-dname", "CN=localhost", "-noprompt"
        ).redirectErrorStream(true).start()
        assertTrue("keytool did not finish", process.waitFor(30, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals("keytool failed: $output", 0, process.exitValue())

        val store = KeyStore.getInstance("PKCS12").apply {
            FileInputStream(keystore).use { load(it, "test-password".toCharArray()) }
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(store, "test-password".toCharArray()) }
        return SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
            .also { directory.deleteRecursively() }
    }

    private fun createTestClientTlsContext(): SSLContext {
        val trustAll = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
            override fun checkClientTrusted(
                chain: Array<out java.security.cert.X509Certificate>?, authType: String?
            ) = Unit
            override fun checkServerTrusted(
                chain: Array<out java.security.cert.X509Certificate>?, authType: String?
            ) = Unit
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
    }

    /** Minimal RFC 6455 client for deterministic loopback testing of the production receiver. */
    private class RawTlsWebSocketClient(private val socket: SSLSocket) {
        private val input = BufferedInputStream(socket.inputStream)
        private val output = BufferedOutputStream(socket.outputStream)
        private val random = SecureRandom()

        init {
            socket.soTimeout = 5000
            socket.startHandshake()
        }

        fun handshake() {
            val key = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))
            output.write(
                ("GET / HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:${socket.port}\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n").toByteArray(Charsets.US_ASCII)
            )
            output.flush()
            val status = readHttpLine()
            if (!status.startsWith("HTTP/1.1 101")) throw IOException("Unexpected response: $status")
            while (true) {
                if (readHttpLine().isEmpty()) break
            }
        }

        fun sendText(text: String) = sendFrame(0x1, text.toByteArray(Charsets.UTF_8))
        fun sendBinary(bytes: ByteArray) = sendFrame(0x2, bytes)

        fun readText(): String {
            while (true) {
                val (opcode, payload) = readFrame()
                if (opcode == 0x1) return String(payload, Charsets.UTF_8)
                if (opcode == 0x8) throw IOException("Receiver closed before replying")
            }
        }

        fun readCloseCode(): Int {
            while (true) {
                val (opcode, payload) = readFrame()
                if (opcode == 0x8) {
                    if (payload.size < 2) return 1005
                    return ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
                }
            }
        }

        fun close() {
            runCatching { if (!socket.isClosed) sendFrame(0x8, byteArrayOf()) }
            runCatching { socket.close() }
        }

        private fun sendFrame(opcode: Int, payload: ByteArray) {
            output.write(0x80 or opcode)
            if (payload.size < 126) {
                output.write(0x80 or payload.size)
            } else {
                output.write(0x80 or 126)
                output.write((payload.size ushr 8) and 0xff)
                output.write(payload.size and 0xff)
            }
            val mask = ByteArray(4).also(random::nextBytes)
            output.write(mask)
            payload.forEachIndexed { index, value ->
                output.write((value.toInt() xor mask[index % 4].toInt()) and 0xff)
            }
            output.flush()
        }

        private fun readFrame(): Pair<Int, ByteArray> {
            val first = input.readRequired()
            val second = input.readRequired()
            val opcode = first and 0x0f
            var length = (second and 0x7f).toLong()
            if (length == 126L) length = (input.readRequired() shl 8 or input.readRequired()).toLong()
            require(length <= 16 * 1024L) { "Unexpectedly large server frame" }
            val mask = if (second and 0x80 != 0) ByteArray(4).also { input.readFullyCompat(it) } else null
            val payload = ByteArray(length.toInt()).also { input.readFullyCompat(it) }
            if (mask != null) payload.indices.forEach {
                payload[it] = (payload[it].toInt() xor mask[it % 4].toInt()).toByte()
            }
            return opcode to payload
        }

        private fun readHttpLine(): String {
            val bytes = ArrayList<Byte>()
            while (true) {
                val value = input.readRequired()
                if (value == '\r'.code) {
                    if (input.readRequired() != '\n'.code) throw IOException("Malformed HTTP line")
                    return bytes.toByteArray().toString(Charsets.US_ASCII)
                }
                bytes += value.toByte()
            }
        }

        private fun java.io.InputStream.readRequired(): Int = read().takeIf { it >= 0 }
            ?: throw IOException("Unexpected end of stream")

        private fun java.io.InputStream.readFullyCompat(buffer: ByteArray) {
            var offset = 0
            while (offset < buffer.size) {
                val count = read(buffer, offset, buffer.size - offset)
                if (count < 0) throw IOException("Unexpected end of frame")
                offset += count
            }
        }
    }
}
