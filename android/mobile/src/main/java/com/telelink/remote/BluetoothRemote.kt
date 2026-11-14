package com.telelink.remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

data class BtStatus(
    val message: String = "Bluetooth remote is off",
    val registered: Boolean = false,
    val connectedName: String? = null,
    val busy: Boolean = false
)

data class BtDeviceItem(val name: String, val address: String)

/**
 * Makes this phone behave like a Bluetooth keyboard, mouse and media remote (Android's HID Device
 * profile). The TV treats it like its physical remote, so Home, Back, the D-pad, volume and
 * typing work in every app and on the launcher, with no software or permissions on the TV.
 */
@SuppressLint("MissingPermission", "NewApi")
class BluetoothRemote private constructor(private val context: Context) {
    /** The screen currently showing the remote. The remote itself outlives screens. */
    var listener: ((BtStatus) -> Unit)? = null
    var lastStatus: BtStatus = BtStatus()
        private set
    private val prefs = context.getSharedPreferences("telelink_bluetooth", Context.MODE_PRIVATE)
    private var userDisconnected = false
    private var reconnectAttempts = 0
    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val executor = ContextCompat.getMainExecutor(context)
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var registered = false
    private var pointerRemainderX = 0f
    private var pointerRemainderY = 0f
    private var wheelRemainder = 0f

    private fun nameOf(device: BluetoothDevice): String =
        (try { device.name } catch (_: SecurityException) { null }) ?: device.address

