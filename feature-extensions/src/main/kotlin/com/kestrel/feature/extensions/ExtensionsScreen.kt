// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.extensions

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.kestrel.core.designsystem.EmptyStateView
import com.kestrel.core.designsystem.GlassCircleButton
import com.kestrel.core.designsystem.GlassScreenHeader
import com.kestrel.core.designsystem.GlassSearchField
import com.kestrel.core.designsystem.KestrelBackdrop
import com.kestrel.core.designsystem.KestrelBackground
import com.kestrel.core.designsystem.KestrelCard
import com.kestrel.core.designsystem.KestrelPrimaryButton
import com.kestrel.core.designsystem.KestrelTheme
import com.kestrel.core.designsystem.SourceBadge
import com.kestrel.core.designsystem.glassPanel
import com.kestrel.core.designsystem.kestrelNavClearance
import com.kestrel.core.designsystem.rememberKestrelImageRequest
import com.kestrel.domain.model.PluginInstallState
import com.kestrel.domain.model.PluginMetadata
import com.kestrel.domain.model.RepoConfig
import com.kestrel.domain.model.RepoType

private const val PAGE_HUB = "hub"
private const val PAGE_INSTALLED = "installed"
private const val PAGE_LIST_PREFIX = "list:"
private const val PAGE_REPO_PREFIX = "repo:"

private val LilacText = Color(0xFFBBA9FF)
private val IconTileTint = Color(0xFFD7CBFF)

private enum class RepoFilter(
    val key: String,
    val title: String,
    val subtitle: String,
    val type: RepoType?,
    val icon: ImageVector
) {
    ALL("ALL", "All installed repos", "All repos you've added", null, Icons.Outlined.Folder),
    CLOUDSTREAM("CLOUDSTREAM", "CloudStream repos", "repo.json \u00B7 CloudStream extensions", RepoType.CLOUDSTREAM, Icons.Outlined.Cloud),
    NUVIO("NUVIO", "Nuvio repos", "manifest.json \u00B7 Nuvio providers", RepoType.NUVIO, Icons.Outlined.PlayArrow),
    STREMIO("STREMIO", "Stremio repos", "manifest.json \u00B7 Stremio addons", RepoType.STREMIO, Icons.Outlined.Movie);

    companion object {
        fun fromKey(key: String): RepoFilter = entries.firstOrNull { it.key == key } ?: ALL
    }
}

private fun PluginMetadata.isInstalled(): Boolean =
    installState == PluginInstallState.INSTALLED || installState == PluginInstallState.UPDATE_AVAILABLE

private fun PluginMetadata.needsInstall(): Boolean =
    installState == PluginInstallState.NOT_INSTALLED ||
        installState == PluginInstallState.ERROR ||
        installState == PluginInstallState.UPDATE_AVAILABLE

