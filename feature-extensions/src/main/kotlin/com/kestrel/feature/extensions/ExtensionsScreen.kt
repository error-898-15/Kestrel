// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.extensions

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.kestrel.core.designsystem.EmptyStateView
import com.kestrel.core.designsystem.KestrelCard
import com.kestrel.core.designsystem.KestrelPrimaryButton
import com.kestrel.core.designsystem.KestrelTheme
import com.kestrel.core.designsystem.KestrelTopBar
import com.kestrel.core.designsystem.SectionHeader
import com.kestrel.core.designsystem.SourceBadge
import com.kestrel.domain.model.PluginInstallState
import com.kestrel.domain.model.PluginMetadata
import com.kestrel.domain.model.RepoConfig

@Composable
fun ExtensionsScreen(
    modifier: Modifier = Modifier,
    viewModel: ExtensionsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = KestrelTheme.colors

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.canvas,
        topBar = {
            KestrelTopBar(
                title = "Extensions",
                subtitle = "CloudStream .cs3, Nuvio & Stremio repositories"
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                AddRepositoryCard(
                    value = state.repoInput,
                    isAdding = state.isAddingRepo,
                    onValueChange = viewModel::onRepoInputChanged,
                    onAdd = viewModel::addRepository
                )
            }

            state.bannerMessage?.let { msg ->
                item {
                    KestrelCard(
                        highlighted = !state.bannerIsError,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (state.bannerIsError) {
                                        Icons.Outlined.ErrorOutline
                                    } else {
                                        Icons.Outlined.CheckCircle
                                    },
                                    contentDescription = null,
                                    tint = if (state.bannerIsError) colors.statusError else colors.statusSuccess,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textPrimary
                                )
                            }
                            IconButton(onClick = viewModel::dismissBanner) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = "Dismiss",
                                    tint = colors.textMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (state.repositories.isNotEmpty()) {
                item(key = "repos_header", contentType = "header") {
                    SectionHeader(
                        title = "Repositories",
                        badge = "${state.repositories.size}"
                    )
                }
                items(state.repositories, key = { it.id }, contentType = { "repo_card" }) { repo ->
                    RepositoryCard(
                        repo = repo,
                        isRefreshing = repo.id in state.refreshingRepoIds,
                        isInstallingAll = repo.id in state.installingRepoIds,
                        onInstallAll = { viewModel.installAllInRepo(repo.id) },
                        onValidate = { viewModel.validateAndRefreshRepo(repo.id) },
                        onRemove = { viewModel.removeRepository(repo.id) }
                    )
                }
            }

            item(key = "plugins_header", contentType = "header") {
                SectionHeader(
                    title = "Plugins",
                    badge = "${state.plugins.size}"
                )
            }

            if (state.repositories.isNotEmpty()) {
                item(key = "plugins_filters", contentType = "filters") {
                    PluginFiltersSection(
                        searchQuery = state.searchQuery,
                        languages = state.availableLanguages,
                        selectedLanguage = state.selectedLanguage,
                        tvTypes = state.availableTvTypes,
                        selectedTvType = state.selectedTvType,
                        onSearchChanged = viewModel::onSearchQueryChanged,
                        onSelectLanguage = viewModel::selectLanguage,
                        onSelectTvType = viewModel::selectTvType
                    )
                }
            }

            if (state.plugins.isEmpty()) {
                item(key = "plugins_empty", contentType = "empty") {
                    Box(modifier = Modifier.height(260.dp)) {
                        EmptyStateView(
                            icon = Icons.Outlined.Extension,
                            title = if (state.repositories.isEmpty()) {
                                "No repositories added"
                            } else {
                                "No matching plugins"
                            },
                            description = if (state.repositories.isEmpty()) {
                                "Paste a CloudStream repo.json URL, cloudstreamrepo:// link, shortcode, Nuvio manifest.json, or Stremio manifest.json above."
                            } else {
                                "Adjust your language, category, or search filters."
                            }
                        )
                    }
                }
            } else {
                items(state.plugins, key = { it.id }, contentType = { "plugin_row" }) { plugin ->
                    PluginRowCard(
                        plugin = plugin,
                        isBusy = plugin.id in state.busyPluginIds,
                        onInstallOrUpdate = { viewModel.installOrUpdatePlugin(plugin) },
                        onUninstall = { viewModel.uninstallPlugin(plugin.id) },
                        onToggleEnabled = { enabled -> viewModel.togglePluginEnabled(plugin.id, enabled) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AddRepositoryCard(
    value: String,
    isAdding: Boolean,
    onValueChange: (String) -> Unit,
    onAdd: () -> Unit
) {
    val colors = KestrelTheme.colors
    KestrelCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Add Repository",
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Supports GitHub/Codeberg URLs, cloudstreamrepo://, shortcodes, Nuvio, and Stremio manifests.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                placeholder = { Text("https://... or cloudstreamrepo://...") },
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accentAmber,
                    unfocusedBorderColor = colors.borderSubtle,
                    focusedContainerColor = colors.surfaceLow,
                    unfocusedContainerColor = colors.surfaceLow,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary
                )
            )
            KestrelPrimaryButton(
                text = if (isAdding) "..." else "Add",
                enabled = !isAdding && value.isNotBlank(),
                onClick = onAdd
            )
        }
    }
}

@Composable
private fun RepositoryCard(
    repo: RepoConfig,
    isRefreshing: Boolean,
    isInstallingAll: Boolean,
    onInstallAll: () -> Unit,
    onValidate: () -> Unit,
    onRemove: () -> Unit
) {
    val colors = KestrelTheme.colors
    KestrelCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = repo.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    SourceBadge(label = repo.type.name)
                }
                if (repo.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = repo.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = repo.lastError ?: repo.resolvedUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (repo.isHealthy) colors.textMuted else colors.statusError,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (repo.type.name != "STREMIO") {
                    IconButton(onClick = onInstallAll, enabled = !isInstallingAll) {
                        if (isInstallingAll) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = colors.accentAmber
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Download,
                                contentDescription = "Install All Plugins",
                                tint = colors.accentAmber
                            )
                        }
                    }
                }
                IconButton(onClick = onValidate, enabled = !isRefreshing) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = colors.accentAmber
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Validate Repository",
                            tint = colors.accentTeal
                        )
                    }
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "Remove Repository",
                        tint = colors.statusError
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginFiltersSection(
    searchQuery: String,
    languages: List<String>,
    selectedLanguage: String?,
    tvTypes: List<String>,
    selectedTvType: String?,
    onSearchChanged: (String) -> Unit,
    onSelectLanguage: (String?) -> Unit,
    onSelectTvType: (String?) -> Unit
) {
    val colors = KestrelTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChanged,
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            placeholder = { Text("Filter plugins by name...") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accentAmber,
                unfocusedBorderColor = colors.borderSubtle,
                focusedContainerColor = colors.surface,
                unfocusedContainerColor = colors.surface,
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary
            )
        )
        if (languages.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(languages) { lang ->
                    FilterChip(
                        selected = selectedLanguage == lang,
                        onClick = { onSelectLanguage(lang) },
                        label = { Text(lang.uppercase()) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.accentAmberDim,
                            selectedLabelColor = colors.accentAmber
                        )
                    )
                }
            }
        }
        if (tvTypes.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(tvTypes) { type ->
                    FilterChip(
                        selected = selectedTvType == type,
                        onClick = { onSelectTvType(type) },
                        label = { Text(type) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.accentTealDim,
                            selectedLabelColor = colors.accentTeal
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginRowCard(
    plugin: PluginMetadata,
    isBusy: Boolean,
    onInstallOrUpdate: () -> Unit,
    onUninstall: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit
) {
    val colors = KestrelTheme.colors
    KestrelCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceHigh),
                contentAlignment = Alignment.Center
            ) {
                if (!plugin.iconUrl.isNullOrBlank()) {
                    val ctx = LocalContext.current
                    val iconReq = remember(plugin.iconUrl) {
                        ImageRequest.Builder(ctx)
                            .data(plugin.iconUrl)
                            .size(96, 96)
                            .build()
                    }
                    AsyncImage(
                        model = iconReq,
                        contentDescription = plugin.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Extension,
                        contentDescription = null,
                        tint = colors.accentAmber
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = plugin.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    SourceBadge(label = plugin.language)
                    Text(
                        text = "v${plugin.version}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted
                    )
                }
                if (plugin.tvTypes.isNotEmpty()) {
                    Text(
                        text = plugin.tvTypes.joinToString(" • "),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (plugin.description.isNotBlank()) {
                    Text(
                        text = plugin.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!plugin.lastError.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = plugin.lastError!!,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.statusError
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                when (plugin.installState) {
                    PluginInstallState.NOT_INSTALLED,
                    PluginInstallState.ERROR -> {
                        IconButton(onClick = onInstallOrUpdate, enabled = !isBusy) {
                            if (isBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.Download,
                                    contentDescription = "Install",
                                    tint = colors.accentAmber
                                )
                            }
                        }
                    }

                    PluginInstallState.INSTALLING -> {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(10.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp,
                            color = colors.accentAmber
                        )
                    }

                    PluginInstallState.UPDATE_AVAILABLE -> {
                        IconButton(onClick = onInstallOrUpdate, enabled = !isBusy) {
                            Icon(
                                imageVector = Icons.Outlined.SystemUpdateAlt,
                                contentDescription = "Update",
                                tint = colors.accentAmber
                            )
                        }
                        Switch(
                            checked = plugin.isEnabled,
                            onCheckedChange = onToggleEnabled,
                            colors = SwitchDefaults.colors(checkedTrackColor = colors.accentAmber)
                        )
                    }

                    PluginInstallState.INSTALLED -> {
                        IconButton(onClick = onUninstall) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = "Uninstall",
                                tint = colors.textMuted
                            )
                        }
                        Switch(
                            checked = plugin.isEnabled,
                            onCheckedChange = onToggleEnabled,
                            colors = SwitchDefaults.colors(checkedTrackColor = colors.accentAmber)
                        )
                    }

                    PluginInstallState.UNSUPPORTED_API -> {
                        SourceBadge(
                            label = "API v${plugin.apiVersion}",
                            accent = colors.statusError,
                            container = colors.statusErrorDim
                        )
                    }
                }
            }
        }
    }
}
