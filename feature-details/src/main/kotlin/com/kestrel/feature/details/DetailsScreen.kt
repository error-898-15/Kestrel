// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.details

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kestrel.core.designsystem.EmptyStateView
import com.kestrel.core.designsystem.KestrelTheme
import com.kestrel.core.designsystem.KestrelTopBar

@Composable
fun DetailsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = KestrelTheme.colors.canvas,
        topBar = {
            KestrelTopBar(
                title = "Details",
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
                icon = Icons.Outlined.Movie,
                title = "Details screen ready in Step 4",
                description = "Poster, plot, seasons/episodes, cast, recommendations, and stream source picker will bind here."
            )
        }
    }
}
