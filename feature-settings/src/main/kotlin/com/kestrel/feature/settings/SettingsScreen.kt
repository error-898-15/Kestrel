// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.settings

import android.annotation.SuppressLint
import android.os.Message
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.SwipeVertical
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.kestrel.core.designsystem.kestrelNavClearance
import com.kestrel.domain.model.PlaybackStartMode
import com.kestrel.domain.model.PlayerOrientationMode
import com.kestrel.domain.model.PluginInstallState
import com.kestrel.domain.model.PluginMetadata
import com.kestrel.domain.model.ProviderSettingField
import com.kestrel.domain.model.ProviderSettingType
import com.kestrel.domain.model.ProviderSettingsUiTarget
import com.kestrel.domain.model.RepoType
import com.kestrel.domain.model.ServerSearchSettings
import java.net.URLDecoder
import java.util.Locale
import kotlin.math.roundToInt

enum class SettingsDestination {
    HUB,
    ADDONS,
    PLUGINS,
    SERVERS,
    PLAYER
}

private data class SettingsSearchEntry(
    val title: String,
    val breadcrumb: String,
    val keywords: String,
    val icon: ImageVector,
    val destination: SettingsDestination
)

private val OledBlack = Color(0xFF000000)
private val CardSurface = Color(0xFF141315)
private val InputSurface = Color(0xFF1B1618)
private val DividerLine = Color(0xFF232024)
private val CrimsonAccent = Color(0xFFE53935)
private val CrimsonIconBg = Color(0xFF2B1416)
private val CrimsonMutedButton = Color(0xFF5C2325)
private val PillSurface = Color(0xFF221C1E)
private val AddonBlue = Color(0xFF60A5FA)
private val PluginGreen = Color(0xFF4ADE80)
private val InkPrimary = Color(0xFFF5F5F7)
private val InkSecondary = Color(0xFF9C9CA4)
private val InkMuted = Color(0xFF6C6C74)

@Composable
fun SettingsScreen(
    initialDestination: SettingsDestination? = null,
    navigationNonce: Int = 0,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val backStack = remember { mutableStateListOf(SettingsDestination.HUB) }
    var activePickerFamily by remember { mutableStateOf<RepoType?>(null) }

    LaunchedEffect(navigationNonce, initialDestination) {
        if (navigationNonce > 0 && initialDestination != null) {
            backStack.clear()
            backStack.add(SettingsDestination.HUB)
            if (initialDestination != SettingsDestination.HUB) {
                backStack.add(initialDestination)
            }
        }
    }

    val currentPage = backStack.lastOrNull() ?: SettingsDestination.HUB
    val navigateTo: (SettingsDestination) -> Unit = { dest ->
        if (backStack.lastOrNull() != dest) {
            backStack.add(dest)
        }
    }
    val navigateBack: () -> Unit = {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    BackHandler(enabled = backStack.size > 1) {
        navigateBack()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OledBlack)
    ) {
        AnimatedContent(
            targetState = currentPage,
            transitionSpec = {
                if (targetState == SettingsDestination.HUB) {
                    (slideInHorizontally { -it / 5 } + fadeIn()) togetherWith
                        (slideOutHorizontally { it / 5 } + fadeOut())
                } else {
                    (slideInHorizontally { it / 5 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 5 } + fadeOut())
                }
            },
            label = "settingsPageTransition"
        ) { page ->
            when (page) {
                SettingsDestination.HUB -> {
                    SettingsHubPage(
                        state = state,
                        onSearchChanged = viewModel::onSettingsSearchChanged,
                        onNavigate = navigateTo,
                        onResetDisclaimer = viewModel::resetDisclaimer,
                        onDismissBanner = viewModel::dismissBanner
                    )
                }

                SettingsDestination.PLAYER -> {
                    PlayerSettingsPage(
                        state = state,
                        onBack = navigateBack,
                        onSelectOrientationMode = viewModel::setPlayerOrientationMode,
                        onSelectPlaybackSpeed = viewModel::setDefaultPlaybackSpeed
                    )
                }

                SettingsDestination.ADDONS -> {
                    AddonsPage(
                        state = state,
                        onBack = navigateBack,
                        onInputChanged = viewModel::onAddonInputChanged,
                        onInstallAddon = viewModel::installAddonUrl,
                        onRefreshAddon = { repoId -> viewModel.refreshRepository(repoId) },
                        onOpenSettings = viewModel::openPluginSettings,
                        onToggleEnabled = viewModel::togglePluginEnabled,
                        onDeleteAddon = { repoId -> viewModel.removeRepository(repoId) },
                        onDismissBanner = viewModel::dismissBanner
                    )
                }

                SettingsDestination.PLUGINS -> {
                    PluginsPage(
                        state = state,
                        onBack = navigateBack,
                        onRepoInputChanged = viewModel::onPluginRepoInputChanged,
                        onAddRepo = viewModel::addPluginRepository,
                        onRefreshRepo = viewModel::refreshRepository,
                        onInstallAllRepo = viewModel::installAllInRepo,
                        onDeleteRepo = viewModel::removeRepository,
                        onSearchChanged = viewModel::onPluginSearchChanged,
                        onSelectRepoFilter = viewModel::selectPluginRepoFilter,
                        onSelectFilterMode = viewModel::setPluginFilterMode,
                        onInstallOrUpdatePlugin = viewModel::installOrUpdatePlugin,
                        onUninstallPlugin = viewModel::uninstallPlugin,
                        onToggleEnabled = viewModel::togglePluginEnabled,
                        onOpenSettings = viewModel::openPluginSettings,
                        onDismissBanner = viewModel::dismissBanner
                    )
                }

                SettingsDestination.SERVERS -> {
                    PlaybackAndServersPage(
                        state = state,
                        onBack = navigateBack,
                        onToggleSearchAll = viewModel::setSearchAllInstalled,
                        onToggleSearchEvery = viewModel::setSearchEvery,
                        onOpenFamilyPicker = { family -> activePickerFamily = family },
                        onToggleSameTime = viewModel::setSameSearchTimeForAll,
                        onChangeGlobalTime = viewModel::setGlobalSearchTimeoutSec,
                        onChangeExtensionTime = viewModel::setExtensionTimeoutSec,
                        onSelectStartMode = viewModel::setPlaybackStartMode,
                        onToggleAlwaysShowList = viewModel::setAlwaysShowServerList,
                        onToggleAskWhereToPlay = viewModel::setAskWhereToPlay,
                        onSelectPlaybackSpeed = viewModel::setDefaultPlaybackSpeed
                    )
                }
            }
        }
    }

    val pickerFamily = activePickerFamily
    if (pickerFamily != null) {
        val (extensions, selectedIds) = when (pickerFamily) {
            RepoType.NUVIO -> state.nuvioExtensions to state.settings.selectedNuvioIds
            RepoType.STREMIO -> state.stremioExtensions to state.settings.selectedStremioIds
            RepoType.CLOUDSTREAM -> state.cloudStreamExtensions to state.settings.selectedCloudStreamIds
            RepoType.VEGA -> state.vegaExtensions to state.settings.selectedVegaIds
            RepoType.ZANGETSU -> state.zangetsuExtensions to state.settings.selectedZangetsuIds
            RepoType.SKYSTREAM -> state.skyStreamExtensions to state.settings.selectedSkyStreamIds
            RepoType.HIKARI -> state.hikariExtensions to state.settings.selectedHikariIds
        }
        ServerExtensionPickerDialog(
            family = pickerFamily,
            extensions = extensions,
            selectedIds = selectedIds,
            onToggle = { id -> viewModel.toggleExtensionSelected(pickerFamily, id) },
            onSelectAll = { viewModel.selectAllForFamily(pickerFamily) },
            onClearAll = { viewModel.clearAllForFamily(pickerFamily) },
            onDismiss = { activePickerFamily = null }
        )
    }

    when (val target = state.actions.activeSettingsTarget) {
        is ProviderSettingsUiTarget.NuvioConfig -> {
            NuvioSettingsDialog(
                target = target,
                isSaving = state.actions.isSavingSettings,
                errorMessage = state.actions.settingsError,
                onDismiss = viewModel::dismissSettingsDialog,
                onSave = { values, headers ->
                    viewModel.saveNuvioSettings(target.pluginId, values, headers)
                }
            )
        }

        is ProviderSettingsUiTarget.StremioConfig -> {
            StremioSettingsDialog(
                target = target,
                isSaving = state.actions.isSavingSettings,
                errorMessage = state.actions.settingsError,
                onDismiss = viewModel::dismissSettingsDialog,
                onSave = { manifestUrl, configValues, headers ->
                    viewModel.saveStremioSettings(target.pluginId, manifestUrl, configValues, headers)
                }
            )
        }

        else -> Unit
    }
}

