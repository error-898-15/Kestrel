// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kestrel.core.designsystem.GlassChip
import com.kestrel.core.designsystem.GlassScreenHeader
import com.kestrel.core.designsystem.GlassSearchField
import com.kestrel.core.designsystem.KestrelBackdrop
import com.kestrel.core.designsystem.KestrelBackground
import com.kestrel.core.designsystem.KestrelCard
import com.kestrel.core.designsystem.KestrelTheme
import com.kestrel.core.designsystem.SourceBadge
import com.kestrel.core.designsystem.glassPanel
import com.kestrel.core.designsystem.kestrelNavClearance
import com.kestrel.domain.model.PlaybackStartMode
import com.kestrel.domain.model.RepoType
import com.kestrel.domain.model.ServerSearchSettings
import kotlin.math.roundToInt

private enum class SettingsPage {
    HUB,
    SERVERS
}

private val LilacAccent = Color(0xFFBBA9FF)
private val LilacTileTint = Color(0xFFD7CBFF)
private val LilacSwitchTrack = Color(0xFF8B6CFF)

@Composable
fun SettingsScreen(
    onOpenExtensions: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf(SettingsPage.HUB) }
    var activePickerFamily by remember { mutableStateOf<RepoType?>(null) }

    BackHandler(enabled = page != SettingsPage.HUB) {
        page = SettingsPage.HUB
    }

    KestrelBackground(
        modifier = modifier.fillMaxSize(),
        style = KestrelBackdrop.Purple
    ) {
        when (page) {
            SettingsPage.HUB -> {
                SettingsHubContent(
                    state = state,
                    onOpenServers = { page = SettingsPage.SERVERS },
                    onOpenExtensions = onOpenExtensions,
                    onSelectPlaybackSpeed = viewModel::setDefaultPlaybackSpeed,
                    onResetDisclaimer = viewModel::resetDisclaimer,
                    onDismissBanner = viewModel::dismissBanner
                )
            }

            SettingsPage.SERVERS -> {
                PlaybackAndServersPage(
                    state = state,
                    onBack = { page = SettingsPage.HUB },
                    onToggleSearchAll = viewModel::setSearchAllInstalled,
                    onToggleSearchEvery = viewModel::setSearchEvery,
                    onOpenFamilyPicker = { family -> activePickerFamily = family },
                    onToggleSameTime = viewModel::setSameSearchTimeForAll,
                    onChangeGlobalTime = viewModel::setGlobalSearchTimeoutSec,
                    onChangeExtensionTime = viewModel::setExtensionTimeoutSec,
                    onSelectStartMode = viewModel::setPlaybackStartMode,
                    onToggleAlwaysShowList = viewModel::setAlwaysShowServerList,
                    onToggleAskWhereToPlay = viewModel::setAskWhereToPlay
                )
            }
        }
    }

    val pickerFamily = activePickerFamily
    if (pickerFamily != null) {
        val (extensions, selectedIds) = when (pickerFamily) {
            RepoType.NUVIO -> state.nuvioExtensions to state.settings.selectedNuvioIds
            RepoType.STREMIO -> state.stremioExtensions to state.settings.selectedStremioIds
            RepoType.CLOUDSTREAM -> state.cloudStreamExtensions to state.settings.selectedCloudStreamIds
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
}

@Composable
private fun SettingsHubContent(
    state: SettingsUiState,
    onOpenServers: () -> Unit,
    onOpenExtensions: () -> Unit,
    onSelectPlaybackSpeed: (Float) -> Unit,
    onResetDisclaimer: () -> Unit,
    onDismissBanner: () -> Unit
) {
    val colors = KestrelTheme.colors
    val navClearance = kestrelNavClearance()
    val speedOptions = remember { listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = navClearance + 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "header") {
            GlassScreenHeader(
                title = "Settings",
                subtitle = "Servers, playback & preferences"
            )
        }

        if (!state.bannerMessage.isNullOrBlank()) {
            item(key = "banner") {
                KestrelCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    onClick = onDismissBanner,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = state.bannerMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LilacAccent,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Dismiss",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Servers Folder (Playback & Servers)
        item(key = "servers_folder") {
            val s = state.settings
            val enabledFamilies = buildList {
                if (s.searchAllInstalled) {
                    add("All installed")
                } else {
                    if (s.searchEveryNuvio || s.selectedNuvioIds.isNotEmpty()) add("Nuvio")
                    if (s.searchEveryStremio || s.selectedStremioIds.isNotEmpty()) add("Stremio")
                    if (s.searchEveryCloudStream || s.selectedCloudStreamIds.isNotEmpty()) add("CloudStream")
                }
            }.ifEmpty { listOf("Origin only") }.joinToString(" • ")

            SettingsFolderCard(
                icon = Icons.Outlined.Dns,
                title = "Servers",
                subtitle = "Playback & Servers • $enabledFamilies",
                badge = "${state.totalActiveExtensions} active",
                onClick = onOpenServers,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }

        // Extensions shortcut
        item(key = "extensions_folder") {
            SettingsFolderCard(
                icon = Icons.Outlined.Extension,
                title = "Extensions",
                subtitle = "Manage Nuvio, Stremio & CloudStream repositories",
                badge = null,
                onClick = onOpenExtensions,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }

        // Default Playback Speed
        item(key = "playback_speed") {
            KestrelCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                contentPadding = PaddingValues(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SettingsIconTile(icon = Icons.Outlined.Speed)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Default playback speed",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "Applied automatically when a stream starts",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary
                            )
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(speedOptions) { spd ->
                            val label = if (spd == 1.0f) "1x" else "${spd}x"
                            GlassChip(
                                text = label,
                                selected = state.defaultPlaybackSpeed == spd,
                                onClick = { onSelectPlaybackSpeed(spd) }
                            )
                        }
                    }
                }
            }
        }

        // General / Disclaimer Reset
        item(key = "general_section") {
            KestrelCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                contentPadding = PaddingValues(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SettingsIconTile(icon = Icons.Outlined.Info, size = 34.dp)
                        Text(
                            text = "General",
                            style = MaterialTheme.typography.titleMedium,
                            color = LilacAccent
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(onClick = onResetDisclaimer)
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Show startup disclaimer again",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "Re-open the extension & source notice on next launch",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Reset disclaimer",
                            tint = LilacAccent
                        )
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
    onToggleAskWhereToPlay: (Boolean) -> Unit
) {
    val colors = KestrelTheme.colors
    val navClearance = kestrelNavClearance()
    val s = state.settings
    var showCloudStreamTimers by remember { mutableStateOf(false) }
    var showNuvioTimers by remember { mutableStateOf(false) }
    var showStremioTimers by remember { mutableStateOf(false) }
    var playbackStartExpanded by remember { mutableStateOf(true) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = navClearance + 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "servers_top_bar") {
            GlassScreenHeader(
                title = "Playback & Servers",
                subtitle = "Where servers come from & when playback starts",
                onBack = onBack
            )
        }

        // Card 1: Server search
        item(key = "server_search_card") {
            KestrelCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SettingsIconTile(icon = Icons.Outlined.Extension, size = 34.dp)
                        Text(
                            text = "Server search",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = LilacAccent
                        )
                    }

                    SettingsSwitchRow(
                        title = "Search all installed extensions",
                        subtitle = if (s.searchAllInstalled) {
                            "Every enabled Nuvio, Stremio & CloudStream extension is searched"
                        } else {
                            "Only the extension the title was opened from, plus enabled families below"
                        },
                        checked = s.searchAllInstalled,
                        onCheckedChange = onToggleSearchAll
                    )

                    Text(
                        text = "When off, each family below controls whether titles opened anywhere also pull servers from other installed extensions. Keeping Nuvio & Stremio on works like Nuvio's multi-server lookup.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    FamilySearchBlock(
                        toggleTitle = "Search every Nuvio provider",
                        toggleSubtitle = if (s.searchEveryNuvio) {
                            "A title is also searched in every enabled Nuvio provider"
                        } else {
                            "Only selected Nuvio extensions below are searched"
                        },
                        pickerTitle = if (s.searchEveryNuvio) {
                            "All Nuvio extensions (Nuvio)"
                        } else {
                            "Choose Nuvio extensions (${s.selectedNuvioIds.size}/${state.nuvioExtensions.size})"
                        },
                        pickerSubtitle = "Tap to choose individual Nuvio extensions",
                        checked = s.searchEveryNuvio || s.searchAllInstalled,
                        switchEnabled = !s.searchAllInstalled,
                        onCheckedChange = { onToggleSearchEvery(RepoType.NUVIO, it) },
                        onOpenPicker = { onOpenFamilyPicker(RepoType.NUVIO) }
                    )

                    FamilySearchBlock(
                        toggleTitle = "Search every Stremio addon",
                        toggleSubtitle = if (s.searchEveryStremio) {
                            "A title is also searched in every enabled Stremio addon"
                        } else {
                            "Only selected Stremio extensions below are searched"
                        },
                        pickerTitle = if (s.searchEveryStremio) {
                            "All Stremio extensions (Stremio)"
                        } else {
                            "Choose Stremio extensions (${s.selectedStremioIds.size}/${state.stremioExtensions.size})"
                        },
                        pickerSubtitle = "Tap to choose individual Stremio extensions",
                        checked = s.searchEveryStremio || s.searchAllInstalled,
                        switchEnabled = !s.searchAllInstalled,
                        onCheckedChange = { onToggleSearchEvery(RepoType.STREMIO, it) },
                        onOpenPicker = { onOpenFamilyPicker(RepoType.STREMIO) }
                    )

                    FamilySearchBlock(
                        toggleTitle = "Search every CloudStream",
                        toggleSubtitle = if (s.searchEveryCloudStream) {
                            "Search every enabled CloudStream extension as fallback"
                        } else {
                            "A title plays from the CloudStream repo you opened it from"
                        },
                        pickerTitle = if (s.searchEveryCloudStream) {
                            "All CloudStream extensions"
                        } else {
                            "Choose CloudStream extensions (${s.selectedCloudStreamIds.size}/${state.cloudStreamExtensions.size})"
                        },
                        pickerSubtitle = "Tap to choose individual CloudStream extensions",
                        checked = s.searchEveryCloudStream || s.searchAllInstalled,
                        switchEnabled = !s.searchAllInstalled,
                        onCheckedChange = { onToggleSearchEvery(RepoType.CLOUDSTREAM, it) },
                        onOpenPicker = { onOpenFamilyPicker(RepoType.CLOUDSTREAM) }
                    )
                }
            }
        }

        // Card 2: Server search time
        item(key = "server_search_time_card") {
            KestrelCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SettingsIconTile(icon = Icons.Outlined.Timer, size = 34.dp)
                        Text(
                            text = "Server search time",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = LilacAccent
                        )
                    }

                    SettingsSwitchRow(
                        title = "Same search time for every extension",
                        subtitle = if (s.sameSearchTimeForAll) {
                            "All extensions wait up to ${s.globalSearchTimeoutSec}s"
                        } else {
                            "Set the time per extension below"
                        },
                        checked = s.sameSearchTimeForAll,
                        onCheckedChange = onToggleSameTime
                    )

                    AnimatedVisibility(visible = s.sameSearchTimeForAll) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Global wait time",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textPrimary
                                )
                                Text(
                                    text = "${s.globalSearchTimeoutSec}s",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = LilacAccent
                                )
                            }
                            Slider(
                                value = s.globalSearchTimeoutSec.toFloat(),
                                onValueChange = { onChangeGlobalTime(it.roundToInt()) },
                                valueRange = 5f..60f,
                                colors = SliderDefaults.colors(
                                    thumbColor = LilacAccent,
                                    activeTrackColor = LilacSwitchTrack,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                                )
                            )
                        }
                    }

                    Text(
                        text = "Slow extensions need more than the built-in wait. Raising the time only waits longer — a server that fails outright still reports immediately instead of burning the whole time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )

                    FamilyTimerExpandable(
                        familyTitle = "CloudStream",
                        extensions = state.cloudStreamExtensions,
                        settings = s,
                        expanded = showCloudStreamTimers,
                        onToggleExpand = { showCloudStreamTimers = !showCloudStreamTimers },
                        onChangeExtensionTime = onChangeExtensionTime
                    )

                    FamilyTimerExpandable(
                        familyTitle = "Nuvio",
                        extensions = state.nuvioExtensions,
                        settings = s,
                        expanded = showNuvioTimers,
                        onToggleExpand = { showNuvioTimers = !showNuvioTimers },
                        onChangeExtensionTime = onChangeExtensionTime
                    )

                    FamilyTimerExpandable(
                        familyTitle = "Stremio",
                        extensions = state.stremioExtensions,
                        settings = s,
                        expanded = showStremioTimers,
                        onToggleExpand = { showStremioTimers = !showStremioTimers },
                        onChangeExtensionTime = onChangeExtensionTime
                    )
                }
            }
        }

        // Card 3: Playback start
        item(key = "playback_start_card") {
            val summaryText = when {
                s.alwaysShowServerList -> "Show the server list first"
                s.playbackStartMode == PlaybackStartMode.FIRST_FOUND -> "Play as soon as the first server is found"
                else -> "Wait for more servers first"
            }

            KestrelCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { playbackStartExpanded = !playbackStartExpanded }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SettingsIconTile(icon = Icons.Outlined.PlayArrow, size = 38.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Playback start",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = LilacAccent
                            )
                            Text(
                                text = summaryText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary
                            )
                        }
                        Icon(
                            imageVector = if (playbackStartExpanded) {
                                Icons.Outlined.KeyboardArrowUp
                            } else {
                                Icons.Outlined.KeyboardArrowDown
                            },
                            contentDescription = null,
                            tint = colors.textSecondary
                        )
                    }

                    AnimatedVisibility(visible = playbackStartExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            PlaybackRadioRow(
                                title = "Play as soon as the first server is found",
                                subtitle = "Instant — falls back if it's dead",
                                selected = s.playbackStartMode == PlaybackStartMode.FIRST_FOUND,
                                onClick = { onSelectStartMode(PlaybackStartMode.FIRST_FOUND) }
                            )

                            PlaybackRadioRow(
                                title = "Wait for more servers first",
                                subtitle = "Or when every extension has finished",
                                selected = s.playbackStartMode == PlaybackStartMode.WAIT_FOR_MORE,
                                onClick = { onSelectStartMode(PlaybackStartMode.WAIT_FOR_MORE) }
                            )

                            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                            SettingsSwitchRow(
                                title = "Always show the server list",
                                subtitle = if (s.alwaysShowServerList) {
                                    "On — Play opens the server list"
                                } else {
                                    "Off — Plays straight away when first server is ready"
                                },
                                checked = s.alwaysShowServerList,
                                onCheckedChange = onToggleAlwaysShowList
                            )

                            SettingsSwitchRow(
                                title = "Ask where to play",
                                subtitle = if (s.askWhereToPlay) {
                                    "On — Ask before searching all servers vs origin only"
                                } else {
                                    "Off — Play uses your server-search choices directly"
                                },
                                checked = s.askWhereToPlay,
                                onCheckedChange = onToggleAskWhereToPlay
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsFolderCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badge: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KestrelTheme.colors
    KestrelCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SettingsIconTile(icon = icon, size = 44.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )
                    if (!badge.isNullOrBlank()) {
                        SourceBadge(
                            label = badge,
                            accent = LilacAccent,
                            container = LilacSwitchTrack.copy(alpha = 0.22f)
                        )
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.textSecondary
            )
        }
    }
}

