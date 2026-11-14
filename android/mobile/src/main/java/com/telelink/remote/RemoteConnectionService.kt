package com.telelink.remote

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** Everything the screen shows about the two remotes, owned by the service rather than the screen. */
data class RemoteConnectionState(
    val status: String = "Scan the pairing QR shown on TeleLink TV",
    val isPaired: Boolean = false,
    val wifiTarget: TvPairingPayload? = null,
    val tvApps: List<TvApp> = emptyList(),
    val savedCredentials: List<SavedTvCredential> = emptyList(),
    val credentialStoreError: String? = null,
    val btStatus: BtStatus = BtStatus(),
    val btDevices: List<BtDeviceItem> = emptyList()
)

fun interface RemoteConnectionObserver {
    fun onConnectionState(state: RemoteConnectionState)
}

/**
 * Owns both remote connections so they outlive the screen. The Activity binds to this service only
 * to observe state and send user actions; it never opens or closes a connection itself. Because the
 * service is started and foreground while a TV is connected, the Wi-Fi socket and the Bluetooth HID
 * registration survive backgrounding, screen-off and rotation. START_STICKY plus the saved credential
 * lets Android rebuild the Wi-Fi connection even if the whole process is killed.
 */
class RemoteConnectionService : Service() {

    inner class LocalBinder : Binder() {
        val service: RemoteConnectionService get() = this@RemoteConnectionService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val credentialStore by lazy { SecureCredentialStore(this) }
    private val bluetooth by lazy { BluetoothRemote.shared(this) }
    private val wifiLock by lazy { LowLatencyWifi(this) }

    private var client: TvRemoteClient? = null
    private var observer: RemoteConnectionObserver? = null
    @Volatile private var state = RemoteConnectionState()

    private var hadWifiSession = false
    private var userDisconnected = false
    private var wifiReconnectAttempts = 0
    private var wifiReconnectPending = false
    private var restored = false
    private var isForeground = false
    private var lastNotificationText: String? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        bluetooth.listener = { bt ->
            updateSnapshot { it.copy(btStatus = bt, btDevices = bluetooth.bondedDevices()) }
        }
        state = state.copy(btStatus = bluetooth.lastStatus, btDevices = bluetooth.bondedDevices())
        refreshSavedCredentials()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        // Enter foreground at once so Android's startForegroundService deadline is always met, even
        // when there is nothing to restore; syncForeground stops the service again if it is idle.
        enterForeground()
        when (intent?.action) {
            ACTION_DISCONNECT -> disconnectBoth()
            else -> restoreConnection()
        }
        syncForeground()
        return START_STICKY
    }

    fun addObserver(newObserver: RemoteConnectionObserver) {
        observer = newObserver
        newObserver.onConnectionState(state)
    }

    fun removeObserver(existingObserver: RemoteConnectionObserver) {
        if (observer === existingObserver) observer = null
    }

    // ---- Wi-Fi remote -------------------------------------------------------

    val isWifiOpen: Boolean get() = client?.isOpen == true

