// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.kestrel.core.designsystem.EmptyStateView
import com.kestrel.core.designsystem.GlassChip
import com.kestrel.core.designsystem.GlassCircleButton
import com.kestrel.core.designsystem.KestrelBackdrop
import com.kestrel.core.designsystem.KestrelBackground
import com.kestrel.core.designsystem.KestrelCard
import com.kestrel.core.designsystem.KestrelTheme
import com.kestrel.core.designsystem.LocalGlassState
import com.kestrel.core.designsystem.MediaPosterCard
import com.kestrel.core.designsystem.SectionHeader
import com.kestrel.core.designsystem.glassPanel
import com.kestrel.core.designsystem.glassSource
import com.kestrel.core.designsystem.kestrelNavClearance
import com.kestrel.core.designsystem.rememberGlassState
import com.kestrel.core.designsystem.rememberKestrelImageRequest
import com.kestrel.domain.model.MediaSummary
import com.kestrel.domain.model.MediaType
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    onOpenExtensions: () -> Unit,
    onOpenMedia: (MediaSummary) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = KestrelTheme.colors
    val navClearance = kestrelNavClearance()
    val heroHeight = (LocalConfiguration.current.screenHeightDp * 0.60f).dp
    val homeGlass = rememberGlassState()

    val heroItems = remember(state.rows) {
        state.rows
            .flatMap { it.items }
            .filter { !it.posterUrl.isNullOrBlank() }
            .distinctBy { "${it.providerId}:${it.id}" }
            .take(8)
    }

    KestrelBackground(
        modifier = modifier.fillMaxSize(),
        style = KestrelBackdrop.Dark
    ) {
        if (state.providers.isEmpty()) {
            if (state.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = navClearance),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = colors.accentAmber)
                }
            } else {
                EmptyStateView(
                    icon = Icons.Outlined.Explore,
                    title = "No active sources",
                    description = "Kestrel ships with zero built-in content. Install and enable a CloudStream .cs3, Nuvio, or Stremio repository in Extensions to browse catalogs.",
                    actionLabel = "Manage Extensions",
                    onAction = onOpenExtensions,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(bottom = navClearance)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .glassSource(homeGlass),
                contentPadding = PaddingValues(bottom = navClearance),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                if (heroItems.isNotEmpty()) {
                    item(key = "hero", contentType = "hero") {
                        HeroCarousel(
                            items = heroItems,
                            height = heroHeight,
                            onOpen = onOpenMedia
                        )
                    }
                } else {
                    item(key = "top_spacer", contentType = "spacer") {
                        Spacer(
                            modifier = Modifier
                                .statusBarsPadding()
                                .height(68.dp)
                        )
                    }
                }

                if (state.providerErrors.isNotEmpty()) {
                    item(key = "provider_errors", contentType = "error_banner") {
                        KestrelCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
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

                if (state.isLoading && state.rows.isEmpty()) {
                    item(key = "loading_rows", contentType = "loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = colors.accentAmber)
                        }
                    }
                } else if (state.rows.isEmpty()) {
                    item(key = "empty_rows", contentType = "empty") {
                        Box(modifier = Modifier.height(260.dp)) {
                            EmptyStateView(
                                icon = Icons.Outlined.Explore,
                                title = "No catalog rows returned",
                                description = "Active providers returned no items for this selection. Try refreshing or switching sources."
                            )
                        }
                    }
                } else {
                    itemsIndexed(
                        items = state.rows,
                        key = { index, row -> "${row.id}_$index" },
                        contentType = { _, _ -> "catalog_row" }
                    ) { _, row ->
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionHeader(
                                title = row.title,
                                modifier = Modifier.padding(horizontal = 20.dp),
                                trailing = {
                                    GlassCircleButton(
                                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = "Only ${row.providerName}",
                                        onClick = { viewModel.selectProvider(row.providerId) },
                                        diameter = 36.dp,
                                        modifier = Modifier.padding(start = 12.dp)
                                    )
                                }
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                itemsIndexed(
                                    items = row.items,
                                    key = { idx, item -> "${item.id}_$idx" },
                                    contentType = { _, _ -> "poster_card" }
                                ) { _, item ->
                                    MediaPosterCard(
                                        title = item.title,
                                        posterUrl = item.posterUrl,
                                        sourceTag = item.qualityLabel ?: item.type.name,
                                        subtitle = item.year?.toString(),
                                        posterHeaders = item.posterHeaders,
                                        onClick = { onOpenMedia(item) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Floating liquid-glass source chips + refresh, always accessible over content.
            CompositionLocalProvider(LocalGlassState provides homeGlass) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .statusBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LazyRow(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(start = 20.dp, end = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item(key = "all_sources", contentType = "chip") {
                                GlassChip(
                                    text = "All",
                                    selected = state.selectedProviderId == null,
                                    onClick = { viewModel.selectProvider(null) }
                                )
                            }
                            items(state.providers, key = { it.providerId }, contentType = { "chip" }) { provider ->
                                GlassChip(
                                    text = provider.name,
                                    selected = state.selectedProviderId == provider.providerId,
                                    onClick = { viewModel.selectProvider(provider.providerId) }
                                )
                            }
                        }
                        GlassCircleButton(
                            icon = Icons.Outlined.Refresh,
                            contentDescription = "Refresh Catalogs",
                            onClick = viewModel::refreshCatalogs,
                            tint = colors.accentAmber,
                            modifier = Modifier.padding(end = 20.dp)
                        )
                    }
                    if (state.isLoading && state.rows.isNotEmpty()) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            color = colors.accentAmber,
                            trackColor = Color.Transparent
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroCarousel(
    items: List<MediaSummary>,
    height: Dp,
    onOpen: (MediaSummary) -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { items.size })
    LaunchedEffect(items.size) {
        if (items.size > 1) {
            while (true) {
                delay(5500)
                val next = (pagerState.currentPage + 1) % items.size
                pagerState.animateScrollToPage(next)
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            HeroPage(item = items[page], onOpen = onOpen)
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(items.size) { index ->
                val active = pagerState.currentPage == index
                val dotWidth by animateDpAsState(
                    targetValue = if (active) 26.dp else 7.dp,
                    label = "heroDotWidth"
                )
                Box(
                    modifier = Modifier
                        .height(7.dp)
                        .width(dotWidth)
                        .clip(CircleShape)
                        .background(if (active) Color.White else Color.White.copy(alpha = 0.4f))
                )
            }
        }
    }
}

@Composable
private fun HeroPage(
    item: MediaSummary,
    onOpen: (MediaSummary) -> Unit
) {
    val colors = KestrelTheme.colors
    val request = rememberKestrelImageRequest(item.posterUrl, item.posterHeaders, 900, 1350)
    val metaText = listOfNotNull(item.type.heroLabel(), item.year?.toString()).joinToString("  \u2022  ")
    val rating = item.rating?.takeIf { it > 0.0 }
    Box(modifier = Modifier.fillMaxSize()) {
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = item.title,
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
                        0f to Color(0x99000000),
                        0.28f to Color.Transparent,
                        0.5f to Color(0x55090B10),
                        0.85f to Color(0xEE090B10),
                        1f to Color(0xFF090B10)
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 28.dp)
                .padding(bottom = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = item.title,
                style = TextStyle(
                    fontSize = 32.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp
                ),
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (metaText.isNotBlank()) {
                    Text(
                        text = metaText,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1
                    )
                }
                if (rating != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.accentAmber)
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "\u2605 " + "%.1f".format(rating),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color(0xFF1A1300),
                            maxLines = 1
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .glassPanel(
                        cornerRadius = 26.dp,
                        tint = Color.White.copy(alpha = 0.18f),
                        rimColor = Color.White,
                        rimAlpha = 0.65f,
                        state = null
                    )
                    .clickable { onOpen(item) }
                    .padding(horizontal = 36.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "View Details",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1
                )
            }
        }
    }
}

private fun MediaType.heroLabel(): String = when (this) {
    MediaType.MOVIE -> "Movie"
    MediaType.TV_SERIES -> "Series"
    MediaType.ANIME -> "Anime"
    MediaType.LIVE -> "Live"
    MediaType.OVA -> "OVA"
    MediaType.CARTOON -> "Cartoon"
    MediaType.DOCUMENTARY -> "Documentary"
    MediaType.OTHER -> "Other"
}
