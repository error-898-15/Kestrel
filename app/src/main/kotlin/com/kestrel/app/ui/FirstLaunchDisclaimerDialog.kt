// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.kestrel.core.designsystem.KestrelPrimaryButton
import com.kestrel.core.designsystem.KestrelTheme

@Composable
fun FirstLaunchDisclaimerDialog(
    onAccept: () -> Unit
) {
    val colors = KestrelTheme.colors
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        ),
        containerColor = colors.surface,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        title = {
            Text(
                text = "Content & Extension Notice",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column {
                Text(
                    text = "Kestrel does not host, bundle, or provide any media content or content repositories.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "All catalogs, metadata, and streams come exclusively from third-party extension repositories (.cs3, Nuvio, or Stremio) that you explicitly add and enable. You are solely responsible for the sources and content you choose to access.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            KestrelPrimaryButton(
                text = "I Understand & Continue",
                onClick = onAccept
            )
        }
    )
}
