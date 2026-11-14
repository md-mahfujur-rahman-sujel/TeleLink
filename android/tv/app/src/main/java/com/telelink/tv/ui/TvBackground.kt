package com.telelink.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Deep navy backdrop with soft blue and teal glows. The glass cards sit on top of it, so the
 * glows are what the frosted panels appear to "refract". Drawn with plain gradients, which is
 * cheap enough for a low-power TV GPU (no real-time blur).
 */
@Composable
fun TvBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF060D1B), Color(0xFF0A1A38), Color(0xFF08142A))
                )
            )
            .drawBehind {
                val blueCenter = Offset(size.width * 0.12f, size.height * 0.18f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x663F74F0), Color.Transparent),
                        center = blueCenter,
                        radius = size.minDimension * 0.85f
                    ),
                    radius = size.minDimension * 0.85f,
                    center = blueCenter
                )
                val tealCenter = Offset(size.width * 0.92f, size.height * 0.88f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x552DD4BF), Color.Transparent),
                        center = tealCenter,
                        radius = size.minDimension * 0.8f
                    ),
                    radius = size.minDimension * 0.8f,
                    center = tealCenter
                )
            },
        content = content
    )
}
