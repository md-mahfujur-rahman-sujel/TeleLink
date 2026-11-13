package com.telelink.remote

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

private const val DISCOVERY_WINDOW_MS = 12_000L

class MainActivity : ComponentActivity() {
    private var sharedText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedText = readSharedText(intent)
        setContent { TeleLinkRemoteScreen(sharedText) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedText = readSharedText(intent)
    }

    private fun readSharedText(intent: Intent?): String? {
        if (intent == null) return null
        val shared = when (intent.action) {
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
        return shared?.trim()?.takeIf { it.isNotEmpty() }?.take(8192)
    }
}

@Composable
private fun TeleLinkRemoteScreen(sharedText: String?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var status by remember { mutableStateOf("Scan the pairing QR shown on TeleLink TV") }
    var target by remember { mutableStateOf<TvPairingPayload?>(null) }
    var client by remember { mutableStateOf<TvRemoteClient?>(null) }
    var isPaired by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var tvApps by remember { mutableStateOf<List<TvApp>>(emptyList()) }
    var pendingShare by remember { mutableStateOf<String?>(null) }
    var discoveredTvs by remember { mutableStateOf<List<DiscoveredTv>>(emptyList()) }
    var savedCredentials by remember { mutableStateOf<List<SavedTvCredential>>(emptyList()) }
    var credentialStoreError by remember { mutableStateOf<String?>(null) }
    var isDiscovering by remember { mutableStateOf(false) }
    var discoverySessionId by remember { mutableLongStateOf(0L) }
    var expectedFingerprint by remember { mutableStateOf<String?>(null) }
    var permissionPurpose by remember { mutableStateOf("pair") }
    var pendingPermissionConnection by remember { mutableStateOf<TvPairingPayload?>(null) }
    var manualHost by remember { mutableStateOf("") }
    var useManualHost by remember { mutableStateOf(false) }
    val credentialStore = remember { SecureCredentialStore(context) }
    val discovery = remember { TvDiscovery(context) }

    fun refreshSavedCredentials(): Boolean = try {
        savedCredentials = credentialStore.list()
        credentialStoreError = null
        true
    } catch (exception: Exception) {
        credentialStoreError = exception.message
            ?: "Saved TV credentials could not be read. Clear TeleLink Remote app data and pair again."
        false
    }
    val discoveryTimeout = remember {
        Runnable {
            discovery.stop()
            isDiscovering = false
            if (discoveredTvs.isEmpty()) status = "No TeleLink TVs found. Check Wi-Fi or enter the TV address."
        }
    }

    fun connectToTv(pairing: TvPairingPayload) {
        if (Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_LOCAL_NETWORK
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            val existingClient = client
            client = null
            existingClient?.close()
            status = "Allow local network access before connecting to your TV"
            isPaired = false
            return
        }
        if (!refreshSavedCredentials()) return
        client?.close()
        val saved = savedCredentials.firstOrNull { it.fingerprint == pairing.fingerprint }
        val connectionTarget = if (saved != null) {
            pairing.copy(deviceId = saved.deviceId, token = saved.token, pin = "")
        } else {
            pairing
        }
        target = connectionTarget
        isPaired = false
        tvApps = emptyList()
        status = "Connecting securely to ${connectionTarget.host}…"
        lateinit var newClient: TvRemoteClient
        newClient = TvRemoteClient(
            target = connectionTarget,
            onStatus = { value ->
                mainHandler.post {
                    if (client === newClient) {
                        status = value
                        if (isDisconnectedConnectionStatus(value)) {
                            isPaired = false
                        }
                    }
                }
            },
            onPaired = { success, message, deviceId, token ->
                mainHandler.post {
                    if (client === newClient) {
                        isPaired = success
                        status = if (success) "Paired with TV" else message
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
                                credentialStoreError = exception.message
                                    ?: "Paired, but the reconnect credential could not be saved securely."
                            }
                        }
                    }
                }
            },
            onApps = { apps -> mainHandler.post { if (client === newClient) tvApps = apps } }
        )
        client = newClient
        newClient.connect()
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents == null) {
            expectedFingerprint = null
            useManualHost = false
            status = "Pairing scan cancelled"
        } else {
            val pairing = TvPairingPayload.parse(result.contents)
            if (pairing == null) {
                status = "That QR code is not a valid TeleLink pairing code"
            } else if (expectedFingerprint != null && pairing.fingerprint != expectedFingerprint) {
                status = "The scanned code belongs to a different TV"
                expectedFingerprint = null
            } else {
                expectedFingerprint = null
                val target = if (pairing.host.isBlank()) {
                    discoveredTvs.firstOrNull { it.fingerprint == pairing.fingerprint }
                        ?.let { pairing.copy(host = it.host, port = it.port) }
                } else {
                    pairing
                }
                val selectedTarget = if (useManualHost && manualHost.isNotBlank()) {
                    target?.copy(host = manualHost.trim()) ?: pairing.copy(
                        host = manualHost.trim()
                    )
                } else {
                    target
                }
                useManualHost = false
                if (selectedTarget == null || selectedTarget.host.isBlank()) {
                    status = "TV IP is not shown yet. Discover TVs on Wi-Fi, then scan again."
                } else {
                    connectToTv(selectedTarget)
                }
            }
        }
    }

    fun scanPairingCode() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan the pairing QR shown on your TV")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
        scanLauncher.launch(options)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) scanPairingCode()
        else {
            expectedFingerprint = null
            useManualHost = false
            status = "Camera permission is needed to scan the TV's secure pairing code."
        }
    }

    fun startDiscovery() {
        mainHandler.removeCallbacks(discoveryTimeout)
        discovery.stop()
        discoveredTvs = emptyList()
        isDiscovering = true
        discoverySessionId = 0L
        status = "Searching for TeleLink TVs on this Wi-Fi…"
        discoverySessionId = discovery.start(
            onFound = { tv ->
                mainHandler.post {
                    if (isDiscovering && tv.sessionId == discoverySessionId) {
                        if (discoveredTvs.none { it.fingerprint == tv.fingerprint }) {
                            discoveredTvs = discoveredTvs + tv
                        }
                        status = "Found ${discoveredTvs.size} TeleLink TV${if (discoveredTvs.size == 1) "" else "s"}"
                    }
                }
            },
            onFailure = { message ->
                mainHandler.post {
                    mainHandler.removeCallbacks(discoveryTimeout)
                    discovery.stop()
                    isDiscovering = false
                    status = message
                }
            },
            onLost = { serviceName, sessionId ->
                mainHandler.post {
                    if (isDiscovering && sessionId == discoverySessionId) {
                        discoveredTvs = discoveredTvs.filterNot { it.name == serviceName }
                        status = if (discoveredTvs.isEmpty()) "The discovered TV is no longer available" else status
                    }
                }
            }
        )
        mainHandler.postDelayed(discoveryTimeout, DISCOVERY_WINDOW_MS)
    }

    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (permissionPurpose == "manualConnect") {
                pendingPermissionConnection?.let(::connectToTv)
                pendingPermissionConnection = null
            } else if (permissionPurpose == "discover") {
                startDiscovery()
            } else if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                scanPairingCode()
            }
        }
        else {
            pendingPermissionConnection = null
            expectedFingerprint = null
            useManualHost = false
            status = "Allow local network access to connect to your TV"
        }
    }

    fun connectWithLocalNetworkPermission(pairing: TvPairingPayload) {
        if (Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_LOCAL_NETWORK
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingPermissionConnection = pairing
            permissionPurpose = "manualConnect"
            localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            connectToTv(pairing)
        }
    }

    fun beginSecurePairing() {
        if (Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_LOCAL_NETWORK
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionPurpose = "pair"
            localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            scanPairingCode()
        }
    }

    fun connectUsingManualAddress() {
        val host = manualHost.trim()
        if (!host.matches(Regex("[A-Za-z0-9.-]{1,253}")) ||
            host.startsWith('.') || host.endsWith('.') || host.contains("..")
        ) {
            status = "Enter a valid IPv4 address or local host name"
            return
        }
        if (!refreshSavedCredentials()) return
        val saved = savedCredentials.lastOrNull()
        if (saved != null) {
            connectWithLocalNetworkPermission(
                TvPairingPayload(
                    host = host,
                    port = saved.port,
                    fingerprint = saved.fingerprint,
                    pin = "",
                    deviceId = saved.deviceId,
                    token = saved.token
                )
            )
        } else {
            useManualHost = true
            beginSecurePairing()
        }
    }

    fun beginDiscovery() {
        if (Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_LOCAL_NETWORK
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionPurpose = "discover"
            localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            startDiscovery()
        }
    }

    LaunchedEffect(Unit) {
        if (!refreshSavedCredentials()) return@LaunchedEffect
        savedCredentials.lastOrNull()?.let { saved ->
            if (Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_LOCAL_NETWORK
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                connectToTv(
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
                status = "Find your TV or allow local network access to reconnect"
            }
        }
    }

    LaunchedEffect(isPaired, client) {
        if (isPaired) client?.sendCommand("LIST_APPS")
    }

    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) {
            pendingShare = sharedText
            if (sharedText.startsWith("http://", true) || sharedText.startsWith("https://", true)) {
                urlInput = sharedText
            } else {
                textInput = sharedText
            }
        }
    }

    DisposableEffect(client) {
        onDispose { client?.close() }
    }
    DisposableEffect(context, client) {
        val activity = findComponentActivity(context)
        if (activity == null) {
            onDispose { }
        } else {
            val permissionObserver = LifecycleEventObserver { _, event ->
                val hasNetworkSession = client != null || isDiscovering
                val permissionRevoked = Build.VERSION.SDK_INT >= 37 &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_LOCAL_NETWORK
                    ) != PackageManager.PERMISSION_GRANTED
                if (event == Lifecycle.Event.ON_RESUME && hasNetworkSession && permissionRevoked) {
                    val activeClient = client
                    client = null
                    activeClient?.close()
                    isPaired = false
                    pendingPermissionConnection = null
                    mainHandler.removeCallbacks(discoveryTimeout)
                    discovery.stop()
                    isDiscovering = false
                    discoveredTvs = emptyList()
                    status = "Local network access was revoked. Allow it in Settings, then reconnect."
                }
            }
            activity.lifecycle.addObserver(permissionObserver)
            onDispose { activity.lifecycle.removeObserver(permissionObserver) }
        }
    }
    DisposableEffect(discovery) {
        onDispose {
            mainHandler.removeCallbacks(discoveryTimeout)
            discovery.stop()
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = painterResource(R.drawable.telelink_logo),
                        contentDescription = "TeleLink logo",
                        modifier = Modifier.width(260.dp).height(68.dp)
                    )
                    Text("Phone Remote", fontSize = 18.sp)
                }
                Spacer(Modifier.height(20.dp))
                Text(credentialStoreError ?: status, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = { beginSecurePairing() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isPaired) "Scan another TV" else "Scan TV pairing code")
                }

                OutlinedButton(
                    onClick = { beginDiscovery() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (isDiscovering) "Searching for TVs…" else "Discover TVs on Wi-Fi") }

                OutlinedTextField(
                    value = manualHost,
                    onValueChange = { manualHost = it },
                    label = { Text("TV IP address or local host") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = { connectUsingManualAddress() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (savedCredentials.isEmpty()) "Scan QR and pair at this address" else "Connect at this address") }

                discoveredTvs.forEach { tv ->
                    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(tv.name, fontWeight = FontWeight.SemiBold)
                            Text("${tv.host}:${tv.port}", style = MaterialTheme.typography.bodySmall)
                            val saved = savedCredentials.firstOrNull { it.fingerprint == tv.fingerprint }
                            Button(
                                onClick = {
                                    if (saved != null) {
                                        connectWithLocalNetworkPermission(
                                            TvPairingPayload(
                                                host = tv.host,
                                                port = tv.port,
                                                fingerprint = tv.fingerprint,
                                                pin = "",
                                                deviceId = saved.deviceId,
                                                token = saved.token
                                            )
                                        )
                                    } else {
                                        expectedFingerprint = tv.fingerprint
                                        beginSecurePairing()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (saved != null) "Reconnect" else "Scan QR to trust and pair")
                            }
                        }
                    }
                }

                if (!isPaired && target?.let { targetTv ->
                        savedCredentials.any { it.fingerprint == targetTv.fingerprint }
                    } == true
                ) {
                    OutlinedButton(
                        onClick = {
                            client?.close()
                            client = null
                            try {
                                target?.fingerprint?.let(credentialStore::remove)
                                refreshSavedCredentials()
                            } catch (exception: Exception) {
                                credentialStoreError = exception.message
                                    ?: "Saved pairing could not be removed securely."
                            }
                            target = null
                            tvApps = emptyList()
                            status = "Saved pairing removed. Scan the TV code to pair again."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Forget saved pairing and pair again") }
                }

                if (target != null) {
                    Spacer(Modifier.height(20.dp))
                    Text("TV ${target!!.host}:${target!!.port}")
                    Text(
                        "Certificate verified from the QR code",
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                pendingShare?.let { shared ->
                    Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Shared from another app", fontWeight = FontWeight.SemiBold)
                            Text(shared.take(300), style = MaterialTheme.typography.bodySmall)
                            Button(
                                onClick = {
                                    if (shared.startsWith("http://", true) || shared.startsWith("https://", true)) {
                                        val error = sendUrl(client, shared, "integrated")
                                        if (error == null) pendingShare = null else status = error
                                    } else {
                                        client?.sendCommand(
                                            "SHARE_TEXT",
                                            JSONObject().put("text", shared)
                                        )
                                        pendingShare = null
                                    }
                                },
                                enabled = isPaired,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (shared.startsWith("http", true)) "Open on TV" else "Send to TV") }
                        }
                    }
                }

                if (isPaired) {
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))

                    Text("Navigation", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { sendKey(client, "UP") }) { Text("▲") }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { sendKey(client, "LEFT") }) { Text("◀") }
                        Button(onClick = { sendKey(client, "SELECT") }) { Text("OK") }
                        Button(onClick = { sendKey(client, "RIGHT") }) { Text("▶") }
                    }
                    Button(onClick = { sendKey(client, "DOWN") }) { Text("▼") }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { sendKey(client, "BACK") }) { Text("Back") }
                        OutlinedButton(onClick = { sendKey(client, "HOME") }) { Text("Home") }
                    }

                    Spacer(Modifier.height(18.dp))
                    Text("Touchpad", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .pointerInput(client, isPaired) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val start = down.position
                                    var end = start
                                    var event: androidx.compose.ui.input.pointer.PointerEvent
                                    do {
                                        event = awaitPointerEvent()
                                        val change = event.changes.first()
                                        end = change.position
                                        change.consume()
                                    } while (event.changes.any { it.pressed })
                                    val width = size.width.toFloat().coerceAtLeast(1f)
                                    val height = size.height.toFloat().coerceAtLeast(1f)
                                    client?.sendCommand(
                                        "TOUCH_GESTURE",
                                        JSONObject()
                                            .put("x", start.x / width)
                                            .put("y", start.y / height)
                                            .put("endX", end.x / width)
                                            .put("endY", end.y / height)
                                            .put("durationMs", 180)
                                    )
                                }
                            },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Tap or swipe to control the TV screen")
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        label = { Text("Type text for the TV") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            client?.sendCommand(
                                "TEXT_INPUT",
                                JSONObject().put("text", textInput)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Send text") }

                    Spacer(Modifier.height(18.dp))
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("URL to open on TV") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                sendUrl(client, urlInput, "integrated")?.let { status = it }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Open in TeleLink") }
                        OutlinedButton(
                            onClick = {
                                sendUrl(client, urlInput, "external")?.let { status = it }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("External browser") }
                    }

                    Spacer(Modifier.height(18.dp))
                    Text("TV apps", fontWeight = FontWeight.SemiBold)
                    if (tvApps.isEmpty()) {
                        Text("No launchable apps were returned")
                    } else {
                        tvApps.forEach { app ->
                            OutlinedButton(
                                onClick = {
                                    client?.sendCommand(
                                        "LAUNCH_APP",
                                        JSONObject().put("packageName", app.packageName)
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(app.name) }
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    Text("Playback", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { sendMedia(client, "PLAY") }) { Text("Play") }
                        Button(onClick = { sendMedia(client, "PAUSE") }) { Text("Pause") }
                        OutlinedButton(onClick = { sendMedia(client, "STOP") }) { Text("Stop") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { sendMedia(client, "SEEK_BACK") }) { Text("−10 sec") }
                        OutlinedButton(onClick = { sendMedia(client, "SEEK_FORWARD") }) { Text("+10 sec") }
                        OutlinedButton(onClick = { sendMedia(client, "VOLUME_DOWN") }) { Text("Vol −") }
                        OutlinedButton(onClick = { sendMedia(client, "VOLUME_UP") }) { Text("Vol +") }
                    }
                    OutlinedButton(
                        onClick = {
                            client?.close()
                            client = null
                            target = null
                            isPaired = false
                            status = "Disconnected"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Disconnect") }
                    TextButton(
                        onClick = {
                            client?.close()
                            client = null
                            isPaired = false
                            try {
                                target?.fingerprint?.let(credentialStore::remove)
                                refreshSavedCredentials()
                            } catch (exception: Exception) {
                                credentialStoreError = exception.message
                                    ?: "Saved pairing could not be removed securely."
                            }
                            status = "Saved TV credentials removed from this phone"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Forget saved TV on this phone") }
                }
            }
        }
    }
}

private fun sendKey(client: TvRemoteClient?, key: String) {
    client?.sendCommand("KEY_PRESS", JSONObject().put("key", key))
}

private fun findComponentActivity(context: Context): ComponentActivity? {
    var current = context
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    return current as? ComponentActivity
}

private fun sendUrl(client: TvRemoteClient?, value: String, target: String): String? {
    val url = value.trim()
    validateOpenUrl(url)?.let { return it }
    val connectedClient = client ?: return "Connect and pair with a TV first"
    if (!connectedClient.isOpen) return "Connect and pair with a TV first"
    connectedClient.sendCommand(
        "OPEN_URL",
        JSONObject().put("url", url).put("target", target)
    )
    return null
}

private fun sendMedia(client: TvRemoteClient?, action: String) {
    client?.sendCommand("MEDIA_CONTROL", JSONObject().put("action", action))
}