    fun connectWifi(pairing: TvPairingPayload, isReconnect: Boolean = false) {
        if (!localNetworkAllowed()) {
            client?.close()
            client = null
            updateSnapshot {
                it.copy(status = "Allow local network access before connecting to your TV", isPaired = false)
            }
            return
        }
        if (!refreshSavedCredentials()) return
        client?.close()
        val saved = state.savedCredentials.firstOrNull { it.fingerprint == pairing.fingerprint }
        val connectionTarget = if (saved != null) {
            pairing.copy(deviceId = saved.deviceId, token = saved.token, pin = "")
        } else {
            pairing
        }
        if (!isReconnect) {
            userDisconnected = false
            wifiReconnectAttempts = 0
            wifiReconnectPending = false
            mainHandler.removeCallbacksAndMessages(null)
        }
        updateSnapshot {
            it.copy(
                wifiTarget = connectionTarget,
                isPaired = false,
                tvApps = emptyList(),
                status = "Connecting securely to ${connectionTarget.host}…"
            )
        }
        lateinit var newClient: TvRemoteClient
        newClient = TvRemoteClient(
            target = connectionTarget,
            onStatus = { value ->
                mainHandler.post {
                    if (client === newClient) {
                        val disconnected = isDisconnectedConnectionStatus(value)
                        if (disconnected) wifiLock.release()
                        updateSnapshot {
                            it.copy(status = value, isPaired = if (disconnected) false else it.isPaired)
                        }
                        if (disconnected) maybeReconnectWifi()
                    }
                }
            },
            onPaired = { success, message, deviceId, token ->
                mainHandler.post {
                    if (client === newClient) {
                        if (success) {
                            hadWifiSession = true
                            userDisconnected = false
                            wifiReconnectAttempts = 0
                            wifiLock.acquire()
                        } else {
                            wifiLock.release()
                        }
                        updateSnapshot {
                            it.copy(isPaired = success, status = if (success) "Paired with TV" else message)
                        }
                        if (success && deviceId != null && token != null) {
                            try {
                                credentialStore.save(
                                    SavedTvCredential(
                                        host = connectionTarget.host,
                                        port = connectionTarget.port,
                                        fingerprint = connectionTarget.fingerprint,
                                        deviceId = deviceId,
                                        token = token,
                                        deviceName = "TeleLink TV"
                                    )
                                )
                                refreshSavedCredentials()
                            } catch (exception: Exception) {
                                updateSnapshot {
                                    it.copy(
                                        credentialStoreError = exception.message
                                            ?: "Paired, but the reconnect credential could not be saved securely."
                                    )
                                }
                            }
                        }
                        if (!success) maybeReconnectWifi()
                    }
                }
            },
            onApps = { apps ->
                mainHandler.post { if (client === newClient) updateSnapshot { it.copy(tvApps = apps) } }
            }
        )
        client = newClient
        newClient.connect()
        syncForeground()
    }

    fun disconnectWifi(message: String, forget: Boolean, clearTarget: Boolean) {
        userDisconnected = true
        wifiReconnectPending = false
        mainHandler.removeCallbacksAndMessages(null)
        client?.close()
        client = null
        wifiLock.release()
        if (forget) {
            try {
                state.wifiTarget?.fingerprint?.let(credentialStore::remove)
                refreshSavedCredentials()
            } catch (exception: Exception) {
                updateSnapshot {
                    it.copy(
                        credentialStoreError = exception.message
                            ?: "Saved pairing could not be removed securely."
                    )
                }
            }
        }
        updateSnapshot {
            it.copy(
                isPaired = false,
                tvApps = emptyList(),
                status = message,
                wifiTarget = if (clearTarget) null else it.wifiTarget
            )
        }
    }

    fun sendWifiCommand(type: String, payload: JSONObject = JSONObject()) {
        client?.sendCommand(type, payload)
    }

    /** Lets the screen push discovery/pairing flow messages into the shared status banner. */
    fun setStatus(message: String) {
        updateSnapshot { it.copy(status = message) }
    }

    /** Lets the screen show a Bluetooth setup hint in the Bluetooth banner. */
    fun setBluetoothMessage(message: String) {
        updateSnapshot { it.copy(btStatus = it.btStatus.copy(message = message)) }
    }

    fun sendWifiUrl(value: String, target: String): String? {
        val url = toBrowsableUrl(value)
        validateOpenUrl(url)?.let { return it }
        val connectedClient = client ?: return "Connect and pair with a TV first"
        if (!connectedClient.isOpen) return "Connect and pair with a TV first"
        connectedClient.sendCommand("OPEN_URL", JSONObject().put("url", url).put("target", target))
        return null
    }

    fun onLocalNetworkPermissionRevoked() {
        userDisconnected = true
        wifiReconnectPending = false
        mainHandler.removeCallbacksAndMessages(null)
        client?.close()
        client = null
        wifiLock.release()
        updateSnapshot {
            it.copy(
                isPaired = false,
                status = "Local network access was revoked. Allow it in Settings, then reconnect."
            )
        }
    }

    private fun maybeReconnectWifi() {
        val target = state.wifiTarget ?: return
        if (!hadWifiSession || userDisconnected || state.isPaired || wifiReconnectPending) return
        if (wifiReconnectAttempts >= MAX_WIFI_RECONNECTS) return
        wifiReconnectAttempts++
        wifiReconnectPending = true
        mainHandler.postDelayed({
            wifiReconnectPending = false
            if (!state.isPaired && !userDisconnected) connectWifi(target, isReconnect = true)
        }, 2500L * wifiReconnectAttempts)
    }

    private fun restoreConnection() {
        if (restored) return
        restored = true
        val saved = try { credentialStore.latest() } catch (_: Exception) { null } ?: return
        if (localNetworkAllowed()) {
            connectWifi(
                TvPairingPayload(
                    host = saved.host,
                    port = saved.port,
                    fingerprint = saved.fingerprint,
                    pin = "",
                    deviceId = saved.deviceId,
                    token = saved.token
                )
            )
        } else {
            updateSnapshot { it.copy(status = "Find your TV or allow local network access to reconnect") }
        }
    }

