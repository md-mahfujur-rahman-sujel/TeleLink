package com.telelink.tv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// A TV is watched from a distance in a dim room, so the receiver always uses the dark brand theme.
// Dynamic (wallpaper) colour is intentionally not used: it is unpredictable on TV firmware.
private val TvColorScheme = darkColorScheme(
    primary = TeleBlue,
    onPrimary = TeleNavy,
    secondary = TeleTeal,
    onSecondary = TeleNavy,
    background = TeleNavy,
    onBackground = TeleText,
    surface = TeleSurface,
    onSurface = TeleText,
    surfaceVariant = TeleSurfaceHigh,
    onSurfaceVariant = TeleTextMuted,
    outline = TeleOutline,
    outlineVariant = TeleOutline,
    error = TeleDanger,
    onError = TeleNavy
)

@Composable
fun TeleLinkTVTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TvColorScheme,
        typography = Typography,
        content = content
    )
}