@Composable
private fun SettingsHubPage(
    state: SettingsUiState,
    onSearchChanged: (String) -> Unit,
    onNavigate: (SettingsDestination) -> Unit,
    onResetDisclaimer: () -> Unit,
    onDismissBanner: () -> Unit
) {
    val navClearance = kestrelNavClearance()
    val query = state.actions.settingsSearchQuery
    val searchableItems = remember {
        listOf(
            SettingsSearchEntry(
                title = "Addons",
                breadcrumb = "Sources • Stremio addons",
                keywords = "stremio addons manifest install catalog",
                icon = Icons.Outlined.Extension,
                destination = SettingsDestination.ADDONS
            ),
            SettingsSearchEntry(
                title = "Plugins",
                breadcrumb = "Sources • Nuvio & CloudStream repositories",
                keywords = "plugins nuvio cloudstream cs3 vega zangetsu skystream hikari scraper repositories providers install",
                icon = Icons.Outlined.Extension,
                destination = SettingsDestination.PLUGINS
            ),
            SettingsSearchEntry(
                title = "Servers",
                breadcrumb = "Sources • Multi-server search, timeouts, stream start",
                keywords = "servers stream selection multi server search auto play",
                icon = Icons.Outlined.Dns,
                destination = SettingsDestination.SERVERS
            ),
            SettingsSearchEntry(
                title = "Allowed Plugins & Addons",
                breadcrumb = "Servers • Multi-server search",
                keywords = "allowed plugins addons nuvio stremio cloudstream vega zangetsu skystream hikari server search",
                icon = Icons.Outlined.Dns,
                destination = SettingsDestination.SERVERS
            ),
            SettingsSearchEntry(
                title = "Server Search Time & Timeout",
                breadcrumb = "Servers • Timeouts",
                keywords = "timeout time wait seconds slow extensions",
                icon = Icons.Outlined.Timer,
                destination = SettingsDestination.SERVERS
            ),
            SettingsSearchEntry(
                title = "Player",
                breadcrumb = "Player • Landscape orientation, speed & gestures",
                keywords = "player landscape portrait auto orientation drawer speed gestures",
                icon = Icons.Outlined.PlayCircle,
                destination = SettingsDestination.PLAYER
            ),
            SettingsSearchEntry(
                title = "Player Orientation",
                breadcrumb = "Player • Landscape vs Adaptive (Auto)",
                keywords = "landscape orientation rotate full screen portrait drawer",
                icon = Icons.Outlined.ScreenRotation,
                destination = SettingsDestination.PLAYER
            ),
            SettingsSearchEntry(
                title = "Default Playback Speed",
                breadcrumb = "Player • Playback defaults",
                keywords = "speed 1x 1.5x 2x playback",
                icon = Icons.Outlined.Speed,
                destination = SettingsDestination.PLAYER
            )
        )
    }

    val filteredResults = remember(query, searchableItems) {
        val q = query.trim().lowercase(Locale.US)
        if (q.isEmpty()) emptyList()
        else searchableItems.filter {
            it.title.lowercase(Locale.US).contains(q) ||
                it.breadcrumb.lowercase(Locale.US).contains(q) ||
                it.keywords.contains(q)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp).let {
            PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = navClearance + 20.dp)
        },
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "hub_title") {
            Text(
                text = "Settings",
                fontSize = 34.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.6).sp,
                color = InkPrimary,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        item(key = "hub_search") {
            OledSearchField(
                value = query,
                onValueChange = onSearchChanged,
                placeholder = "Search settings...",
                onClear = { onSearchChanged("") }
            )
        }

        if (!state.actions.bannerMessage.isNullOrBlank()) {
            item(key = "hub_banner") {
                OledBanner(
                    message = state.actions.bannerMessage,
                    isError = state.actions.bannerIsError,
                    onDismiss = onDismissBanner
                )
            }
        }

        if (query.isNotBlank()) {
            item(key = "results_label") {
                SectionEyebrow("RESULTS")
            }
            item(key = "results_card") {
                if (filteredResults.isEmpty()) {
                    OledGroupCard {
                        Text(
                            text = "No matching settings for \"$query\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkSecondary,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    OledGroupCard {
                        filteredResults.forEachIndexed { idx, entry ->
                            SettingsMenuRow(
                                icon = entry.icon,
                                title = entry.title,
                                subtitle = entry.breadcrumb,
                                onClick = { onNavigate(entry.destination) }
                            )
                            if (idx < filteredResults.lastIndex) {
                                HorizontalDivider(
                                    color = DividerLine,
                                    modifier = Modifier.padding(start = 68.dp, end = 16.dp)
                                )
                            }
                        }
                    }
                }
            }
        } else {

            item(key = "group_sources_shortcuts") {
                OledGroupCard {
                    val addonCount = state.stremioAddons.size
                    val addonActive = state.stremioAddons.count { it.isEnabled }
                    SettingsMenuRow(
                        icon = Icons.Outlined.Extension,
                        title = "Addons",
                        subtitle = if (addonCount == 0) {
                            "Install and manage Stremio addons"
                        } else {
                            "$addonCount installed • $addonActive active"
                        },
                        onClick = { onNavigate(SettingsDestination.ADDONS) }
                    )
                    HorizontalDivider(
                        color = DividerLine,
                        modifier = Modifier.padding(start = 68.dp, end = 16.dp)
                    )
                    val repoCount = state.pluginRepos.size
                    val pluginCount = state.allNonStremioPlugins.size
                    SettingsMenuRow(
                        icon = Icons.Outlined.Extension,
                        title = "Plugins",
                        subtitle = if (repoCount == 0) {
                            "Install Nuvio & CloudStream repositories"
                        } else {
                            "$repoCount repositories • $pluginCount providers"
                        },
                        onClick = { onNavigate(SettingsDestination.PLUGINS) }
                    )
                    HorizontalDivider(
                        color = DividerLine,
                        modifier = Modifier.padding(start = 68.dp, end = 16.dp)
                    )
                    val s = state.settings
                    val modeSummary = if (s.searchAllInstalled) {
                        "Searching all ${state.totalActiveExtensions} active extensions"
                    } else {
                        val fams = buildList {
                            if (s.searchEveryNuvio || s.selectedNuvioIds.isNotEmpty()) add("Nuvio")
                            if (s.searchEveryStremio || s.selectedStremioIds.isNotEmpty()) add("Stremio")
                            if (s.searchEveryCloudStream || s.selectedCloudStreamIds.isNotEmpty()) add("CloudStream")
                        }.ifEmpty { listOf("Origin only") }
                        "Multi-server search: ${fams.joinToString(", ")}"
                    }
                    SettingsMenuRow(
                        icon = Icons.Outlined.Dns,
                        title = "Servers",
                        subtitle = modeSummary,
                        onClick = { onNavigate(SettingsDestination.SERVERS) }
                    )
                }
            }

            item(key = "group_player") {
                OledGroupCard {
                    val orientLabel = if (state.playerOrientationMode == PlayerOrientationMode.LANDSCAPE) {
                        "Landscape"
                    } else {
                        "Adaptive (Auto)"
                    }
                    val speedLabel = if (state.defaultPlaybackSpeed == 1.0f) {
                        "1x"
                    } else {
                        "${state.defaultPlaybackSpeed}x"
                    }
                    SettingsMenuRow(
                        icon = Icons.Outlined.PlayCircle,
                        title = "Player",
                        subtitle = "$orientLabel • $speedLabel default speed • Orientation & gestures",
                        onClick = { onNavigate(SettingsDestination.PLAYER) }
                    )
                }
            }

            item(key = "group_general_about") {
                OledGroupCard {
                    SettingsMenuRow(
                        icon = Icons.Outlined.Refresh,
                        title = "Startup Disclaimer",
                        subtitle = "Show the extension & source notice again on next launch.",
                        onClick = onResetDisclaimer
                    )
                    HorizontalDivider(
                        color = DividerLine,
                        modifier = Modifier.padding(start = 68.dp, end = 16.dp)
                    )
                    SettingsMenuRow(
                        icon = Icons.Outlined.Info,
                        title = "About Kestrel",
                        subtitle = "${state.totalActiveExtensions} active extensions • Unified media host",
                        showChevron = false,
                        onClick = {}
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerSettingsPage(
    state: SettingsUiState,
    onBack: () -> Unit,
    onSelectOrientationMode: (PlayerOrientationMode) -> Unit,
    onSelectPlaybackSpeed: (Float) -> Unit
) {
    val navClearance = kestrelNavClearance()
    val speedOptions = remember { listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = navClearance + 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "player_header") {
            OledSubPageHeader(
                title = "Player",
                onBack = onBack
            )
        }

        item(key = "player_orientation_label") {
            SectionEyebrow("ORIENTATION & LAYOUT")
        }

        item(key = "player_orientation_card") {
            OledGroupCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    OledRadioRow(
                        title = "Landscape (Cinema)",
                        subtitle = "Locks the player in landscape with a side-panel drawer for Sources, Quality, Audio, Subtitles & Speed",
                        selected = state.playerOrientationMode == PlayerOrientationMode.LANDSCAPE,
                        onClick = { onSelectOrientationMode(PlayerOrientationMode.LANDSCAPE) }
                    )
                    HorizontalDivider(color = DividerLine)
                    OledRadioRow(
                        title = "Current / Adaptive (Auto)",
                        subtitle = "Follows device rotation with portrait layout and bottom sheets",
                        selected = state.playerOrientationMode == PlayerOrientationMode.AUTO,
                        onClick = { onSelectOrientationMode(PlayerOrientationMode.AUTO) }
                    )
                }
            }
        }

        item(key = "player_speed_label") {
            SectionEyebrow("DEFAULT PLAYBACK SPEED")
        }

        item(key = "player_speed_card") {
            OledGroupCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Starting speed for new streams",
                        style = MaterialTheme.typography.titleMedium,
                        color = InkPrimary
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(speedOptions) { spd ->
                            val label = if (spd == 1.0f) "1x" else "${spd}x"
                            OledFilterChip(
                                text = label,
                                selected = state.defaultPlaybackSpeed == spd,
                                onClick = { onSelectPlaybackSpeed(spd) }
                            )
                        }
                    }
                }
            }
        }

        item(key = "player_gestures_label") {
            SectionEyebrow("CONTROLS & GESTURES")
        }

        item(key = "player_gestures_card") {
            OledGroupCard {
                SettingsMenuRow(
                    icon = Icons.Outlined.SwipeVertical,
                    title = "Edge Brightness & Volume",
                    subtitle = "Swipe vertically on the left side for brightness or right side for volume.",
                    showChevron = false,
                    onClick = {}
                )
                HorizontalDivider(
                    color = DividerLine,
                    modifier = Modifier.padding(start = 68.dp, end = 16.dp)
                )
                SettingsMenuRow(
                    icon = Icons.Outlined.Lock,
                    title = "Lock, Aspect Ratio & PiP",
                    subtitle = "Lock on-screen controls, switch Fit / Fill / Zoom, or enter Picture-in-Picture.",
                    showChevron = false,
                    onClick = {}
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddonsPage(
    state: SettingsUiState,
    onBack: () -> Unit,
    onInputChanged: (String) -> Unit,
    onInstallAddon: () -> Unit,
    onRefreshAddon: (String) -> Unit,
    onOpenSettings: (PluginMetadata) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onDeleteAddon: (String) -> Unit,
    onDismissBanner: () -> Unit
) {
    val navClearance = kestrelNavClearance()
    val addons = state.stremioAddons
    val activeCount = addons.count { it.isEnabled }
    val typeCount = addons.flatMap { it.tvTypes }.distinct().size
    val addonInput = state.actions.addonInput

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = navClearance + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "addons_header") {
            OledSubPageHeader(title = "Addons", onBack = onBack)
        }

        if (!state.actions.bannerMessage.isNullOrBlank()) {
            item(key = "addons_banner") {
                OledBanner(
                    message = state.actions.bannerMessage,
                    isError = state.actions.bannerIsError,
                    onDismiss = onDismissBanner
                )
            }
        }

        item(key = "addons_overview_label") {
            SectionEyebrow("Overview")
        }

        item(key = "addons_overview_card") {
            OledGroupCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OverviewStatColumn(
                        value = addons.size.toString(),
                        label = "Addons",
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(34.dp)
                            .background(DividerLine)
                    )
                    OverviewStatColumn(
                        value = activeCount.toString(),
                        label = "Active",
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(34.dp)
                            .background(DividerLine)
                    )
                    OverviewStatColumn(
                        value = typeCount.toString(),
                        label = "Types",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item(key = "add_addon_label") {
            SectionEyebrow("Add Addon")
        }

        item(key = "add_addon_card") {
            OledGroupCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OledUrlTextField(
                        value = addonInput,
                        onValueChange = onInputChanged,
                        placeholder = "Addon URL (https://.../manifest.json or stremio://)",
                        onSubmit = onInstallAddon
                    )
                    OledCrimsonButton(
                        text = "Install Addon",
                        enabled = addonInput.isNotBlank() && !state.actions.isAddingAddon,
                        busy = state.actions.isAddingAddon,
                        onClick = onInstallAddon
                    )
                }
            }
        }

        item(key = "installed_addons_label") {
            SectionEyebrow("Installed Addons")
        }

        if (addons.isEmpty()) {
            item(key = "addons_empty") {
                OledGroupCard {
                    Text(
                        text = "No Stremio addons installed yet. Paste an addon manifest URL above to add one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkSecondary,
                        modifier = Modifier.padding(18.dp)
                    )
                }
            }
        } else {
            items(addons, key = { it.id }) { addon ->
                val isRefreshing = addon.repoId in state.actions.refreshingRepoIds
                val isSettingsLoading = addon.id in state.actions.loadingSettingsPluginIds
                OledGroupCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            ExtensionPuzzleIcon(
                                iconUrl = addon.iconUrl,
                                fallbackTint = AddonBlue
                            )
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = addon.displayName,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = InkPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Version ${addon.version}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = InkSecondary
                                )
                            }
                            OledSwitch(
                                checked = addon.isEnabled,
                                onCheckedChange = { enabled -> onToggleEnabled(addon.id, enabled) }
                            )
                        }

                        // Action icons row matching screenshot
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { onRefreshAddon(addon.repoId) },
                                enabled = !isRefreshing
                            ) {
                                if (isRefreshing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = CrimsonAccent
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Outlined.Refresh,
                                        contentDescription = "Refresh addon",
                                        tint = CrimsonAccent
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onOpenSettings(addon) },
                                enabled = !isSettingsLoading
                            ) {
                                if (isSettingsLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = Color(0xFFFFB4B4)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Outlined.Settings,
                                        contentDescription = "Addon settings",
                                        tint = Color(0xFFFFB4B4)
                                    )
                                }
                            }
                            IconButton(onClick = { onDeleteAddon(addon.repoId) }) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = "Remove addon",
                                    tint = Color(0xFFFB7185)
                                )
                            }
                        }

                        HorizontalDivider(color = DividerLine)

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MetadataPill(if (addon.isEnabled) "Active" else "Disabled")
                            MetadataPill("v${addon.version}")
                            if (addon.id in state.configurablePluginIds || addon.repoType == RepoType.STREMIO) {
                                MetadataPill("Configurable")
                            }
                            addon.tvTypes.take(3).forEach { typeTag ->
                                MetadataPill(typeTag)
                            }
                        }

                        if (addon.description.isNotBlank()) {
                            Text(
                                text = addon.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = InkSecondary,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (addon.tvTypes.isNotEmpty()) {
                            Text(
                                text = addon.tvTypes.joinToString(" / "),
                                style = MaterialTheme.typography.bodySmall,
                                color = InkMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PluginsPage(
    state: SettingsUiState,
    onBack: () -> Unit,
    onRepoInputChanged: (String) -> Unit,
    onAddRepo: () -> Unit,
    onRefreshRepo: (String) -> Unit,
    onInstallAllRepo: (String) -> Unit,
    onDeleteRepo: (String) -> Unit,
    onSearchChanged: (String) -> Unit,
    onSelectRepoFilter: (String?) -> Unit,
    onSelectFilterMode: (PluginFilterMode) -> Unit,
    onInstallOrUpdatePlugin: (PluginMetadata) -> Unit,
    onUninstallPlugin: (String) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onOpenSettings: (PluginMetadata) -> Unit,
    onDismissBanner: () -> Unit
) {
    val navClearance = kestrelNavClearance()
    val repos = state.pluginRepos
    val allPlugins = state.allNonStremioPlugins
    val visiblePlugins = state.filteredNonStremioPlugins
    val enabledCount = allPlugins.count { it.isEnabled }
    val repoInput = state.actions.pluginRepoInput

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = navClearance + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "plugins_header") {
            OledSubPageHeader(title = "Plugins", onBack = onBack)
        }

        if (!state.actions.bannerMessage.isNullOrBlank()) {
            item(key = "plugins_banner") {
                OledBanner(
                    message = state.actions.bannerMessage,
                    isError = state.actions.bannerIsError,
                    onDismiss = onDismissBanner
                )
            }
        }

        item(key = "plugins_overview_label") {
            SectionEyebrow("OVERVIEW")
        }

        item(key = "plugins_overview_card") {
            OledGroupCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetadataPill("${repos.size} repos")
                        MetadataPill("${allPlugins.size} providers")
                        MetadataPill("$enabledCount enabled")
                    }
                    Text(
                        text = "Add CloudStream, Vega, Zangetsu, SkyStream, Hikari, or Nuvio repositories below, then install and enable individual providers for multi-server streaming.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkSecondary
                    )
                }
            }
        }

        item(key = "add_repo_label") {
            SectionEyebrow("ADD REPOSITORY")
        }

        item(key = "add_repo_card") {
            OledGroupCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OledUrlTextField(
                        value = repoInput,
                        onValueChange = onRepoInputChanged,
                        placeholder = "Repository URL (CloudStream / Vega / Zangetsu / SkyStream / Hikari / Nuvio)",
                        onSubmit = onAddRepo
                    )
                    OledCrimsonButton(
                        text = "Install Plugin Repository",
                        enabled = repoInput.isNotBlank() && !state.actions.isAddingPluginRepo,
                        busy = state.actions.isAddingPluginRepo,
                        onClick = onAddRepo
                    )
                }
            }
        }

        if (repos.isNotEmpty()) {
            item(key = "installed_repos_label") {
                SectionEyebrow("INSTALLED REPOSITORIES")
            }

            items(repos, key = { "repo_${it.id}" }) { repo ->
                val count = allPlugins.count { it.repoId == repo.id }
                val isRefreshing = repo.id in state.actions.refreshingRepoIds
                val isInstallingAll = repo.id in state.actions.installingRepoIds

                OledGroupCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = repo.name,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = InkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { onInstallAllRepo(repo.id) },
                                    enabled = !isInstallingAll
                                ) {
                                    if (isInstallingAll) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = CrimsonAccent
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Outlined.Download,
                                            contentDescription = "Install all plugins in repo",
                                            tint = InkSecondary
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { onRefreshRepo(repo.id) },
                                    enabled = !isRefreshing
                                ) {
                                    if (isRefreshing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = CrimsonAccent
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Outlined.Refresh,
                                            contentDescription = "Refresh repository",
                                            tint = CrimsonAccent
                                        )
                                    }
                                }
                                IconButton(onClick = { onDeleteRepo(repo.id) }) {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteOutline,
                                        contentDescription = "Remove repository",
                                        tint = Color(0xFFFB7185)
                                    )
                                }
                            }
                        }

                        Text(
                            text = "Version ${repo.manifestVersion}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkSecondary
                        )
                        Text(
                            text = repo.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = InkMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!repo.lastError.isNullOrBlank()) {
                            Text(
                                text = repo.lastError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = CrimsonAccent
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MetadataPill("$count providers")
                            MetadataPill(repoTypeLabel(repo.type))
                        }
                    }
                }
            }
        }

        item(key = "providers_label") {
            SectionEyebrow("PROVIDERS (${visiblePlugins.size})")
        }

        item(key = "providers_search_filters") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OledSearchField(
                    value = state.actions.pluginSearchQuery,
                    onValueChange = onSearchChanged,
                    placeholder = "Search plugins...",
                    onClear = { onSearchChanged("") }
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "mode_all") {
                        OledFilterChip(
                            text = "All",
                            selected = state.actions.pluginFilterMode == PluginFilterMode.ALL,
                            onClick = { onSelectFilterMode(PluginFilterMode.ALL) }
                        )
                    }
                    item(key = "mode_installed") {
                        OledFilterChip(
                            text = "Installed",
                            selected = state.actions.pluginFilterMode == PluginFilterMode.INSTALLED,
                            onClick = { onSelectFilterMode(PluginFilterMode.INSTALLED) }
                        )
                    }
                    item(key = "mode_updates") {
                        OledFilterChip(
                            text = "Updates",
                            selected = state.actions.pluginFilterMode == PluginFilterMode.UPDATES,
                            onClick = { onSelectFilterMode(PluginFilterMode.UPDATES) }
                        )
                    }
                    items(repos, key = { "filter_repo_${it.id}" }) { repo ->
                        OledFilterChip(
                            text = repo.name,
                            selected = state.actions.selectedPluginRepoId == repo.id,
                            onClick = { onSelectRepoFilter(repo.id) }
                        )
                    }
                }
            }
        }

        if (visiblePlugins.isEmpty()) {
            item(key = "plugins_empty") {
                OledGroupCard {
                    Text(
                        text = if (allPlugins.isEmpty()) {
                            "No plugins yet. Add a repository URL above to load plugins."
                        } else {
                            "No plugins match your current search or filter."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkSecondary,
                        modifier = Modifier.padding(18.dp)
                    )
                }
            }
        } else {
            items(visiblePlugins, key = { "plugin_${it.id}" }) { plugin ->
                val repoName = state.repoNameById[plugin.repoId] ?: plugin.repoType.name
                val isBusy = plugin.id in state.actions.busyPluginIds || plugin.installState == PluginInstallState.INSTALLING
                val isConfigurable = plugin.id in state.configurablePluginIds
                val isSettingsLoading = plugin.id in state.actions.loadingSettingsPluginIds
                val isInstalled = plugin.installState == PluginInstallState.INSTALLED ||
                    plugin.installState == PluginInstallState.UPDATE_AVAILABLE

                OledGroupCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            ExtensionPuzzleIcon(
                                iconUrl = plugin.iconUrl,
                                fallbackTint = PluginGreen,
                                modifier = Modifier.padding(top = 4.dp)
                            )

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = repoName,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = CrimsonAccent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = plugin.displayName.ifBlank { plugin.internalName },
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = InkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (plugin.description.isNotBlank()) {
                                    Text(
                                        text = plugin.description,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = InkSecondary,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            OledSwitch(
                                checked = plugin.isEnabled,
                                enabled = isInstalled && !isBusy,
                                onCheckedChange = { enabled -> onToggleEnabled(plugin.id, enabled) }
                            )
                        }

                        // Metadata chips & details (type, version, language, size, authors)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (plugin.tvTypes.isNotEmpty()) {
                                MetadataPill(plugin.tvTypes.joinToString(" | "))
                            }
                            MetadataPill("v${plugin.version}")
                            if (plugin.language.isNotBlank()) {
                                MetadataPill(plugin.language.uppercase(Locale.US))
                            }
                            MetadataPill(repoTypeLabel(plugin.repoType))
                            plugin.fileSizeBytes?.takeIf { it > 0L }?.let { bytes ->
                                MetadataPill(formatFileSize(bytes))
                            }
                        }

                        if (plugin.authors.isNotEmpty()) {
                            Text(
                                text = "By ${plugin.authors.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = InkMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (!plugin.lastError.isNullOrBlank()) {
                            Text(
                                text = plugin.lastError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = CrimsonAccent
                            )
                        }

                        // Install / Update / Uninstall + Settings action row (replacing "Test Provider")
                        when (plugin.installState) {
                            PluginInstallState.NOT_INSTALLED,
                            PluginInstallState.ERROR -> {
                                OledCrimsonButton(
                                    text = if (plugin.installState == PluginInstallState.ERROR) "Retry Install" else "Install",
                                    enabled = !isBusy,
                                    busy = isBusy,
                                    onClick = { onInstallOrUpdatePlugin(plugin) }
                                )
                            }

                            PluginInstallState.INSTALLING -> {
                                OledCrimsonButton(
                                    text = "Installing...",
                                    enabled = false,
                                    busy = true,
                                    onClick = {}
                                )
                            }

                            PluginInstallState.UPDATE_AVAILABLE -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OledCrimsonButton(
                                        text = "Update",
                                        enabled = !isBusy,
                                        busy = isBusy,
                                        onClick = { onInstallOrUpdatePlugin(plugin) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isConfigurable) {
                                        OledSquareIconAction(
                                            icon = Icons.Outlined.Settings,
                                            contentDescription = "Plugin settings",
                                            busy = isSettingsLoading,
                                            onClick = { onOpenSettings(plugin) }
                                        )
                                    }
                                    OledSquareIconAction(
                                        icon = Icons.Outlined.DeleteOutline,
                                        contentDescription = "Uninstall",
                                        tint = Color(0xFFFB7185),
                                        onClick = { onUninstallPlugin(plugin.id) }
                                    )
                                }
                            }

                            PluginInstallState.INSTALLED -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Button(
                                        onClick = { onUninstallPlugin(plugin.id) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp),
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = CrimsonIconBg,
                                            contentColor = CrimsonAccent
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.DeleteOutline,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Uninstall",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    if (isConfigurable) {
                                        OledSquareIconAction(
                                            icon = Icons.Outlined.Settings,
                                            contentDescription = "Plugin settings",
                                            busy = isSettingsLoading,
                                            onClick = { onOpenSettings(plugin) }
                                        )
                                    }
                                }
                            }

                            PluginInstallState.UNSUPPORTED_API -> {
                                Button(
                                    onClick = {},
                                    enabled = false,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        disabledContainerColor = PillSurface,
                                        disabledContentColor = InkMuted
                                    )
                                ) {
                                    Text("Unsupported API v${plugin.apiVersion}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackAndServersPage(
    state: SettingsUiState,
    onBack: () -> Unit,
    onToggleSearchAll: (Boolean) -> Unit,
    onToggleSearchEvery: (RepoType, Boolean) -> Unit,
    onOpenFamilyPicker: (RepoType) -> Unit,
    onToggleSameTime: (Boolean) -> Unit,
    onChangeGlobalTime: (Int) -> Unit,
    onChangeExtensionTime: (String, Int) -> Unit,
    onSelectStartMode: (PlaybackStartMode) -> Unit,
    onToggleAlwaysShowList: (Boolean) -> Unit,
    onToggleAskWhereToPlay: (Boolean) -> Unit,
    onSelectPlaybackSpeed: (Float) -> Unit
) {
    val navClearance = kestrelNavClearance()
    val s = state.settings
    val speedOptions = remember { listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f) }
    var showCloudStreamTimers by remember { mutableStateOf(false) }
    var showNuvioTimers by remember { mutableStateOf(false) }
    var showStremioTimers by remember { mutableStateOf(false) }
    var showVegaTimers by remember { mutableStateOf(false) }
    var showZangetsuTimers by remember { mutableStateOf(false) }
    var showSkyStreamTimers by remember { mutableStateOf(false) }
    var showHikariTimers by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = navClearance + 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "servers_header") {
            OledSubPageHeader(
                title = "Stream selection",
                onBack = onBack
            )
        }

        item(key = "server_search_label") {
            SectionEyebrow("STREAM AUTO-PLAY & SERVER SEARCH")
        }

        item(key = "server_search_card") {
            OledGroupCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OledSwitchRow(
                        title = "Search all installed extensions",
                        subtitle = if (s.searchAllInstalled) {
                            "Every enabled source across all installed ecosystems is queried"
                        } else {
                            "Search origin extension plus enabled families below"
                        },
                        checked = s.searchAllInstalled,
                        onCheckedChange = onToggleSearchAll
                    )

                    HorizontalDivider(color = DividerLine)

                    OledFamilyBlock(
                        toggleTitle = "Search every Nuvio provider",
                        toggleSubtitle = if (s.searchEveryNuvio) {
                            "All enabled Nuvio providers are searched"
                        } else {
                            "Only selected Nuvio plugins below"
                        },
                        pickerTitle = "Allowed Nuvio Plugins",
                        pickerSubtitle = if (s.searchEveryNuvio) {
                            "All enabled Nuvio providers (${state.nuvioExtensions.size})"
                        } else {
                            "${s.selectedNuvioIds.size} of ${state.nuvioExtensions.size} selected"
                        },
                        checked = s.searchEveryNuvio || s.searchAllInstalled,
                        switchEnabled = !s.searchAllInstalled,
                        onCheckedChange = { onToggleSearchEvery(RepoType.NUVIO, it) },
                        onOpenPicker = { onOpenFamilyPicker(RepoType.NUVIO) }
                    )

                    OledFamilyBlock(
                        toggleTitle = "Search every Stremio addon",
                        toggleSubtitle = if (s.searchEveryStremio) {
                            "All enabled Stremio addons are searched"
                        } else {
                            "Only selected Stremio addons below"
                        },
                        pickerTitle = "Allowed Addons",
                        pickerSubtitle = if (s.searchEveryStremio) {
                            "All installed addons (${state.stremioExtensions.size})"
                        } else {
                            "${s.selectedStremioIds.size} of ${state.stremioExtensions.size} selected"
                        },
                        checked = s.searchEveryStremio || s.searchAllInstalled,
                        switchEnabled = !s.searchAllInstalled,
                        onCheckedChange = { onToggleSearchEvery(RepoType.STREMIO, it) },
                        onOpenPicker = { onOpenFamilyPicker(RepoType.STREMIO) }
                    )

                    OledFamilyBlock(
                        toggleTitle = "Search every CloudStream",
                        toggleSubtitle = if (s.searchEveryCloudStream) {
                            "All enabled CloudStream plugins are searched"
                        } else {
                            "Plays from the CloudStream repo you opened it from"
                        },
                        pickerTitle = "Allowed CloudStream Plugins",
                        pickerSubtitle = if (s.searchEveryCloudStream) {
                            "All enabled CloudStream plugins (${state.cloudStreamExtensions.size})"
                        } else {
                            "${s.selectedCloudStreamIds.size} of ${state.cloudStreamExtensions.size} selected"
                        },
                        checked = s.searchEveryCloudStream || s.searchAllInstalled,
                        switchEnabled = !s.searchAllInstalled,
                        onCheckedChange = { onToggleSearchEvery(RepoType.CLOUDSTREAM, it) },
                        onOpenPicker = { onOpenFamilyPicker(RepoType.CLOUDSTREAM) }
                    )

                    OledFamilyBlock(
                        toggleTitle = "Search every Vega provider",
                        toggleSubtitle = if (s.searchEveryVega) {
                            "All enabled Vega providers are searched"
                        } else {
                            "Only selected Vega providers below"
                        },
                        pickerTitle = "Allowed Vega Providers",
                        pickerSubtitle = if (s.searchEveryVega) {
                            "All enabled Vega providers (${state.vegaExtensions.size})"
                        } else {
                            "${s.selectedVegaIds.size} of ${state.vegaExtensions.size} selected"
                        },
                        checked = s.searchEveryVega || s.searchAllInstalled,
                        switchEnabled = !s.searchAllInstalled,
                        onCheckedChange = { onToggleSearchEvery(RepoType.VEGA, it) },
                        onOpenPicker = { onOpenFamilyPicker(RepoType.VEGA) }
                    )

                    OledFamilyBlock(
                        toggleTitle = "Search every Zangetsu source",
                        toggleSubtitle = if (s.searchEveryZangetsu) {
                            "All enabled Zangetsu sources are searched"
                        } else {
                            "Only selected Zangetsu sources below"
                        },
                        pickerTitle = "Allowed Zangetsu Sources",
                        pickerSubtitle = if (s.searchEveryZangetsu) {
                            "All enabled Zangetsu sources (${state.zangetsuExtensions.size})"
                        } else {
                            "${s.selectedZangetsuIds.size} of ${state.zangetsuExtensions.size} selected"
                        },
                        checked = s.searchEveryZangetsu || s.searchAllInstalled,
                        switchEnabled = !s.searchAllInstalled,
                        onCheckedChange = { onToggleSearchEvery(RepoType.ZANGETSU, it) },
                        onOpenPicker = { onOpenFamilyPicker(RepoType.ZANGETSU) }
                    )

                    OledFamilyBlock(
                        toggleTitle = "Search every SkyStream plugin",
                        toggleSubtitle = if (s.searchEverySkyStream) {
                            "All enabled SkyStream plugins are searched"
                        } else {
                            "Only selected SkyStream plugins below"
                        },
                        pickerTitle = "Allowed SkyStream Plugins",
                        pickerSubtitle = if (s.searchEverySkyStream) {
                            "All enabled SkyStream plugins (${state.skyStreamExtensions.size})"
                        } else {
                            "${s.selectedSkyStreamIds.size} of ${state.skyStreamExtensions.size} selected"
                        },
                        checked = s.searchEverySkyStream || s.searchAllInstalled,
                        switchEnabled = !s.searchAllInstalled,
                        onCheckedChange = { onToggleSearchEvery(RepoType.SKYSTREAM, it) },
                        onOpenPicker = { onOpenFamilyPicker(RepoType.SKYSTREAM) }
                    )

                    OledFamilyBlock(
                        toggleTitle = "Search every Hikari extension",
                        toggleSubtitle = if (s.searchEveryHikari) {
                            "All enabled Hikari extensions are searched"
                        } else {
                            "Only selected Hikari extensions below"
                        },
                        pickerTitle = "Allowed Hikari Extensions",
                        pickerSubtitle = if (s.searchEveryHikari) {
                            "All enabled Hikari extensions (${state.hikariExtensions.size})"
                        } else {
                            "${s.selectedHikariIds.size} of ${state.hikariExtensions.size} selected"
                        },
                        checked = s.searchEveryHikari || s.searchAllInstalled,
                        switchEnabled = !s.searchAllInstalled,
                        onCheckedChange = { onToggleSearchEvery(RepoType.HIKARI, it) },
                        onOpenPicker = { onOpenFamilyPicker(RepoType.HIKARI) }
                    )
                }
            }
        }

        item(key = "timeout_label") {
            SectionEyebrow("STREAM SELECTION TIMEOUT")
        }

        item(key = "timeout_card") {
            OledGroupCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    OledSwitchRow(
                        title = "Same search time for every extension",
                        subtitle = if (s.sameSearchTimeForAll) {
                            "Wait up to ${s.globalSearchTimeoutSec}s across extensions"
                        } else {
                            "Customize timeout per extension below"
                        },
                        checked = s.sameSearchTimeForAll,
                        onCheckedChange = onToggleSameTime
                    )

                    AnimatedVisibility(visible = s.sameSearchTimeForAll) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Stream Selection Timeout",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = InkPrimary
                                )
                                Text(
                                    text = "${s.globalSearchTimeoutSec}s",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CrimsonAccent
                                )
                            }
                            Slider(
                                value = s.globalSearchTimeoutSec.toFloat(),
                                onValueChange = { onChangeGlobalTime(it.roundToInt()) },
                                valueRange = 5f..60f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = CrimsonAccent,
                                    inactiveTrackColor = Color(0xFF2B282C)
                                )
                            )
                        }
                    }

                    OledFamilyTimerExpandable(
                        familyTitle = "Nuvio",
                        extensions = state.nuvioExtensions,
                        settings = s,
                        expanded = showNuvioTimers,
                        onToggleExpand = { showNuvioTimers = !showNuvioTimers },
                        onChangeExtensionTime = onChangeExtensionTime
                    )

                    OledFamilyTimerExpandable(
                        familyTitle = "Stremio",
                        extensions = state.stremioExtensions,
                        settings = s,
                        expanded = showStremioTimers,
                        onToggleExpand = { showStremioTimers = !showStremioTimers },
                        onChangeExtensionTime = onChangeExtensionTime
                    )

                    OledFamilyTimerExpandable(
                        familyTitle = "CloudStream",
                        extensions = state.cloudStreamExtensions,
                        settings = s,
                        expanded = showCloudStreamTimers,
                        onToggleExpand = { showCloudStreamTimers = !showCloudStreamTimers },
                        onChangeExtensionTime = onChangeExtensionTime
                    )

                    OledFamilyTimerExpandable(
                        familyTitle = "Vega",
                        extensions = state.vegaExtensions,
                        settings = s,
                        expanded = showVegaTimers,
                        onToggleExpand = { showVegaTimers = !showVegaTimers },
                        onChangeExtensionTime = onChangeExtensionTime
                    )

                    OledFamilyTimerExpandable(
                        familyTitle = "Zangetsu",
                        extensions = state.zangetsuExtensions,
                        settings = s,
                        expanded = showZangetsuTimers,
                        onToggleExpand = { showZangetsuTimers = !showZangetsuTimers },
                        onChangeExtensionTime = onChangeExtensionTime
                    )

                    OledFamilyTimerExpandable(
                        familyTitle = "SkyStream",
                        extensions = state.skyStreamExtensions,
                        settings = s,
                        expanded = showSkyStreamTimers,
                        onToggleExpand = { showSkyStreamTimers = !showSkyStreamTimers },
                        onChangeExtensionTime = onChangeExtensionTime
                    )

                    OledFamilyTimerExpandable(
                        familyTitle = "Hikari",
                        extensions = state.hikariExtensions,
                        settings = s,
                        expanded = showHikariTimers,
                        onToggleExpand = { showHikariTimers = !showHikariTimers },
                        onChangeExtensionTime = onChangeExtensionTime
                    )
                }
            }
        }

        item(key = "playback_start_label") {
            SectionEyebrow("PLAYBACK START & SPEED")
        }

        item(key = "playback_start_card") {
            OledGroupCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    OledRadioRow(
                        title = "Play as soon as the first server is found",
                        subtitle = "Instant — falls back if a stream fails",
                        selected = s.playbackStartMode == PlaybackStartMode.FIRST_FOUND,
                        onClick = { onSelectStartMode(PlaybackStartMode.FIRST_FOUND) }
                    )

                    OledRadioRow(
                        title = "Wait for more servers first",
                        subtitle = "Or when every extension has finished",
                        selected = s.playbackStartMode == PlaybackStartMode.WAIT_FOR_MORE,
                        onClick = { onSelectStartMode(PlaybackStartMode.WAIT_FOR_MORE) }
                    )

                    HorizontalDivider(color = DividerLine)

                    OledSwitchRow(
                        title = "Always show the server list",
                        subtitle = if (s.alwaysShowServerList) {
                            "On — Play opens the server list"
                        } else {
                            "Off — Auto-plays straight away on first match"
                        },
                        checked = s.alwaysShowServerList,
                        onCheckedChange = onToggleAlwaysShowList
                    )

                    OledSwitchRow(
                        title = "Ask where to play",
                        subtitle = if (s.askWhereToPlay) {
                            "On — Ask before searching all servers vs origin only"
                        } else {
                            "Off — Play uses your server-search choices directly"
                        },
                        checked = s.askWhereToPlay,
                        onCheckedChange = onToggleAskWhereToPlay
                    )

                    HorizontalDivider(color = DividerLine)

                    Text(
                        text = "Default playback speed",
                        style = MaterialTheme.typography.titleMedium,
                        color = InkPrimary
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(speedOptions) { spd ->
                            val label = if (spd == 1.0f) "1x" else "${spd}x"
                            OledFilterChip(
                                text = label,
                                selected = state.defaultPlaybackSpeed == spd,
                                onClick = { onSelectPlaybackSpeed(spd) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OledSubPageHeader(
    title: String,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Back",
                tint = InkPrimary
            )
        }
        Text(
            text = title,
            fontSize = 28.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            color = InkPrimary
        )
    }
}

@Composable
private fun SectionEyebrow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = InkSecondary,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun OledGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardSurface)
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp)),
        content = content
    )
}