    private fun refreshSavedCredentials(): Boolean = try {
        val list = credentialStore.list()
        updateSnapshot { it.copy(savedCredentials = list, credentialStoreError = null) }
        true
    } catch (exception: Exception) {
        updateSnapshot {
            it.copy(
                credentialStoreError = exception.message
                    ?: "Saved TV credentials could not be read. Clear TeleLink Remote app data and pair again."
            )
        }
        false
    }

    // ---- Bluetooth remote ---------------------------------------------------

    fun startBluetooth() {
        bluetooth.start()
        updateSnapshot { it.copy(btDevices = bluetooth.bondedDevices()) }
    }

    fun connectBluetooth(address: String) = bluetooth.connect(address)
    fun disconnectBluetooth() = bluetooth.disconnect()
    fun refreshBluetoothDevices() = updateSnapshot { it.copy(btDevices = bluetooth.bondedDevices()) }
    fun sendBluetoothKey(key: String) = bluetooth.sendKey(key)
    fun sendBluetoothMedia(action: String) = bluetooth.sendMedia(action)
    fun moveBluetoothMouse(dx: Float, dy: Float) = bluetooth.moveMouse(dx, dy)
    fun clickBluetooth() = bluetooth.click()
    fun scrollBluetooth(amount: Float) = bluetooth.scroll(amount)
    fun typeBluetooth(text: String) = bluetooth.typeText(text)

    private fun disconnectBoth() {
        disconnectWifi("Disconnected", forget = false, clearTarget = true)
        bluetooth.disconnect()
    }

    // ---- Foreground / notification -----------------------------------------

    private fun isActive(): Boolean =
        state.isPaired || state.btStatus.connectedName != null ||
            state.status.startsWith("Connecting") || state.btStatus.busy

    private fun syncForeground() {
        if (isActive()) {
            if (!isForeground) enterForeground() else updateNotification()
        } else if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
            lastNotificationText = null
            stopSelf()
        }
    }

    private fun enterForeground() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
            lastNotificationText = notificationText()
        } catch (_: Exception) {
            // If Android refuses a foreground service the remote still works while the app is open.
            stopSelf()
        }
    }

    private fun updateSnapshot(transform: (RemoteConnectionState) -> RemoteConnectionState) {
        state = transform(state)
        observer?.onConnectionState(state)
        syncForeground()
    }

    private fun updateNotification() {
        if (!notificationsAllowed()) return
        val text = notificationText()
        if (text == lastNotificationText) return
        lastNotificationText = text
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun localNetworkAllowed(): Boolean =
        Build.VERSION.SDK_INT < 37 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) ==
            PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Remote connection", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnect = PendingIntent.getService(
            this,
            REQUEST_DISCONNECT,
            Intent(this, RemoteConnectionService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = notificationText()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("TeleLink Remote")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Disconnect",
                    disconnect
                ).build()
            )
            .build()
    }

    private fun notificationText(): String = when {
        state.isPaired && state.btStatus.connectedName != null -> "Connected over Wi-Fi and Bluetooth"
        state.isPaired -> "Connected to your TV over Wi-Fi"
        state.btStatus.connectedName != null -> "Connected to your TV over Bluetooth"
        else -> "Connecting to your TV…"
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        client?.close()
        client = null
        wifiLock.release()
        bluetooth.listener = null
        observer = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_DISCONNECT = "com.telelink.remote.action.DISCONNECT"
        private const val CHANNEL_ID = "telelink_remote_connection"
        private const val NOTIFICATION_ID = 21
        private const val REQUEST_OPEN_APP = 22
        private const val REQUEST_DISCONNECT = 23
        private const val MAX_WIFI_RECONNECTS = 3
    }
}

/** A web address opens directly; anything else becomes a Google search. */
private fun toBrowsableUrl(value: String): String {
    val input = value.trim()
    if (input.isEmpty()) return input
    if (input.startsWith("http://", true) || input.startsWith("https://", true)) return input
    val looksLikeAddress = !input.contains(' ') && input.contains('.')
    return if (looksLikeAddress) {
        "https://$input"
    } else {
        "https://www.google.com/search?q=" + android.net.Uri.encode(input)
    }
}