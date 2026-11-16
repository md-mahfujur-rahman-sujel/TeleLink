package com.telelink.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun RemoteSettingsDialog(
    darkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    onManual: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Appearance", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = !darkMode,
                        onClick = { onDarkModeChange(false) },
                        label = { Text("Light") }
                    )
                    FilterChip(
                        selected = darkMode,
                        onClick = { onDarkModeChange(true) },
                        label = { Text("Dark") }
                    )
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) {
                    Text("Open user manual", modifier = Modifier.fillMaxWidth())
                }
                Text(
                    "TeleLink Remote · Version 1.0\nSecure phone-to-TV control over your local network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
internal fun RemoteUserManualDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("TeleLink quick guide") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ManualSection("Connect your TV", "Put your phone and TV on the same Wi-Fi. Open TeleLink TV and start its receiver, then scan the TV's pairing QR code here. Allow camera and local-network access when asked.")
                ManualSection("Find your way around", "Use the Wi-Fi remote for D-pad, touchpad and media controls. Type sends text to the focused TV field. Apps launches TV apps, and TV shows connection and pairing options.")
                ManualSection("Share something", "Paste a URL or text in Type, or share it from another phone app to TeleLink Remote. Choose whether a URL opens in TeleLink's TV browser or an external browser.")
                ManualSection("Bluetooth remote", "Choose Bluetooth remote, allow Nearby devices access and pair the phone from your TV's Bluetooth settings. This mode can control the TV launcher and other apps.")
                ManualSection("Privacy and pairing", "Pairing and discovery stay on your local network. The TV displays a short-lived PIN and verifies this phone. Use TV options to revoke a paired phone; use TV in this app to forget saved pairing details.")
                ManualSection("If the TV does not appear", "Check that both devices use the same Wi-Fi and that the router allows devices to communicate. Try Find TVs on Wi-Fi or enter the TV address manually. Some TVs require TeleLink's remote-access service to be enabled for controls in other apps.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun ManualSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}
