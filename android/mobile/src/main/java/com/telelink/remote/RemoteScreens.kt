package com.telelink.remote

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ConnectContent(
    isDiscovering: Boolean,
    discoveredTvs: List<DiscoveredTv>,
    savedFingerprints: Set<String>,
    manualHost: String,
    onManualHostChange: (String) -> Unit,
    manualActionLabel: String,
    showForgetPairing: Boolean,
    onScan: () -> Unit,
    onDiscover: () -> Unit,
    onManualConnect: () -> Unit,
    onTvClick: (DiscoveredTv) -> Unit,
    onForgetPairing: () -> Unit
) {
    var showManual by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel("Get started") {
            Text(
                text = "On the TV, open TeleLink TV and select Start Receiver. Then scan the QR code shown on the TV.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            RemoteButton(
                text = "Scan TV pairing code",
                onClick = onScan,
                kind = RemoteButtonKind.Primary,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            RemoteButton(
                text = if (isDiscovering) "Searching for TVs…" else "Find TVs on Wi-Fi",
                onClick = onDiscover,
                kind = RemoteButtonKind.Outlined,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (discoveredTvs.isNotEmpty()) {
            Panel("TVs on your Wi-Fi") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    discoveredTvs.forEach { tv ->
                        val saved = tv.fingerprint in savedFingerprints
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = tv.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${tv.host}:${tv.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            RemoteButton(
                                text = if (saved) "Reconnect" else "Pair",
                                onClick = { onTvClick(tv) },
                                kind = if (saved) RemoteButtonKind.Primary else RemoteButtonKind.Tonal
                            )
                        }
                    }
                }
            }
        }

        Panel("Manual connection") {
            if (!showManual) {
                TextButton(onClick = { showManual = true }) {
                    Text("Enter the TV address manually")
                }
            } else {
                OutlinedTextField(
                    value = manualHost,
                    onValueChange = onManualHostChange,
                    label = { Text("TV IP address or local host") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(onGo = { onManualConnect() }),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                RemoteButton(
                    text = manualActionLabel,
                    onClick = onManualConnect,
                    kind = RemoteButtonKind.Tonal,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (showForgetPairing) {
            RemoteButton(
                text = "Forget saved pairing and pair again",
                onClick = onForgetPairing,
                kind = RemoteButtonKind.Danger,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
internal fun SharedCard(
    shared: String,
    isPaired: Boolean,
    onSend: () -> Unit
) {
    Panel("Shared from another app") {
        Text(
            text = shared.take(300),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(12.dp))
        RemoteButton(
            text = if (shared.startsWith("http", true)) "Open on TV" else "Send to TV",
            onClick = onSend,
            enabled = isPaired,
            kind = RemoteButtonKind.Primary,
            modifier = Modifier.fillMaxWidth()
        )
        if (!isPaired) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Connect to your TV first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun RemoteTab(
    onKey: (String) -> Unit,
    onMedia: (String) -> Unit,
    onGesture: (Float, Float, Float, Float, Long) -> Unit
) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SegmentedTabs(
            labels = listOf("D-pad", "Touchpad"),
            selected = mode,
            onSelect = { mode = it }
        )
        if (mode == 0) {
            DPad(onKey = onKey)
        } else {
            Touchpad(onGesture = onGesture)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RemoteButton("Back", { onKey("BACK") }, Modifier.weight(1f))
            RemoteButton("Home", { onKey("HOME") }, Modifier.weight(1f))
        }
        Panel("Playback") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RemoteButton("−10s", { onMedia("SEEK_BACK") }, Modifier.weight(1f), compact = true)
                RemoteButton("Play", { onMedia("PLAY") }, Modifier.weight(1f), RemoteButtonKind.Primary, compact = true)
                RemoteButton("Pause", { onMedia("PAUSE") }, Modifier.weight(1f), RemoteButtonKind.Primary, compact = true)
                RemoteButton("+10s", { onMedia("SEEK_FORWARD") }, Modifier.weight(1f), compact = true)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RemoteButton("Stop", { onMedia("STOP") }, Modifier.weight(1f), RemoteButtonKind.Outlined, compact = true)
                RemoteButton("Vol −", { onMedia("VOLUME_DOWN") }, Modifier.weight(1f), compact = true)
                RemoteButton("Vol +", { onMedia("VOLUME_UP") }, Modifier.weight(1f), compact = true)
            }
        }
    }
}

@Composable
internal fun TypeTab(
    textInput: String,
    onTextChange: (String) -> Unit,
    onSendText: () -> Unit,
    urlInput: String,
    onUrlChange: (String) -> Unit,
    onOpenIntegrated: () -> Unit,
    onOpenExternal: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel("Type on the TV") {
            Text(
                text = "Select a text field on the TV first, then send your text.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = textInput,
                onValueChange = onTextChange,
                label = { Text("Text for the TV") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSendText() }),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            RemoteButton(
                text = "Send text",
                onClick = onSendText,
                kind = RemoteButtonKind.Primary,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Panel("Search or open a link on the TV") {
            OutlinedTextField(
                value = urlInput,
                onValueChange = onUrlChange,
                label = { Text("Search Google or enter a web address") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(onGo = { onOpenIntegrated() }),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            RemoteButton(
                text = "Open in TeleLink",
                onClick = onOpenIntegrated,
                kind = RemoteButtonKind.Primary,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            RemoteButton(
                text = "Open in external browser",
                onClick = onOpenExternal,
                kind = RemoteButtonKind.Outlined,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
internal fun AppsTab(
    apps: List<TvApp>,
    onLaunch: (TvApp) -> Unit,
    onRefresh: () -> Unit
) {
    if (apps.isEmpty()) {
        Panel("TV apps") {
            Text(
                text = "No launchable apps were returned yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            RemoteButton(
                text = "Refresh app list",
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth()
            )
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            apps.sortedBy { it.name.lowercase() }.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    pair.forEach { app ->
                        Surface(
                            onClick = { onLaunch(app) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 68.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Box(
                                modifier = Modifier.padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = app.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun TvInfoTab(
    hostPort: String,
    onDisconnect: () -> Unit,
    onForget: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel("Connection") {
            Text(
                text = if (hostPort.isBlank()) "TV" else "TV at $hostPort",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Certificate verified from the QR code",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(14.dp))
            RemoteButton(
                text = "Disconnect",
                onClick = onDisconnect,
                kind = RemoteButtonKind.Outlined,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            RemoteButton(
                text = "Forget saved TV on this phone",
                onClick = onForget,
                kind = RemoteButtonKind.Danger,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
internal fun ContactDeveloperPanel(onError: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Panel(title = "Contact developer", modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ContactButton("GitHub", onClick = { launchContact(context, ContactKind.GitHub, onError) }) {
                Image(
                    painter = painterResource(R.drawable.ic_contact_github),
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                )
            }
            ContactButton("Website", onClick = { launchContact(context, ContactKind.Website, onError) }) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_contact_website),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
            }
            ContactButton("Email", onClick = { launchContact(context, ContactKind.Email, onError) }) {
                Image(
                    painter = painterResource(R.drawable.ic_contact_gmail),
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                )
            }
        }
    }
}

@Composable
private fun ContactButton(label: String, onClick: () -> Unit, logo: @Composable () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        logo()
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private enum class ContactKind { GitHub, Website, Email }

private fun contactIntent(context: Context, kind: ContactKind): Intent = when (kind) {
    ContactKind.GitHub -> Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/md-mahfujur-rahman-sujel"))
    ContactKind.Website -> Intent(Intent.ACTION_VIEW, Uri.parse("https://md-mahfujur-rahman-sujel.github.io/"))
    ContactKind.Email -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:mahfujur.rahman.sujel@gmail.com")).apply {
        putExtra(Intent.EXTRA_SUBJECT, "TeleLink Remote feedback")
        putExtra(Intent.EXTRA_TEXT, contactBody(context))
    }
}

private fun contactBody(context: Context): String {
    val version = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (_: Exception) {
        null
    }
    return "\n\n---\nTeleLink Remote " + (version ?: "?") +
        "\nAndroid " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")" +
        "\n" + Build.MANUFACTURER + " " + Build.MODEL
}

private fun launchContact(context: Context, kind: ContactKind, onError: (String) -> Unit) {
    try {
        context.startActivity(contactIntent(context, kind))
    } catch (_: Exception) {
        onError(
            if (kind == ContactKind.Email) "No email app is set up on this phone"
            else "No browser is available to open the link"
        )
    }
}