@Composable
private fun SettingsIconTile(
    icon: ImageVector,
    size: androidx.compose.ui.unit.Dp = 38.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(LilacSwitchTrack.copy(alpha = 0.24f))
            .border(1.dp, LilacAccent.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = LilacTileTint,
            modifier = Modifier.size(size * 0.54f)
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val colors = KestrelTheme.colors
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
                color = if (enabled) colors.textPrimary else colors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) colors.textSecondary else colors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = LilacSwitchTrack,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = Color.White.copy(alpha = 0.14f),
                uncheckedThumbColor = colors.textSecondary,
                uncheckedBorderColor = Color.White.copy(alpha = 0.25f)
            )
        )
    }
}

@Composable
private fun FamilySearchBlock(
    toggleTitle: String,
    toggleSubtitle: String,
    pickerTitle: String,
    pickerSubtitle: String,
    checked: Boolean,
    switchEnabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onOpenPicker: () -> Unit
) {
    val colors = KestrelTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsSwitchRow(
            title = toggleTitle,
            subtitle = toggleSubtitle,
            checked = checked,
            enabled = switchEnabled,
            onCheckedChange = onCheckedChange
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .glassPanel(
                    cornerRadius = 18.dp,
                    tint = Color.White.copy(alpha = 0.05f),
                    rimAlpha = 0.28f
                )
                .clickable(onClick = onOpenPicker)
                .padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsIconTile(icon = Icons.Outlined.Extension, size = 38.dp)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = pickerTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = pickerSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.textSecondary
                )
            }
        }
    }
}

