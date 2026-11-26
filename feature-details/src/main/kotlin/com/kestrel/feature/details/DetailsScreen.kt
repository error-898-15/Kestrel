// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import java.util.Locale
import com.kestrel.core.designsystem.EmptyStateView
import com.kestrel.core.designsystem.KestrelCard
import com.kestrel.core.designsystem.KestrelPrimaryButton
import com.kestrel.core.designsystem.KestrelTheme
import com.kestrel.core.designsystem.KestrelTopBar
import com.kestrel.core.designsystem.MediaPosterCard
import com.kestrel.core.designsystem.SectionHeader
import com.kestrel.core.designsystem.SourceBadge
import com.kestrel.domain.model.Episode
import com.kestrel.domain.model.MediaDetails
import com.kestrel.domain.model.MediaSummary
import com.kestrel.domain.model.StreamSource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.canvas,
        topBar = {
            KestrelTopBar(
                title = state.details?.title ?: "Details",
                subtitle = state.details?.providerName,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    state.details?.let {
                        val fav = state.libraryEntry?.isFavorite == true
                        IconButton(onClick = viewModel::toggleFavorite) {
                            Icon(
                                imageVector = if (fav) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                contentDescription = "Toggle Favorite",
                                tint = if (fav) colors.accentAmber else colors.textPrimary
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = colors.accentAmber)
                }
            }

            state.errorMessage != null || state.details == null -> {
                EmptyStateView(
                    icon = Icons.Outlined.ErrorOutline,
                    title = "Could not load title",
                    description = state.errorMessage ?: "Provider returned no metadata.",
                    actionLabel = "Retry",
                    onAction = viewModel::load,
                    modifier = Modifier.padding(padding)
                )
            }

            else -> {
                val details = state.details!!
                val seasons = details.episodes.mapNotNull { it.season }.distinct().sorted()
                val visibleEpisodes = if (seasons.isEmpty() || state.selectedSeason == null) {
                    details.episodes
                } else {
                    details.episodes.filter { it.season == state.selectedSeason }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        HeroHeader(
                            details = details,
                            hasResume = (state.libraryEntry?.lastPositionMs ?: 0L) > 0L,
                            isResolving = state.isResolvingLinks,
                            onPlay = { viewModel.resolveForPlay() }
                        )
                    }

                    if (!details.plot.isNullOrBlank()) {
                        item {
                            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                Text(
                                    text = details.plot!!,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.textSecondary
                                )
                            }
                        }
                    }

                    if (details.tags.isNotEmpty()) {
                        item {
                            FlowRow(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                details.tags.take(10).forEach { tag ->
                                    SourceBadge(
                                        label = tag,
                                        accent = colors.textSecondary,
                                        container = colors.surface
                                    )
                                }
                            }
                        }
                    }

                    if (state.linkError != null && state.resolvedLinks == null) {
                        item {
                            KestrelCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                            ) {
                                Text(
                                    text = state.linkError!!,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.statusError
                                )
                            }
                        }
                    }

                    if (details.episodes.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = "Episodes",
                                badge = "${details.episodes.size}",
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }

                        if (seasons.size > 1) {
                            item {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(seasons) { season ->
                                        FilterChip(
                                            selected = state.selectedSeason == season,
                                            onClick = { viewModel.selectSeason(season) },
                                            label = { Text("Season $season") },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = colors.accentAmberDim,
                                                selectedLabelColor = colors.accentAmber
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        items(visibleEpisodes, key = { it.id }) { ep ->
                            EpisodeCard(
                                episode = ep,
                                onClick = { viewModel.resolveForPlay(ep) },
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }

                    if (details.actors.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = "Cast",
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(details.actors) { actor ->
                                    KestrelCard(
                                        modifier = Modifier.width(140.dp),
                                        contentPadding = PaddingValues(10.dp)
                                    ) {
                                        Text(
                                            text = actor.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = colors.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (!actor.role.isNullOrBlank()) {
                                            Text(
                                                text = actor.role!!,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = colors.textSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (details.recommendations.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = "More Like This",
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(details.recommendations, key = { it.id }) { rec ->
                                    MediaPosterCard(
                                        title = rec.title,
                                        posterUrl = rec.posterUrl,
                                        sourceTag = rec.providerName,
                                        subtitle = rec.year?.toString(),
                                        onClick = { onOpenMedia(rec) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val resolved = state.resolvedLinks
        if (resolved != null && resolved.streams.isNotEmpty()) {
            ModalBottomSheet(
                onDismissRequest = viewModel::dismissSourcePicker,
                containerColor = colors.surfaceLow
            ) {
                SourcePickerSheet(
                    streams = resolved.streams,
                    onSelect = { source ->
                        viewModel.startPlayback(source)
                        onPlayStarted()
                    }
                )
            }
        }
    }
}

@Composable
private fun HeroHeader(
    details: MediaDetails,
    hasResume: Boolean,
    isResolving: Boolean,
    onPlay: () -> Unit
) {
    val colors = KestrelTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(250.dp)
    ) {
        if (!details.backdropUrl.isNullOrBlank()) {
            AsyncImage(
                model = details.backdropUrl,
                contentDescription = details.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0x55090B10), colors.canvas)
                    )
                )
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            if (!details.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = details.posterUrl,
                    contentDescription = details.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(96.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(10.dp))
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                SourceBadge(label = details.providerName)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = details.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                val metaParts = listOfNotNull(
                    details.year?.toString(),
                    details.durationMinutes?.let { "${it}m" },
                    details.rating?.let { String.format(Locale.US, "%.1f ★", it) }
                )
                if (metaParts.isNotEmpty()) {
                    Text(
                        text = metaParts.joinToString(" • "),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                KestrelPrimaryButton(
                    text = when {
                        isResolving -> "Finding streams..."
                        hasResume -> "Resume"
                        else -> "Play"
                    },
                    enabled = !isResolving,
                    onClick = onPlay
                )
            }
        }
    }
}

@Composable
private fun EpisodeCard(
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
                    .clip(RoundedCornerShape(8.dp))
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
                } else {
                    ""
                }
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
                    text = "${mins}m",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textMuted
                )
            }
        }
    }
}

@Composable
private fun SourcePickerSheet(
    streams: List<StreamSource>,
    onSelect: (StreamSource) -> Unit
) {
    val colors = KestrelTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Select Stream Source (${streams.size})",
            style = MaterialTheme.typography.titleLarge,
            color = colors.textPrimary
        )
        streams.forEach { source ->
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
                            color = colors.textSecondary
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
    }
}
