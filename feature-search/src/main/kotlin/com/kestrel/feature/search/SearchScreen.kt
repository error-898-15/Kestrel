// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kestrel.core.designsystem.EmptyStateView
import com.kestrel.core.designsystem.GlassCircleButton
import com.kestrel.core.designsystem.GlassScreenHeader
import com.kestrel.core.designsystem.GlassSearchField
import com.kestrel.core.designsystem.KestrelBackdrop
import com.kestrel.core.designsystem.KestrelBackground
import com.kestrel.core.designsystem.KestrelCard
import com.kestrel.core.designsystem.KestrelTheme
import com.kestrel.core.designsystem.LocalGlassState
import com.kestrel.core.designsystem.MediaPosterCard
import com.kestrel.core.designsystem.SectionHeader
import com.kestrel.core.designsystem.glassSource
import com.kestrel.core.designsystem.kestrelNavClearance
import com.kestrel.core.designsystem.rememberGlassState
import com.kestrel.domain.model.MediaSummary

@Composable
fun SearchScreen(
    onOpenExtensions: () -> Unit,
    onOpenMedia: (MediaSummary) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = KestrelTheme.colors
    val navClearance = kestrelNavClearance()
    val searchGlass = rememberGlassState()

    KestrelBackground(
        modifier = modifier.fillMaxSize(),
        style = KestrelBackdrop.Dark
    ) {
        CompositionLocalProvider(LocalGlassState provides searchGlass) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                GlassScreenHeader(
                    title = "Search",
                    subtitle = if (state.activeProviderCount == 0) {
                        "Unified search across enabled adapters"
                    } else {
                        "Searching ${state.activeProviderCount} active provider(s)"
                    },
                    trailing = {
                        if (state.query.isNotBlank()) {
                            GlassCircleButton(
                                icon = Icons.Outlined.Search,
                                contentDescription = "Run search",
                                onClick = viewModel::submitSearch,
                                tint = colors.accentAmber
                            )
                        }
                    }
                )

                GlassSearchField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChanged,
                    placeholder = "Search movies, series, anime...",
                    onSearch = viewModel::submitSearch,
                    onClear = { viewModel.onQueryChanged("") },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .glassSource(searchGlass)
                ) {
                    when {
                        state.activeProviderCount == 0 -> {
                            EmptyStateView(
                                icon = Icons.Outlined.Search,
                                title = "No providers enabled",
                                description = "Install and enable at least one plugin to search across your added repositories.",
                                actionLabel = "Open Extensions",
                                onAction = onOpenExtensions,
                                modifier = Modifier.padding(bottom = navClearance)
                            )
                        }

                        state.isSearching -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = navClearance),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = colors.accentAmber)
                            }
                        }

                        !state.hasSearched -> {
                            EmptyStateView(
                                icon = Icons.Outlined.Search,
                                title = "Search across sources",
                                description = "Type a title above and press search. Results will appear grouped by each enabled provider.",
                                modifier = Modifier.padding(bottom = navClearance)
                            )
                        }

                        state.groups.all { it.items.isEmpty() && it.errorMessage == null } -> {
                            EmptyStateView(
                                icon = Icons.Outlined.Search,
                                title = "No results found",
                                description = "None of the enabled providers matched \"${state.query}\".",
                                modifier = Modifier.padding(bottom = navClearance)
                            )
                        }

                        else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(top = 14.dp, bottom = navClearance),
                                verticalArrangement = Arrangement.spacedBy(20.dp)
                            ) {
                                items(state.groups, key = { it.providerId }) { group ->
                                    if (group.items.isNotEmpty() || group.errorMessage != null) {
                                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            SectionHeader(
                                                title = group.providerName,
                                                badge = if (group.errorMessage != null) "ERROR" else "${group.items.size}",
                                                modifier = Modifier.padding(horizontal = 20.dp)
                                            )
                                            if (group.errorMessage != null) {
                                                KestrelCard(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 20.dp)
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Outlined.ErrorOutline,
                                                            contentDescription = null,
                                                            tint = colors.statusError,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                        Text(
                                                            text = group.errorMessage!!,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = colors.textSecondary
                                                        )
                                                    }
                                                }
                                            } else {
                                                LazyRow(
                                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                ) {
                                                    itemsIndexed(
                                                        items = group.items,
                                                        key = { idx, item -> "${item.id}_$idx" },
                                                        contentType = { _, _ -> "poster_card" }
                                                    ) { _, item ->
                                                        MediaPosterCard(
                                                            title = item.title,
                                                            posterUrl = item.posterUrl,
                                                            sourceTag = item.providerName,
                                                            subtitle = item.year?.toString() ?: item.qualityLabel,
                                                            posterHeaders = item.posterHeaders,
                                                            onClick = { onOpenMedia(item) }
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
                }
            }
        }
    }
}