    private fun emit(message: String, busy: Boolean = false) {
        val next = BtStatus(message, registered, host?.let { nameOf(it) }, busy)
        lastStatus = next
        listener?.invoke(next)
    }

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            emit("The Bluetooth remote needs Android 9 or newer")
            return
        }
        val bluetooth = adapter
        if (bluetooth == null) {
            emit("This phone has no Bluetooth")
            return
        }
        if (!bluetooth.isEnabled) {
            emit("Turn Bluetooth on, then tap Start again")
            return
        }
        if (hid != null) {
            registerApp()
            return
        }
        emit("Starting the Bluetooth remote…", busy = true)
        val requested = bluetooth.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
        if (!requested) {
            emit("This phone does not support acting as a Bluetooth remote")
            return
        }
        handler.postDelayed({
            if (hid == null) emit("This phone does not seem to support acting as a Bluetooth remote")
        }, 6000)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        val proxy = hid
        if (proxy != null) {
            try { proxy.unregisterApp() } catch (_: Exception) { }
            try { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy) } catch (_: Exception) { }
        }
        hid = null
        host = null
        registered = false
    }

    fun bondedDevices(): List<BtDeviceItem> = try {
        adapter?.bondedDevices.orEmpty()
            .map { BtDeviceItem(nameOf(it), it.address) }
            .sortedBy { it.name.lowercase() }
    } catch (_: SecurityException) {
        emptyList()
    }

    fun connect(address: String) {
        val proxy = hid
        if (proxy == null) {
            emit("Tap Start Bluetooth remote first")
            return
        }
        val device = try { adapter?.getRemoteDevice(address) } catch (_: IllegalArgumentException) { null }
        if (device == null) {
            emit("That device is not available")
            return
        }
        userDisconnected = false
        emit("Connecting to ${nameOf(device)}…", busy = true)
        if (!proxy.connect(device)) {
            emit("Could not connect. Pair this phone with the TV in Bluetooth settings first.")
        }
    }

    fun disconnect() {
        userDisconnected = true
        val device = host ?: return
        hid?.disconnect(device)
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            handler.post {
                hid = proxy as BluetoothHidDevice
                registerApp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            handler.post {
                hid = null
                host = null
                registered = false
                emit("The Bluetooth remote stopped")
            }
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@BluetoothRemote.registered = registered
            if (registered) {
                emit("Ready. Pair this phone with your TV, then connect below.")
                // Reconnect to the TV used last time without any taps.
                val last = prefs.getString(LAST_HOST, null)
                if (last != null && host == null && !userDisconnected) {
                    handler.postDelayed({ if (host == null && !userDisconnected) connect(last) }, 800)
                }
            } else {
                emit("The remote profile is not active. Close other apps that act as a Bluetooth keyboard or remote.")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    reconnectAttempts = 0
                    prefs.edit().putString(LAST_HOST, device.address).apply()
                    emit("Connected to ${nameOf(device)}")
                }

                BluetoothProfile.STATE_CONNECTING -> emit("Connecting to ${nameOf(device)}…", busy = true)

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host?.address == device.address) host = null
                    emit("Disconnected. Tap your TV to connect again.")
                    // An unexpected drop: try to reconnect a few times automatically.
                    if (!userDisconnected && reconnectAttempts < MAX_RECONNECTS) {
                        reconnectAttempts++
                        val delayMs = 2500L * reconnectAttempts
                        handler.postDelayed({
                            if (host == null && !userDisconnected) connect(device.address)
                        }, delayMs)
                    }
                }
            }
        }
    }

    private fun registerApp() {
        val proxy = hid ?: return
        val settings = BluetoothHidDeviceAppSdpSettings(
            "TeleLink Remote",
            "Phone remote control",
            "TeleLink",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            DESCRIPTOR
        )
        if (!proxy.registerApp(settings, null, null, executor, callback)) {
            emit("Could not start the remote profile. Close other apps that act as a Bluetooth keyboard or remote.")
        }
    }

    private fun send(reportId: Int, data: ByteArray): Boolean {
        val device = host ?: return false
        return try {
            hid?.sendReport(device, reportId, data) == true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun tapKey(usage: Int, modifiers: Int = 0) {
        send(KEYBOARD_ID, byteArrayOf(modifiers.toByte(), 0, usage.toByte(), 0, 0, 0, 0, 0))
        handler.postDelayed({ send(KEYBOARD_ID, ByteArray(8)) }, 30)
    }

    private fun tapConsumer(usage: Int) {
        send(CONSUMER_ID, byteArrayOf((usage and 0xFF).toByte(), (usage shr 8).toByte()))
        handler.postDelayed({ send(CONSUMER_ID, byteArrayOf(0, 0)) }, 40)
    }

    fun sendKey(key: String) {
        when (key.uppercase()) {
            "UP" -> tapKey(0x52)
            "DOWN" -> tapKey(0x51)
            "LEFT" -> tapKey(0x50)
            "RIGHT" -> tapKey(0x4F)
            "SELECT", "ENTER", "OK" -> tapKey(0x28)
            "BACKSPACE" -> tapKey(0x2A)
            "BACK" -> tapConsumer(0x0224)
            "HOME" -> tapConsumer(0x0223)
            "MENU" -> tapConsumer(0x0040)
        }
    }

    fun sendMedia(action: String) {
        when (action.uppercase()) {
            "PLAY", "PAUSE", "PLAY_PAUSE" -> tapConsumer(0xCD)
            "STOP" -> tapConsumer(0xB7)
            "SEEK_FORWARD" -> tapConsumer(0xB3)
            "SEEK_BACK" -> tapConsumer(0xB4)
            "VOLUME_UP" -> tapConsumer(0xE9)
            "VOLUME_DOWN" -> tapConsumer(0xEA)
            "MUTE" -> tapConsumer(0xE2)
        }
    }

    fun moveMouse(dx: Float, dy: Float) {
        pointerRemainderX += dx * MOUSE_GAIN
        pointerRemainderY += dy * MOUSE_GAIN
        var stepX = pointerRemainderX.toInt()
        var stepY = pointerRemainderY.toInt()
        pointerRemainderX -= stepX
        pointerRemainderY -= stepY
        while (stepX != 0 || stepY != 0) {
            val sendX = stepX.coerceIn(-127, 127)
            val sendY = stepY.coerceIn(-127, 127)
            send(MOUSE_ID, byteArrayOf(0, sendX.toByte(), sendY.toByte(), 0))
            stepX -= sendX
            stepY -= sendY
        }
    }

    fun click() {
        send(MOUSE_ID, byteArrayOf(1, 0, 0, 0))
        handler.postDelayed({ send(MOUSE_ID, byteArrayOf(0, 0, 0, 0)) }, 40)
    }

    fun scroll(amount: Float) {
        wheelRemainder += amount * SCROLL_GAIN
        val steps = wheelRemainder.toInt()
        if (steps == 0) return
        wheelRemainder -= steps
        send(MOUSE_ID, byteArrayOf(0, 0, 0, steps.coerceIn(-127, 127).toByte()))
    }

    /** Types text as a US-layout keyboard would. Characters outside basic ASCII are skipped. */
    fun typeText(text: String) {
        var delay = 0L
        for (character in text.take(500)) {
            val (modifiers, usage) = hidFor(character) ?: continue
            handler.postDelayed({
                send(KEYBOARD_ID, byteArrayOf(modifiers.toByte(), 0, usage.toByte(), 0, 0, 0, 0, 0))
            }, delay)
            delay += 25
            handler.postDelayed({ send(KEYBOARD_ID, ByteArray(8)) }, delay)
            delay += 25
        }
    }

    private fun hidFor(c: Char): Pair<Int, Int>? {
        val shift = 0x02
        return when (c) {
            in 'a'..'z' -> 0 to (0x04 + (c - 'a'))
            in 'A'..'Z' -> shift to (0x04 + (c - 'A'))
            in '1'..'9' -> 0 to (0x1E + (c - '1'))
            '0' -> 0 to 0x27
            '\n' -> 0 to 0x28
            '\t' -> 0 to 0x2B
            ' ' -> 0 to 0x2C
            '-' -> 0 to 0x2D
            '_' -> shift to 0x2D
            '=' -> 0 to 0x2E
            '+' -> shift to 0x2E
            '[' -> 0 to 0x2F
            '{' -> shift to 0x2F
            ']' -> 0 to 0x30
            '}' -> shift to 0x30
            '\\' -> 0 to 0x31
            '|' -> shift to 0x31
            ';' -> 0 to 0x33
            ':' -> shift to 0x33
            '\'' -> 0 to 0x34
            '"' -> shift to 0x34
            '`' -> 0 to 0x35
            '~' -> shift to 0x35
            ',' -> 0 to 0x36
            '<' -> shift to 0x36
            '.' -> 0 to 0x37
            '>' -> shift to 0x37
            '/' -> 0 to 0x38
            '?' -> shift to 0x38
            '!' -> shift to 0x1E
            '@' -> shift to 0x1F
            '#' -> shift to 0x20
            '$' -> shift to 0x21
            '%' -> shift to 0x22
            '^' -> shift to 0x23
            '&' -> shift to 0x24
            '*' -> shift to 0x25
            '(' -> shift to 0x26
            ')' -> shift to 0x27
            else -> null
        }
    }

    companion object {
        private const val KEYBOARD_ID = 1
        private const val CONSUMER_ID = 2
        private const val MOUSE_ID = 3
        private const val MOUSE_GAIN = 1.6f
        private const val SCROLL_GAIN = 0.12f
        private const val LAST_HOST = "last_host"
        private const val MAX_RECONNECTS = 4

        @Volatile
        private var shared: BluetoothRemote? = null

        /** One remote for the whole app, so it keeps working when the screen is closed or rotated. */
        fun shared(context: Context): BluetoothRemote =
            shared ?: synchronized(this) {
                shared ?: BluetoothRemote(context.applicationContext).also { shared = it }
            }

        // One combined device: keyboard (id 1), media/consumer keys (id 2) and mouse (id 3).
        private val DESCRIPTOR: ByteArray = intArrayOf(
            // Keyboard
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
            0xC0,
            // Consumer control (media, volume, Home, Back, Menu)
            0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, 0x02,
            0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03,
            0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
            0xC0,
            // Mouse
            0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x03, 0x09, 0x01, 0xA1, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01,
            0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x05, 0x81, 0x03,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
            0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
            0xC0, 0xC0
        ).map { it.toByte() }.toByteArray()
    }
}
