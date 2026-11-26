// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kestrel.core.designsystem.EmptyStateView
import com.kestrel.core.designsystem.KestrelTheme
import com.kestrel.core.designsystem.KestrelTopBar

@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = KestrelTheme.colors.canvas,
        topBar = {
            KestrelTopBar(
                title = "Library",
                subtitle = "Favorites, history & continue watching"
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            EmptyStateView(
                icon = Icons.Outlined.Bookmarks,
                title = "Your library is empty",
                description = "Titles you bookmark or start watching will appear here with resume progress."
            )
        }
    }
}
