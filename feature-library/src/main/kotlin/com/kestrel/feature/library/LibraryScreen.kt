// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.kestrel.core.designsystem.EmptyStateView
import com.kestrel.core.designsystem.GlassCircleButton
import com.kestrel.core.designsystem.GlassScreenHeader
import com.kestrel.core.designsystem.GlassTabs
import com.kestrel.core.designsystem.KestrelBackdrop
import com.kestrel.core.designsystem.KestrelBackground
import com.kestrel.core.designsystem.KestrelCard
import com.kestrel.core.designsystem.KestrelTheme
import com.kestrel.core.designsystem.LocalGlassState
import com.kestrel.core.designsystem.SourceBadge
import com.kestrel.core.designsystem.glassSource
import com.kestrel.core.designsystem.kestrelNavClearance
import com.kestrel.core.designsystem.rememberGlassState
import com.kestrel.core.designsystem.rememberKestrelImageRequest
import com.kestrel.domain.model.LibraryEntry

@Composable
fun LibraryScreen(
    onOpenEntry: (LibraryEntry) -> Unit = {},
    onOpenExtensions: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = KestrelTheme.colors
    val navClearance = kestrelNavClearance()
    val libraryGlass = rememberGlassState()

    val items = when (state.selectedTab) {
        LibraryTab.CONTINUE_WATCHING -> state.continueWatching
        LibraryTab.FAVORITES -> state.favorites
        LibraryTab.HISTORY -> state.history
    }

    KestrelBackground(
        modifier = modifier.fillMaxSize(),
        style = KestrelBackdrop.Dark
    ) {
        CompositionLocalProvider(LocalGlassState provides libraryGlass) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                GlassScreenHeader(
                    title = "Library",
                    subtitle = "Favorites, history & continue watching",
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.selectedTab == LibraryTab.HISTORY && state.history.isNotEmpty()) {
                                GlassCircleButton(
                                    icon = Icons.Outlined.DeleteSweep,
                                    contentDescription = "Clear history",
                                    onClick = viewModel::clearHistory,
                                    tint = colors.statusError
                                )
                            }
                            GlassCircleButton(
                                icon = Icons.Outlined.Extension,
                                contentDescription = "Extensions & Settings",
                                onClick = onOpenExtensions,
                                tint = colors.accentAmber
                            )
                        }
                    }
                )

                GlassTabs(
                    labels = LibraryTab.entries.map { it.label },
                    selectedIndex = state.selectedTab.ordinal,
                    onSelect = { index -> viewModel.selectTab(LibraryTab.entries[index]) },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .glassSource(libraryGlass)
                ) {
                    if (items.isEmpty()) {
                        val (icon, title, desc) = when (state.selectedTab) {
                            LibraryTab.CONTINUE_WATCHING -> Triple(
                                Icons.Outlined.PlayCircle,
                                "Nothing in progress",
                                "Start watching a movie or series and your resume position will appear here."
                            )
                            LibraryTab.FAVORITES -> Triple(
                                Icons.Outlined.Bookmarks,
                                "No favorites yet",
                                "Bookmark any title on its Details screen to save it here."
                            )
                            LibraryTab.HISTORY -> Triple(
                                Icons.Outlined.History,
                                "Watch history is empty",
                                "Titles you play are logged here locally on your device."
                            )
                        }
                        EmptyStateView(
                            icon = icon,
                            title = title,
                            description = desc,
                            modifier = Modifier.padding(bottom = navClearance)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 20.dp,
                                end = 20.dp,
                                top = 12.dp,
                                bottom = navClearance
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(items, key = { it.mediaId }) { entry ->
                                LibraryEntryCard(
                                    entry = entry,
                                    showRemove = state.selectedTab == LibraryTab.HISTORY,
                                    onClick = { onOpenEntry(entry) },
                                    onRemove = { viewModel.removeFromHistory(entry.mediaId) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryEntryCard(
    entry: LibraryEntry,
    showRemove: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val colors = KestrelTheme.colors
    val posterRequest = rememberKestrelImageRequest(entry.posterUrl, emptyMap(), 240, 360)

    KestrelCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (posterRequest != null) {
                AsyncImage(
                    model = posterRequest,
                    contentDescription = entry.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(56.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(10.dp))
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SourceBadge(label = entry.providerName)
                    if (!entry.lastEpisodeLabel.isNullOrBlank()) {
                        Text(
                            text = entry.lastEpisodeLabel!!,
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (entry.durationMs > 0L && entry.lastPositionMs > 0L) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val progress = (entry.lastPositionMs.toFloat() / entry.durationMs.toFloat())
                        .coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = colors.accentAmber,
                        trackColor = Color.White.copy(alpha = 0.14f)
                    )
                }
            }
            if (showRemove) {
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "Remove from history",
                        tint = colors.textMuted
                    )
                }
            }
        }
    }
}
