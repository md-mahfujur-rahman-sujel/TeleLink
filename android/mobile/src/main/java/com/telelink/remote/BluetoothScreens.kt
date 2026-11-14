package com.telelink.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun BluetoothContent(
    status: BtStatus,
    devices: List<BtDeviceItem>,
    onStart: () -> Unit,
    onMakeVisible: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onConnect: (BtDeviceItem) -> Unit,
    onDisconnect: () -> Unit,
    onKey: (String) -> Unit,
    onMedia: (String) -> Unit,
    onMove: (Float, Float) -> Unit,
    onClick: () -> Unit,
    onScroll: (Float) -> Unit,
    onType: (String) -> Unit
) {
    if (status.connectedName != null) {
        BluetoothControls(
            onDisconnect = onDisconnect,
            onKey = onKey,
            onMedia = onMedia,
            onMove = onMove,
            onClick = onClick,
            onScroll = onScroll,
            onType = onType
        )
    } else {
        BluetoothSetup(
            status = status,
            devices = devices,
            onStart = onStart,
            onMakeVisible = onMakeVisible,
            onOpenSettings = onOpenSettings,
            onRefresh = onRefresh,
            onConnect = onConnect
        )
    }
}

@Composable
private fun BluetoothSetup(
    status: BtStatus,
    devices: List<BtDeviceItem>,
    onStart: () -> Unit,
    onMakeVisible: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onConnect: (BtDeviceItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel("Use your phone as a Bluetooth remote") {
            Text(
                text = "Your TV will treat this phone like its own remote, so Home, Back, the D-pad, " +
                    "volume and typing work in every app. Nothing is installed on the TV.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "1. Tap Start and allow the permission.\n" +
                    "2. Tap Make phone visible. On the TV, open Bluetooth settings, add a new device " +
                    "and choose this phone. Confirm on both screens.\n" +
                    "3. Come back here, tap Refresh, then Connect next to your TV.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(14.dp))
            RemoteButton(
                text = if (status.registered) "Bluetooth remote is on" else "Start Bluetooth remote",
                onClick = onStart,
                kind = RemoteButtonKind.Primary,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            RemoteButton(
                text = "Make phone visible (5 minutes)",
                onClick = onMakeVisible,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            RemoteButton(
                text = "Open phone Bluetooth settings",
                onClick = onOpenSettings,
                kind = RemoteButtonKind.Outlined,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Panel("Paired devices") {
            if (devices.isEmpty()) {
                Text(
                    text = "No paired devices yet. Pair this phone with your TV first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    devices.forEach { device ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = device.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = device.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            RemoteButton(
                                text = "Connect",
                                onClick = { onConnect(device) },
                                enabled = status.registered,
                                kind = RemoteButtonKind.Primary
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            RemoteButton(
                text = "Refresh list",
                onClick = onRefresh,
                kind = RemoteButtonKind.Outlined,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun BluetoothControls(
    onDisconnect: () -> Unit,
    onKey: (String) -> Unit,
    onMedia: (String) -> Unit,
    onMove: (Float, Float) -> Unit,
    onClick: () -> Unit,
    onScroll: (Float) -> Unit,
    onType: (String) -> Unit
) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SegmentedTabs(
            labels = listOf("D-pad", "Touchpad", "Type"),
            selected = mode,
            onSelect = { mode = it }
        )
        when (mode) {
            0 -> DPad(onKey = onKey)
            1 -> MousePad(onMove = onMove, onClick = onClick, onScroll = onScroll)
            else -> Panel("Type on the TV") {
                Text(
                    text = "Select a text box on the TV first, then send your text.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Text for the TV") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        onType(text)
                        text = ""
                    }),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                RemoteButton(
                    text = "Send text",
                    onClick = {
                        onType(text)
                        text = ""
                    },
                    kind = RemoteButtonKind.Primary,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RemoteButton("Backspace", { onKey("BACKSPACE") }, Modifier.weight(1f))
                    RemoteButton("Enter", { onKey("SELECT") }, Modifier.weight(1f))
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RemoteButton("Back", { onKey("BACK") }, Modifier.weight(1f), compact = true)
            RemoteButton("Home", { onKey("HOME") }, Modifier.weight(1f), compact = true)
            RemoteButton("Menu", { onKey("MENU") }, Modifier.weight(1f), compact = true)
        }
        Panel("Playback") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RemoteButton("−10s", { onMedia("SEEK_BACK") }, Modifier.weight(1f), compact = true)
                RemoteButton(
                    "Play / Pause",
                    { onMedia("PLAY_PAUSE") },
                    Modifier.weight(2f),
                    RemoteButtonKind.Primary,
                    compact = true
                )
                RemoteButton("+10s", { onMedia("SEEK_FORWARD") }, Modifier.weight(1f), compact = true)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RemoteButton("Stop", { onMedia("STOP") }, Modifier.weight(1f), RemoteButtonKind.Outlined, compact = true)
                RemoteButton("Mute", { onMedia("MUTE") }, Modifier.weight(1f), compact = true)
                RemoteButton("Vol −", { onMedia("VOLUME_DOWN") }, Modifier.weight(1f), compact = true)
                RemoteButton("Vol +", { onMedia("VOLUME_UP") }, Modifier.weight(1f), compact = true)
            }
        }
        RemoteButton(
            text = "Disconnect",
            onClick = onDisconnect,
            kind = RemoteButtonKind.Danger,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Drag to move the TV's mouse pointer, tap to click, and use the right strip to scroll. */
@Composable
private fun MousePad(
    onMove: (Float, Float) -> Unit,
    onClick: () -> Unit,
    onScroll: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentMove by rememberUpdatedState(onMove)
    val currentClick by rememberUpdatedState(onClick)
    val currentScroll by rememberUpdatedState(onScroll)
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(320.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { currentClick() })
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        currentMove(drag.x, drag.y)
                    }
                },
            shape = RoundedCornerShape(24.dp),
            color = scheme.surfaceVariant,
            border = BorderStroke(1.dp, scheme.outline)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "Drag to move the pointer.\nTap to click.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
            }
        }
        Surface(
            modifier = Modifier
                .width(56.dp)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, amount ->
                        change.consume()
                        currentScroll(amount)
                    }
                },
            shape = RoundedCornerShape(24.dp),
            color = scheme.surfaceVariant,
            border = BorderStroke(1.dp, scheme.outline)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "▲\n\nScroll\n\n▼",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
