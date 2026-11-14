package com.telelink.tv

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.telelink.tv.network.CommandResult
import com.telelink.tv.network.LowLatencyWifi
import com.telelink.tv.network.ReceiverServer
import com.telelink.tv.network.TvCommandRouter
import com.telelink.tv.network.TvServicePublisher
import com.telelink.tv.security.PairedDevice
import com.telelink.tv.security.PairedDeviceStore
import com.telelink.tv.security.PairingManager
import com.telelink.tv.security.TlsIdentity
import org.java_websocket.WebSocket
import org.json.JSONObject
import java.util.concurrent.Executors

data class ReceiverSnapshot(
    val running: Boolean = false,
    val starting: Boolean = false,
    val status: String = "Receiver stopped",
    val pairingPin: String? = null,
    val pinSecondsRemaining: Int = 0,
    val connectedPhoneCount: Int = 0,
    val pairedDevices: List<PairedDevice> = emptyList(),
    val certificateFingerprint: String? = null,
    val ipAddress: String = "Unavailable"
)

fun interface ReceiverSnapshotObserver {
    fun onSnapshot(snapshot: ReceiverSnapshot)
}

class ReceiverForegroundService : Service() {
    inner class LocalBinder : Binder() {
        val service: ReceiverForegroundService get() = this@ReceiverForegroundService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tlsExecutor = Executors.newSingleThreadExecutor()
    // Navigation keys and swipes run here, off the main thread, in strict arrival order.
    private val inputExecutor = Executors.newSingleThreadExecutor()
    private val wifiLatencyLock by lazy { LowLatencyWifi(this) }
    private val pairingManager = PairingManager()
    private lateinit var tlsIdentity: TlsIdentity
    private lateinit var servicePublisher: TvServicePublisher
    private lateinit var commandRouter: TvCommandRouter
    private lateinit var pairedDeviceStore: PairedDeviceStore
    private var pairedDeviceStorageError: String? = null
    private lateinit var receiver: ReceiverServer
    private var observer: ReceiverSnapshotObserver? = null
    @Volatile private var pendingRemoteLaunch: PendingRemoteLaunch? = null
    @Volatile private var startGeneration = 0
    @Volatile private var snapshot = ReceiverSnapshot()

    private val pinTicker = object : Runnable {
        override fun run() {
            val current = snapshot
            if (!current.running && !current.starting) return
            val pin = pairingManager.getOrRenewCurrentPin()
            val remaining = pairingManager.getRemainingValidityMillis()
            updateSnapshot {
                it.copy(
                    pairingPin = pin,
                    pinSecondsRemaining = ((remaining + 999L) / 1000L).toInt()
                )
            }
            mainHandler.postDelayed(this, PIN_TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        tlsIdentity = TlsIdentity()
        servicePublisher = TvServicePublisher(this)
        commandRouter = TvCommandRouter(this, ::queueRemoteLaunch)
        pairedDeviceStore = PairedDeviceStore(this)
        val savedDevices = try {
            pairedDeviceStore.list()
        } catch (exception: Exception) {
            pairedDeviceStorageError = exception.message
            emptyList()
        }
        snapshot = snapshot.copy(
            pairedDevices = savedDevices,
            ipAddress = getLocalIpAddress(this),
            status = pairedDeviceStorageError ?: snapshot.status
        )
        receiver = ReceiverServer(
            port = RECEIVER_PORT,
            onPairingRequested = { suppliedPin, deviceName ->
                if (pairingManager.verifyAndRotatePin(suppliedPin)) {
                    pairedDeviceStore.create(deviceName)
                } else null
            },
            onPairedDeviceCreated = {
                mainHandler.post {
                    updateSnapshot { it.copy(pairedDevices = pairedDeviceStore.list()) }
                }
            },
            onPairedDeviceAuthentication = { deviceId, token ->
                pairedDeviceStore.authenticate(deviceId, token)
            },
            onStatusChanged = ::handleReceiverStatus,
            onCommandReceived = { connection, command ->
                if (command.optString("type") in FAST_INPUT_COMMANDS) {
                    // Rapid key presses and swipes skip the main thread so they never queue behind
                    // UI work, and they no longer repaint the screen or notification each time.
                    try {
                        inputExecutor.execute {
                            if (!receiver.isAuthenticated(connection)) return@execute
                            if (pendingRemoteLaunch != null) {
                                mainHandler.post {
                                    clearPendingRemoteLaunch()
                                    refreshForegroundNotification()
                                }
                            }
                            commandRouter.execute(command) { result ->
                                sendCommandResult(connection, command, result)
                                if (!result.success) {
                                    mainHandler.post {
                                        updateSnapshot { it.copy(status = result.message) }
                                    }
                                }
                            }
                        }
                    } catch (_: java.util.concurrent.RejectedExecutionException) {
                        // Service is shutting down.
                    }
                } else {
                    mainHandler.post {
                        if (!receiver.isAuthenticated(connection)) return@post
                        if (pendingRemoteLaunch != null) {
                            clearPendingRemoteLaunch()
                            refreshForegroundNotification()
                        }
                        commandRouter.execute(command) { result ->
                            sendCommandResult(connection, command, result)
                            updateSnapshot { it.copy(status = result.message) }
                        }
                    }
                }
            },
            onAuthenticatedClientCountChanged = { count ->
                mainHandler.post { updateSnapshot { it.copy(connectedPhoneCount = count) } }
            }
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                try {
                    enterForeground()
                    startReceiver()
                } catch (exception: Exception) {
                    pairingManager.invalidatePin()
                    updateSnapshot {
                        it.copy(
                            running = false,
                            starting = false,
                            status = "Could not start receiver: ${exception.message ?: "service error"}",
                            pairingPin = null,
                            pinSecondsRemaining = 0
                        )
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            ACTION_STOP -> stopReceiver()
            ACTION_STOP_PERMISSION_REVOKED -> stopReceiver(
                "Local network permission revoked; allow it in Settings before restarting"
            )
            ACTION_CLEAR_PENDING_LAUNCH -> {
                if (pendingRemoteLaunch?.authorizationToken ==
                    intent.getStringExtra(EXTRA_PENDING_LAUNCH_TOKEN)
                ) {
                    clearPendingRemoteLaunch()
                }
                refreshForegroundNotification()
            }
            else -> if (!snapshot.running && !snapshot.starting) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    fun addObserver(newObserver: ReceiverSnapshotObserver) {
        observer = newObserver
        newObserver.onSnapshot(snapshot)
    }

    fun removeObserver(existingObserver: ReceiverSnapshotObserver) {
        if (observer === existingObserver) observer = null
    }

    fun revokeDevice(deviceId: String) {
        try {
            if (pairedDeviceStore.remove(deviceId)) receiver.revokeDevice(deviceId)
            updateSnapshot { it.copy(pairedDevices = pairedDeviceStore.list()) }
        } catch (exception: Exception) {
            updateSnapshot {
                it.copy(status = exception.message ?: "Could not update saved paired devices")
            }
        }
    }

    private fun sendCommandResult(connection: WebSocket, command: JSONObject, result: CommandResult) {
        if (!connection.isOpen) return
        try {
            connection.send(
                JSONObject()
                    .put("type", "COMMAND_RESULT")
                    .put("requestId", command.optString("requestId"))
                    .put("success", result.success)
                    .put("message", result.message)
                    .put("data", result.data ?: JSONObject())
                    .toString()
            )
        } catch (_: Exception) {
            // The peer may disconnect between the open check and the send.
        }
    }

    fun refreshIpAddress() {
        updateSnapshot { it.copy(ipAddress = getLocalIpAddress(this)) }
    }

    private fun enterForeground() {
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startReceiver() {
        if (snapshot.running || snapshot.starting) return
        pairedDeviceStorageError?.let { error ->
            updateSnapshot {
                it.copy(running = false, starting = false, status = error, pairingPin = null)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val generation = ++startGeneration
        wifiLatencyLock.acquire()
        val pin = pairingManager.startSession()
        updateSnapshot {
            it.copy(
                starting = true,
                status = "Starting receiver…",
                pairingPin = pin,
                pinSecondsRemaining = PIN_VALIDITY_SECONDS,
                connectedPhoneCount = 0,
                certificateFingerprint = null,
                ipAddress = getLocalIpAddress(this)
            )
        }
        mainHandler.removeCallbacks(pinTicker)
        mainHandler.post(pinTicker)
        tlsExecutor.execute {
            try {
                val sslContext = tlsIdentity.serverContext()
                val fingerprint = tlsIdentity.certificateFingerprint()
                mainHandler.post {
                    if (generation != startGeneration) return@post
                    updateSnapshot { it.copy(certificateFingerprint = fingerprint) }
                    receiver.start(sslContext)
                }
            } catch (exception: Exception) {
                mainHandler.post {
                    if (generation != startGeneration) return@post
                    pairingManager.invalidatePin()
                    mainHandler.removeCallbacks(pinTicker)
                    updateSnapshot {
                        it.copy(
                            running = false,
                            starting = false,
                            status = "Start failed: ${exception.message ?: "TLS setup error"}",
                            pairingPin = null,
                            pinSecondsRemaining = 0,
                            certificateFingerprint = null
                        )
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun stopReceiver(status: String = "Receiver stopped") {
        startGeneration++
        clearPendingRemoteLaunch()
        mainHandler.removeCallbacks(pinTicker)
        pairingManager.invalidatePin()
        servicePublisher.stop()
        receiver.stop(status)
        wifiLatencyLock.release()
        updateSnapshot {
            it.copy(
                running = false,
                starting = false,
                status = status,
                pairingPin = null,
                pinSecondsRemaining = 0,
                connectedPhoneCount = 0,
                certificateFingerprint = null
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleReceiverStatus(status: String) {
        mainHandler.post {
            when {
                status.startsWith("Receiver listening") -> {
                    if (!snapshot.starting && !snapshot.running) return@post
                    try {
                        snapshot.certificateFingerprint?.let { fingerprint ->
                            servicePublisher.start(fingerprint) { message ->
                                handleReceiverStatus("Receiver listening; $message")
                            }
                        }
                        updateSnapshot { it.copy(running = true, starting = false, status = status) }
                    } catch (exception: Exception) {
                        updateSnapshot {
                            it.copy(
                                running = true,
                                starting = false,
                                status = "Receiver listening; discovery failed: ${exception.message}"
                            )
                        }
                    }
                }
                status.startsWith("A device connected") || status.startsWith("Device paired") -> {
                    if (!snapshot.starting && !snapshot.running) return@post
                    updateSnapshot { it.copy(running = true, starting = false, status = status) }
                }
                status.startsWith("Receiver stopped") || status.startsWith("Start failed") -> {
                    servicePublisher.stop()
                    pairingManager.invalidatePin()
                    mainHandler.removeCallbacks(pinTicker)
                    updateSnapshot {
                        it.copy(
                            running = false,
                            starting = false,
                            status = status,
                            pairingPin = null,
                            pinSecondsRemaining = 0,
                            connectedPhoneCount = 0,
                            certificateFingerprint = null
                        )
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                else -> updateSnapshot { it.copy(status = status) }
            }
        }
    }

    @SuppressLint("NotificationPermission") // Guard below skips updates when Android 13+ permission is denied.
    private fun updateSnapshot(transform: (ReceiverSnapshot) -> ReceiverSnapshot) {
        val previousStatus = snapshot.status
        snapshot = transform(snapshot)
        observer?.onSnapshot(snapshot)
        val notificationsAllowed = notificationsAllowed()
        if ((snapshot.running || snapshot.starting) && previousStatus != snapshot.status && notificationsAllowed) {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private data class PendingRemoteLaunch(
        val type: String,
        val value: String,
        val integrated: Boolean,
        val authorizationToken: String = ""
    )

    private fun queueRemoteLaunch(intent: Intent) {
        val pending = when {
            intent.component?.className == com.telelink.tv.browser.TvBrowserActivity::class.java.name -> {
                val url = intent.getStringExtra(com.telelink.tv.browser.TvBrowserActivity.EXTRA_URL)
                    ?: return
                PendingRemoteLaunch("OPEN_URL", url, integrated = true)
            }
            intent.action == Intent.ACTION_VIEW && intent.data?.toString() != null ->
                PendingRemoteLaunch("OPEN_URL", intent.data.toString(), integrated = false)
            intent.component?.packageName != null ->
                PendingRemoteLaunch("LAUNCH_APP", intent.component!!.packageName, integrated = false)
            else -> return
        }
        clearPendingRemoteLaunch()
        val token = (application as TeleLinkApplication).authorizeRemoteLaunch(
            AuthorizedRemoteLaunch(pending.type, pending.value, pending.integrated)
        )
        pendingRemoteLaunch = pending.copy(authorizationToken = token)
        refreshForegroundNotification()
    }

    private fun clearPendingRemoteLaunch() {
        pendingRemoteLaunch?.let { pending ->
            (application as TeleLinkApplication).revokeRemoteLaunch(pending.authorizationToken)
        }
        pendingRemoteLaunch = null
    }

    private fun refreshForegroundNotification() {
        if (!snapshot.running && !snapshot.starting) return
        if (!notificationsAllowed()) return
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "TeleLink TV receiver",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows while the TV is accepting remote connections" }
        )
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, ReceiverForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("TeleLink TV receiver")
            .setContentText(
                when {
                    pendingRemoteLaunch != null -> "A remote launch request is ready for TV confirmation"
                    snapshot.running -> "Ready for phone controls"
                    else -> "Starting receiver"
                }
            )
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        pendingRemoteLaunch?.let { pending ->
            val executeRequest = Intent(this, MainActivity::class.java)
                .setAction(ACTION_EXECUTE_PENDING_LAUNCH)
                .putExtra(EXTRA_PENDING_LAUNCH_TOKEN, pending.authorizationToken)
            val openRequest = PendingIntent.getActivity(
                this,
                REQUEST_PENDING_LAUNCH,
                executeRequest,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_view),
                    "Open request",
                    openRequest
                ).build()
            )
        }
        return builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                    "Stop",
                    stopAction
                ).build()
            )
            .build()
    }

    override fun onDestroy() {
        startGeneration++
        clearPendingRemoteLaunch()
        mainHandler.removeCallbacks(pinTicker)
        pairingManager.invalidatePin()
        servicePublisher.stop()
        receiver.stop()
        wifiLatencyLock.release()
        tlsExecutor.shutdownNow()
        inputExecutor.shutdownNow()
        observer = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.telelink.tv.receiver.START"
        const val ACTION_STOP = "com.telelink.tv.receiver.STOP"
        const val ACTION_STOP_PERMISSION_REVOKED = "com.telelink.tv.receiver.STOP_PERMISSION_REVOKED"
        const val ACTION_CLEAR_PENDING_LAUNCH = "com.telelink.tv.receiver.CLEAR_PENDING_LAUNCH"
        const val ACTION_EXECUTE_PENDING_LAUNCH = "com.telelink.tv.receiver.EXECUTE_PENDING_LAUNCH"
        const val EXTRA_PENDING_LAUNCH_TOKEN = "com.telelink.tv.receiver.PENDING_LAUNCH_TOKEN"
        private const val RECEIVER_PORT = 8765
        private val FAST_INPUT_COMMANDS = setOf("KEY_PRESS", "TOUCH_GESTURE")
        private const val PIN_TICK_MS = 1000L
        private const val PIN_VALIDITY_SECONDS = 5 * 60
        private const val NOTIFICATION_CHANNEL_ID = "telelink_receiver"
        private const val NOTIFICATION_ID = 7601
        private const val REQUEST_OPEN_APP = 7602
        private const val REQUEST_STOP = 7603
        private const val REQUEST_PENDING_LAUNCH = 7604
    }
}