@Composable
private fun SettingsMenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    showChevron: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(CrimsonIconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = CrimsonAccent,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = InkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = InkSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (showChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = InkMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun TextOnlyMenuRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = InkPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = InkSecondary
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = InkMuted
        )
    }
}

@Composable
private fun OledSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onClear: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val borderColor = if (value.isNotEmpty()) CrimsonAccent.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.08f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(InputSurface)
            .border(1.dp, borderColor, shape)
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = InkPrimary),
            placeholder = {
                Text(
                    text = placeholder,
                    color = InkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = InkSecondary
                )
            },
            trailingIcon = {
                if (value.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Clear",
                            tint = InkSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = InkPrimary,
                unfocusedTextColor = InkPrimary,
                cursorColor = CrimsonAccent
            )
        )
    }
}

@Composable
private fun OledUrlTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSubmit: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = TextStyle(fontSize = 15.sp, color = InkPrimary),
        placeholder = {
            Text(
                text = placeholder,
                color = InkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Clear URL",
                        tint = InkSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = CrimsonAccent.copy(alpha = 0.7f),
            unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
            focusedContainerColor = InputSurface,
            unfocusedContainerColor = InputSurface,
            focusedTextColor = InkPrimary,
            unfocusedTextColor = InkPrimary,
            cursorColor = CrimsonAccent
        )
    )
}

