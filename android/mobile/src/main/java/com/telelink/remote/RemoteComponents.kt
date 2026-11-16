package com.telelink.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class BannerState { Idle, Busy, Connected, Error }

internal enum class RemoteButtonKind { Primary, Tonal, Outlined, Danger }

@Composable
internal fun BrandHeader(
    connected: Boolean,
    modifier: Modifier = Modifier,
    onSettings: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher),
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = scheme.onBackground)) { append("Tele") }
                    withStyle(SpanStyle(color = scheme.primary)) { append("Link") }
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Phone remote",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant
            )
        }
        if (connected) {
            Surface(
                shape = RoundedCornerShape(50),
                color = scheme.secondary.copy(alpha = 0.16f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(scheme.secondary, CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Connected",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.secondary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        IconButton(onClick = onSettings) {
            Text(
                text = "⚙︎",
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = "Settings" }
            )
        }
    }
}

@Composable
internal fun StatusBanner(text: String, state: BannerState, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dot = when (state) {
        BannerState.Connected -> scheme.secondary
        BannerState.Busy -> scheme.primary
        BannerState.Error -> scheme.error
        BannerState.Idle -> scheme.outline
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(dot, CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface
            )
        }
    }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = modifier.padding(bottom = 10.dp)
    )
}

@Composable
internal fun Panel(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            SectionLabel(title)
            content()
        }
    }
}

@Composable
internal fun RemoteButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: RemoteButtonKind = RemoteButtonKind.Tonal,
    enabled: Boolean = true,
    compact: Boolean = false
) {
    val shape = RoundedCornerShape(14.dp)
    val sized = modifier.heightIn(min = 52.dp)
    val padding = if (compact) PaddingValues(horizontal = 4.dp) else ButtonDefaults.ContentPadding
    val label: @Composable () -> Unit = {
        Text(text = text, maxLines = 2, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
    }
    when (kind) {
        RemoteButtonKind.Primary -> Button(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            shape = shape,
            contentPadding = padding
        ) { label() }

        RemoteButtonKind.Tonal -> FilledTonalButton(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            shape = shape,
            contentPadding = padding
        ) { label() }

        RemoteButtonKind.Outlined -> OutlinedButton(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            shape = shape,
            contentPadding = padding
        ) { label() }

        RemoteButtonKind.Danger -> OutlinedButton(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            shape = shape,
            contentPadding = padding,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
        ) { label() }
    }
}

@Composable
internal fun SegmentedTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surfaceVariant)
            .padding(4.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val isSelected = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) scheme.primary else Color.Transparent)
                    .selectable(
                        selected = isSelected,
                        role = Role.Tab,
                        onClick = { onSelect(index) }
                    )
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) scheme.onPrimary else scheme.onSurfaceVariant,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

/** Fires once on press, then repeats while the key is held (like a physical TV remote). */
@Composable
private fun Modifier.repeatOnHold(onTrigger: () -> Unit): Modifier {
    val haptic = LocalHapticFeedback.current
    val current by rememberUpdatedState(onTrigger)
    return this.pointerInput(Unit) {
        detectTapGestures(
            onPress = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                current()
                coroutineScope {
                    val repeater = launch {
                        delay(450)
                        while (true) {
                            current()
                            delay(130)
                        }
                    }
                    tryAwaitRelease()
                    repeater.cancel()
                }
            }
        )
    }
}

@Composable
private fun PadArrow(
    glyph: String,
    description: String,
    alignment: Alignment,
    onKey: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(76.dp)
            .clip(CircleShape)
            .semantics { contentDescription = description }
            .repeatOnHold(onKey),
        contentAlignment = alignment
    ) {
        Text(
            text = glyph,
            fontSize = 28.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
internal fun DPad(onKey: (String) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(280.dp)
            .clip(CircleShape)
            .background(scheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        PadArrow("▲", "Up", Alignment.Center, { onKey("UP") }, Modifier.align(Alignment.TopCenter).padding(top = 6.dp))
        PadArrow("▼", "Down", Alignment.Center, { onKey("DOWN") }, Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
        PadArrow("◀", "Left", Alignment.Center, { onKey("LEFT") }, Modifier.align(Alignment.CenterStart).padding(start = 6.dp))
        PadArrow("▶", "Right", Alignment.Center, { onKey("RIGHT") }, Modifier.align(Alignment.CenterEnd).padding(end = 6.dp))
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(scheme.primary)
                .semantics { contentDescription = "Select" }
                .repeatOnHold { onKey("SELECT") },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "OK",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = scheme.onPrimary
            )
        }
    }
}

@Composable
internal fun Touchpad(
    onGesture: (startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnGesture by rememberUpdatedState(onGesture)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(320.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = down.position
                    var end = start
                    var endMs = down.uptimeMillis
                    var event: PointerEvent
                    do {
                        event = awaitPointerEvent()
                        val change = event.changes.first()
                        end = change.position
                        endMs = change.uptimeMillis
                        change.consume()
                    } while (event.changes.any { it.pressed })
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val height = size.height.toFloat().coerceAtLeast(1f)
                    // Use the real gesture time so quick taps are taps and swipes keep their speed.
                    val elapsed = (endMs - down.uptimeMillis).coerceIn(50L, 600L)
                    currentOnGesture(
                        start.x / width,
                        start.y / height,
                        end.x / width,
                        end.y / height,
                        elapsed
                    )
                }
            },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = "Tap or swipe here to control the TV screen",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp)
            )
        }
    }
}
