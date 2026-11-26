// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.search

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
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
fun SearchScreen(
    onOpenExtensions: () -> Unit,
    onOpenMedia: (MediaSummary) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = KestrelTheme.colors

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.canvas,
        topBar = {
            KestrelTopBar(
                title = "Search",
                subtitle = if (state.activeProviderCount == 0) {
                    "Unified search across enabled adapters"
                } else {
                    "Searching ${state.activeProviderCount} active provider(s)"
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text("Search movies, series, anime...") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotBlank()) {
                        IconButton(onClick = viewModel::submitSearch) {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = "Run search",
                                tint = colors.accentAmber
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.submitSearch() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accentAmber,
                    unfocusedBorderColor = colors.borderSubtle,
                    focusedContainerColor = colors.surface,
                    unfocusedContainerColor = colors.surface,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary
                )
            )

            when {
                state.activeProviderCount == 0 -> {
                    EmptyStateView(
                        icon = Icons.Outlined.Search,
                        title = "No providers enabled",
                        description = "Install and enable at least one plugin to search across your added repositories.",
                        actionLabel = "Open Extensions",
                        onAction = onOpenExtensions
                    )
                }

                state.isSearching -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = colors.accentAmber)
                    }
                }

                !state.hasSearched -> {
                    EmptyStateView(
                        icon = Icons.Outlined.Search,
                        title = "Search across sources",
                        description = "Type a title above and press search. Results will appear grouped by each enabled provider."
                    )
                }

                state.groups.all { it.items.isEmpty() && it.errorMessage == null } -> {
                    EmptyStateView(
                        icon = Icons.Outlined.Search,
                        title = "No results found",
                        description = "None of the enabled providers matched \"${state.query}\"."
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        items(state.groups, key = { it.providerId }) { group ->
                            if (group.items.isNotEmpty() || group.errorMessage != null) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SectionHeader(
                                        title = group.providerName,
                                        badge = if (group.errorMessage != null) "ERROR" else "${group.items.size}",
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )
                                    if (group.errorMessage != null) {
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
                                            contentPadding = PaddingValues(horizontal = 16.dp),
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
                        item { Spacer(modifier = Modifier.height(12.dp)) }
                    }
                }
            }
        }
    }
}