@Composable
private fun OledCrimsonButton(
    text: String,
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = CrimsonAccent,
            contentColor = Color.White,
            disabledContainerColor = CrimsonMutedButton,
            disabledContentColor = Color.White.copy(alpha = 0.55f)
        )
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = Color.White
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun OledSquareIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = InkPrimary,
    busy: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(PillSurface)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .clickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = CrimsonAccent
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ExtensionPuzzleIcon(
    iconUrl: String?,
    fallbackTint: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1F191C)),
        contentAlignment = Alignment.Center
    ) {
        if (!iconUrl.isNullOrBlank()) {
            AsyncImage(
                model = iconUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Extension,
                contentDescription = null,
                tint = fallbackTint,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun OverviewStatColumn(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = InkPrimary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = InkSecondary
        )
    }
}

@Composable
private fun repoTypeLabel(type: RepoType): String = when (type) {
    RepoType.CLOUDSTREAM -> "CloudStream"
    RepoType.NUVIO -> "Nuvio"
    RepoType.STREMIO -> "Stremio"
    RepoType.VEGA -> "Vega"
    RepoType.ZANGETSU -> "Zangetsu"
    RepoType.SKYSTREAM -> "SkyStream"
    RepoType.HIKARI -> "Hikari"
}

private fun MetadataPill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(PillSurface)
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = InkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun OledFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) CrimsonIconBg else PillSurface)
            .border(
                width = 1.dp,
                color = if (selected) CrimsonAccent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(50)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) CrimsonAccent else InkSecondary,
            maxLines = 1
        )
    }
}

