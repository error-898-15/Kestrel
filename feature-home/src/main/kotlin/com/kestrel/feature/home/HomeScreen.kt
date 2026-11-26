// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kestrel.core.designsystem.EmptyStateView
import com.kestrel.core.designsystem.KestrelCard
import com.kestrel.core.designsystem.KestrelTheme
import com.kestrel.core.designsystem.KestrelTopBar
import com.kestrel.core.designsystem.MediaPosterCard
import com.kestrel.core.designsystem.SectionHeader
import com.kestrel.domain.model.MediaSummary

@Composable
fun HomeScreen(
    onOpenExtensions: () -> Unit,
    onOpenMedia: (MediaSummary) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = KestrelTheme.colors

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.canvas,
        topBar = {
            KestrelTopBar(
                title = "Discover",
                subtitle = if (state.providers.isEmpty()) {
                    "Catalogs from enabled extensions"
                } else {
                    "${state.providers.size} active catalog source(s)"
                },
                actions = {
                    if (state.providers.isNotEmpty()) {
                        IconButton(onClick = viewModel::refreshCatalogs) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Refresh Catalogs",
                                tint = colors.accentAmber
                            )
                        }
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
            if (state.providers.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = state.selectedProviderId == null,
                            onClick = { viewModel.selectProvider(null) },
                            label = { Text("All Sources") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.accentAmberDim,
                                selectedLabelColor = colors.accentAmber
                            )
                        )
                    }
                    items(state.providers, key = { it.providerId }) { provider ->
                        FilterChip(
                            selected = state.selectedProviderId == provider.providerId,
                            onClick = { viewModel.selectProvider(provider.providerId) },
                            label = { Text(provider.name) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.accentTealDim,
                                selectedLabelColor = colors.accentTeal
                            )
                        )
                    }
                }
            }

            when {
                state.isLoading && state.rows.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = colors.accentAmber)
                    }
                }

                state.providers.isEmpty() -> {
                    EmptyStateView(
                        icon = Icons.Outlined.Explore,
                        title = "No active sources",
                        description = "Kestrel ships with zero built-in content. Install and enable a CloudStream .cs3, Nuvio, or Stremio repository in Extensions to browse catalogs.",
                        actionLabel = "Manage Extensions",
                        onAction = onOpenExtensions
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        if (state.providerErrors.isNotEmpty()) {
                            item {
                                KestrelCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.ErrorOutline,
                                            contentDescription = null,
                                            tint = colors.statusWarning,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = state.providerErrors.joinToString("\n"),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colors.textSecondary
                                        )
                                    }
                                }
                            }
                        }

                        if (state.rows.isEmpty()) {
                            item {
                                Box(modifier = Modifier.height(260.dp)) {
                                    EmptyStateView(
                                        icon = Icons.Outlined.Explore,
                                        title = "No catalog rows returned",
                                        description = "Active providers returned no items for this selection. Try refreshing or switching sources."
                                    )
                                }
                            }
                        } else {
                            items(state.rows, key = { it.id }) { row ->
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SectionHeader(
                                        title = row.title,
                                        badge = row.providerName,
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        items(row.items, key = { it.id }) { item ->
                                            MediaPosterCard(
                                                title = item.title,
                                                posterUrl = item.posterUrl,
                                                sourceTag = item.qualityLabel ?: item.type.name,
                                                subtitle = item.year?.toString(),
                                                onClick = { onOpenMedia(item) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        item { Spacer(modifier = Modifier.height(8.dp)) }
                    }
                }
            }
        }
    }
}
