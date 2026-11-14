package com.telelink.remote

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.bluetooth.BluetoothAdapter
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
        setContent {
            TeleLinkRemoteTheme {
                TeleLinkRemoteScreen(sharedText)
            }
        }
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

    // Both connections are owned by RemoteConnectionService. The screen only observes its state and
    // forwards user actions, so rotating or backgrounding the screen never drops a connection.
    var conn by remember { mutableStateOf(RemoteConnectionState()) }
    var service by remember { mutableStateOf<RemoteConnectionService?>(null) }
    val serviceConnection = remember(context) {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? RemoteConnectionService.LocalBinder)?.service
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
    }
    DisposableEffect(context, serviceConnection) {
        val bound = context.bindService(
            Intent(context, RemoteConnectionService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
        onDispose { if (bound) context.unbindService(serviceConnection) }
    }
    DisposableEffect(service) {
        val current = service
        if (current == null) {
            onDispose { }
        } else {
            val observer = RemoteConnectionObserver { state -> mainHandler.post { conn = state } }
            current.addObserver(observer)
            onDispose { current.removeObserver(observer) }
        }
    }

    fun ensureServiceStarted() {
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RemoteConnectionService::class.java)
            )
        } catch (_: Exception) {
            // The remote still works while the app is open if Android refuses the service.
        }
    }

    fun setStatus(message: String) {
        service?.setStatus(message)
    }

    // UI-flow state (discovery, pairing, input) stays on the screen; connection state comes from conn.
    var textInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var pendingShare by remember { mutableStateOf<String?>(null) }
    var discoveredTvs by remember { mutableStateOf<List<DiscoveredTv>>(emptyList()) }
    var isDiscovering by remember { mutableStateOf(false) }
    var discoverySessionId by remember { mutableLongStateOf(0L) }
    var expectedFingerprint by remember { mutableStateOf<String?>(null) }
    var permissionPurpose by remember { mutableStateOf("pair") }
    var pendingPermissionConnection by remember { mutableStateOf<TvPairingPayload?>(null) }
    var manualHost by remember { mutableStateOf("") }
    var useManualHost by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    val discovery = remember { TvDiscovery(context) }

    val isPaired = conn.isPaired
    val target = conn.wifiTarget
    val savedCredentials = conn.savedCredentials
    val btStatus = conn.btStatus

    fun startBluetoothRemote() {
        ensureServiceStarted()
        service?.startBluetooth()
    }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) startBluetoothRemote()
        else service?.setBluetoothMessage("Allow the Nearby devices permission to use the Bluetooth remote")
    }
    val btVisibleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }
    fun beginBluetoothRemote() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
            if (needed.any {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
            ) {
                btPermissionLauncher.launch(needed)
                return
            }
        }
        startBluetoothRemote()
    }

    val discoveryTimeout = remember {
        Runnable {
            discovery.stop()
            isDiscovering = false
            if (discoveredTvs.isEmpty()) setStatus("No TeleLink TVs found. Check Wi-Fi or enter the TV address.")
        }
    }

    fun connectToTv(pairing: TvPairingPayload) {
        ensureServiceStarted()
        service?.connectWifi(pairing)
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents == null) {
            expectedFingerprint = null
            useManualHost = false
            setStatus("Pairing scan cancelled")
        } else {
            val pairing = TvPairingPayload.parse(result.contents)
            if (pairing == null) {
                setStatus("That QR code is not a valid TeleLink pairing code")
            } else if (expectedFingerprint != null && pairing.fingerprint != expectedFingerprint) {
                setStatus("The scanned code belongs to a different TV")
                expectedFingerprint = null
            } else {
                expectedFingerprint = null
                val resolved = if (pairing.host.isBlank()) {
                    discoveredTvs.firstOrNull { it.fingerprint == pairing.fingerprint }
                        ?.let { pairing.copy(host = it.host, port = it.port) }
                } else {
                    pairing
                }
                val selectedTarget = if (useManualHost && manualHost.isNotBlank()) {
                    resolved?.copy(host = manualHost.trim()) ?: pairing.copy(host = manualHost.trim())
                } else {
                    resolved
                }
                useManualHost = false
                if (selectedTarget == null || selectedTarget.host.isBlank()) {
                    setStatus("TV IP is not shown yet. Discover TVs on Wi-Fi, then scan again.")
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
            setStatus("Camera permission is needed to scan the TV's secure pairing code.")
        }
    }

    fun startDiscovery() {
        mainHandler.removeCallbacks(discoveryTimeout)
        discovery.stop()
        discoveredTvs = emptyList()
        isDiscovering = true
        discoverySessionId = 0L
        setStatus("Searching for TeleLink TVs on this Wi-Fi…")
        discoverySessionId = discovery.start(
            onFound = { tv ->
                mainHandler.post {
                    if (isDiscovering && tv.sessionId == discoverySessionId) {
                        if (discoveredTvs.none { it.fingerprint == tv.fingerprint }) {
                            discoveredTvs = discoveredTvs + tv
                        }
                        setStatus("Found ${discoveredTvs.size} TeleLink TV${if (discoveredTvs.size == 1) "" else "s"}")
                    }
                }
            },
            onFailure = { message ->
                mainHandler.post {
                    mainHandler.removeCallbacks(discoveryTimeout)
                    discovery.stop()
                    isDiscovering = false
                    setStatus(message)
                }
            },
            onLost = { serviceName, sessionId ->
                mainHandler.post {
                    if (isDiscovering && sessionId == discoverySessionId) {
                        discoveredTvs = discoveredTvs.filterNot { it.name == serviceName }
                        if (discoveredTvs.isEmpty()) setStatus("The discovered TV is no longer available")
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
        } else {
            pendingPermissionConnection = null
            expectedFingerprint = null
            useManualHost = false
            setStatus("Allow local network access to connect to your TV")
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
            setStatus("Enter a valid IPv4 address or local host name")
            return
        }
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

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Starting the service runs its one-time restore, reconnecting to the last saved TV.
        ensureServiceStarted()
    }

    LaunchedEffect(isPaired) {
        if (isPaired) service?.sendWifiCommand("LIST_APPS")
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

    LaunchedEffect(mode) {
        if (mode == 1 && !btStatus.registered) beginBluetoothRemote()
    }

    // If local-network access is revoked while a session is active, close it and stop discovery.
    DisposableEffect(context, service) {
        val activity = findComponentActivity(context)
        if (activity == null) {
            onDispose { }
        } else {
            val permissionObserver = LifecycleEventObserver { _, event ->
                val hasNetworkSession = isPaired || target != null || isDiscovering
                val permissionRevoked = Build.VERSION.SDK_INT >= 37 &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_LOCAL_NETWORK
                    ) != PackageManager.PERMISSION_GRANTED
                if (event == Lifecycle.Event.ON_RESUME && hasNetworkSession && permissionRevoked) {
                    service?.onLocalNetworkPermissionRevoked()
                    pendingPermissionConnection = null
                    mainHandler.removeCallbacks(discoveryTimeout)
                    discovery.stop()
                    isDiscovering = false
                    discoveredTvs = emptyList()
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

    fun sendShared(shared: String) {
        val current = service ?: return
        if (shared.startsWith("http://", true) || shared.startsWith("https://", true)) {
            val error = current.sendWifiUrl(shared, "integrated")
            if (error == null) pendingShare = null else setStatus(error)
        } else {
            current.sendWifiCommand("SHARE_TEXT", JSONObject().put("text", shared))
            pendingShare = null
        }
    }

    val bannerState = when {
        conn.credentialStoreError != null -> BannerState.Error
        isPaired -> BannerState.Connected
        isDiscovering || conn.status.startsWith("Connecting") || conn.status.startsWith("Searching") -> BannerState.Busy
        else -> BannerState.Idle
    }
    val knownTargetHasSavedPairing = target?.let { targetTv ->
        savedCredentials.any { it.fingerprint == targetTv.fingerprint }
    } == true

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // safeDrawingPadding keeps the UI clear of the status bar, camera cut-out,
        // navigation bar and on-screen keyboard (edge-to-edge is enforced on modern Android).
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp)
        ) {
            BrandHeader(
                connected = if (mode == 1) btStatus.connectedName != null else isPaired
            )
            Spacer(Modifier.height(12.dp))
            SegmentedTabs(
                labels = listOf("Wi-Fi remote", "Bluetooth remote"),
                selected = mode,
                onSelect = { mode = it }
            )
            Spacer(Modifier.height(12.dp))

            if (mode == 0 && isPaired) {
                SegmentedTabs(
                    labels = listOf("Remote", "Type", "Apps", "TV"),
                    selected = selectedTab,
                    onSelect = { selectedTab = it }
                )
                Spacer(Modifier.height(12.dp))
            }

            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val viewportHeight = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
            // Before pairing the content is centred in the screen; once paired it starts at the top.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = viewportHeight),
                verticalArrangement = Arrangement.spacedBy(
                    16.dp,
                    if (isPaired || mode == 1) Alignment.Top else Alignment.CenterVertically
                )
            ) {
                if (mode == 1) {
                    StatusBanner(
                        btStatus.message,
                        when {
                            btStatus.connectedName != null -> BannerState.Connected
                            btStatus.busy -> BannerState.Busy
                            else -> BannerState.Idle
                        }
                    )
                } else {
                    StatusBanner(conn.credentialStoreError ?: conn.status, bannerState)
                }

                pendingShare?.let { shared ->
                    SharedCard(
                        shared = shared,
                        isPaired = isPaired,
                        onSend = { sendShared(shared) }
                    )
                }

                if (mode == 1) {
                    BluetoothContent(
                        status = btStatus,
                        devices = conn.btDevices,
                        onStart = { beginBluetoothRemote() },
                        onMakeVisible = {
                            try {
                                btVisibleLauncher.launch(
                                    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                                        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                                )
                            } catch (_: Exception) {
                                service?.setBluetoothMessage("Could not make the phone visible. Use Bluetooth settings instead.")
                            }
                        },
                        onOpenSettings = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                            } catch (_: Exception) {
                                service?.setBluetoothMessage("Open your phone's Bluetooth settings manually")
                            }
                        },
                        onRefresh = { service?.refreshBluetoothDevices() },
                        onConnect = { service?.connectBluetooth(it.address) },
                        onDisconnect = { service?.disconnectBluetooth() },
                        onKey = { service?.sendBluetoothKey(it) },
                        onMedia = { service?.sendBluetoothMedia(it) },
                        onMove = { dx, dy -> service?.moveBluetoothMouse(dx, dy) },
                        onClick = { service?.clickBluetooth() },
                        onScroll = { service?.scrollBluetooth(it) },
                        onType = { service?.typeBluetooth(it) }
                    )
                } else if (isPaired) {
                    when (selectedTab) {
                        0 -> RemoteTab(
                            onKey = { key ->
                                service?.sendWifiCommand("KEY_PRESS", JSONObject().put("key", key))
                            },
                            onMedia = { action ->
                                service?.sendWifiCommand("MEDIA_CONTROL", JSONObject().put("action", action))
                            },
                            onGesture = { startX, startY, endX, endY, durationMs ->
                                service?.sendWifiCommand(
                                    "TOUCH_GESTURE",
                                    JSONObject()
                                        .put("x", startX)
                                        .put("y", startY)
                                        .put("endX", endX)
                                        .put("endY", endY)
                                        .put("durationMs", durationMs)
                                )
                            }
                        )

                        1 -> TypeTab(
                            textInput = textInput,
                            onTextChange = { textInput = it },
                            onSendText = {
                                service?.sendWifiCommand(
                                    "TEXT_INPUT",
                                    JSONObject().put("text", textInput)
                                )
                            },
                            urlInput = urlInput,
                            onUrlChange = { urlInput = it },
                            onOpenIntegrated = {
                                service?.sendWifiUrl(urlInput, "integrated")?.let { setStatus(it) }
                            },
                            onOpenExternal = {
                                service?.sendWifiUrl(urlInput, "external")?.let { setStatus(it) }
                            }
                        )

                        2 -> AppsTab(
                            apps = conn.tvApps,
                            onLaunch = { app ->
                                service?.sendWifiCommand(
                                    "LAUNCH_APP",
                                    JSONObject().put("packageName", app.packageName)
                                )
                            },
                            onRefresh = { service?.sendWifiCommand("LIST_APPS") }
                        )

                        else -> TvInfoTab(
                            hostPort = target?.let { "${it.host}:${it.port}" } ?: "",
                            onDisconnect = {
                                service?.disconnectWifi(
                                    message = "Disconnected",
                                    forget = false,
                                    clearTarget = true
                                )
                            },
                            onForget = {
                                service?.disconnectWifi(
                                    message = "Saved TV credentials removed from this phone",
                                    forget = true,
                                    clearTarget = false
                                )
                            }
                        )
                    }
                } else {
                    ConnectContent(
                        isDiscovering = isDiscovering,
                        discoveredTvs = discoveredTvs,
                        savedFingerprints = savedCredentials.map { it.fingerprint }.toSet(),
                        manualHost = manualHost,
                        onManualHostChange = { manualHost = it },
                        manualActionLabel = if (savedCredentials.isEmpty()) {
                            "Scan QR and pair at this address"
                        } else {
                            "Connect at this address"
                        },
                        showForgetPairing = knownTargetHasSavedPairing,
                        onScan = { beginSecurePairing() },
                        onDiscover = { beginDiscovery() },
                        onManualConnect = { connectUsingManualAddress() },
                        onTvClick = { tv ->
                            val saved = savedCredentials.firstOrNull { it.fingerprint == tv.fingerprint }
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
                        onForgetPairing = {
                            service?.disconnectWifi(
                                message = "Saved pairing removed. Scan the TV code to pair again.",
                                forget = true,
                                clearTarget = true
                            )
                        }
                    )
                }
                ContactDeveloperPanel(onError = { setStatus(it) })
                Spacer(Modifier.height(8.dp))
            }
            }
            }
        }
    }
}

private fun findComponentActivity(context: Context): ComponentActivity? {
    var current = context
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    return current as? ComponentActivity
}