@Composable
private fun OledBanner(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit
) {
    val accent = if (isError) CrimsonAccent else PluginGreen
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isError) CrimsonIconBg else Color(0xFF132219))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = InkPrimary,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = "Dismiss",
            tint = InkSecondary,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun OledSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = CrimsonAccent,
            checkedThumbColor = Color.White,
            uncheckedTrackColor = Color(0xFF28262A),
            uncheckedThumbColor = Color(0xFF9C9CA4),
            uncheckedBorderColor = Color.White.copy(alpha = 0.14f),
            disabledCheckedTrackColor = CrimsonMutedButton,
            disabledUncheckedTrackColor = Color(0xFF1D1B1F)
        )
    )
}

@Composable
private fun OledSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) InkPrimary else InkMuted
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) InkSecondary else InkMuted
            )
        }
        OledSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun OledFamilyBlock(
    toggleTitle: String,
    toggleSubtitle: String,
    pickerTitle: String,
    pickerSubtitle: String,
    checked: Boolean,
    switchEnabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onOpenPicker: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OledSwitchRow(
            title = toggleTitle,
            subtitle = toggleSubtitle,
            checked = checked,
            enabled = switchEnabled,
            onCheckedChange = onCheckedChange
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(InputSurface)
                .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
                .clickable(onClick = onOpenPicker)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = pickerTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = InkPrimary
                )
                Text(
                    text = pickerSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = InkMuted
            )
        }
    }
}

