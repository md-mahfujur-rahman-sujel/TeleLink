package com.telelink.tv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// A TV is watched from a distance in a dim room, so the receiver always uses the dark brand theme.
// Dynamic (wallpaper) colour is intentionally not used: it is unpredictable on TV firmware.
private val TvDarkColorScheme = darkColorScheme(
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

private val TvLightColorScheme = lightColorScheme(
    primary = Color(0xFF315FD2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF10285C),
    secondary = Color(0xFF087F73),
    onSecondary = Color.White,
    background = Color(0xFFF4F7FC),
    onBackground = Color(0xFF111827),
    surface = Color.White,
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE8EDF5),
    onSurfaceVariant = Color(0xFF4C5B70),
    outline = Color(0xFF8290A5),
    outlineVariant = Color(0xFFD2DAE7),
    error = Color(0xFFB3261E),
    onError = Color.White
)

@Composable
fun TeleLinkTVTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) TvDarkColorScheme else TvLightColorScheme,
        typography = Typography,
        content = content
    )
}
