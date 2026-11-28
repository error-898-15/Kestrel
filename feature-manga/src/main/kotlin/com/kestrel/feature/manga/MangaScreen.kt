// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.manga

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.kestrel.core.designsystem.EmptyStateView
import com.kestrel.core.designsystem.GlassSearchField
import com.kestrel.core.designsystem.LocalGlassState
import com.kestrel.core.designsystem.kestrelNavClearance
import com.kestrel.core.designsystem.rememberGlassState
import com.kestrel.core.designsystem.rememberKestrelImageRequest
import com.kestrel.domain.model.MangaBrowseMode
import com.kestrel.domain.model.MangaProgressInfo
import com.kestrel.domain.model.MangaSectionRow
import com.kestrel.domain.model.MangaSourceDescriptor
import com.kestrel.domain.model.MangaSummary
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Calendar
import java.util.Locale

private val PlumBgTop = Color(0xFF151120)
private val PlumBgBottom = Color(0xFF0E0C14)
private val PlumSurface = Color(0xFF181422)
private val PlumSurfaceElevated = Color(0xFF221D30)
private val LavenderAccent = Color(0xFFC0A4E8)
private val LavenderBright = Color(0xFFE9DDFF)
private val TextMutedPlum = Color(0xFFA7A0B8)

@Composable
fun MangaScreen(
    onOpenMangaSourceSettings: () -> Unit,
    onOpenManga: (MangaSummary) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MangaViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navClearance = kestrelNavClearance()
    val mangaGlass = rememberGlassState()
    var searchExpanded by rememberSaveable { mutableStateOf(false) }

    val sources = remember(state.installedExtensions) {
        state.installedExtensions
            .flatMap { ext ->
                if (ext.sources.isNotEmpty()) {
                    ext.sources.filter { !it.isVideo }
                } else {
                    listOf(MangaSourceDescriptor(ext.id, ext.name, ext.lang))
                }
            }
            .distinctBy { it.id }
    }
    val duplicateSourceNames = remember(sources) { sources.groupingBy { it.name }.eachCount() }

    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when {
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    val isOverviewMode = state.selectedSourceId == null &&
        state.query.isBlank() &&
        state.seeAllTitle == null

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(PlumBgTop, PlumBgBottom)
                )
            )
    ) {
        CompositionLocalProvider(LocalGlassState provides mangaGlass) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                // Top Header matching reference design
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(LavenderAccent.copy(alpha = 0.22f))
                                .border(1.dp, LavenderAccent.copy(alpha = 0.45f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AutoStories,
                                contentDescription = null,
                                tint = LavenderBright,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Manga",
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Text(
                                text = if (state.installedExtensions.isEmpty()) {
                                    "Install sources in Settings -> Manga Source"
                                } else {
                                    "$greeting · ${sources.size} provider${if (sources.size == 1) "" else "s"}"
                                },
                                color = TextMutedPlum,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlumCircleAction(
                            onClick = {
                                if (searchExpanded && state.query.isNotBlank()) {
                                    viewModel.onQueryChanged("")
                                    viewModel.loadCatalog()
                                }
                                searchExpanded = !searchExpanded
                            }
                        ) {
                            Icon(
                                imageVector = if (searchExpanded) Icons.Outlined.Close else Icons.Outlined.Search,
                                contentDescription = "Search manga",
                                tint = LavenderBright,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        PlumCircleAction(onClick = onOpenMangaSourceSettings) {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = "Manga Source settings",
                                tint = LavenderAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                AnimatedVisibility(visible = searchExpanded || state.query.isNotBlank()) {
                    GlassSearchField(
                        value = state.query,
                        onValueChange = viewModel::onQueryChanged,
                        placeholder = "Search manga, manhwa, webtoons...",
                        onSearch = viewModel::loadCatalog,
                        onClear = {
                            viewModel.onQueryChanged("")
                            viewModel.loadCatalog()
                        },
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
                    )
                }

                // Provider Filter Chips
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        PlumFilterChip(
                            text = "All Sources",
                            selected = state.selectedSourceId == null && state.seeAllTitle == null,
                            onClick = { viewModel.selectSource(null) }
                        )
                    }
                    items(sources, key = { it.id }) { src ->
                        val chipLabel = if ((duplicateSourceNames[src.name] ?: 0) > 1) {
                            "${src.name} (${src.lang.uppercase()})"
                        } else {
                            src.name
                        }
                        PlumFilterChip(
                            text = chipLabel,
                            selected = state.selectedSourceId == src.id,
                            onClick = { viewModel.selectSource(src.id) }
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when {
                        state.isLoading -> {
                            CircularProgressIndicator(
                                color = LavenderAccent,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }

                        state.error != null -> {
                            EmptyStateView(
                                icon = Icons.Outlined.ErrorOutline,
                                title = "Source error",
                                description = state.error.orEmpty(),
                                actionLabel = "Retry",
                                onAction = viewModel::loadCatalog
                            )
                        }

                        state.installedExtensions.isEmpty() -> {
                            EmptyStateView(
                                icon = Icons.Outlined.AutoStories,
                                title = "No manga sources installed",
                                description = "Install an Aniyomi manga extension in Settings -> Manga Source to browse and read chapters.",
                                actionLabel = "Open Manga Source",
                                onAction = onOpenMangaSourceSettings
                            )
                        }

                        isOverviewMode -> {
                            if (state.sectionRows.isEmpty() && state.items.isEmpty() && state.libraryEntries.isEmpty()) {
                                EmptyStateView(
                                    icon = Icons.Outlined.AutoStories,
                                    title = "No results from installed sources",
                                    description = "Try refreshing or check your enabled extensions in Settings -> Manga Source.",
                                    actionLabel = "Retry",
                                    onAction = viewModel::loadCatalog
                                )
                            } else {
                                MangaOverviewFeed(
                                    heroItems = state.items.take(6),
                                    libraryEntries = state.libraryEntries,
                                    sectionRows = state.sectionRows,
                                    bottomPadding = navClearance + 22.dp,
                                    onOpenManga = onOpenManga,
                                    onSeeAll = viewModel::openSeeAll
                                )
                            }
                        }

                        else -> {
                            val activeSourceName = sources.firstOrNull { it.id == state.selectedSourceId }?.name
                            Column(modifier = Modifier.fillMaxSize()) {
                                // Grid Header & Popular/Latest Toggle
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 18.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        if (state.seeAllTitle != null) {
                                            PlumCircleAction(
                                                size = 32.dp,
                                                onClick = viewModel::closeSeeAll
                                            ) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                                    contentDescription = "Back to all sections",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = state.seeAllTitle
                                                ?: activeSourceName
                                                ?: if (state.query.isNotBlank()) "Search Results" else "Catalog",
                                            color = Color.White,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    if (state.query.isBlank() && state.seeAllTitle == null) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            PlumModePill(
                                                label = "Popular",
                                                selected = state.browseMode == MangaBrowseMode.POPULAR,
                                                onClick = { viewModel.selectBrowseMode(MangaBrowseMode.POPULAR) }
                                            )
                                            PlumModePill(
                                                label = "Latest",
                                                selected = state.browseMode == MangaBrowseMode.LATEST,
                                                onClick = { viewModel.selectBrowseMode(MangaBrowseMode.LATEST) }
                                            )
                                        }
                                    }
                                }

                                if (state.items.isEmpty()) {
                                    EmptyStateView(
                                        icon = Icons.Outlined.AutoStories,
                                        title = "No results from this source",
                                        description = "Try another search query, switch provider, or toggle Popular / Latest.",
                                        actionLabel = "Retry",
                                        onAction = viewModel::loadCatalog
                                    )
                                } else {
                                    MangaPaginatedGrid(
                                        items = state.items,
                                        hasNextPage = state.hasNextPage,
                                        isLoadingMore = state.isLoadingMore,
                                        bottomPadding = navClearance + 22.dp,
                                        onLoadMore = viewModel::loadNextPage,
                                        onOpenManga = onOpenManga
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

@Composable
private fun MangaOverviewFeed(
    heroItems: List<MangaSummary>,
    libraryEntries: List<MangaProgressInfo>,
    sectionRows: List<MangaSectionRow>,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onOpenManga: (MangaSummary) -> Unit,
    onSeeAll: (MangaSectionRow) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 6.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        if (heroItems.isNotEmpty()) {
            item(key = "hero_carousel") {
                MangaHeroCarousel(
                    items = heroItems,
                    onOpenManga = onOpenManga
                )
            }
        }

        if (libraryEntries.isNotEmpty()) {
            item(key = "continue_reading") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MangaSectionTitleRow(
                        title = "Continue Reading",
                        subtitle = "${libraryEntries.size} in library",
                        onSeeAll = null
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(libraryEntries, key = { it.mangaId }) { entry ->
                            ContinueReadingCard(
                                entry = entry,
                                onClick = {
                                    onOpenManga(
                                        MangaSummary(
                                            id = entry.mangaId,
                                            extensionId = entry.extensionId,
                                            sourceId = entry.sourceId,
                                            sourceName = entry.sourceName,
                                            title = entry.title,
                                            url = entry.url,
                                            coverUrl = entry.coverUrl
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

        items(sectionRows, key = { it.id }) { row ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MangaSectionTitleRow(
                    title = row.title,
                    subtitle = if (row.sourceId != null) row.sourceName else null,
                    onSeeAll = { onSeeAll(row) }
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(row.items.distinctBy { it.id }, key = { "${row.id}_${it.id}" }) { manga ->
                        MangaPosterTile(
                            manga = manga,
                            modifier = Modifier.width(114.dp),
                            onClick = { onOpenManga(manga) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MangaHeroCarousel(
    items: List<MangaSummary>,
    onOpenManga: (MangaSummary) -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { items.size })
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 18.dp),
            pageSpacing = 12.dp,
            modifier = Modifier.fillMaxWidth()
        ) { pageIdx ->
            val manga = items[pageIdx]
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(228.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(PlumSurface)
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(22.dp))
                    .clickable { onOpenManga(manga) }
            ) {
                MangaCoverImage(
                    url = manga.coverUrl,
                    headers = manga.coverHeaders,
                    contentDescription = manga.title,
                    width = 720,
                    height = 480,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0x28100C18),
                                    Color(0x88100C18),
                                    Color(0xF20E0C14)
                                )
                            )
                        )
                )
                // Top-right rating/source pill + open arrow
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xBB181422))
                            .border(1.dp, LavenderAccent.copy(alpha = 0.35f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (manga.rating != null) {
                                Icon(
                                    imageVector = Icons.Outlined.Star,
                                    contentDescription = null,
                                    tint = Color(0xFFF5C518),
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = String.format(Locale.US, "%.1f", manga.rating),
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            } else {
                                Text(
                                    text = manga.sourceName,
                                    color = LavenderBright,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(0xBB181422))
                            .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.NorthEast,
                            contentDescription = "Open",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                // Bottom title & description
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = manga.title,
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = manga.description?.takeIf { it.isNotBlank() }
                        ?: listOfNotNull(manga.author?.takeIf { it.isNotBlank() }, manga.sourceName).joinToString(" · ")
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            color = TextMutedPlum,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        if (items.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(items.size) { index ->
                    val selected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .height(6.dp)
                            .width(if (selected) 20.dp else 6.dp)
                            .clip(CircleShape)
                            .background(if (selected) LavenderAccent else Color(0xFF423B54))
                    )
                }
            }
        }
    }
}

@Composable
private fun ContinueReadingCard(
    entry: MangaProgressInfo,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(126.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(PlumSurface)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(PlumSurfaceElevated)
        ) {
            MangaCoverImage(
                url = entry.coverUrl,
                headers = emptyMap(),
                contentDescription = entry.title,
                width = 260,
                height = 390,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC151120))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = entry.sourceName,
                    color = LavenderBright,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
        Text(
            text = entry.title,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = entry.lastChapterName ?: "Continue reading",
            color = TextMutedPlum,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val progress = if (entry.totalPages > 0) {
            ((entry.lastPageRead + 1).toFloat() / entry.totalPages.toFloat()).coerceIn(0f, 1f)
        } else {
            0.25f
        }
        LinearProgressIndicator(
            progress = { progress },
            color = LavenderAccent,
            trackColor = Color(0xFF312A42),
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(CircleShape)
        )
    }
}

@Composable
private fun MangaPaginatedGrid(
    items: List<MangaSummary>,
    hasNextPage: Boolean,
    isLoadingMore: Boolean,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onLoadMore: () -> Unit,
    onOpenManga: (MangaSummary) -> Unit
) {
    val gridState = rememberLazyGridState()
    val distinctItems = remember(items) { items.distinctBy { it.id } }

    LaunchedEffect(gridState, distinctItems.size, hasNextPage, isLoadingMore) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible to info.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (lastVisible, total) ->
                if (total > 0 && lastVisible >= total - 6 && hasNextPage && !isLoadingMore) {
                    onLoadMore()
                }
            }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 6.dp,
            bottom = bottomPadding
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(distinctItems, key = { it.id }) { manga ->
            MangaPosterTile(
                manga = manga,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onOpenManga(manga) }
            )
        }

        if (isLoadingMore || (!hasNextPage && distinctItems.isNotEmpty())) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "grid_footer") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoadingMore) {
                        CircularProgressIndicator(
                            color = LavenderAccent,
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Text(
                            text = "That's everything",
                            color = TextMutedPlum,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MangaPosterTile(
    manga: MangaSummary,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(14.dp))
                .background(PlumSurface)
                .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(14.dp))
        ) {
            MangaCoverImage(
                url = manga.coverUrl,
                headers = manga.coverHeaders,
                contentDescription = manga.title,
                width = 300,
                height = 450,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0x990E0C14)),
                            startY = 160f
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC161221))
                    .border(1.dp, LavenderAccent.copy(alpha = 0.28f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = manga.sourceName.uppercase(Locale.US),
                    color = LavenderBright,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = manga.title,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 15.sp
        )
        manga.author?.takeIf { it.isNotBlank() }?.let { author ->
            Text(
                text = author,
                color = TextMutedPlum,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MangaSectionTitleRow(
    title: String,
    subtitle: String?,
    onSeeAll: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = TextMutedPlum,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (onSeeAll != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onSeeAll)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "See All",
                    color = LavenderAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = "See all",
                    tint = LavenderAccent,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun PlumCircleAction(
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 38.dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(PlumSurfaceElevated)
            .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun PlumFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) LavenderAccent.copy(alpha = 0.24f) else PlumSurface)
            .border(
                width = 1.dp,
                color = if (selected) LavenderAccent else Color.White.copy(alpha = 0.09f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            color = if (selected) LavenderBright else TextMutedPlum,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun PlumModePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) LavenderAccent else PlumSurfaceElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            color = if (selected) Color(0xFF140F1D) else TextMutedPlum,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun MangaCoverImage(
    url: String?,
    headers: Map<String, String>,
    contentDescription: String,
    width: Int,
    height: Int,
    modifier: Modifier = Modifier
) {
    val request = rememberKestrelImageRequest(url, headers, width, height)
    if (request != null) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier.background(PlumSurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoStories,
                contentDescription = null,
                tint = TextMutedPlum.copy(alpha = 0.5f),
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