@Composable
fun ExtensionsScreen(
    modifier: Modifier = Modifier,
    viewModel: ExtensionsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navClearance = kestrelNavClearance()
    val clipboard = LocalClipboardManager.current

    var page by rememberSaveable { mutableStateOf(PAGE_HUB) }
    var repoParent by rememberSaveable { mutableStateOf(PAGE_HUB) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var wasAdding by remember { mutableStateOf(false) }

    fun go(target: String) {
        viewModel.onSearchQueryChanged("")
        page = target
    }

    fun openRepo(repoId: String) {
        repoParent = page
        go(PAGE_REPO_PREFIX + repoId)
    }

    fun back() {
        if (page.startsWith(PAGE_REPO_PREFIX)) go(repoParent) else go(PAGE_HUB)
    }

    BackHandler(enabled = page != PAGE_HUB) { back() }

    DisposableEffect(Unit) {
        onDispose { viewModel.onSearchQueryChanged("") }
    }

    LaunchedEffect(state.isAddingRepo) {
        if (wasAdding && !state.isAddingRepo && state.repoInput.isBlank()) {
            showAddDialog = false
        }
        wasAdding = state.isAddingRepo
    }

    val appear = remember(page) { Animatable(0f) }
    LaunchedEffect(page) { appear.animateTo(1f, tween(240)) }

    KestrelBackground(
        modifier = modifier.fillMaxSize(),
        style = KestrelBackdrop.Purple
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .graphicsLayer {
                    alpha = appear.value
                    translationY = (1f - appear.value) * 28f
                }
        ) {
            when {
                page == PAGE_HUB -> {
                    GlassScreenHeader(
                        title = "Extensions",
                        subtitle = "CloudStream .cs3, Nuvio & Stremio repositories"
                    )
                    BannerCard(state = state, onDismiss = viewModel::dismissBanner)
                    HubPage(
                        state = state,
                        bottomPadding = navClearance + 76.dp,
                        onNavigate = { target -> go(target) }
                    )
                }

                page == PAGE_INSTALLED -> {
                    val installed = state.plugins.filter { it.isInstalled() }
                    GlassScreenHeader(
                        title = "Installed extensions",
                        subtitle = "Manage, toggle & uninstall",
                        onBack = { back() },
                        trailing = {
                            Text(
                                text = "${installed.size} installed",
                                style = MaterialTheme.typography.labelLarge,
                                color = LilacText
                            )
                        }
                    )
                    BannerCard(state = state, onDismiss = viewModel::dismissBanner)
                    GlassSearchField(
                        value = state.searchQuery,
                        onValueChange = viewModel::onSearchQueryChanged,
                        placeholder = "Filter installed extensions\u2026",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        onClear = { viewModel.onSearchQueryChanged("") }
                    )
                    PluginList(
                        plugins = installed,
                        state = state,
                        bottomPadding = navClearance,
                        viewModel = viewModel,
                        emptyTitle = "Nothing installed yet",
                        emptyDescription = "Open a repository and install a plugin to see it here."
                    )
                }

                page.startsWith(PAGE_REPO_PREFIX) -> {
                    val repoId = page.removePrefix(PAGE_REPO_PREFIX)
                    val repo = state.repositories.firstOrNull { it.id == repoId }
                    if (repo == null) {
                        LaunchedEffect(repoId) { back() }
                    } else {
                        val repoPlugins = state.plugins.filter { it.repoId == repo.id }
                        val pending = repoPlugins.count { it.needsInstall() }
                        val isRefreshing = repo.id in state.refreshingRepoIds
                        val isInstallingAll = repo.id in state.installingRepoIds

                        GlassScreenHeader(
                            title = repo.name,
                            subtitle = repo.description.ifBlank { repo.type.name.lowercase() + " repository" },
                            onBack = { back() },
                            trailing = {
                                Text(
                                    text = "${repoPlugins.size} plugins",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = LilacText
                                )
                                ActionCircle(
                                    icon = Icons.Outlined.Refresh,
                                    description = "Validate Repository",
                                    busy = isRefreshing,
                                    onClick = { viewModel.validateAndRefreshRepo(repo.id) }
                                )
                            }
                        )
                        BannerCard(state = state, onDismiss = viewModel::dismissBanner)
                        GlassSearchField(
                            value = state.searchQuery,
                            onValueChange = viewModel::onSearchQueryChanged,
                            placeholder = "Search this repo\u2026",
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            onClear = { viewModel.onSearchQueryChanged("") }
                        )
                        if (repo.type != RepoType.STREMIO) {
                            KestrelPrimaryButton(
                                text = when {
                                    isInstallingAll -> "Installing\u2026"
                                    pending > 0 -> "Install all ($pending)"
                                    else -> "All plugins installed"
                                },
                                onClick = { viewModel.installAllInRepo(repo.id) },
                                enabled = pending > 0 && !isInstallingAll,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 6.dp)
                                    .height(52.dp)
                            )
                        }
                        PluginList(
                            plugins = repoPlugins,
                            state = state,
                            bottomPadding = navClearance,
                            viewModel = viewModel,
                            emptyTitle = "No matching plugins",
                            emptyDescription = "This repository has no plugins matching your search."
                        )
                    }
                }

                else -> {
                    val filter = RepoFilter.fromKey(page.removePrefix(PAGE_LIST_PREFIX))
                    val repos = state.repositories.filter { filter.type == null || it.type == filter.type }
                    GlassScreenHeader(
                        title = filter.title,
                        subtitle = filter.subtitle,
                        onBack = { back() },
                        trailing = {
                            Text(
                                text = "${repos.size} repos",
                                style = MaterialTheme.typography.labelLarge,
                                color = LilacText
                            )
                        }
                    )
                    BannerCard(state = state, onDismiss = viewModel::dismissBanner)
                    if (repos.isEmpty()) {
                        EmptyStateView(
                            icon = filter.icon,
                            title = "No repositories",
                            description = "Tap Add repo and paste a CloudStream repo.json URL, cloudstreamrepo:// link, shortcode, Nuvio manifest.json, or Stremio manifest.json."
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 20.dp,
                                end = 20.dp,
                                top = 8.dp,
                                bottom = navClearance + 76.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(repos, key = { it.id }, contentType = { "repo_card" }) { repo ->
                                RepositoryCard(
                                    repo = repo,
                                    pluginCount = state.plugins.count { it.repoId == repo.id },
                                    pendingCount = state.plugins.count { it.repoId == repo.id && it.needsInstall() },
                                    isRefreshing = repo.id in state.refreshingRepoIds,
                                    isInstalling = repo.id in state.installingRepoIds,
                                    onOpen = { openRepo(repo.id) },
                                    onInstallAll = { viewModel.installAllInRepo(repo.id) },
                                    onCopy = { clipboard.setText(AnnotatedString(repo.url)) },
                                    onValidate = { viewModel.validateAndRefreshRepo(repo.id) },
                                    onRemove = { viewModel.removeRepository(repo.id) }
                                )
                            }
                        }
                    }
                }
            }
        }

        val showAddButton = page == PAGE_HUB || page.startsWith(PAGE_LIST_PREFIX)
        if (showAddButton) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 20.dp, end = 20.dp, bottom = navClearance)
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(CircleShape)
                    .glassPanel(
                        cornerRadius = 28.dp,
                        tint = KestrelTheme.colors.accentAmber.copy(alpha = 0.30f),
                        rimColor = KestrelTheme.colors.accentAmber,
                        rimAlpha = 0.85f
                    )
                    .clickable { showAddDialog = true },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    tint = KestrelTheme.colors.accentAmber,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "Add repo",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = KestrelTheme.colors.accentAmber
                )
            }
        }
    }

    if (showAddDialog) {
        AddRepoDialog(
            value = state.repoInput,
            isAdding = state.isAddingRepo,
            errorMessage = if (state.bannerIsError) state.bannerMessage else null,
            onValueChange = viewModel::onRepoInputChanged,
            onAdd = viewModel::addRepository,
            onDismiss = { showAddDialog = false }
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Pages
// ---------------------------------------------------------------------------------------------

@Composable
private fun HubPage(
    state: ExtensionsUiState,
    bottomPadding: Dp,
    onNavigate: (String) -> Unit
) {
    val installed = state.plugins.count { it.isInstalled() }
    val enabled = state.plugins.count { it.isInstalled() && it.isEnabled }
    val rows = listOf(
        Triple(RepoFilter.ALL, "${state.repositories.size} total", PAGE_LIST_PREFIX + RepoFilter.ALL.key),
        Triple(RepoFilter.CLOUDSTREAM, "${state.repositories.count { it.type == RepoType.CLOUDSTREAM }} repos", PAGE_LIST_PREFIX + RepoFilter.CLOUDSTREAM.key),
        Triple(RepoFilter.NUVIO, "${state.repositories.count { it.type == RepoType.NUVIO }} repos", PAGE_LIST_PREFIX + RepoFilter.NUVIO.key),
        Triple(RepoFilter.STREMIO, "${state.repositories.count { it.type == RepoType.STREMIO }} repos", PAGE_LIST_PREFIX + RepoFilter.STREMIO.key)
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "summary") {
            KestrelCard(
                onClick = { onNavigate(PAGE_INSTALLED) },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp)
            ) {
                MenuRowContent(
                    icon = Icons.Outlined.Extension,
                    title = "$installed extensions installed",
                    subtitle = "${state.repositories.size} repos \u00B7 $enabled enabled"
                )
            }
        }
        item(key = "add_source_label") {
            Text(
                text = "ADD A SOURCE",
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp),
                color = KestrelTheme.colors.textMuted,
                modifier = Modifier.padding(start = 6.dp, top = 4.dp)
            )
        }
        item(key = "sources") {
            KestrelCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(0.dp)
            ) {
                rows.forEachIndexed { index, (filter, count, target) ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigate(target) }
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        MenuRowContent(
                            icon = filter.icon,
                            title = filter.title,
                            subtitle = filter.subtitle + " \u00B7 " + count
                        )
                    }
                    if (index != rows.lastIndex) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 78.dp)
                                .height(1.dp)
                                .background(Color.White.copy(alpha = 0.08f))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRowContent(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    val colors = KestrelTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(17.dp))
                .glassPanel(
                    cornerRadius = 17.dp,
                    tint = Color(0xFF9B7BFF).copy(alpha = 0.22f),
                    rimAlpha = 0.5f
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = IconTileTint,
                modifier = Modifier.size(26.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textSecondary
        )
    }
}

