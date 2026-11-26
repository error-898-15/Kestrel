// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kestrel.core.designsystem.EmptyStateView
import com.kestrel.core.designsystem.KestrelTheme
import com.kestrel.core.designsystem.KestrelTopBar

@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = KestrelTheme.colors.canvas,
        topBar = {
            KestrelTopBar(
                title = "Player",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            EmptyStateView(
                icon = Icons.Outlined.PlayCircle,
                title = "Media3 Player ready in Step 4",
                description = "HLS/DASH/MP4 playback, quality/audio/subtitle selectors, custom headers, speed, PiP, and episode overlay will bind here."
            )
        }
    }
}
