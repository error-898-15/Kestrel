// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.manga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kestrel.core.designsystem.EmptyStateView
import com.kestrel.core.designsystem.GlassChip
import com.kestrel.core.designsystem.GlassCircleButton
import com.kestrel.core.designsystem.GlassScreenHeader
import com.kestrel.core.designsystem.GlassSearchField
import com.kestrel.core.designsystem.KestrelBackdrop
import com.kestrel.core.designsystem.KestrelBackground
import com.kestrel.core.designsystem.KestrelTheme
import com.kestrel.core.designsystem.LocalGlassState
import com.kestrel.core.designsystem.MediaPosterCard
import com.kestrel.core.designsystem.kestrelNavClearance
import com.kestrel.core.designsystem.rememberGlassState
import com.kestrel.domain.model.MangaSummary

@Composable
fun MangaScreen(
    onOpenMangaSourceSettings: () -> Unit,
    onOpenManga: (MangaSummary) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MangaViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = KestrelTheme.colors
    val navClearance = kestrelNavClearance()
    val mangaGlass = rememberGlassState()

    val sources = state.installedExtensions.flatMap { ext ->
        if (ext.sources.isNotEmpty()) ext.sources
        else listOf(com.kestrel.domain.model.MangaSourceDescriptor(ext.id, ext.name, ext.lang))
    }.distinctBy { it.id }
    val duplicateSourceNames = sources.groupingBy { it.name }.eachCount()

    KestrelBackground(
        modifier = modifier.fillMaxSize(),
        style = KestrelBackdrop.Dark
    ) {
        CompositionLocalProvider(LocalGlassState provides mangaGlass) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                GlassScreenHeader(
                    title = "Manga",
                    subtitle = if (state.installedExtensions.isEmpty()) {
                        "Install Aniyomi sources in Settings -> Manga Source"
                    } else {
                        "${state.installedExtensions.size} installed extension(s)"
                    },
                    trailing = {
                        GlassCircleButton(
                            icon = Icons.Outlined.Tune,
                            contentDescription = "Manga Source settings",
                            onClick = onOpenMangaSourceSettings,
                            tint = colors.accentAmber
                        )
                    }
                )

                GlassSearchField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChanged,
                    placeholder = "Search manga, manhwa, webtoons...",
                    onSearch = viewModel::loadCatalog,
                    onClear = {
                        viewModel.onQueryChanged("")
                        viewModel.loadCatalog()
                    },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        GlassChip(
                            text = "All Sources",
                            selected = state.selectedSourceId == null,
                            onClick = { viewModel.selectSource(null) }
                        )
                    }
                    items(sources, key = { it.id }) { src ->
                        val chipLabel = if ((duplicateSourceNames[src.name] ?: 0) > 1) {
                            "${src.name} (${src.lang.uppercase()})"
                        } else {
                            src.name
                        }
                        GlassChip(
                            text = chipLabel,
                            selected = state.selectedSourceId == src.id,
                            onClick = { viewModel.selectSource(src.id) }
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        state.isLoading -> {
                            CircularProgressIndicator(
                                color = colors.accentAmber,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                        state.error != null && state.items.isEmpty() -> {
                            EmptyStateView(
                                icon = Icons.Outlined.ErrorOutline,
                                title = "Source error",
                                description = state.error.orEmpty(),
                                actionLabel = "Retry",
                                onAction = viewModel::loadCatalog
                            )
                        }
                        state.items.isEmpty() -> {
                            EmptyStateView(
                                icon = Icons.Outlined.AutoStories,
                                title = if (state.installedExtensions.isEmpty()) {
                                    "No manga loaded"
                                } else {
                                    "No results from this source"
                                },
                                description = if (state.installedExtensions.isEmpty()) {
                                    "Install an Aniyomi manga extension in Settings -> Manga Source to browse and read chapters."
                                } else {
                                    "Try another search query, switch provider, or check the extension in Settings -> Manga Source."
                                },
                                actionLabel = if (state.installedExtensions.isEmpty()) "Open Manga Source" else "Retry",
                                onAction = if (state.installedExtensions.isEmpty()) {
                                    onOpenMangaSourceSettings
                                } else {
                                    viewModel::loadCatalog
                                }
                            )
                        }
                        else -> {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = 120.dp),
                                contentPadding = PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 8.dp,
                                    bottom = navClearance + 16.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(state.items.distinctBy { it.id }, key = { it.id }) { manga ->
                                    MediaPosterCard(
                                        title = manga.title,
                                        posterUrl = manga.coverUrl,
                                        sourceTag = manga.sourceName,
                                        subtitle = manga.author,
                                        onClick = { onOpenManga(manga) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