@Composable
private fun BannerCard(
    state: ExtensionsUiState,
    onDismiss: () -> Unit
) {
    val message = state.bannerMessage ?: return
    val colors = KestrelTheme.colors
    KestrelCard(
        highlighted = !state.bannerIsError,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        contentPadding = PaddingValues(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
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
                    imageVector = if (state.bannerIsError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = if (state.bannerIsError) colors.statusError else colors.statusSuccess,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary
                )
            }
            IconButton(onClick = onDismiss) {
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

@Composable
private fun PluginList(
    plugins: List<PluginMetadata>,
    state: ExtensionsUiState,
    bottomPadding: Dp,
    viewModel: ExtensionsViewModel,
    emptyTitle: String,
    emptyDescription: String
) {
    if (plugins.isEmpty()) {
        EmptyStateView(
            icon = Icons.Outlined.Extension,
            title = emptyTitle,
            description = emptyDescription
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(plugins, key = { it.id }, contentType = { "plugin_row" }) { plugin ->
            PluginCard(
                plugin = plugin,
                isBusy = plugin.id in state.busyPluginIds,
                onInstallOrUpdate = { viewModel.installOrUpdatePlugin(plugin) },
                onUninstall = { viewModel.uninstallPlugin(plugin.id) },
                onToggleEnabled = { enabled -> viewModel.togglePluginEnabled(plugin.id, enabled) }
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------------------------

@Composable
private fun ActionCircle(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    tint: Color = KestrelTheme.colors.textPrimary
) {
    if (busy) {
        Box(
            modifier = modifier
                .size(44.dp)
                .clip(CircleShape)
                .glassPanel(cornerRadius = 22.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = KestrelTheme.colors.accentAmber
            )
        }
    } else {
        GlassCircleButton(
            icon = icon,
            contentDescription = description,
            onClick = onClick,
            modifier = modifier,
            tint = tint
        )
    }
}

@Composable
private fun RepositoryCard(
    repo: RepoConfig,
    pluginCount: Int,
    pendingCount: Int,
    isRefreshing: Boolean,
    isInstalling: Boolean,
    onOpen: () -> Unit,
    onInstallAll: () -> Unit,
    onCopy: () -> Unit,
    onValidate: () -> Unit,
    onRemove: () -> Unit
) {
    val colors = KestrelTheme.colors
    KestrelCard(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF9B7BFF), Color(0xFF5B3FD0)))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = repo.name.trim().take(1).uppercase(),
                    style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
                    color = Color.White
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = repo.name,
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SourceBadge(label = repo.type.name)
                    Text(
                        text = if (pendingCount > 0 && repo.type != RepoType.STREMIO) {
                            "$pluginCount plugins \u00B7 $pendingCount to install"
                        } else {
                            "$pluginCount plugins"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!repo.lastError.isNullOrBlank() || !repo.isHealthy) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = repo.lastError ?: "Repository unreachable",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.statusError,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (repo.type != RepoType.STREMIO && (pendingCount > 0 || isInstalling)) {
                ActionCircle(
                    icon = Icons.Outlined.Download,
                    description = "Install all plugins from this repository",
                    onClick = onInstallAll,
                    busy = isInstalling,
                    tint = colors.accentAmber
                )
            }
            ActionCircle(
                icon = Icons.Outlined.ContentCopy,
                description = "Copy repository URL",
                onClick = onCopy
            )
            ActionCircle(
                icon = Icons.Outlined.Refresh,
                description = "Validate Repository",
                onClick = onValidate,
                busy = isRefreshing,
                tint = colors.accentTeal
            )
            ActionCircle(
                icon = Icons.Outlined.DeleteOutline,
                description = "Remove Repository",
                onClick = onRemove,
                tint = colors.statusError
            )
        }
    }
}

@Composable
private fun GlassPillButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    accent: Color = KestrelTheme.colors.accentAmber
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .glassPanel(
                cornerRadius = 40.dp,
                tint = accent.copy(alpha = 0.22f),
                rimColor = accent,
                rimAlpha = 0.7f
            )
            .clickable(enabled = enabled && !busy, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = accent
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = accent,
            maxLines = 1
        )
    }
}

@Composable
private fun PluginCard(
    plugin: PluginMetadata,
    isBusy: Boolean,
    onInstallOrUpdate: () -> Unit,
    onUninstall: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit
) {
    val colors = KestrelTheme.colors
    val iconRequest = rememberKestrelImageRequest(plugin.iconUrl, emptyMap(), 120, 120)
    val meta = listOfNotNull(
        plugin.authors.takeIf { it.isNotEmpty() }?.joinToString(", "),
        "v${plugin.version}",
        plugin.tvTypes.takeIf { it.isNotEmpty() }?.joinToString(", ")
    ).joinToString(" \u00B7 ")

    KestrelCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(Color.White.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                if (iconRequest != null) {
                    AsyncImage(
                        model = iconRequest,
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = plugin.displayName,
                        style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    SourceBadge(label = plugin.language)
                }
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (plugin.description.isNotBlank()) {
                    Text(
                        text = plugin.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted,
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
        }

        Spacer(modifier = Modifier.height(12.dp))

        val switchColors = SwitchDefaults.colors(
            checkedTrackColor = colors.accentAmber,
            checkedThumbColor = Color(0xFF1A1300),
            uncheckedTrackColor = Color.White.copy(alpha = 0.12f),
            uncheckedThumbColor = colors.textSecondary,
            uncheckedBorderColor = Color.White.copy(alpha = 0.25f)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (plugin.installState) {
                PluginInstallState.NOT_INSTALLED,
                PluginInstallState.ERROR -> {
                    GlassPillButton(
                        text = "Install",
                        icon = Icons.Outlined.Download,
                        onClick = onInstallOrUpdate,
                        busy = isBusy
                    )
                }

                PluginInstallState.INSTALLING -> {
                    GlassPillButton(
                        text = "Installing\u2026",
                        icon = null,
                        onClick = {},
                        busy = true
                    )
                }

                PluginInstallState.UPDATE_AVAILABLE -> {
                    GlassPillButton(
                        text = "Update",
                        icon = Icons.Outlined.SystemUpdateAlt,
                        onClick = onInstallOrUpdate,
                        busy = isBusy
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(
                        checked = plugin.isEnabled,
                        onCheckedChange = onToggleEnabled,
                        colors = switchColors
                    )
                }

                PluginInstallState.INSTALLED -> {
                    GlassPillButton(
                        text = "Uninstall",
                        icon = Icons.Outlined.DeleteOutline,
                        onClick = onUninstall,
                        accent = colors.statusError
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(
                        checked = plugin.isEnabled,
                        onCheckedChange = onToggleEnabled,
                        colors = switchColors
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

// ---------------------------------------------------------------------------------------------
// Add repository dialog
// ---------------------------------------------------------------------------------------------

@Composable
private fun AddRepoDialog(
    value: String,
    isAdding: Boolean,
    errorMessage: String?,
    onValueChange: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = KestrelTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF2A2150),
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        shape = RoundedCornerShape(28.dp),
        title = {
            Text(
                text = "Add repository",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Supports GitHub/Codeberg URLs, cloudstreamrepo://, shortcodes, Nuvio, and Stremio manifests.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    placeholder = { Text("https://... or cloudstreamrepo://...") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accentAmber,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                        focusedContainerColor = Color.White.copy(alpha = 0.06f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        cursorColor = colors.accentAmber
                    )
                )
                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.statusError
                    )
                }
            }
        },
        confirmButton = {
            KestrelPrimaryButton(
                text = if (isAdding) "..." else "Add",
                enabled = !isAdding && value.isNotBlank(),
                onClick = onAdd
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel", color = colors.textSecondary)
            }
        }
    )
}
