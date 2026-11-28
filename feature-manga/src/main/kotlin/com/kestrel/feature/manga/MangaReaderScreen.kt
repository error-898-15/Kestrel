// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.manga

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.kestrel.core.designsystem.EmptyStateView
import com.kestrel.core.designsystem.rememberKestrelOriginalImageRequest
import com.kestrel.domain.model.MangaPage
import com.kestrel.domain.model.MangaReaderBackground
import com.kestrel.domain.model.MangaReadingLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MangaReaderScreen(
    onBack: () -> Unit,
    viewModel: MangaReaderViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.readerSettings
    val scope = rememberCoroutineScope()
    var controlsVisible by remember { mutableStateOf(true) }
    var showChaptersSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var autoScrolling by remember { mutableStateOf(settings.autoScroll) }

    val listState = rememberLazyListState()
    val pagerState = rememberPagerState(pageCount = { maxOf(1, state.pages.size) })
    val currentPageIndex = if (settings.layout == MangaReadingLayout.CONTINUOUS) {
        listState.firstVisibleItemIndex
    } else {
        pagerState.currentPage
    }

    LaunchedEffect(currentPageIndex, state.pages.size, state.currentChapter?.id) {
        if (state.pages.isNotEmpty() && state.currentChapter != null) {
            viewModel.onPageChanged(currentPageIndex)
        }
    }

    LaunchedEffect(autoScrolling, settings.layout) {
        while (autoScrolling) {
            delay(32L)
            if (settings.layout == MangaReadingLayout.CONTINUOUS) {
                listState.dispatchRawDelta(5f)
            } else if (pagerState.currentPage + 1 < state.pages.size) {
                delay(2400L)
                pagerState.animateScrollToPage(pagerState.currentPage + 1)
            }
        }
    }

    val canvasColor = when (settings.background) {
        MangaReaderBackground.BLACK -> Color.Black
        MangaReaderBackground.DARK_GRAY -> Color(0xFF1D1E24)
        MangaReaderBackground.WHITE -> Color.White
        MangaReaderBackground.SEPIA -> Color(0xFF2B2219)
    }

    val colorFilter = remember(
        settings.grayscale,
        settings.invertColors,
        settings.enhanceColors,
        settings.customBrightness,
        settings.brightnessLevel
    ) {
        val matrix = ColorMatrix()
        if (settings.grayscale) {
            matrix.setToSaturation(0f)
        } else if (settings.enhanceColors) {
            matrix.setToSaturation(1.22f)
        }
        if (settings.invertColors) {
            matrix.timesAssign(
                ColorMatrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        ColorFilter.colorMatrix(matrix)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(canvasColor)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { controlsVisible = !controlsVisible })
            }
    ) {
        when {
            state.isLoadingPages -> {
                CircularProgressIndicator(
                    color = Color(0xFFC0A4E8),
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            state.pagesError != null || state.pages.isEmpty() -> {
                EmptyStateView(
                    icon = Icons.Outlined.ErrorOutline,
                    title = "Failed to load pages",
                    description = state.pagesError ?: "No manga pages were returned by this source.",
                    actionLabel = "Retry",
                    onAction = viewModel::reloadCurrentChapter
                )
            }

            settings.layout == MangaReadingLayout.CONTINUOUS -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoomScale
                            scaleY = zoomScale
                        },
                    verticalArrangement = Arrangement.spacedBy(if (settings.spacedPages) 14.dp else 0.dp)
                ) {
                    itemsIndexed(state.pages, key = { idx, _ -> "${state.currentChapter?.id}_$idx" }) { idx, page ->
                        MangaReaderPageItem(
                            page = page,
                            pageIndex = idx,
                            isContinuous = true,
                            fitToWidth = settings.fitToScreenWidth,
                            canvasColor = canvasColor,
                            colorFilter = colorFilter
                        )
                    }
                }
            }

            else -> {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoomScale
                            scaleY = zoomScale
                        }
                ) { pageIdx ->
                    val page = state.pages.getOrNull(pageIdx)
                    if (page != null) {
                        MangaReaderPageItem(
                            page = page,
                            pageIndex = pageIdx,
                            isContinuous = false,
                            fitToWidth = false,
                            canvasColor = canvasColor,
                            colorFilter = colorFilter
                        )
                    }
                }
            }
        }

        // Color filter overlay tint
        if (settings.colorFilterEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(settings.colorFilterArgb))
            )
        }

        // Top Bar + Page Indicator Pill
        AnimatedVisibility(
            visible = controlsVisible || settings.persistentPageIndicator,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnimatedVisibility(visible = controlsVisible) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xEB1C1924))
                                .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                                .clickable(onClick = onBack),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(23.dp))
                                .background(Color(0xEB1C1924))
                                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(23.dp))
                                .clickable { showChaptersSheet = true }
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Canvas(modifier = Modifier.size(20.dp)) {
                                drawArc(
                                    color = Color(0x40C0A4E8),
                                    startAngle = 0f,
                                    sweepAngle = 360f,
                                    useCenter = false,
                                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                                )
                                drawArc(
                                    color = Color(0xFFC0A4E8),
                                    startAngle = -90f,
                                    sweepAngle = 240f,
                                    useCenter = false,
                                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = state.currentChapter?.name ?: state.mangaTitle,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Chapter ${state.currentChapterIndex + 1} of ${maxOf(1, state.chapters.size)}",
                                    color = Color(0xFFB8B4C4),
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                            Icon(
                                imageVector = Icons.Outlined.KeyboardArrowDown,
                                contentDescription = "Select chapter",
                                tint = Color(0xFFB8B4C4)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xEB1C1924))
                                .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                                .clickable { showSettingsSheet = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = "Reader settings",
                                tint = Color.White
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xE01C1924))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Page ${currentPageIndex + 1} of ${maxOf(1, state.pages.size)}",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Right-Side Floating Zoom / Auto-Scroll Pill
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xEB1C1924))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = { zoomScale = (zoomScale + 0.15f).coerceAtMost(2.2f) }) {
                    Icon(Icons.Outlined.Add, contentDescription = "Zoom in", tint = Color.White)
                }
                IconButton(onClick = { autoScrolling = !autoScrolling }) {
                    Icon(
                        imageVector = if (autoScrolling) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = "Auto scroll",
                        tint = if (autoScrolling) Color(0xFFC0A4E8) else Color.White
                    )
                }
                IconButton(onClick = { zoomScale = (zoomScale - 0.15f).coerceAtLeast(0.75f) }) {
                    Icon(Icons.Outlined.Remove, contentDescription = "Zoom out", tint = Color.White)
                }
            }
        }

        // Bottom Floating Scrubber + Chapter Navigation Capsule
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xF01C1924))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(22.dp))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = viewModel::previousChapter,
                    enabled = state.currentChapterIndex > 0
                ) {
                    Icon(Icons.Outlined.SkipPrevious, contentDescription = "Previous chapter", tint = Color.White)
                }
                val maxIdx = maxOf(1, state.pages.size - 1)
                Slider(
                    value = currentPageIndex.toFloat().coerceIn(0f, maxIdx.toFloat()),
                    onValueChange = { target ->
                        val pageIdx = target.toInt().coerceIn(0, maxIdx)
                        scope.launch {
                            if (settings.layout == MangaReadingLayout.CONTINUOUS) {
                                listState.scrollToItem(pageIdx)
                            } else {
                                pagerState.scrollToPage(pageIdx)
                            }
                        }
                    },
                    valueRange = 0f..maxIdx.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFE9DDFF),
                        activeTrackColor = Color(0xFFC0A4E8),
                        inactiveTrackColor = Color(0xFF3A3548)
                    ),
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = viewModel::nextChapter,
                    enabled = state.currentChapterIndex + 1 < state.chapters.size
                ) {
                    Icon(Icons.Outlined.SkipNext, contentDescription = "Next chapter", tint = Color.White)
                }
            }
        }
    }

    if (showChaptersSheet) {
        MangaChaptersBottomSheet(
            chapters = state.chapters,
            currentChapterIndex = state.currentChapterIndex,
            onSelectChapter = { idx ->
                viewModel.selectChapter(idx)
                showChaptersSheet = false
            },
            onDismiss = { showChaptersSheet = false }
        )
    }

    if (showSettingsSheet) {
        MangaReaderSettingsSheet(
            settings = settings,
            pages = state.pages,
            onUpdateSettings = viewModel::updateReaderSettings,
            onDismiss = { showSettingsSheet = false }
        )
    }
}

