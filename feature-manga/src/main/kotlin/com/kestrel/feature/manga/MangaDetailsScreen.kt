// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.manga

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.kestrel.core.designsystem.EmptyStateView
import com.kestrel.core.designsystem.rememberKestrelImageRequest
import com.kestrel.domain.model.MangaChapter
import com.kestrel.domain.model.MangaDetails
import com.kestrel.domain.model.MangaSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DetailsBg = Color(0xFF0E0C14)
private val DetailsCard = Color(0xFF181422)
private val DetailsCardElevated = Color(0xFF221D30)
private val DetailsLavender = Color(0xFFC0A4E8)
private val DetailsLavenderBright = Color(0xFFE9DDFF)
private val DetailsTextMuted = Color(0xFFA7A0B8)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MangaDetailsScreen(
    onBack: () -> Unit,
    onReadChapter: (extensionId: String, sourceId: String, mangaUrl: String, chapterUrl: String) -> Unit,
    onOpenManga: (MangaSummary) -> Unit = {},
    viewModel: MangaDetailsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var selectedTab by rememberSaveable { mutableIntStateOf(1) } // 0 = Info, 1 = Read (default), 2 = More
    var showDownloadedOnly by rememberSaveable { mutableStateOf(false) }
    var showUnreadOnly by rememberSaveable { mutableStateOf(false) }
    var sortDescending by rememberSaveable { mutableStateOf(true) }
    var synopsisExpanded by rememberSaveable { mutableStateOf(false) }
    var showWrongTitleSheet by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DetailsBg)
    ) {
        val details = state.details
        when {
            state.isLoading -> {
                CircularProgressIndicator(
                    color = DetailsLavender,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            details == null -> {
                EmptyStateView(
                    icon = Icons.Outlined.ErrorOutline,
                    title = "Failed to load manga",
                    description = state.error ?: "Unable to fetch details from this source.",
                    actionLabel = "Retry",
                    onAction = viewModel::load
                )
            }

            else -> {
                val filteredChapters = remember(
                    details.chapters,
                    state.downloadedChapterIds,
                    showDownloadedOnly,
                    showUnreadOnly,
                    sortDescending
                ) {
                    val base = details.chapters.distinctBy { it.id }.filter { ch ->
                        val dlOk = !showDownloadedOnly || (ch.id in state.downloadedChapterIds)
                        val unreadOk = !showUnreadOnly || !ch.isRead
                        dlOk && unreadOk
                    }
                    if (sortDescending) base else base.reversed()
                }

                val resumeChapter = remember(details.chapters, state.progress) {
                    val lastId = state.progress?.lastChapterId
                    details.chapters.firstOrNull { it.id == lastId }
                        ?: details.chapters.lastOrNull()
                        ?: details.chapters.firstOrNull()
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 176.dp)
                ) {
                    // Blurred Header Backdrop + Floating Top Actions + Hero Poster
                    item(key = "hero_header") {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            // Backdrop image fading into DetailsBg
                            MangaDetailsImage(
                                url = details.summary.coverUrl,
                                headers = details.summary.coverHeaders,
                                contentDescription = null,
                                width = 540,
                                height = 760,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(330.dp)
                                    .blur(20.dp)
                                    .alpha(0.36f)
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(330.dp)
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                Color(0x88151120),
                                                Color(0xD60E0C14),
                                                DetailsBg
                                            )
                                        )
                                    )
                            )

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(18.dp)
                            ) {
                                // Floating Top Navigation Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xCC1B1726))
                                            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                                            .clickable(onClick = onBack),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                            contentDescription = "Back",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(Color(0xCC1B1726))
                                            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                                            .padding(horizontal = 6.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        IconButton(
                                            onClick = {
                                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_SUBJECT, details.summary.title)
                                                    putExtra(
                                                        Intent.EXTRA_TEXT,
                                                        "${details.summary.title}\n${details.summary.url}"
                                                    )
                                                }
                                                context.startActivity(Intent.createChooser(shareIntent, "Share manga"))
                                            },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Share,
                                                contentDescription = "Share",
                                                tint = Color.White,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = viewModel::toggleFavorite,
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (details.isFavorite) {
                                                    Icons.Outlined.Bookmark
                                                } else {
                                                    Icons.Outlined.BookmarkBorder
                                                },
                                                contentDescription = "Bookmark",
                                                tint = if (details.isFavorite) DetailsLavender else Color.White,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = onBack,
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Close,
                                                contentDescription = "Close",
                                                tint = Color.White,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }

                                // Hero Poster + Metadata + Custom List Button
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(118.dp)
                                            .height(170.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(DetailsCard)
                                            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
                                    ) {
                                        MangaDetailsImage(
                                            url = details.summary.coverUrl,
                                            headers = details.summary.coverHeaders,
                                            contentDescription = details.summary.title,
                                            width = 360,
                                            height = 516,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val authorName = details.summary.author?.takeIf { it.isNotBlank() }
                                            ?: details.artist?.takeIf { it.isNotBlank() }
                                        if (authorName != null) {
                                            Row(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(Color.White.copy(alpha = 0.08f))
                                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Edit,
                                                    contentDescription = null,
                                                    tint = DetailsLavenderBright,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Text(
                                                    text = authorName,
                                                    color = DetailsLavenderBright,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }

                                        val statusText = details.summary.status?.takeIf { it.isNotBlank() } ?: "Ongoing"
                                        val statusColor = when {
                                            statusText.contains("comp", ignoreCase = true) -> Color(0xFF60A5FA)
                                            statusText.contains("hiatus", ignoreCase = true) ||
                                                statusText.contains("cancel", ignoreCase = true) -> Color(0xFFFBBF24)
                                            else -> Color(0xFF34D399)
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .clip(CircleShape)
                                                    .background(statusColor)
                                            )
                                            Text(
                                                text = statusText,
                                                color = DetailsTextMuted,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }

                                        Text(
                                            text = details.summary.title,
                                            color = Color.White,
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            lineHeight = 25.sp,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(
                                                    if (details.isFavorite) {
                                                        DetailsLavender.copy(alpha = 0.24f)
                                                    } else {
                                                        DetailsCardElevated
                                                    }
                                                )
                                                .border(
                                                    width = 1.dp,
                                                    color = if (details.isFavorite) {
                                                        DetailsLavender
                                                    } else {
                                                        Color.White.copy(alpha = 0.12f)
                                                    },
                                                    shape = RoundedCornerShape(12.dp)
                                                )
                                                .clickable(onClick = viewModel::toggleFavorite)
                                                .padding(vertical = 10.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (details.isFavorite) {
                                                        Icons.Outlined.Bookmark
                                                    } else {
                                                        Icons.Outlined.BookmarkBorder
                                                    },
                                                    contentDescription = null,
                                                    tint = if (details.isFavorite) DetailsLavenderBright else Color.White,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = if (details.isFavorite) "In Custom List" else "Custom List",
                                                    color = if (details.isFavorite) DetailsLavenderBright else Color.White,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Tab Body
                    when (selectedTab) {
                        0 -> {
                            // INFO TAB
                            item(key = "info_progress") {
                                InfoProgressCard(
                                    details = details,
                                    readCount = state.progress?.readChapterIds?.size ?: 0,
                                    lastChapterName = state.progress?.lastChapterName,
                                    onStartOrContinue = {
                                        resumeChapter?.let { ch ->
                                            onReadChapter(
                                                viewModel.extensionId,
                                                viewModel.sourceId,
                                                viewModel.mangaUrl,
                                                ch.url
                                            )
                                        }
                                    }
                                )
                            }

                            item(key = "info_grid") {
                                InfoMetadataGrid(details = details)
                            }

                            details.description?.takeIf { it.isNotBlank() }?.let { desc ->
                                item(key = "info_synopsis") {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(DetailsCard)
                                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                                            .padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Synopsis",
                                            color = Color.White,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = desc,
                                            color = DetailsTextMuted,
                                            fontSize = 13.sp,
                                            lineHeight = 19.sp,
                                            maxLines = if (synopsisExpanded) Int.MAX_VALUE else 4,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = if (synopsisExpanded) "Show less" else "Read more",
                                            color = DetailsLavender,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.clickable { synopsisExpanded = !synopsisExpanded }
                                        )
                                    }
                                }
                            }

                            if (details.genres.isNotEmpty()) {
                                item(key = "info_genres") {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Genres",
                                            color = Color.White,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            details.genres.forEach { genre ->
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(DetailsCardElevated)
                                                        .border(
                                                            1.dp,
                                                            Color.White.copy(alpha = 0.08f),
                                                            RoundedCornerShape(10.dp)
                                                        )
                                                        .padding(horizontal = 11.dp, vertical = 6.dp)
                                                ) {
                                                    Text(
                                                        text = genre,
                                                        color = DetailsLavenderBright,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (state.recommendations.isNotEmpty()) {
                                item(key = "info_more") {
                                    RecommendationsStrip(
                                        sourceName = details.summary.sourceName,
                                        recommendations = state.recommendations,
                                        onOpenManga = onOpenManga
                                    )
                                }
                            }
                        }

                        1 -> {
                            // READ TAB (Default, matching reference video)
                            item(key = "active_source_card") {
                                ActiveSourceCard(
                                    details = details,
                                    onWrongTitle = {
                                        showWrongTitleSheet = true
                                        if (state.wrongTitleResults.isEmpty()) {
                                            viewModel.searchAlternateTitle(details.summary.title)
                                        }
                                    }
                                )
                            }

                            item(key = "chapters_toolbar") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            DetailsSegmentPill(
                                                label = "Online ${details.chapters.size}",
                                                selected = !showDownloadedOnly,
                                                onClick = { showDownloadedOnly = false }
                                            )
                                            DetailsSegmentPill(
                                                label = "Downloaded ${state.downloadedChapterIds.size}",
                                                selected = showDownloadedOnly,
                                                onClick = { showDownloadedOnly = true }
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            DetailsSquareAction(
                                                icon = Icons.Outlined.Download,
                                                contentDescription = "Download all chapters",
                                                onClick = viewModel::downloadAllChapters
                                            )
                                            DetailsSquareAction(
                                                icon = Icons.Outlined.SwapVert,
                                                contentDescription = "Sort chapters",
                                                onClick = { sortDescending = !sortDescending }
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            DetailsMiniChip(
                                                label = "All",
                                                selected = !showUnreadOnly,
                                                onClick = { showUnreadOnly = false }
                                            )
                                            DetailsMiniChip(
                                                label = "Unread",
                                                selected = showUnreadOnly,
                                                onClick = { showUnreadOnly = true }
                                            )
                                        }
                                        Text(
                                            text = "${filteredChapters.size} chapters",
                                            color = DetailsTextMuted,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }

                            if (filteredChapters.isEmpty()) {
                                item(key = "empty_chapters") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (showDownloadedOnly) {
                                                "No downloaded chapters yet"
                                            } else {
                                                "No chapters match this filter"
                                            },
                                            color = DetailsTextMuted,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            } else {
                                items(filteredChapters, key = { it.id }) { chapter ->
                                    ChapterRowCard(
                                        chapter = chapter,
                                        isCurrent = chapter.id == state.progress?.lastChapterId,
                                        isDownloaded = chapter.id in state.downloadedChapterIds,
                                        isDownloading = chapter.id in state.downloadingChapterIds,
                                        onClick = {
                                            onReadChapter(
                                                viewModel.extensionId,
                                                viewModel.sourceId,
                                                viewModel.mangaUrl,
                                                chapter.url
                                            )
                                        },
                                        onDownloadClick = { viewModel.downloadChapter(chapter) },
                                        onDeleteDownloadClick = { viewModel.deleteDownloadedChapter(chapter.id) }
                                    )
                                }
                            }
                        }

                        else -> {
                            // MORE TAB
                            item(key = "more_tab_header") {
                                if (state.recommendations.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(32.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "No related manga suggestions available from this source.",
                                            color = DetailsTextMuted,
                                            fontSize = 13.sp
                                        )
                                    }
                                } else {
                                    RecommendationsStrip(
                                        sourceName = details.summary.sourceName,
                                        recommendations = state.recommendations,
                                        onOpenManga = onOpenManga
                                    )
                                }
                            }
                        }
                    }
                }

                // Bottom Floating Area: Continue Reading Mini-Pill + [ Info | Read | More ] Floating Bar
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (resumeChapter != null) {
                        val prog = state.progress
                        val fraction = if (prog != null && prog.totalPages > 0) {
                            ((prog.lastPageRead + 1).toFloat() / prog.totalPages.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color(0xF01C1828))
                                .border(1.dp, DetailsLavender.copy(alpha = 0.28f), RoundedCornerShape(18.dp))
                                .clickable {
                                    onReadChapter(
                                        viewModel.extensionId,
                                        viewModel.sourceId,
                                        viewModel.mangaUrl,
                                        resumeChapter.url
                                    )
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(34.dp)
                                    .height(46.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(DetailsCardElevated)
                            ) {
                                MangaDetailsImage(
                                    url = details.summary.coverUrl,
                                    headers = details.summary.coverHeaders,
                                    contentDescription = null,
                                    width = 120,
                                    height = 170,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = resumeChapter.name,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (prog != null && prog.totalPages > 0) {
                                        "Continue reading · Page ${prog.lastPageRead + 1} of ${prog.totalPages}"
                                    } else {
                                        "Tap to start reading"
                                    },
                                    color = DetailsTextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                                LinearProgressIndicator(
                                    progress = { if (fraction > 0f) fraction else 0.08f },
                                    color = DetailsLavender,
                                    trackColor = Color(0xFF342D47),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(3.dp)
                                        .clip(CircleShape)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(DetailsLavender),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.PlayArrow,
                                    contentDescription = "Read chapter",
                                    tint = Color(0xFF140F1D),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Floating Bottom [ Info | Read | More ] Tab Capsule
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xF51A1625))
                            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FloatingTabItem(
                            icon = Icons.Outlined.Info,
                            label = "Info",
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 }
                        )
                        FloatingTabItem(
                            icon = Icons.AutoMirrored.Outlined.MenuBook,
                            label = "Read",
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 }
                        )
                        FloatingTabItem(
                            icon = Icons.Outlined.AutoAwesome,
                            label = "More",
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 }
                        )
                    }
                }
            }
        }
    }

    if (showWrongTitleSheet && details != null) {
        WrongTitleSearchSheet(
            initialQuery = details.summary.title,
            results = state.wrongTitleResults,
            isSearching = state.isSearchingWrongTitle,
            onSearch = viewModel::searchAlternateTitle,
            onSelectManga = { match ->
                showWrongTitleSheet = false
                onOpenManga(match)
            },
            onDismiss = { showWrongTitleSheet = false }
        )
    }
}

@Composable
private fun ActiveSourceCard(
    details: MangaDetails,
    onWrongTitle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(DetailsCard)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DetailsLavender.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoStories,
                        contentDescription = null,
                        tint = DetailsLavenderBright,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = details.summary.sourceName,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(DetailsLavender.copy(alpha = 0.18f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Aniyomi",
                        color = DetailsLavenderBright,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(DetailsCardElevated)
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                    .clickable(onClick = onWrongTitle)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "Wrong title?",
                    color = DetailsLavender,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Text(
            text = "Found: ${details.summary.title}",
            color = DetailsTextMuted,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ChapterRowCard(
    chapter: MangaChapter,
    isCurrent: Boolean,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    onClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onDeleteDownloadClick: () -> Unit
) {
    val badgeText = remember(chapter.chapterNumber, chapter.name) {
        if (chapter.chapterNumber > 0f) {
            if (chapter.chapterNumber % 1f == 0f) {
                chapter.chapterNumber.toInt().toString()
            } else {
                String.format(Locale.US, "%.1f", chapter.chapterNumber)
            }
        } else {
            "CH"
        }
    }
    val dateLabel = remember(chapter.dateUploadEpochMs) {
        if (chapter.dateUploadEpochMs > 0L) {
            SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(chapter.dateUploadEpochMs))
        } else {
            null
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(DetailsCard)
            .border(
                width = 1.dp,
                color = if (isCurrent) DetailsLavender.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.07f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (isCurrent) DetailsLavender.copy(alpha = 0.24f) else DetailsCardElevated),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = badgeText,
                color = if (chapter.isRead) DetailsTextMuted else DetailsLavenderBright,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = chapter.name,
                color = if (chapter.isRead) DetailsTextMuted else Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = listOfNotNull(
                chapter.scanlator?.takeIf { it.isNotBlank() },
                dateLabel
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    color = DetailsTextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        when {
            isDownloading -> {
                CircularProgressIndicator(
                    color = DetailsLavender,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp)
                )
            }

            isDownloaded -> {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onDeleteDownloadClick,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = "Downloaded (tap to remove)",
                            tint = Color(0xFF34D399),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            else -> {
                IconButton(
                    onClick = onDownloadClick,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudDownload,
                        contentDescription = "Download chapter",
                        tint = DetailsTextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoProgressCard(
    details: MangaDetails,
    readCount: Int,
    lastChapterName: String?,
    onStartOrContinue: () -> Unit
) {
    val total = details.chapters.size.coerceAtLeast(1)
    val fraction = (readCount.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(DetailsCard)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .clickable(onClick = onStartOrContinue)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Reading progress",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "$readCount / ${details.chapters.size}",
                color = DetailsLavenderBright,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        LinearProgressIndicator(
            progress = { fraction },
            color = DetailsLavender,
            trackColor = Color(0xFF312A42),
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(CircleShape)
        )
        Text(
            text = lastChapterName ?: "Tap to start reading",
            color = DetailsTextMuted,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InfoMetadataGrid(details: MangaDetails) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            InfoMetricBox(
                label = "FORMAT",
                value = "Manga",
                modifier = Modifier.weight(1f)
            )
            InfoMetricBox(
                label = "SOURCE",
                value = details.summary.sourceName,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            InfoMetricBox(
                label = "CHAPTERS",
                value = details.chapters.size.toString(),
                modifier = Modifier.weight(1f)
            )
            InfoMetricBox(
                label = "STATUS",
                value = details.summary.status?.takeIf { it.isNotBlank() } ?: "Ongoing",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun InfoMetricBox(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(DetailsCard)
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = label,
            color = DetailsTextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RecommendationsStrip(
    sourceName: String,
    recommendations: List<MangaSummary>,
    onOpenManga: (MangaSummary) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "More from $sourceName",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(recommendations.distinctBy { it.id }, key = { it.id }) { item ->
                Column(
                    modifier = Modifier
                        .width(106.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenManga(item) }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(DetailsCard)
                    ) {
                        MangaDetailsImage(
                            url = item.coverUrl,
                            headers = item.coverHeaders,
                            contentDescription = item.title,
                            width = 240,
                            height = 360,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = item.title,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WrongTitleSearchSheet(
    initialQuery: String,
    results: List<MangaSummary>,
    isSearching: Boolean,
    onSearch: (String) -> Unit,
    onSelectManga: (MangaSummary) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf(initialQuery) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = DetailsCard,
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Search across installed sources",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Enter manga title...", color = DetailsTextMuted) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = DetailsCardElevated,
                        unfocusedContainerColor = DetailsCardElevated,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(DetailsLavender)
                        .clickable { onSearch(query) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = "Search",
                        tint = Color(0xFF140F1D)
                    )
                }
            }

            if (isSearching) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = DetailsLavender)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(results.distinctBy { it.id }, key = { it.id }) { match ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(DetailsCardElevated)
                                .clickable { onSelectManga(match) }
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(44.dp)
                                    .height(62.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(DetailsBg)
                            ) {
                                MangaDetailsImage(
                                    url = match.coverUrl,
                                    headers = match.coverHeaders,
                                    contentDescription = match.title,
                                    width = 140,
                                    height = 200,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = match.title,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = match.sourceName,
                                    color = DetailsLavender,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun FloatingTabItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) DetailsLavender.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) DetailsLavenderBright else DetailsTextMuted,
            modifier = Modifier.size(17.dp)
        )
        Text(
            text = label,
            color = if (selected) DetailsLavenderBright else DetailsTextMuted,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
private fun DetailsSegmentPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) DetailsLavender else DetailsCardElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            color = if (selected) Color(0xFF140F1D) else DetailsTextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun DetailsSquareAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(DetailsCardElevated)
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = DetailsLavenderBright,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun DetailsMiniChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) DetailsLavender.copy(alpha = 0.2f) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) DetailsLavender else Color.White.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = if (selected) DetailsLavenderBright else DetailsTextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun MangaDetailsImage(
    url: String?,
    headers: Map<String, String>,
    contentDescription: String?,
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
    }
}
