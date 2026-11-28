// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.details

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.kestrel.core.designsystem.CertificationBadge
import com.kestrel.core.designsystem.EmptyStateView
import com.kestrel.core.designsystem.GlassChip
import com.kestrel.core.designsystem.GlassCircleButton
import com.kestrel.core.designsystem.ImdbRatingBadge
import com.kestrel.core.designsystem.KestrelPrimaryButton
import com.kestrel.core.designsystem.KestrelBackdrop
import com.kestrel.core.designsystem.KestrelBackground
import com.kestrel.core.designsystem.KestrelCard
import com.kestrel.core.designsystem.KestrelTheme
import com.kestrel.core.designsystem.LocalGlassState
import com.kestrel.core.designsystem.MediaPosterCard
import com.kestrel.core.designsystem.SourceBadge
import com.kestrel.core.designsystem.glassPanel
import com.kestrel.core.designsystem.glassSource
import com.kestrel.core.designsystem.rememberGlassState
import com.kestrel.core.designsystem.rememberKestrelImageRequest
import com.kestrel.domain.model.ActorCredit
import com.kestrel.domain.model.Episode
import com.kestrel.domain.model.MediaDetails
import com.kestrel.domain.model.MediaSummary
import com.kestrel.domain.model.ProviderServerProgress
import com.kestrel.domain.model.SeasonInfo
import com.kestrel.domain.model.StreamSource
import com.kestrel.domain.model.TrailerInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(
    onBack: () -> Unit,
    onPlayStarted: () -> Unit,
    onOpenMedia: (MediaSummary) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: DetailsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = KestrelTheme.colors
    val glassState = rememberGlassState()
    val listState = rememberLazyListState()
    val showStickyTopBar by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 360
        }
    }

    var showMorePlot by remember { mutableStateOf(false) }
    var usePosterEpisodeCards by remember { mutableStateOf(true) }
    var showMoreSheet by remember { mutableStateOf(false) }

    LaunchedEffect(state.autoPlayEvent) {
        if (state.autoPlayEvent > 0) {
            onPlayStarted()
        }
    }

    KestrelBackground(
        modifier = modifier.fillMaxSize(),
        style = KestrelBackdrop.Dark
    ) {
        when {
            state.isLoading -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    CircularProgressIndicator(
                        color = colors.accentAmber,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    GlassCircleButton(
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack,
                        diameter = 42.dp,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }

            state.errorMessage != null || state.details == null -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    EmptyStateView(
                        icon = Icons.Outlined.ErrorOutline,
                        title = "Could not load details",
                        description = state.errorMessage ?: "Provider returned no metadata.",
                        actionLabel = "Retry",
                        onAction = viewModel::load,
                        modifier = Modifier.statusBarsPadding()
                    )
                    GlassCircleButton(
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack,
                        diameter = 42.dp,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }

            else -> {
                val details = state.details!!
                val isFavorite = state.libraryEntry?.isFavorite == true
                val isWatched = (state.libraryEntry?.lastPositionMs ?: 0L) > 0L
                val seasons = remember(details.episodes, details.seasons) {
                    buildSeasonList(details)
                }
                val visibleEpisodes = remember(details.episodes, state.selectedSeason) {
                    val s = state.selectedSeason
                    if (s == null) details.episodes
                    else details.episodes.filter { it.season == s }
                }
                val primaryEpisode = remember(details.episodes, state.libraryEntry) {
                    val lastId = state.libraryEntry?.lastEpisodeId
                    details.episodes.firstOrNull { it.id == lastId } ?: details.episodes.firstOrNull()
                }
                val heroHeight = (LocalConfiguration.current.screenHeightDp * 0.56f).coerceIn(380f, 520f).dp

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .glassSource(glassState),
                    contentPadding = PaddingValues(bottom = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // 1. Cinematic Full-Bleed Hero Backdrop + Logo + Genres + Action Row
                    item(key = "hero") {
                        CinematicHeroSection(
                            details = details,
                            heroHeight = heroHeight,
                            primaryEpisode = primaryEpisode,
                            hasResume = isWatched,
                            isResolving = state.isResolvingLinks,
                            isFavorite = isFavorite,
                            isWatched = isWatched,
                            onPlay = { viewModel.resolveForPlay(primaryEpisode) },
                            onToggleWatched = viewModel::markWatched,
                            onToggleFavorite = viewModel::toggleFavorite,
                            onMore = { showMoreSheet = true }
                        )
                    }

                    // 2. Metadata Row + Director + Expandable Synopsis
                    item(key = "meta_plot") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            MetadataStrip(details = details)

                            if (!details.director.isNullOrBlank()) {
                                Text(
                                    text = buildAnnotatedString {
                                        withStyle(SpanStyle(color = colors.textSecondary, fontWeight = FontWeight.Medium)) {
                                            append("Director:  ")
                                        }
                                        withStyle(SpanStyle(color = colors.textPrimary, fontWeight = FontWeight.Medium)) {
                                            append(details.director)
                                        }
                                    },
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }

                            if (!details.plot.isNullOrBlank()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showMorePlot = !showMorePlot },
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = details.plot!!,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Color.White.copy(alpha = 0.86f),
                                        maxLines = if (showMorePlot) Int.MAX_VALUE else 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = if (showMorePlot) "Show Less" else "Show More",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = colors.textSecondary
                                        )
                                        Icon(
                                            imageVector = if (showMorePlot) {
                                                Icons.Outlined.KeyboardArrowUp
                                            } else {
                                                Icons.Outlined.KeyboardArrowDown
                                            },
                                            contentDescription = null,
                                            tint = colors.textSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (!state.linkError.isNullOrBlank()) {
                        item(key = "link_error") {
                            KestrelCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = colors.statusError)
                                    Text(text = state.linkError!!, color = colors.statusError)
                                }
                            }
                        }
                    }

                    // 3. Cast Circular Avatars
                    if (details.actors.isNotEmpty()) {
                        item(key = "cast") {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = "Cast",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textPrimary,
                                    modifier = Modifier.padding(horizontal = 20.dp)
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    itemsIndexed(
                                        items = details.actors,
                                        key = { idx, actor -> "${actor.name}_$idx" }
                                    ) { _, actor ->
                                        CastAvatarCard(actor = actor, headers = details.posterHeaders)
                                    }
                                }
                            }
                        }
                    }

                    // 4. Trailers Row
                    if (details.trailers.isNotEmpty()) {
                        item(key = "trailers") {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = "Trailers",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textPrimary,
                                    modifier = Modifier.padding(horizontal = 20.dp)
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    itemsIndexed(
                                        items = details.trailers,
                                        key = { idx, t -> "${t.url}_$idx" }
                                    ) { _, trailer ->
                                        TrailerCard(
                                            trailer = trailer,
                                            headers = details.posterHeaders
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 5. Seasons Poster Row
                    if (seasons.size > 1) {
                        item(key = "seasons") {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Seasons",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.textPrimary
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .glassPanel(
                                                cornerRadius = 10.dp,
                                                tint = Color.White.copy(alpha = 0.06f),
                                                rimAlpha = 0.4f
                                            )
                                            .clickable { usePosterEpisodeCards = !usePosterEpisodeCards }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = if (usePosterEpisodeCards) "Posters" else "List",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = colors.textPrimary
                                        )
                                    }
                                }
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    itemsIndexed(seasons, key = { idx, s -> "season_${s.season}_$idx" }) { _, seasonInfo ->
                                        SeasonPosterCard(
                                            seasonInfo = seasonInfo,
                                            selected = seasonInfo.season == state.selectedSeason,
                                            headers = details.posterHeaders,
                                            onClick = { viewModel.selectSeason(seasonInfo.season) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 6. Episodes Row (Landscape Cards matching Nuvio) or List
                    if (visibleEpisodes.isNotEmpty()) {
                        if (usePosterEpisodeCards) {
                            item(key = "episodes_row") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    itemsIndexed(visibleEpisodes, key = { idx, ep -> "ep_card_${ep.id}_$idx" }) { _, ep ->
                                        LandscapeEpisodeCard(
                                            episode = ep,
                                            fallbackPoster = details.backdropUrl ?: details.posterUrl,
                                            headers = details.posterHeaders,
                                            onClick = { viewModel.resolveForPlay(ep) }
                                        )
                                    }
                                }
                            }
                        } else {
                            itemsIndexed(visibleEpisodes, key = { idx, ep -> "ep_row_${ep.id}_$idx" }) { _, ep ->
                                CompactEpisodeRow(
                                    episode = ep,
                                    onClick = { viewModel.resolveForPlay(ep) },
                                    modifier = Modifier.padding(horizontal = 20.dp)
                                )
                            }
                        }
                    }

                    // 7. Show Details Key-Value Table
                    item(key = "show_details") {
                        ShowDetailsTable(
                            details = details,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                    }

                    // 8. More Like This
                    if (details.recommendations.isNotEmpty()) {
                        item(key = "recommendations") {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = "More Like This",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textPrimary,
                                    modifier = Modifier.padding(horizontal = 20.dp)
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    itemsIndexed(
                                        items = details.recommendations,
                                        key = { idx, rec -> "${rec.id}_$idx" },
                                        contentType = { _, _ -> "poster_card" }
                                    ) { _, rec ->
                                        MediaPosterCard(
                                            title = rec.title,
                                            posterUrl = rec.posterUrl,
                                            sourceTag = rec.providerName,
                                            subtitle = rec.year?.toString(),
                                            posterHeaders = rec.posterHeaders,
                                            onClick = { onOpenMedia(rec) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.navigationBarsPadding())
                    }
                }

                // Floating Liquid Glass Back Button & Sticky Top Bar
                CompositionLocalProvider(LocalGlassState provides glassState) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                    ) {
                        AnimatedVisibility(
                            visible = showStickyTopBar,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xE0090B10))
                                    .statusBarsPadding()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                GlassCircleButton(
                                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = "Back",
                                    onClick = onBack,
                                    diameter = 40.dp
                                )
                                Text(
                                    text = details.title.uppercase(Locale.US),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 2.sp,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 12.dp),
                                    textAlign = TextAlign.Center
                                )
                                GlassCircleButton(
                                    icon = if (isFavorite) Icons.Outlined.Check else Icons.Outlined.Add,
                                    contentDescription = "Bookmark",
                                    onClick = viewModel::toggleFavorite,
                                    tint = if (isFavorite) colors.accentAmber else colors.textPrimary,
                                    diameter = 40.dp
                                )
                            }
                        }

                        if (!showStickyTopBar) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                GlassCircleButton(
                                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = "Back",
                                    onClick = onBack,
                                    diameter = 42.dp
                                )
                                SourceBadge(label = details.providerName)
                            }
                        }
                    }
                }
            }
        }

        if (state.isSourceSheetOpen) {
            ModalBottomSheet(
                onDismissRequest = viewModel::dismissSourcePicker,
                containerColor = Color(0xEB10141F)
            ) {
                SourcePickerSheet(
                    title = state.details?.title ?: "Select Stream Source",
                    episode = state.resolvingEpisode,
                    streams = state.resolvedLinks?.streams.orEmpty(),
                    providers = state.serverSearchProgress,
                    selectedProviderId = state.selectedServerFilterProviderId,
                    isResolving = state.isResolvingLinks,
                    completedCount = state.completedServerCount,
                    totalCount = state.totalServerCount,
                    linkError = state.linkError,
                    onSelectFilter = viewModel::selectServerFilter,
                    onRefresh = viewModel::refreshStreams,
                    onSelect = { source ->
                        viewModel.startPlayback(source)
                        onPlayStarted()
                    }
                )
            }
        }

        if (state.isAskWhereToPlayOpen && state.details != null) {
            val d = state.details!!
            ModalBottomSheet(
                onDismissRequest = viewModel::dismissAskWhereToPlay,
                containerColor = Color(0xEB10141F)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Where to search servers",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Choose whether to search all enabled extensions or only ${d.providerName}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                    KestrelCard(
                        onClick = { viewModel.confirmAskWhereToPlay(onlyOriginProvider = false) },
                        modifier = Modifier.fillMaxWidth(),
                        highlighted = true,
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "All Enabled Servers",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "Search across Nuvio, Stremio & CloudStream per your Servers settings",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary
                            )
                        }
                    }
                    KestrelCard(
                        onClick = { viewModel.confirmAskWhereToPlay(onlyOriginProvider = true) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Only ${d.providerName}",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "Resolve streams strictly from the origin extension",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary
                            )
                        }
                    }
                }
            }
        }

        if (showMoreSheet && state.details != null) {
            val d = state.details!!
            ModalBottomSheet(
                onDismissRequest = { showMoreSheet = false },
                containerColor = Color(0xEB10141F)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = d.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary
                    )
                    KestrelCard(
                        onClick = {
                            showMoreSheet = false
                            viewModel.load()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, tint = colors.accentAmber)
                            Text("Refresh metadata", color = colors.textPrimary)
                        }
                    }
                    KestrelCard(
                        onClick = {
                            showMoreSheet = false
                            val ep = d.episodes.firstOrNull { it.id == state.libraryEntry?.lastEpisodeId }
                                ?: d.episodes.firstOrNull()
                            viewModel.resolveForPlay(
                                episode = ep,
                                forceShowSheet = true
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = colors.accentAmber)
                            Text("Select stream source", color = colors.textPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CinematicHeroSection(
    details: MediaDetails,
    heroHeight: androidx.compose.ui.unit.Dp,
    primaryEpisode: Episode?,
    hasResume: Boolean,
    isResolving: Boolean,
    isFavorite: Boolean,
    isWatched: Boolean,
    onPlay: () -> Unit,
    onToggleWatched: () -> Unit,
    onToggleFavorite: () -> Unit,
    onMore: () -> Unit
) {
    val colors = KestrelTheme.colors
    val backdropReq = rememberKestrelImageRequest(
        details.backdropUrl ?: details.posterUrl,
        details.posterHeaders,
        1080,
        1440
    )
    val logoReq = rememberKestrelImageRequest(
        details.logoUrl,
        details.posterHeaders,
        800,
        240
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight)
        ) {
            if (backdropReq != null) {
                AsyncImage(
                    model = backdropReq,
                    contentDescription = details.title,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0x66090B10),
                            0.35f to Color.Transparent,
                            0.68f to Color(0xAA090B10),
                            0.92f to Color(0xEB090B10),
                            1f to colors.canvas
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (logoReq != null) {
                    AsyncImage(
                        model = logoReq,
                        contentDescription = details.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth(0.75f)
                            .height(74.dp)
                    )
                } else {
                    Text(
                        text = details.title.uppercase(Locale.US),
                        style = TextStyle(
                            fontSize = 30.sp,
                            lineHeight = 34.sp,
                            fontWeight = FontWeight.Light,
                            letterSpacing = 4.sp
                        ),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (details.tags.isNotEmpty()) {
                    Text(
                        text = details.tags.take(3).joinToString("  •  "),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.78f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Action Row (White Capsule Play Button + Liquid Glass Circular Action Buttons)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val playLabel = when {
                isResolving -> "Finding streams..."
                primaryEpisode != null && primaryEpisode.season != null && primaryEpisode.episodeNumber != null -> {
                    val verb = if (hasResume) "Resume" else "Play"
                    "$verb S${primaryEpisode.season} E${primaryEpisode.episodeNumber}"
                }
                hasResume -> "Resume"
                else -> "Play"
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (isResolving) 0.7f else 0.96f))
                    .clickable(enabled = !isResolving, onClick = onPlay),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isResolving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF090B10)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.PlayArrow,
                            contentDescription = null,
                            tint = Color(0xFF090B10),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Text(
                        text = playLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF090B10),
                        maxLines = 1
                    )
                }
            }

            if (details.episodes.isNotEmpty() || isWatched) {
                GlassCircleButton(
                    icon = Icons.Outlined.CheckCircleOutline,
                    contentDescription = "Mark Watched",
                    onClick = onToggleWatched,
                    tint = if (isWatched) colors.accentAmber else Color.White,
                    diameter = 50.dp
                )
            }

            GlassCircleButton(
                icon = if (isFavorite) Icons.Outlined.Check else Icons.Outlined.Add,
                contentDescription = if (isFavorite) "Remove from Library" else "Add to Library",
                onClick = onToggleFavorite,
                tint = if (isFavorite) colors.accentAmber else Color.White,
                diameter = 50.dp
            )

            GlassCircleButton(
                icon = Icons.Outlined.MoreVert,
                contentDescription = "More Actions",
                onClick = onMore,
                tint = Color.White,
                diameter = 50.dp
            )
        }
    }
}

@Composable
private fun MetadataStrip(details: MediaDetails) {
    val colors = KestrelTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        details.year?.let { year ->
            Text(
                text = year.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary
            )
        }
        details.durationMinutes?.takeIf { it > 0 }?.let { mins ->
            Text(
                text = formatRuntime(mins),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary
            )
        }
        details.contentRating?.let { cert ->
            CertificationBadge(label = cert)
        }
        details.rating?.let { score ->
            ImdbRatingBadge(rating = score)
        }
    }
}

