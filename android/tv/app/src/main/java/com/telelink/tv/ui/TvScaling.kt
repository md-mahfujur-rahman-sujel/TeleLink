package com.telelink.tv.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

private const val REFERENCE_WIDTH_DP = 960f
private const val REFERENCE_HEIGHT_DP = 540f

/**
 * Lays the UI out in a fixed 960 x 540 dp design space, whatever density the TV firmware reports.
 *
 * Budget Android TVs often report a wrong or very low screen density, so the same dp values end up
 * tiny and "zoomed out", or the layout ends up far larger than the visible panel. Deriving the
 * density from the real pixel size of the window makes the UI fill the screen consistently on any
 * 720p / 1080p / 4K panel. The system font scale is ignored on purpose (TV has no such setting).
 */
@Composable
fun TvDesignSpace(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = minOf(
            constraints.maxWidth / REFERENCE_WIDTH_DP,
            constraints.maxHeight / REFERENCE_HEIGHT_DP
        ).coerceIn(0.5f, 4f)
        CompositionLocalProvider(
            LocalDensity provides Density(density = scale, fontScale = 1f)
        ) {
            content()
        }
    }
}
