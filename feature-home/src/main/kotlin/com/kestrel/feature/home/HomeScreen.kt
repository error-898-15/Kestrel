// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kestrel.core.designsystem.EmptyStateView
import com.kestrel.core.designsystem.KestrelTheme
import com.kestrel.core.designsystem.KestrelTopBar

@Composable
fun HomeScreen(
    onOpenExtensions: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = KestrelTheme.colors.canvas,
        topBar = {
            KestrelTopBar(
                title = "Discover",
                subtitle = "Catalogs from enabled extensions"
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            EmptyStateView(
                icon = Icons.Outlined.Explore,
                title = "No active sources",
                description = "Kestrel ships with zero built-in content. Add a CloudStream .cs3, Nuvio, or Stremio repository in Extensions to browse catalogs.",
                actionLabel = "Manage Extensions",
                onAction = onOpenExtensions
            )
        }
    }
}
