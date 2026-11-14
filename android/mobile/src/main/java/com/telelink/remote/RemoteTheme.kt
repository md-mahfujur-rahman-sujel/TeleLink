package com.telelink.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The remote is mostly used in a dim living room, so it uses the dark TeleLink brand theme.
private val RemoteColors = darkColorScheme(
    primary = Color(0xFF6A9BFF),
    onPrimary = Color(0xFF0B1425),
    primaryContainer = Color(0xFF1F3A78),
    onPrimaryContainer = Color(0xFFDCE7FF),
    secondary = Color(0xFF2DD4BF),
    onSecondary = Color(0xFF0B1425),
    background = Color(0xFF0B1425),
    onBackground = Color(0xFFF2F5FA),
    surface = Color(0xFF111D36),
    onSurface = Color(0xFFF2F5FA),
    surfaceVariant = Color(0xFF1A2A4A),
    onSurfaceVariant = Color(0xFFA9B6CF),
    outline = Color(0xFF3A4D78),
    outlineVariant = Color(0xFF263759),
    error = Color(0xFFFF7A7A),
    onError = Color(0xFF0B1425)
)

@Composable
fun TeleLinkRemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RemoteColors, content = content)
}