@Composable
private fun OledFamilyTimerExpandable(
    familyTitle: String,
    extensions: List<InstalledServerExtension>,
    settings: ServerSearchSettings,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onChangeExtensionTime: (String, Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onToggleExpand)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = familyTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = InkPrimary
                )
                Text(
                    text = "${extensions.size} active extensions",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = if (expanded) "Hide" else "Show",
                    style = MaterialTheme.typography.labelLarge,
                    color = CrimsonAccent
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = CrimsonAccent,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            if (extensions.isEmpty()) {
                Text(
                    text = "No active $familyTitle extensions installed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkMuted,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    extensions.forEach { ext ->
                        val extSec = if (settings.sameSearchTimeForAll) {
                            settings.globalSearchTimeoutSec
                        } else {
                            settings.perExtensionTimeoutsSec[ext.id] ?: settings.globalSearchTimeoutSec
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(InputSurface)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = ext.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = InkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${extSec}s",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = CrimsonAccent
                                )
                            }
                            Slider(
                                value = extSec.toFloat(),
                                enabled = !settings.sameSearchTimeForAll,
                                onValueChange = { onChangeExtensionTime(ext.id, it.roundToInt()) },
                                valueRange = 5f..60f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = CrimsonAccent,
                                    inactiveTrackColor = Color(0xFF2B282C)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OledRadioRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = CrimsonAccent,
                unselectedColor = InkSecondary
            )
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = InkPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = InkSecondary
            )
        }
    }
}

