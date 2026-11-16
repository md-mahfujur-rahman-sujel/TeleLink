package com.telelink.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TvSettingsDialog(
    darkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    onManual: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Appearance", style = MaterialTheme.typography.titleSmall)
                TvButton(
                    text = if (darkMode) "✓  Dark mode" else "Light mode",
                    onClick = { onDarkModeChange(true) },
                    style = if (darkMode) TvButtonStyle.Primary else TvButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth()
                )
                TvButton(
                    text = if (!darkMode) "✓  Light mode" else "Dark mode",
                    onClick = { onDarkModeChange(false) },
                    style = if (!darkMode) TvButtonStyle.Primary else TvButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(2.dp))
                TvButton(
                    text = "User manual",
                    onClick = onManual,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "TeleLink TV · Version 1.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
fun TvUserManualDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("TeleLink TV quick guide") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GuideSection("Start the receiver", "Choose Start Receiver and keep TeleLink TV available while pairing. The six-digit PIN is temporary and rotates after it expires.")
                GuideSection("Pair your phone", "On the phone, install TeleLink Remote and scan the QR code shown here. Both devices should be on the same Wi-Fi. Allow local-network access when requested.")
                GuideSection("Control other apps", "Enable TeleLink TV remote access from Android Accessibility settings for keyboard, touchpad and navigation in other apps. Some TV apps may restrict remote input.")
                GuideSection("Use the TV browser", "The phone can send a URL to the built-in browser or ask Android to open an external TV browser. Video playback and media support vary by site and TV firmware.")
                GuideSection("Manage paired phones", "Your paired phones appear on the home screen. Choose Revoke next to a phone to remove its saved access. Pair it again by scanning a fresh QR code.")
                GuideSection("Stop safely", "Choose Stop Receiver when you do not want this TV to accept remote connections. You can start it again whenever you want to use TeleLink Remote.")
            }
        },
        confirmButton = {
            TvButton(text = "Close guide", onClick = onDismiss, style = TvButtonStyle.Primary)
        }
    )
}

@Composable
private fun GuideSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        SectionLabel(title)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}
