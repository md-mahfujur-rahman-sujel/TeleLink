package com.telelink.tv.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telelink.tv.ui.theme.GlassBorder
import com.telelink.tv.ui.theme.GlassFillBottom
import com.telelink.tv.ui.theme.GlassFillTop
import com.telelink.tv.ui.theme.TeleBlue
import com.telelink.tv.ui.theme.TeleBlueDeep
import com.telelink.tv.ui.theme.TeleDanger
import com.telelink.tv.ui.theme.TeleTeal
import com.telelink.tv.ui.theme.TeleTextMuted

enum class TvButtonStyle { Primary, Secondary, Danger }

/** Gradient canvas with two soft brand glows so the translucent glass panels have depth to sit on. */
@Composable
fun TvBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0A1120), Color(0xFF0B1425), Color(0xFF101F3A))
                )
            )
    ) {
        val glowBlur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(90.dp) else Modifier
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset(x = (-70).dp, y = (-90).dp)
                .size(430.dp)
                .then(glowBlur)
                .background(Brush.radialGradient(listOf(TeleBlueDeep.copy(alpha = 0.34f), Color.Transparent)))
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 90.dp, y = 100.dp)
                .size(470.dp)
                .then(glowBlur)
                .background(Brush.radialGradient(listOf(TeleTeal.copy(alpha = 0.20f), Color.Transparent)))
        )
        content()
    }
}

/** D-pad friendly glass button: bright focus ring, no raster scale-up so text stays crisp. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: TvButtonStyle = TvButtonStyle.Secondary,
    compact: Boolean = false,
    focusRequester: FocusRequester? = null
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)

    val fill = when {
        focused && style == TvButtonStyle.Danger -> Brush.horizontalGradient(listOf(TeleDanger, TeleDanger))
        focused -> Brush.horizontalGradient(listOf(TeleBlue, TeleBlueDeep))
        style == TvButtonStyle.Primary -> Brush.horizontalGradient(listOf(TeleBlueDeep, TeleBlue))
        style == TvButtonStyle.Danger -> Brush.verticalGradient(
            listOf(TeleDanger.copy(alpha = 0.16f), TeleDanger.copy(alpha = 0.08f))
        )
        else -> Brush.verticalGradient(listOf(GlassFillTop, GlassFillBottom))
    }
    val content = when {
        focused -> scheme.onPrimary
        style == TvButtonStyle.Primary -> Color.White
        style == TvButtonStyle.Danger -> TeleDanger
        else -> scheme.onSurface
    }
    val borderColor = when {
        focused -> Color.White
        style == TvButtonStyle.Danger -> TeleDanger.copy(alpha = 0.6f)
        else -> GlassBorder
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = shape,
        color = Color.Transparent,
        contentColor = content,
        border = BorderStroke(if (focused) 3.dp else 1.dp, borderColor),
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .heightIn(min = if (compact) 40.dp else 52.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(fill)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Frosted-glass panel: translucent gradient fill plus a light rim, sitting on the backdrop. */
@Composable
fun TvCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(GlassFillTop, GlassFillBottom)))
            .border(BorderStroke(1.dp, GlassBorder), shape)
    ) {
        Column(modifier = Modifier.padding(20.dp), content = content)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = TeleTextMuted,
        letterSpacing = 1.5.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
    )
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TeleTextMuted
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(start = 16.dp)
        )
    }
}