@Composable
private fun FamilyTimerExpandable(
    familyTitle: String,
    extensions: List<InstalledServerExtension>,
    settings: ServerSearchSettings,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onChangeExtensionTime: (String, Int) -> Unit
) {
    val colors = KestrelTheme.colors
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
                    color = LilacAccent
                )
                Text(
                    text = "${extensions.size} extensions",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
            Text(
                text = if (expanded) "Hide" else "Show",
                style = MaterialTheme.typography.labelLarge,
                color = LilacAccent
            )
        }

        AnimatedVisibility(visible = expanded) {
            if (extensions.isEmpty()) {
                Text(
                    text = "No active $familyTitle extensions installed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
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
                                .background(Color.White.copy(alpha = 0.04f))
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
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${extSec}s",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = LilacAccent
                                )
                            }
                            Slider(
                                value = extSec.toFloat(),
                                enabled = !settings.sameSearchTimeForAll,
                                onValueChange = { onChangeExtensionTime(ext.id, it.roundToInt()) },
                                valueRange = 5f..60f,
                                colors = SliderDefaults.colors(
                                    thumbColor = LilacAccent,
                                    activeTrackColor = LilacSwitchTrack,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.14f)
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
private fun PlaybackRadioRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = KestrelTheme.colors
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
                selectedColor = LilacAccent,
                unselectedColor = colors.textSecondary
            )
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
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
    val colors = KestrelTheme.colors
    var query by remember { mutableStateOf("") }
    val familyLabel = when (family) {
        RepoType.NUVIO -> "Nuvio"
        RepoType.STREMIO -> "Stremio"
        RepoType.CLOUDSTREAM -> "CloudStream"
    }
    val filtered = remember(extensions, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) extensions
        else extensions.filter { it.name.lowercase().contains(q) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xF01C2032))
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(26.dp))
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
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close",
                            tint = colors.textPrimary
                        )
                    }
                }

                Text(
                    text = "Scrollable list — tap again to unselect. Only the extensions you keep selected are searched when the family switch above is off.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )

                GlassSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search $familyLabel extensions",
                    onClear = { query = "" }
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassChip(
                        text = "Select all",
                        selected = extensions.isNotEmpty() && selectedIds.size == extensions.size,
                        onClick = onSelectAll
                    )
                    GlassChip(
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
                        color = colors.textSecondary,
                        modifier = Modifier.padding(vertical = 24.dp)
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
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(
                                        if (isSelected) LilacSwitchTrack.copy(alpha = 0.22f)
                                        else Color.White.copy(alpha = 0.06f)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) LilacAccent.copy(alpha = 0.45f)
                                        else Color.White.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(18.dp)
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
                                        checkedColor = LilacAccent,
                                        uncheckedColor = colors.textSecondary,
                                        checkmarkColor = Color(0xFF1A1033)
                                    )
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = ext.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (isSelected) LilacAccent else colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = familyLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textSecondary
                                    )
                                }
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(LilacAccent),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Check,
                                            contentDescription = "Selected",
                                            tint = Color(0xFF1A1033),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Text(
                    text = "Tap a row to include or exclude it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
        }
    }
}