@Composable
private fun MangaReaderPageItem(
    page: MangaPage,
    pageIndex: Int,
    isContinuous: Boolean,
    fitToWidth: Boolean,
    canvasColor: Color,
    colorFilter: ColorFilter
) {
    var retryNonce by remember(page.imageUrl) { mutableIntStateOf(0) }
    val imageRequest = rememberKestrelOriginalImageRequest(page.imageUrl, page.headers, retryNonce)

    SubcomposeAsyncImage(
        model = imageRequest,
        contentDescription = "Page ${pageIndex + 1}",
        contentScale = if (isContinuous && fitToWidth) {
            ContentScale.FillWidth
        } else {
            ContentScale.Fit
        },
        colorFilter = colorFilter,
        modifier = if (isContinuous) {
            Modifier.fillMaxWidth()
        } else {
            Modifier.fillMaxSize()
        },
        loading = {
            Box(
                modifier = if (isContinuous) {
                    Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                        .background(canvasColor)
                } else {
                    Modifier
                        .fillMaxSize()
                        .background(canvasColor)
                },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFFC0A4E8),
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = "Loading page ${pageIndex + 1}...",
                        color = Color(0xFF9F96B8),
                        fontSize = 12.sp
                    )
                }
            }
        },
        error = {
            Box(
                modifier = (if (isContinuous) {
                    Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                } else {
                    Modifier.fillMaxSize()
                })
                    .background(Color(0xFF161220))
                    .clickable { retryNonce++ },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Retry page",
                        tint = Color(0xFFC0A4E8),
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = "Page ${pageIndex + 1} failed to load · Tap to retry",
                        color = Color(0xFFD6CEE8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        success = {
            SubcomposeAsyncImageContent()
        }
    )
}
