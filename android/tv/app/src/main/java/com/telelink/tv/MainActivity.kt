
package com.telelink.tv

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.telelink.tv.network.TvCommandRouter
import com.telelink.tv.security.PairedDevice
import com.telelink.tv.ui.DetailsCard
import com.telelink.tv.ui.PairedPhonesCard
import com.telelink.tv.ui.PairingCard
import com.telelink.tv.ui.ReceiverStatusCard
import com.telelink.tv.ui.TvBackdrop
import com.telelink.tv.ui.DeveloperInfo
import com.telelink.tv.ui.TvBrandHeader
import com.telelink.tv.ui.TvContactScreen
import com.telelink.tv.ui.TvButton
import com.telelink.tv.ui.TvButtonStyle
import com.telelink.tv.ui.TvDesignSpace
import com.telelink.tv.ui.theme.TeleLinkTVTheme
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.ByteMatrix
import com.google.zxing.qrcode.encoder.Encoder
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            TeleLinkTVTheme {
                TvDesignSpace {
                    TeleLinkHomeScreen()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        window.decorView.post { executePendingRemoteLaunch(intent) }
    }

    override fun onResume() {
        super.onResume()
        executePendingRemoteLaunch(intent)
    }

    private fun executePendingRemoteLaunch(request: Intent?) {
        if (request?.action != ReceiverForegroundService.ACTION_EXECUTE_PENDING_LAUNCH) return
        request.action = null

        val token = request.getStringExtra(ReceiverForegroundService.EXTRA_PENDING_LAUNCH_TOKEN)
            ?: return
        val authorized = (application as TeleLinkApplication).consumeRemoteLaunch(token) ?: return
        val command = when (authorized.type) {
            "OPEN_URL" -> org.json.JSONObject()
                .put("type", "OPEN_URL")
                .put("url", authorized.value)
                .put(
                    "target",
                    if (authorized.integrated) "integrated" else "external"
                )
            "LAUNCH_APP" -> org.json.JSONObject()
                .put("type", "LAUNCH_APP")
                .put("packageName", authorized.value)
            else -> return
        }
        TvCommandRouter(this).execute(command) { result ->
            if (!result.success) {
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
            startService(
                Intent(this, ReceiverForegroundService::class.java)
                    .setAction(ReceiverForegroundService.ACTION_CLEAR_PENDING_LAUNCH)
                    .putExtra(ReceiverForegroundService.EXTRA_PENDING_LAUNCH_TOKEN, token)
            )
        }
    }
}

@Composable
fun TeleLinkHomeScreen() {

    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val commandRouter = remember { TvCommandRouter(context) }
    var receiverService by remember { mutableStateOf<ReceiverForegroundService?>(null) }

    val serviceConnection = remember(context) {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: android.os.IBinder?) {
                receiverService = (binder as? ReceiverForegroundService.LocalBinder)?.service
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                receiverService = null
            }
        }
    }

    DisposableEffect(context, serviceConnection) {
        val bound = context.bindService(
            Intent(context, ReceiverForegroundService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
        onDispose {
            if (bound) context.unbindService(serviceConnection)
        }
    }

    var receiverRunning by remember {
        mutableStateOf(false)
    }

    var receiverStarting by remember {
        mutableStateOf(false)
    }

    var receiverStatus by remember {
        mutableStateOf("Receiver stopped")
    }

    var notificationAccessGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var pairingPin by remember {
        mutableStateOf<String?>(null)
    }

    var pinSecondsRemaining by remember {
        mutableIntStateOf(0)
    }

    var connectedPhoneCount by remember {
        mutableIntStateOf(0)
    }

    var pairedDevices by remember {
        mutableStateOf<List<PairedDevice>>(emptyList())
    }

    var certificateFingerprint by remember {
        mutableStateOf<String?>(null)
    }

    var ipAddress by remember {
        mutableStateOf(getLocalIpAddress(context))
    }

    val snapshotObserver = remember(mainHandler) {
        ReceiverSnapshotObserver { state ->
            mainHandler.post {
                receiverRunning = state.running
                receiverStarting = state.starting
                receiverStatus = state.status
                pairingPin = state.pairingPin
                pinSecondsRemaining = state.pinSecondsRemaining
                connectedPhoneCount = state.connectedPhoneCount
                pairedDevices = state.pairedDevices
                certificateFingerprint = state.certificateFingerprint
                ipAddress = state.ipAddress
            }
        }
    }

    DisposableEffect(receiverService, snapshotObserver) {
        receiverService?.addObserver(snapshotObserver)
        onDispose { receiverService?.removeObserver(snapshotObserver) }
    }

    DisposableEffect(context, receiverRunning, receiverStarting) {
        val activity = findComponentActivity(context)
        if (activity == null) {
            onDispose { }
        } else {
            val permissionObserver = LifecycleEventObserver { _, event ->
                val receiverActive = receiverRunning || receiverStarting
                val permissionRevoked = Build.VERSION.SDK_INT >= 37 &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_LOCAL_NETWORK
                    ) != PackageManager.PERMISSION_GRANTED
                if (event == Lifecycle.Event.ON_RESUME && receiverActive && permissionRevoked) {
                    context.startService(
                        Intent(context, ReceiverForegroundService::class.java)
                            .setAction(ReceiverForegroundService.ACTION_STOP_PERMISSION_REVOKED)
                    )
                }
            }
            activity.lifecycle.addObserver(permissionObserver)
            onDispose { activity.lifecycle.removeObserver(permissionObserver) }
        }
    }

    fun startReceiverNow() {
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ReceiverForegroundService::class.java)
                    .setAction(ReceiverForegroundService.ACTION_START)
            )
            receiverStarting = true
            receiverStatus = "Starting receiver…"
        } catch (exception: Exception) {
            receiverStarting = false
            receiverStatus = "Could not start receiver: ${exception.message ?: "service error"}"
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationAccessGranted = granted
        // Foreground service startup remains available if the user declines notifications.
        startReceiverNow()
    }

    fun startAfterNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startReceiverNow()
        }
    }

    val localNetworkPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->

            if (isGranted) {
                startAfterNotificationPermission()
            } else {
                pairingPin = null
                receiverRunning = false
                receiverStarting = false
                receiverStatus = "Local network permission denied"
            }
        }

    val pairingQrMatrix = remember(pairingPin, ipAddress, certificateFingerprint) {
        val pin = pairingPin
        val fingerprint = certificateFingerprint
        if (pin == null || fingerprint == null) {
            null
        } else {
            val payload = Uri.Builder()
                .scheme("telelink")
                .authority("pair")
                .appendQueryParameter(
                    "host",
                    if (ipAddress == "Unavailable" || ipAddress == "Not connected") "" else ipAddress
                )
                .appendQueryParameter("port", "8765")
                .appendQueryParameter("fingerprint", fingerprint)
                .appendQueryParameter("pin", pin)
                .build()
                .toString()
            createQrMatrix(payload)
        }
    }

    fun toggleReceiver() {
        if (receiverRunning || receiverStarting) {
            context.startService(
                Intent(context, ReceiverForegroundService::class.java)
                    .setAction(ReceiverForegroundService.ACTION_STOP)
            )
        } else if (Build.VERSION.SDK_INT >= 37) {
            val permissionGranted =
                context.checkSelfPermission(
                    Manifest.permission.ACCESS_LOCAL_NETWORK
                ) == PackageManager.PERMISSION_GRANTED

            if (permissionGranted) {
                startAfterNotificationPermission()
            } else {
                localNetworkPermissionLauncher.launch(
                    Manifest.permission.ACCESS_LOCAL_NETWORK
                )
            }
        } else {
            startAfterNotificationPermission()
        }
    }

    // The main action gets D-pad focus on launch so the remote's OK button works immediately.
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { primaryFocus.requestFocus() }
        // Silently turn phone controls on when the one-time adb grant has been done.
        commandRouter.autoEnableAccessibility()
    }

    val receiverActive = receiverRunning || receiverStarting

    // "Contact developer" swaps the home screen for a page with the developer's details and QR codes.
    var showContact by remember { mutableStateOf(false) }
    val contactQrs = remember {
        DeveloperInfo.links.map { link -> link to createQrMatrix(link.url) }
    }
    BackHandler(enabled = showContact) { showContact = false }
    LaunchedEffect(showContact) {
        if (!showContact) runCatching { primaryFocus.requestFocus() }
    }

    TvBackdrop {
        if (showContact) {
            TvContactScreen(qrCodes = contactQrs, onClose = { showContact = false })
        } else
        // 48 dp / 28 dp margins keep everything inside the TV's overscan-safe area.
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TvBrandHeader()
                ReceiverStatusCard(
                    status = receiverStatus,
                    running = receiverRunning,
                    starting = receiverStarting,
                    showNotificationHint = receiverRunning && !notificationAccessGranted
                )
                PairingCard(
                    receiverActive = receiverActive,
                    pin = pairingPin,
                    secondsRemaining = pinSecondsRemaining,
                    qr = pairingQrMatrix,
                    modifier = Modifier.weight(1f)
                )
            }

            Column(
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TvButton(
                    text = when {
                        receiverStarting -> "Starting…"
                        receiverRunning -> "Stop Receiver"
                        else -> "Start Receiver"
                    },
                    onClick = { toggleReceiver() },
                    style = if (receiverActive) TvButtonStyle.Secondary else TvButtonStyle.Primary,
                    focusRequester = primaryFocus,
                    modifier = Modifier.fillMaxWidth()
                )
                TvButton(
                    text = "Enable phone controls",
                    onClick = {
                        commandRouter.openAccessibilitySettings()?.let { hint ->
                            Toast.makeText(context, hint, Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                TvButton(
                    text = "Refresh IP address",
                    onClick = {
                        receiverService?.refreshIpAddress()
                        if (receiverService == null) ipAddress = getLocalIpAddress(context)
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(4.dp))

                DetailsCard(
                    device = "${Build.MANUFACTURER} ${Build.MODEL}",
                    address = "$ipAddress:8765",
                    connectedPhones = connectedPhoneCount
                )

                PairedPhonesCard(
                    devices = pairedDevices,
                    onRevoke = { device -> receiverService?.revokeDevice(device.id) }
                )

                TvButton(
                    text = "Contact developer",
                    onClick = { showContact = true },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "TeleLink TV 1.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

fun getLocalIpAddress(context: Context): String {

    return try {

        val connectivityManager =
            context.getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as ConnectivityManager

        val network = connectivityManager.activeNetwork
            ?: return "Not connected"

        val linkProperties =
            connectivityManager.getLinkProperties(network)
                ?: return "Unavailable"

        val ipv4Address = linkProperties.linkAddresses
            .mapNotNull { it.address as? java.net.Inet4Address }
            .firstOrNull {
                !it.isLoopbackAddress && !it.isLinkLocalAddress
            }

        ipv4Address?.hostAddress ?: "Unavailable"

    } catch (e: Exception) {

        "Unavailable"

    }
}

private fun createQrMatrix(value: String): ByteMatrix? = try {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
    )
    Encoder.encode(value, ErrorCorrectionLevel.M, hints).matrix
} catch (_: Exception) {
    null
}

private fun findComponentActivity(context: Context): ComponentActivity? {
    var current = context
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    return current as? ComponentActivity
}