@Composable
private fun ServerExtensionPickerDialog(
    family: RepoType,
    extensions: List<InstalledServerExtension>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val familyLabel = repoTypeLabel(family)
    val filtered = remember(extensions, query) {
        val q = query.trim().lowercase(Locale.US)
        if (q.isEmpty()) extensions
        else extensions.filter { it.name.lowercase(Locale.US).contains(q) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(CardSurface)
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                .padding(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "$familyLabel servers",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = InkPrimary
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close",
                            tint = InkPrimary
                        )
                    }
                }

                Text(
                    text = "Only selected extensions are queried when the family toggle is off.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSecondary
                )

                OledSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search $familyLabel extensions",
                    onClear = { query = "" }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OledFilterChip(
                        text = "Select all",
                        selected = extensions.isNotEmpty() && selectedIds.size == extensions.size,
                        onClick = onSelectAll
                    )
                    OledFilterChip(
                        text = "Clear",
                        selected = selectedIds.isEmpty(),
                        onClick = onClearAll
                    )
                }

                if (filtered.isEmpty()) {
                    Text(
                        text = if (extensions.isEmpty()) {
                            "No enabled $familyLabel extensions installed yet."
                        } else {
                            "No $familyLabel extensions match \"$query\"."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkSecondary,
                        modifier = Modifier.padding(vertical = 20.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filtered, key = { it.id }) { ext ->
                            val isSelected = ext.id in selectedIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (isSelected) CrimsonIconBg else InputSurface)
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) CrimsonAccent.copy(alpha = 0.6f)
                                        else Color.White.copy(alpha = 0.08f),
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                    .clickable { onToggle(ext.id) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { onToggle(ext.id) },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = CrimsonAccent,
                                        uncheckedColor = InkSecondary,
                                        checkmarkColor = Color.White
                                    )
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = ext.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (isSelected) Color.White else InkPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = familyLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = InkSecondary
                                    )
                                }
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(CrimsonAccent),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Check,
                                            contentDescription = "Selected",
                                            tint = Color.White,
                                            modifier = Modifier.size(15.dp)
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

@Composable
private fun NuvioSettingsDialog(
    target: ProviderSettingsUiTarget.NuvioConfig,
    isSaving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (values: Map<String, String>, customHeaders: Map<String, String>) -> Unit
) {
    val fieldValues = remember(target) {
        mutableStateMapOf<String, String>().apply {
            target.fields.forEach { field ->
                if (field.type != ProviderSettingType.HEADER) {
                    put(field.key, field.currentValue)
                }
            }
        }
    }
    val headerRows = remember(target) {
        mutableStateListOf<Pair<String, String>>().apply {
            target.customHeaders.forEach { (k, v) -> add(k to v) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        titleContentColor = InkPrimary,
        textContentColor = InkSecondary,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = "${target.displayName} settings",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (target.fields.isEmpty()) {
                    Text(
                        text = "This Nuvio provider does not define custom fields. You can configure HTTP headers (Cookie, User-Agent, Authorization) below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkSecondary
                    )
                } else {
                    SettingsFieldsSection(fields = target.fields, values = fieldValues)
                }

                CustomHeadersSection(headers = headerRows)

                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CrimsonAccent
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isSaving,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CrimsonAccent, contentColor = Color.White),
                onClick = {
                    val headerMap = headerRows
                        .mapNotNull { (k, v) ->
                            val key = k.trim()
                            val value = v.trim()
                            if (key.isNotEmpty() && value.isNotEmpty()) key to value else null
                        }
                        .toMap()
                    onSave(fieldValues.toMap(), headerMap)
                }
            ) {
                Text(if (isSaving) "Saving…" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel", color = InkSecondary)
            }
        }
    )
}

