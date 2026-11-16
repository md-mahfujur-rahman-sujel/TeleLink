package com.telelink.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The remote is mostly used in a dim living room, so it uses the dark TeleLink brand theme.
private val RemoteDarkColors = darkColorScheme(
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

private val RemoteLightColors = lightColorScheme(
    primary = Color(0xFF315FD2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF10285C),
    secondary = Color(0xFF087F73),
    onSecondary = Color.White,
    background = Color(0xFFF5F7FC),
    onBackground = Color(0xFF111827),
    surface = Color.White,
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE9EEF7),
    onSurfaceVariant = Color(0xFF4C5B70),
    outline = Color(0xFF8290A5),
    outlineVariant = Color(0xFFD2DAE7),
    error = Color(0xFFB3261E),
    onError = Color.White
)

@Composable
fun TeleLinkRemoteTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) RemoteDarkColors else RemoteLightColors, content = content)
}