@Composable
private fun CastAvatarCard(
    actor: ActorCredit,
    headers: Map<String, String>
) {
    val colors = KestrelTheme.colors
    val imageReq = rememberKestrelImageRequest(actor.imageUrl, headers, 200, 200)
    Column(
        modifier = Modifier.width(84.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .glassPanel(
                    cornerRadius = 38.dp,
                    tint = Color.White.copy(alpha = 0.08f),
                    rimAlpha = 0.35f
                ),
            contentAlignment = Alignment.Center
        ) {
            if (imageReq != null) {
                AsyncImage(
                    model = imageReq,
                    contentDescription = actor.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                )
            } else {
                Text(
                    text = actor.name.take(2).uppercase(Locale.US),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.textSecondary
                )
            }
        }
        Text(
            text = actor.name,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        if (!actor.role.isNullOrBlank()) {
            Text(
                text = actor.role!!,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun TrailerCard(
    trailer: TrailerInfo,
    headers: Map<String, String>
) {
    val colors = KestrelTheme.colors
    val uriHandler = LocalUriHandler.current
    val req = rememberKestrelImageRequest(trailer.thumbnailUrl, headers, 480, 270)
    Column(
        modifier = Modifier
            .width(206.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable {
                runCatching { uriHandler.openUri(trailer.url) }
            },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
                .glassPanel(
                    cornerRadius = 14.dp,
                    tint = Color.White.copy(alpha = 0.06f),
                    rimAlpha = 0.35f
                ),
            contentAlignment = Alignment.Center
        ) {
            if (req != null) {
                AsyncImage(
                    model = req,
                    contentDescription = trailer.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x55090B10))
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .glassPanel(cornerRadius = 20.dp, tint = Color.Black.copy(alpha = 0.45f), rimAlpha = 0.65f),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = Color.White)
            }
        }
        Text(
            text = trailer.title,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SeasonPosterCard(
    seasonInfo: SeasonInfo,
    selected: Boolean,
    headers: Map<String, String>,
    onClick: () -> Unit
) {
    val colors = KestrelTheme.colors
    val req = rememberKestrelImageRequest(seasonInfo.posterUrl, headers, 320, 480)
    Column(
        modifier = Modifier
            .width(114.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) Color.White else Color.White.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (req != null) {
                AsyncImage(
                    model = req,
                    contentDescription = seasonInfo.name ?: "Season ${seasonInfo.season}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = "S${seasonInfo.season}",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.textSecondary
                )
            }
        }
        Text(
            text = seasonInfo.name?.takeIf { it.isNotBlank() } ?: "Season ${seasonInfo.season}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) Color.White else colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LandscapeEpisodeCard(
    episode: Episode,
    fallbackPoster: String?,
    headers: Map<String, String>,
    onClick: () -> Unit
) {
    val colors = KestrelTheme.colors
    val req = rememberKestrelImageRequest(episode.posterUrl ?: fallbackPoster, headers, 580, 326)
    val epTag = if (episode.season != null && episode.episodeNumber != null) {
        "S${episode.season} E${episode.episodeNumber}"
    } else {
        "E${episode.episodeNumber ?: 1}"
    }

    Box(
        modifier = Modifier
            .width(292.dp)
            .clip(RoundedCornerShape(18.dp))
            .glassPanel(
                cornerRadius = 18.dp,
                tint = Color.White.copy(alpha = 0.06f),
                rimAlpha = 0.38f
            )
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(158.dp)
                    .background(colors.surface)
            ) {
                if (req != null) {
                    AsyncImage(
                        model = req,
                        contentDescription = episode.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.55f to Color.Transparent,
                                1f to Color(0xDD10131C)
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .glassPanel(
                            cornerRadius = 8.dp,
                            tint = Color(0xAA0C0E14),
                            rimAlpha = 0.45f
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = epTag,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = episode.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!episode.description.isNullOrBlank()) {
                    Text(
                        text = episode.description!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        episode.runtimeMinutes?.takeIf { it > 0 }?.let { mins ->
                            Text(
                                text = formatRuntime(mins),
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textSecondary
                            )
                        }
                        episode.rating?.let { score ->
                            ImdbRatingBadge(rating = score)
                        }
                    }
                    episode.airDateEpochMs?.let { ms ->
                        Text(
                            text = formatAirDate(ms),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactEpisodeRow(
    episode: Episode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KestrelTheme.colors
    KestrelCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.PlayArrow,
                    contentDescription = "Play Episode",
                    tint = colors.accentAmber
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                val prefix = if (episode.season != null && episode.episodeNumber != null) {
                    "S${episode.season}E${episode.episodeNumber} • "
                } else ""
                Text(
                    text = "$prefix${episode.name}",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!episode.description.isNullOrBlank()) {
                    Text(
                        text = episode.description!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            episode.runtimeMinutes?.let { mins ->
                Text(
                    text = formatRuntime(mins),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textMuted
                )
            }
        }
    }
}

@Composable
private fun ShowDetailsTable(
    details: MediaDetails,
    modifier: Modifier = Modifier
) {
    val colors = KestrelTheme.colors
    val rows = buildList {
        details.showStatus?.takeIf { it.isNotBlank() }?.let { add("Status" to it) }
        details.year?.let { add("Release Info" to it.toString()) }
        details.contentRating?.let { add("Certification" to it) }
        details.durationMinutes?.takeIf { it > 0 }?.let { add("Runtime" to formatRuntime(it)) }
        add("Source" to details.providerName)
        if (details.tags.isNotEmpty()) {
            add("Genres" to details.tags.take(4).joinToString(", "))
        }
    }
    if (rows.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Show Details",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary
        )
        KestrelCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
        ) {
            rows.forEachIndexed { idx, (label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textSecondary
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
                if (idx < rows.lastIndex) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.09f))
                }
            }
        }
    }
}

@Composable
private fun SourcePickerSheet(
    title: String,
    episode: Episode?,
    streams: List<StreamSource>,
    providers: List<ProviderServerProgress>,
    selectedProviderId: String?,
    isResolving: Boolean,
    completedCount: Int,
    totalCount: Int,
    linkError: String?,
    onSelectFilter: (String?) -> Unit,
    onRefresh: () -> Unit,
    onSelect: (StreamSource) -> Unit
) {
    val colors = KestrelTheme.colors
    val visibleStreams = remember(streams, selectedProviderId) {
        if (selectedProviderId == null) streams
        else streams.filter { it.providerId == selectedProviderId }
    }
    val filterProviders = remember(providers) {
        providers.filter { it.isFetching || it.streams.isNotEmpty() }
    }
    val subtitleText = buildString {
        if (episode != null) {
            if (episode.season != null && episode.episodeNumber != null) {
                append("S${episode.season}E${episode.episodeNumber} • ")
            }
            append(episode.name)
            append(" • ")
        }
        append("${streams.size} server(s)")
        if (totalCount > 0) {
            append(" • $completedCount/$totalCount checked")
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "sheet_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                GlassCircleButton(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = "Refresh servers",
                    onClick = onRefresh,
                    diameter = 38.dp
                )
            }
        }

        if (filterProviders.isNotEmpty()) {
            item(key = "provider_filters") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item(key = "filter_all") {
                        GlassChip(
                            text = "All (${streams.size})",
                            selected = selectedProviderId == null,
                            onClick = { onSelectFilter(null) }
                        )
                    }
                    items(filterProviders, key = { it.providerId }) { prov ->
                        val suffix = if (prov.isFetching) "…" else "${prov.streams.size}"
                        GlassChip(
                            text = "${prov.providerName} ($suffix)",
                            selected = selectedProviderId == prov.providerId,
                            onClick = { onSelectFilter(prov.providerId) }
                        )
                    }
                }
            }
        }

        if (isResolving) {
            item(key = "resolving_banner") {
                KestrelCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = colors.accentAmber
                        )
                        Text(
                            text = if (totalCount > 0) {
                                "Checking more extensions ($completedCount/$totalCount)..."
                            } else {
                                "Searching servers..."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary
                        )
                    }
                }
            }
        }

        if (visibleStreams.isEmpty() && !isResolving) {
            item(key = "empty_streams") {
                KestrelCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                tint = colors.statusError
                            )
                            Text(
                                text = linkError ?: "No playable streams found",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.statusError
                            )
                        }
                        KestrelPrimaryButton(
                            text = "Retry Server Search",
                            onClick = onRefresh
                        )
                    }
                }
            }
        }

        itemsIndexed(visibleStreams, key = { idx, source -> "stream_${source.id}_$idx" }) { _, source ->
            KestrelCard(
                onClick = { onSelect(source) },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = source.extractorName,
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = source.providerName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SourceBadge(
                            label = source.qualityLabel,
                            accent = colors.accentAmber,
                            container = colors.accentAmberDim
                        )
                        SourceBadge(label = source.format.name)
                    }
                }
            }
        }

        val fetchingProviders = providers.filter { it.isFetching }
        if (fetchingProviders.isNotEmpty() && visibleStreams.isNotEmpty()) {
            item(key = "fetching_footer") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    fetchingProviders.take(4).forEach { prov ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = prov.providerName,
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Fetching...",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.accentTeal
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun buildSeasonList(details: MediaDetails): List<SeasonInfo> {
    val fromEpisodes = details.episodes
        .mapNotNull { it.season }
        .distinct()
        .sorted()
    if (fromEpisodes.isEmpty()) return emptyList()
    val infoByNum = details.seasons.associateBy { it.season }
    return fromEpisodes.map { seasonNum ->
        val existing = infoByNum[seasonNum]
        SeasonInfo(
            season = seasonNum,
            name = existing?.name ?: "Season $seasonNum",
            posterUrl = existing?.posterUrl
                ?: details.episodes.firstOrNull { it.season == seasonNum }?.posterUrl
                ?: details.posterUrl
        )
    }
}

private fun formatRuntime(mins: Int): String {
    val h = mins / 60
    val m = mins % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

private fun formatAirDate(epochMs: Long): String = runCatching {
    val fmt = SimpleDateFormat("d MMMM yyyy", Locale.US)
    fmt.format(Date(epochMs))
}.getOrDefault("")