@Composable
private fun StremioSettingsDialog(
    target: ProviderSettingsUiTarget.StremioConfig,
    isSaving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (manifestUrl: String, configValues: Map<String, String>, customHeaders: Map<String, String>) -> Unit
) {
    var manifestUrl by remember(target) { mutableStateOf(target.manifestUrl) }
    var showWebConfigure by remember(target) { mutableStateOf(false) }
    var capturedFromWeb by remember(target) { mutableStateOf(false) }
    val fieldValues = remember(target) {
        mutableStateMapOf<String, String>().apply {
            target.fields.forEach { field ->
                if (field.type != ProviderSettingType.HEADER) {
                    put(field.key, field.currentValue)
                }
            }
        }
    }
    val headerRows = remember(target) {
        mutableStateListOf<Pair<String, String>>().apply {
            target.customHeaders.forEach { (k, v) -> add(k to v) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        titleContentColor = InkPrimary,
        textContentColor = InkSecondary,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = "${target.displayName} settings",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!target.configureWebUrl.isNullOrBlank()) {
                    Button(
                        onClick = { showWebConfigure = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CrimsonIconBg, contentColor = CrimsonAccent)
                    ) {
                        Icon(Icons.Outlined.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open Web Configurator")
                    }
                }

                Text(
                    text = "Manifest URL",
                    style = MaterialTheme.typography.labelLarge,
                    color = InkPrimary
                )
                OutlinedTextField(
                    value = manifestUrl,
                    onValueChange = {
                        manifestUrl = it
                        capturedFromWeb = false
                    },
                    singleLine = true,
                    placeholder = { Text("https://.../manifest.json") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = oledDialogFieldColors()
                )

                if (capturedFromWeb) {
                    Text(
                        text = "Configured manifest URL captured from web configurator. Tap Save to apply.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PluginGreen
                    )
                }

                if (target.fields.isNotEmpty()) {
                    SettingsFieldsSection(fields = target.fields, values = fieldValues)
                }

                CustomHeadersSection(headers = headerRows)

                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CrimsonAccent
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isSaving && manifestUrl.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CrimsonAccent, contentColor = Color.White),
                onClick = {
                    val headerMap = headerRows
                        .mapNotNull { (k, v) ->
                            val key = k.trim()
                            val value = v.trim()
                            if (key.isNotEmpty() && value.isNotEmpty()) key to value else null
                        }
                        .toMap()
                    onSave(manifestUrl.trim(), fieldValues.toMap(), headerMap)
                }
            ) {
                Text(if (isSaving) "Saving…" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel", color = InkSecondary)
            }
        }
    )

    val webUrl = target.configureWebUrl
    if (showWebConfigure && !webUrl.isNullOrBlank()) {
        StremioWebConfigureDialog(
            title = "${target.displayName} Configure",
            configureUrl = webUrl,
            onManifestCaptured = { capturedUrl ->
                manifestUrl = capturedUrl
                capturedFromWeb = true
                showWebConfigure = false
            },
            onDismiss = { showWebConfigure = false }
        )
    }
}

@Composable
private fun SettingsFieldsSection(
    fields: List<ProviderSettingField>,
    values: MutableMap<String, String>
) {
    fields.forEach { field ->
        when (field.type) {
            ProviderSettingType.HEADER -> {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = field.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = CrimsonAccent
                    )
                    if (field.description.isNotBlank()) {
                        Text(
                            text = field.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = InkMuted
                        )
                    }
                }
            }

            ProviderSettingType.TOGGLE -> {
                val checked = (values[field.key] ?: field.defaultValue).equals("true", ignoreCase = true)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = field.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkPrimary
                        )
                        if (field.description.isNotBlank()) {
                            Text(
                                text = field.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = InkMuted
                            )
                        }
                    }
                    OledSwitch(
                        checked = checked,
                        onCheckedChange = { values[field.key] = if (it) "true" else "false" }
                    )
                }
            }

            ProviderSettingType.SELECT -> {
                var expanded by remember(field.key) { mutableStateOf(false) }
                val currentVal = values[field.key] ?: field.defaultValue
                val currentLabel = field.options.firstOrNull { it.value == currentVal }?.label
                    ?: currentVal.ifBlank { "Select…" }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = field.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = InkPrimary
                    )
                    Box {
                        OutlinedTextField(
                            value = currentLabel,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.ArrowDropDown,
                                    contentDescription = null,
                                    tint = InkSecondary
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expanded = true },
                            enabled = false,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                disabledTextColor = InkPrimary,
                                disabledBorderColor = Color.White.copy(alpha = 0.14f),
                                disabledContainerColor = InputSurface
                            )
                        )
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            field.options.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt.label) },
                                    onClick = {
                                        values[field.key] = opt.value
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            ProviderSettingType.PASSWORD -> {
                var reveal by remember(field.key) { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = field.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = InkPrimary
                    )
                    OutlinedTextField(
                        value = values[field.key].orEmpty(),
                        onValueChange = { values[field.key] = it },
                        singleLine = true,
                        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { reveal = !reveal }) {
                                Icon(
                                    imageVector = if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = null,
                                    tint = InkSecondary
                                )
                            }
                        },
                        placeholder = if (field.description.isNotBlank()) {
                            { Text(field.description, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = oledDialogFieldColors()
                    )
                }
            }

            ProviderSettingType.NUMBER,
            ProviderSettingType.TEXT -> {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = field.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = InkPrimary
                    )
                    OutlinedTextField(
                        value = values[field.key].orEmpty(),
                        onValueChange = { values[field.key] = it },
                        singleLine = true,
                        keyboardOptions = if (field.type == ProviderSettingType.NUMBER) {
                            KeyboardOptions(keyboardType = KeyboardType.Number)
                        } else {
                            KeyboardOptions.Default
                        },
                        placeholder = if (field.description.isNotBlank()) {
                            { Text(field.description, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = oledDialogFieldColors()
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomHeadersSection(
    headers: MutableList<Pair<String, String>>
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Custom HTTP Headers",
                style = MaterialTheme.typography.labelLarge,
                color = CrimsonAccent
            )
            TextButton(onClick = { headers.add("" to "") }) {
                Text(text = "+ Add header", color = InkPrimary)
            }
        }

        headers.forEachIndexed { index, pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = pair.first,
                    onValueChange = { headers[index] = it to pair.second },
                    singleLine = true,
                    placeholder = { Text("Header") },
                    modifier = Modifier.weight(0.42f),
                    shape = RoundedCornerShape(12.dp),
                    colors = oledDialogFieldColors()
                )
                OutlinedTextField(
                    value = pair.second,
                    onValueChange = { headers[index] = pair.first to it },
                    singleLine = true,
                    placeholder = { Text("Value") },
                    modifier = Modifier.weight(0.58f),
                    shape = RoundedCornerShape(12.dp),
                    colors = oledDialogFieldColors()
                )
                IconButton(onClick = { headers.removeAt(index) }) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "Remove header",
                        tint = Color(0xFFFB7185)
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun StremioWebConfigureDialog(
    title: String,
    configureUrl: String,
    onManifestCaptured: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = CardSurface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = InkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close configurator",
                            tint = InkSecondary
                        )
                    }
                }

                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            fun tryCaptureManifestUrl(rawUrl: String): Boolean {
                                val normalized = extractConfiguredStremioManifestUrl(rawUrl) ?: return false
                                post { onManifestCaptured(normalized) }
                                return true
                            }

                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.setSupportMultipleWindows(true)
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                            addJavascriptInterface(
                                object {
                                    @JavascriptInterface
                                    fun onUrlCaptured(raw: String?) {
                                        if (!raw.isNullOrBlank()) {
                                            tryCaptureManifestUrl(raw)
                                        }
                                    }
                                },
                                "__kestrelStremio"
                            )

                            webChromeClient = object : WebChromeClient() {
                                override fun onCreateWindow(
                                    view: WebView?,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: Message?
                                ): Boolean {
                                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                    val popup = WebView(ctx).apply {
                                        webViewClient = object : WebViewClient() {
                                            override fun shouldOverrideUrlLoading(
                                                v: WebView?,
                                                req: WebResourceRequest?
                                            ): Boolean {
                                                val target = req?.url?.toString().orEmpty()
                                                if (tryCaptureManifestUrl(target)) {
                                                    return true
                                                }
                                                view?.loadUrl(target)
                                                return true
                                            }
                                        }
                                    }
                                    transport.webView = popup
                                    resultMsg.sendToTarget()
                                    return true
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val url = request?.url?.toString().orEmpty()
                                    return tryCaptureManifestUrl(url)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    val hookJs = """
                                        (function() {
                                          if (window.__kestrelHooked) return;
                                          window.__kestrelHooked = true;
                                          var origOpen = window.open;
                                          window.open = function(u) {
                                            try {
                                              if (u && window.__kestrelStremio) window.__kestrelStremio.onUrlCaptured(String(u));
                                            } catch (e) {}
                                            return origOpen ? origOpen.apply(window, arguments) : null;
                                          };
                                          if (navigator.clipboard && navigator.clipboard.writeText) {
                                            var origCopy = navigator.clipboard.writeText.bind(navigator.clipboard);
                                            navigator.clipboard.writeText = function(text) {
                                              try {
                                                if (text && window.__kestrelStremio) window.__kestrelStremio.onUrlCaptured(String(text));
                                              } catch (e) {}
                                              return origCopy(text);
                                            };
                                          }
                                        })();
                                    """.trimIndent()
                                    view?.evaluateJavascript(hookJs, null)
                                }
                            }
                            loadUrl(configureUrl)
                        }
                    }
                )
            }
        }
    }
}

private fun extractConfiguredStremioManifestUrl(rawUrl: String): String? {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return null
    val lower = trimmed.lowercase(Locale.US)

    if (lower.startsWith("stremio://")) {
        val rest = trimmed.substring("stremio://".length).trimStart('/')
        return if (rest.startsWith("http://", ignoreCase = true) ||
            rest.startsWith("https://", ignoreCase = true)
        ) {
            rest
        } else {
            "https://$rest"
        }
    }

    if ("web.stremio.com" in lower && "addon=" in lower) {
        val encodedAddon = trimmed.substringAfter("addon=").substringBefore('&')
        val decoded = runCatching { URLDecoder.decode(encodedAddon, "UTF-8") }.getOrNull()?.trim()
        if (!decoded.isNullOrBlank()) {
            return extractConfiguredStremioManifestUrl(decoded) ?: decoded
        }
    }

    val pathPart = lower.substringBefore('?').substringBefore('#')
    if (pathPart.endsWith("/manifest.json")) {
        return trimmed
    }

    return null
}

@Composable
private fun oledDialogFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = CrimsonAccent.copy(alpha = 0.7f),
    unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
    focusedContainerColor = InputSurface,
    unfocusedContainerColor = InputSurface,
    focusedTextColor = InkPrimary,
    unfocusedTextColor = InkPrimary,
    cursorColor = CrimsonAccent
)

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024L) return "${bytes} B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1f MB", mb)
}
