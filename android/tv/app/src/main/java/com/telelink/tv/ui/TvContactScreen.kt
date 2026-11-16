package com.telelink.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.google.zxing.qrcode.encoder.ByteMatrix
import com.telelink.tv.ui.theme.TeleBlue

data class DeveloperLink(val label: String, val display: String, val url: String)

/** Same contact details as the phone app's "Contact developer" panel. */
object DeveloperInfo {
    const val NAME = "Md Mahfujur Rahman Sujel"
    val links = listOf(
        DeveloperLink(
            "Website",
            "md-mahfujur-rahman-sujel.github.io",
            "https://md-mahfujur-rahman-sujel.github.io/"
        ),
        DeveloperLink(
            "GitHub",
            "github.com/md-mahfujur-rahman-sujel",
            "https://github.com/md-mahfujur-rahman-sujel"
        ),
        DeveloperLink(
            "Email",
            "mahfujur.rahman.sujel@gmail.com",
            "mailto:mahfujur.rahman.sujel@gmail.com?subject=TeleLink%20TV%20feedback"
        )
    )
}

/**
 * A TV cannot open a mail app or follow links, so it shows the developer's details and QR codes.
 * Scanning a code with a phone camera opens the page, or starts an email, on the phone.
 */
@Composable
fun TvContactScreen(
    qrCodes: List<Pair<DeveloperLink, ByteMatrix?>>,
    onClose: () -> Unit
) {
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { closeFocus.requestFocus() }
    }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TvBrandHeader()
            TvCard(Modifier.fillMaxWidth()) {
                SectionLabel("Contact the developer")
                Spacer(Modifier.height(12.dp))
                Text(DeveloperInfo.NAME, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "Developer of TeleLink",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                DeveloperInfo.links.forEach { link ->
                    Text(
                        text = link.label.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = link.display,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            TvButton(
                text = "Back",
                onClick = onClose,
                style = TvButtonStyle.Primary,
                focusRequester = closeFocus,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            TvCard(Modifier.fillMaxWidth()) {
                SectionLabel("Scan with your phone's camera")
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    qrCodes.forEach { (link, matrix) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (matrix != null) {
                                QrCode(
                                    matrix = matrix,
                                    description = "QR code for the developer's ${link.label}",
                                    modifier = Modifier.size(112.dp)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(link.label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}
