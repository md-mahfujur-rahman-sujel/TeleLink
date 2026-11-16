package com.telelink.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.qrcode.encoder.ByteMatrix
import com.telelink.tv.R
import com.telelink.tv.security.PairedDevice
import com.telelink.tv.ui.theme.TeleBlue
import com.telelink.tv.ui.theme.TeleTeal

@Composable
fun TvBrandHeader(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher),
            contentDescription = null,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onBackground)) { append("Tele") }
                    withStyle(SpanStyle(color = TeleBlue)) { append("Link") }
                },
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "TV Receiver",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ReceiverStatusCard(
    status: String,
    running: Boolean,
    starting: Boolean,
    showNotificationHint: Boolean,
    modifier: Modifier = Modifier
) {
    val dot = when {
        running -> TeleTeal
        starting -> TeleBlue
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    TvCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(14.dp)
                    .background(dot, CircleShape)
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
        }
        if (showNotificationHint) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Notifications are off. Use Stop Receiver here when finished.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun PairingCard(
    receiverActive: Boolean,
    pin: String?,
    secondsRemaining: Int,
    qr: ByteMatrix?,
    modifier: Modifier = Modifier
) {
    TvCard(modifier = modifier.fillMaxWidth()) {
        SectionLabel("Pair a phone")
        Spacer(Modifier.height(14.dp))
        when {
            receiverActive && pin != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (qr != null) {
                        QrCode(
                            matrix = qr,
                            modifier = Modifier.size(168.dp)
                        )
                        Spacer(Modifier.width(20.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Scan with TeleLink Remote",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "The code verifies this TV before pairing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "OR ENTER PIN",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = pin,
                            style = MaterialTheme.typography.headlineLarge,
                            color = TeleBlue,
                            letterSpacing = 6.sp
                        )
                        Text(
                            text = "Expires in %d:%02d, then a new PIN is issued."
                                .format(secondsRemaining / 60, secondsRemaining % 60),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            receiverActive -> Text(
                text = "Preparing a secure pairing code…",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            else -> Text(
                text = "Select Start Receiver to show a pairing code for your phone.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun DetailsCard(
    device: String,
    address: String,
    connectedPhones: Int,
    modifier: Modifier = Modifier
) {
    TvCard(modifier = modifier.fillMaxWidth()) {
        SectionLabel("This TV")
        Spacer(Modifier.height(8.dp))
        InfoRow("Device", device)
        InfoRow("Address", address)
        InfoRow("Connected phones", connectedPhones.toString())
    }
}

@Composable
fun PairedPhonesCard(
    devices: List<PairedDevice>,
    onRevoke: (PairedDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    TvCard(modifier = modifier.fillMaxWidth()) {
        SectionLabel("Paired phones")
        Spacer(Modifier.height(10.dp))
        if (devices.isEmpty()) {
            Text(
                text = "No phones paired yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                devices.forEach { device ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = device.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        TvButton(
                            text = "Revoke",
                            onClick = { onRevoke(device) },
                            style = TvButtonStyle.Danger,
                            compact = true
                        )
                    }
                }
            }
        }
    }
}

/**
 * Draws the QR module grid directly at the display resolution, so edges stay hard on 720p/1080p/4K
 * panels instead of being a small bitmap stretched up. Includes a 4-module quiet zone for scanning.
 */
@Composable
fun QrCode(
    matrix: ByteMatrix,
    modifier: Modifier = Modifier,
    description: String = "Scan with TeleLink Remote to pair securely"
) {
    val quiet = 4
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .semantics { contentDescription = description }
    ) {
        val modules = matrix.width + quiet * 2
        val cell = size.minDimension / modules
        val offX = (size.width - cell * modules) / 2f
        val offY = (size.height - cell * modules) / 2f
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y].toInt() != 0) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(offX + (x + quiet) * cell, offY + (y + quiet) * cell),
                        size = Size(cell + 0.5f, cell + 0.5f)
                    )
                }
            }
        }
    }
}
