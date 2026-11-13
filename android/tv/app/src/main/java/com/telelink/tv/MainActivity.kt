
package com.telelink.tv

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telelink.tv.network.TvCommandRouter
import com.telelink.tv.security.PairedDevice
import com.telelink.tv.ui.theme.TeleLinkTVTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            TeleLinkTVTheme {
                TeleLinkHomeScreen()
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

    val pairingQrBitmap = remember(pairingPin, ipAddress, certificateFingerprint) {
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
            createQrBitmap(payload)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),

            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(R.drawable.telelink_logo),
                    contentDescription = "TeleLink logo",
                    modifier = Modifier.width(380.dp).height(100.dp)
                )
                Text("TV Companion", fontSize = 18.sp)
            }

            Spacer(modifier = Modifier.height(32.dp))

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {

                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                    Text(
                        text = "DEVICE INFORMATION",
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(text = "Device: ${Build.MANUFACTURER} ${Build.MODEL}")

                    Text(text = "Local IP: $ipAddress")

                    Text(text = "Connection: Wi-Fi")

                    Text(text = "Receiver Port: 8765")

                    Text(
                        text = if (connectedPhoneCount == 1) {
                            "Connected phones: 1"
                        } else {
                            "Connected phones: $connectedPhoneCount"
                        }
                    )

                    if (pairedDevices.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("PAIRED PHONES", fontWeight = FontWeight.Bold)
                        pairedDevices.forEach { device ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(device.name, modifier = Modifier.weight(1f))
                                TextButton(
                                    onClick = {
                                        receiverService?.revokeDevice(device.id)
                                    }
                                ) {
                                    Text("Revoke")
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Receiver Status: $receiverStatus",
                        color = if (receiverRunning || receiverStarting) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        fontWeight = FontWeight.Bold
                    )

                    if (receiverRunning && !notificationAccessGranted) {
                        Text(
                            text = "Notifications are off. Use Stop Receiver here when finished.",
                            fontSize = 12.sp
                        )
                    }

                    if (pairingPin != null &&
                        (receiverRunning || receiverStarting)
                    ) {

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = "PAIRING PIN",
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = pairingPin!!,
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 8.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Expires in %d:%02d. A new PIN is issued " +
                                    "after pairing or when this one expires."
                                .format(
                                    pinSecondsRemaining / 60,
                                    pinSecondsRemaining % 60
                                ),
                            fontSize = 12.sp
                        )

                        if (pairingQrBitmap != null) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Image(
                                bitmap = pairingQrBitmap.asImageBitmap(),
                                contentDescription = "Scan with TeleLink Remote to pair securely",
                                modifier = Modifier.size(220.dp)
                            )
                            Text(
                                text = "Scan this code from TeleLink Remote. " +
                                        "It verifies this TV and starts secure pairing.",
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {

                    if (Build.VERSION.SDK_INT >= 37) {

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
                },
                enabled = !receiverRunning && !receiverStarting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    if (receiverStarting) {
                        "Starting..."
                    } else {
                        "Start Receiver"
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = { commandRouter.openAccessibilitySettings() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Enable phone controls")
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    context.startService(
                        Intent(context, ReceiverForegroundService::class.java)
                            .setAction(ReceiverForegroundService.ACTION_STOP)
                    )
                },
                enabled = receiverRunning || receiverStarting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Stop Receiver")
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    receiverService?.refreshIpAddress()
                    if (receiverService == null) ipAddress = getLocalIpAddress(context)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Refresh IP Address")
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "TeleLink TV 1.0",
                fontSize = 12.sp
            )
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

private fun createQrBitmap(value: String): Bitmap {
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 512, 512)
    val pixels = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            pixels[y * matrix.width + x] =
                if (matrix[x, y]) android.graphics.Color.BLACK
                else android.graphics.Color.WHITE
        }
    }
    return createBitmap(matrix.width, matrix.height)
        .apply { setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height) }
}

private fun findComponentActivity(context: Context): ComponentActivity? {
    var current = context
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    return current as? ComponentActivity
}
