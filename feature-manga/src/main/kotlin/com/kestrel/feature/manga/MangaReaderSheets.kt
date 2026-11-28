// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.kestrel.core.designsystem.rememberKestrelImageRequest
import com.kestrel.domain.model.MangaChapter
import com.kestrel.domain.model.MangaControlTheme
import com.kestrel.domain.model.MangaDualPageMode
import com.kestrel.domain.model.MangaImageQuality
import com.kestrel.domain.model.MangaPage
import com.kestrel.domain.model.MangaReaderBackground
import com.kestrel.domain.model.MangaReaderSettings
import com.kestrel.domain.model.MangaReadingDirection
import com.kestrel.domain.model.MangaReadingLayout
import kotlin.math.roundToInt

private val SheetBg = Color(0xFF14111B)
private val RowBg = Color(0xFF1C1826)
private val ActiveRowBg = Color(0xFF29223A)
private val ChipBg = Color(0xFF272235)
private val Lavender = Color(0xFFC0A4E8)
private val LavenderBright = Color(0xFFE9DDFF)
private val MutedText = Color(0xFF9E99B0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaChaptersBottomSheet(
    chapters: List<MangaChapter>,
    currentChapterIndex: Int,
    onSelectChapter: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    var sortDescending by remember { mutableStateOf(false) }

    val filtered = remember(chapters, searchQuery, sortDescending) {
        val indexed = chapters.mapIndexed { idx, ch -> idx to ch }.filter { (_, ch) ->
            searchQuery.isBlank() || ch.name.contains(searchQuery, ignoreCase = true)
        }
        if (sortDescending) indexed.reversed() else indexed
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetBg,
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Outlined.AutoStories, contentDescription = null, tint = Lavender)
                    Text("Chapters", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(ChipBg)
                            .padding(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Text("${chapters.size}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = LavenderBright)
                    }
                }
                IconButton(onClick = { sortDescending = !sortDescending }) {
                    Icon(Icons.Outlined.SwapVert, contentDescription = "Sort chapters", tint = MutedText)
                }
            }

            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search chapters...", color = MutedText, fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = MutedText) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear", tint = MutedText)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF1E1A29),
                    unfocusedContainerColor = Color(0xFF1E1A29),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                itemsIndexed(filtered, key = { _, pair -> pair.second.id }) { _, (origIdx, chapter) ->
                    val isCurrent = origIdx == currentChapterIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isCurrent) ActiveRowBg else RowBg)
                            .border(
                                width = 1.dp,
                                color = if (isCurrent) Lavender.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.06f),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable { onSelectChapter(origIdx) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isCurrent) Color(0xFF3C2F57) else ChipBg)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Ch. ${chapter.chapterNumber.toInt()}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isCurrent) LavenderBright else Lavender
                            )
                        }
                        Text(
                            text = chapter.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (isCurrent) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Lavender),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = SheetBg,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class ReaderSettingsTab(val label: String, val isNew: Boolean = false) {
    READING_MODE("Reading Mode"),
    GENERAL("General"),
    COLOR_FILTER("Color Filter", isNew = true),
    UPSCALE("Upscale", isNew = true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaReaderSettingsSheet(
    settings: MangaReaderSettings,
    pages: List<MangaPage>,
    onUpdateSettings: ((MangaReaderSettings) -> MangaReaderSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var activeTab by remember { mutableStateOf(ReaderSettingsTab.READING_MODE) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetBg,
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Reader Settings",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(ReaderSettingsTab.entries) { _, tab ->
                    val selected = activeTab == tab
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) LavenderBright else Color(0xFF221D2E))
                            .clickable { activeTab = tab }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = tab.label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) SheetBg else Color(0xFFC8C2D6)
                        )
                        if (tab.isNew) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (selected) Color(0xFF6D28D9) else Color(0xFF3C2F57))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = "NEW",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (selected) Color.White else LavenderBright
                                )
                            }
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(440.dp)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                when (activeTab) {
                    ReaderSettingsTab.READING_MODE -> {
                        item {
                            ReaderSegmentRow(
                                title = "Layout",
                                subtitle = if (settings.layout == MangaReadingLayout.CONTINUOUS) "Continuous" else "Paged",
                                options = listOf("Continuous" to MangaReadingLayout.CONTINUOUS, "Paged" to MangaReadingLayout.PAGED),
                                selected = settings.layout,
                                onSelect = { layout -> onUpdateSettings { it.copy(layout = layout) } }
                            )
                        }
                        item {
                            ReaderSegmentRow(
                                title = "Direction",
                                subtitle = settings.direction.name.replace('_', ' '),
                                options = listOf(
                                    "↑" to MangaReadingDirection.UP,
                                    "↓" to MangaReadingDirection.DOWN,
                                    "←" to MangaReadingDirection.LEFT,
                                    "→" to MangaReadingDirection.RIGHT
                                ),
                                selected = settings.direction,
                                onSelect = { dir -> onUpdateSettings { it.copy(direction = dir) } }
                            )
                        }
                        item {
                            ReaderSegmentRow(
                                title = "Dual Page Mode",
                                subtitle = when (settings.dualPageMode) {
                                    MangaDualPageMode.SINGLE -> "Standard (Single)"
                                    MangaDualPageMode.DUAL -> "Dual Page"
                                    MangaDualPageMode.SPLIT -> "Split Spread"
                                },
                                options = listOf(
                                    "1P" to MangaDualPageMode.SINGLE,
                                    "2P" to MangaDualPageMode.DUAL,
                                    "Split" to MangaDualPageMode.SPLIT
                                ),
                                selected = settings.dualPageMode,
                                onSelect = { mode -> onUpdateSettings { it.copy(dualPageMode = mode) } }
                            )
                        }
                        item {
                            ReaderSwitchRow(
                                title = "Spaced Pages",
                                subtitle = "Continuous Mode only",
                                checked = settings.spacedPages,
                                onCheckedChange = { v -> onUpdateSettings { it.copy(spacedPages = v) } }
                            )
                        }
                        item {
                            ReaderSwitchRow(
                                title = "Overscroll",
                                subtitle = "To Prev/Next Chapter",
                                checked = settings.overscroll,
                                onCheckedChange = { v -> onUpdateSettings { it.copy(overscroll = v) } }
                            )
                        }
                        item {
                            ReaderSwitchRow(
                                title = "Auto Webtoon Mode",
                                subtitle = "Automatically switch to continuous mode for long-strip manga",
                                checked = settings.autoWebtoonMode,
                                onCheckedChange = { v -> onUpdateSettings { it.copy(autoWebtoonMode = v) } }
                            )
                        }
                        item {
                            ReaderSwitchRow(
                                title = "Navigate by Number",
                                subtitle = "Always checks current chapter number when navigating",
                                checked = settings.navigateByNumber,
                                onCheckedChange = { v -> onUpdateSettings { it.copy(navigateByNumber = v) } }
                            )
                        }
                        item {
                            ReaderSwitchRow(
                                title = "Fit to Screen Width",
                                subtitle = "Stretch images to fit screen width",
                                checked = settings.fitToScreenWidth,
                                onCheckedChange = { v -> onUpdateSettings { it.copy(fitToScreenWidth = v) } }
                            )
                        }
                    }

                    ReaderSettingsTab.GENERAL -> {
                        item {
                            ReaderCycleRow(
                                title = "Control Theme",
                                value = settings.controlTheme.name,
                                onClick = {
                                    val all = MangaControlTheme.entries
                                    val next = all[(settings.controlTheme.ordinal + 1) % all.size]
                                    onUpdateSettings { it.copy(controlTheme = next) }
                                }
                            )
                        }
                        item {
                            ReaderCycleRow(
                                title = "Background",
                                value = settings.background.name,
                                onClick = {
                                    val all = MangaReaderBackground.entries
                                    val next = all[(settings.background.ordinal + 1) % all.size]
                                    onUpdateSettings { it.copy(background = next) }
                                }
                            )
                        }
                        item {
                            ReaderCycleRow(
                                title = "Image Filter Quality",
                                value = settings.imageFilterQuality.name,
                                onClick = {
                                    val all = MangaImageQuality.entries
                                    val next = all[(settings.imageFilterQuality.ordinal + 1) % all.size]
                                    onUpdateSettings { it.copy(imageFilterQuality = next) }
                                }
                            )
                        }
                        item {
                            ReaderSwitchRow(
                                title = "Persistent Page Indicator",
                                subtitle = "Always show page indicator",
                                checked = settings.persistentPageIndicator,
                                onCheckedChange = { v -> onUpdateSettings { it.copy(persistentPageIndicator = v) } }
                            )
                        }
                        item {
                            ReaderSwitchRow(
                                title = "Crop Borders",
                                subtitle = "Remove white/black borders",
                                checked = settings.cropBorders,
                                onCheckedChange = { v -> onUpdateSettings { it.copy(cropBorders = v) } }
                            )
                        }
                        item {
                            ReaderSwitchRow(
                                title = "Keep Screen On",
                                subtitle = "Prevent screen from sleeping while reading",
                                checked = settings.keepScreenOn,
                                onCheckedChange = { v -> onUpdateSettings { it.copy(keepScreenOn = v) } }
                            )
                        }
                        item {
                            ReaderSwitchRow(
                                title = "Always Show Chapter Transition",
                                subtitle = "Show transition page even when chapters are adjacent",
                                checked = settings.alwaysShowChapterTransition,
                                onCheckedChange = { v -> onUpdateSettings { it.copy(alwaysShowChapterTransition = v) } }
                            )
                        }
                        item {
                            ReaderSwitchRow(
                                title = "Long Press for Page Actions",
                                subtitle = "Long-press a page to save, share, or copy it",
                                checked = settings.longPressForPageActions,
                                onCheckedChange = { v -> onUpdateSettings { it.copy(longPressForPageActions = v) } }
                            )
                        }
                        item {
                            ReaderSwitchRow(
                                title = "E-ink Display Refresh",
                                subtitle = "Flash screen on page turn to clear ghosting",
                                checked = settings.eInkDisplayRefresh,
                                onCheckedChange = { v -> onUpdateSettings { it.copy(eInkDisplayRefresh = v) } }
                            )
                        }
                        item {
                            ReaderSwitchRow(
                                title = "Volume Keys Navigation",
                                subtitle = "Use volume keys to change pages",
                                checked = settings.volumeKeysNavigation,
                                onCheckedChange = { v -> onUpdateSettings { it.copy(volumeKeysNavigation = v) } }
                            )
                        }
                        item {
                            ReaderSwitchRow(
                                title = "Invert Volume Keys",
                                subtitle = "Swap Up/Down actions",
                                checked = settings.invertVolumeKeys,
                                onCheckedChange = { v -> onUpdateSettings { it.copy(invertVolumeKeys = v) } }
                            )
                        }
                        item {
                            ReaderSwitchRow(
                                title = "Auto Scroll",
                                subtitle = "Automatically scroll/advance pages",
                                checked = settings.autoScroll,
                                onCheckedChange = { v -> onUpdateSettings { it.copy(autoScroll = v) } }
                            )
                        }
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(RowBg)
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("Preload Pages", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                        Text("Pages ahead of time for faster loading", fontSize = 12.sp, color = MutedText)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(ChipBg)
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text("${settings.preloadPages}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LavenderBright)
                                    }
                                }
                                Slider(
                                    value = settings.preloadPages.toFloat(),
                                    onValueChange = { v -> onUpdateSettings { it.copy(preloadPages = v.roundToInt().coerceIn(1, 10)) } },
                                    valueRange = 1f..10f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = LavenderBright,
                                        activeTrackColor = Lavender
                                    )
                                )
                            }
                        }
                    }

                    ReaderSettingsTab.COLOR_FILTER -> {
                        item {
                            ReaderSwitchRow(
                                title = "Custom Brightness",
                                subtitle = "Override system screen brightness",
                                checked = settings.customBrightness,
                                onCheckedChange = { v -> onUpdateSettings { it.copy(customBrightness = v) } }
                            )
                        }
                        item {
                            ReaderSwitchRow(
                                title = "Enhance Colors",
                                subtitle = "Apply vivid color and contrast enhancement",
                                checked = settings.enhanceColors,
                                onCheckedChange = { v -> onUpdateSettings { it.copy(enhanceColors = v) } }
                            )
                        }
                        item {
                            ReaderSwitchRow(
                                title = "Color Filter",
                                subtitle = "Apply a color tint over pages",
                                checked = settings.colorFilterEnabled,
                                onCheckedChange = { v -> onUpdateSettings { it.copy(colorFilterEnabled = v) } }
                            )
                        }
                        item {
                            ReaderSwitchRow(
                                title = "Grayscale",
                                subtitle = "Display pages in black & white",
                                checked = settings.grayscale,
                                onCheckedChange = { v -> onUpdateSettings { it.copy(grayscale = v) } }
                            )
                        }
                        item {
                            ReaderSwitchRow(
                                title = "Invert Colors",
                                subtitle = "Invert all page colors",
                                checked = settings.invertColors,
                                onCheckedChange = { v -> onUpdateSettings { it.copy(invertColors = v) } }
                            )
                        }
                    }

                    ReaderSettingsTab.UPSCALE -> {
                        item {
                            UpscalePreviewCard(pages = pages)
                        }
                        item {
                            ReaderSwitchRow(
                                title = "Super Resolution",
                                subtitle = "High-definition edge sharpening & detail enhancement",
                                checked = settings.superResolution,
                                onCheckedChange = { v -> onUpdateSettings { it.copy(superResolution = v) } }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpscalePreviewCard(pages: List<MangaPage>) {
    var previewIdx by remember { mutableIntStateOf(0) }
    var splitFraction by remember { mutableFloatStateOf(0.64f) }
    var boxWidthPx by remember { mutableFloatStateOf(1f) }
    val page = pages.getOrNull(previewIdx)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Interactive Preview", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { previewIdx = (previewIdx - 1).coerceAtLeast(0) }) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = null, tint = Color.White)
                }
                Text(
                    text = "Page ${previewIdx + 1} / ${maxOf(1, pages.size)}",
                    fontSize = 12.sp,
                    color = LavenderBright
                )
                IconButton(onClick = { previewIdx = (previewIdx + 1).coerceAtMost(maxOf(0, pages.lastIndex)) }) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = Color.White)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF0D0B12))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                .onSizeChanged { boxWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, dragAmount ->
                        change.consume()
                        splitFraction = (splitFraction + dragAmount / boxWidthPx).coerceIn(0.08f, 0.92f)
                    }
                }
        ) {
            if (page != null) {
                AsyncImage(
                    model = rememberKestrelImageRequest(page.imageUrl, page.headers, 1080, 1440),
                    contentDescription = "Original",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(splitFraction)
                        .clipToBounds()
                ) {
                    AsyncImage(
                        model = rememberKestrelImageRequest(page.imageUrl, page.headers, 1080, 1440),
                        contentDescription = "Super Res",
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.12f) }),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Text(
                text = "Original",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Text(
                text = "Super Res • Manga (Crisp Lines)",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = LavenderBright,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xE63C2F57))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(2.dp)
                    .offset { IntOffset((boxWidthPx * splitFraction).roundToInt(), 0) }
                    .background(Color.White)
            )
        }
    }
}

@Composable
private fun ReaderSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RowBg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(subtitle, fontSize = 12.sp, color = MutedText, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = SheetBg,
                checkedTrackColor = Lavender,
                uncheckedThumbColor = MutedText,
                uncheckedTrackColor = Color(0xFF383347)
            )
        )
    }
}

@Composable
private fun <T> ReaderSegmentRow(
    title: String,
    subtitle: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RowBg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(subtitle, fontSize = 12.sp, color = MutedText)
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(ChipBg)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { (label, value) ->
                val isSel = value == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSel) Color(0xFF3C2F57) else Color.Transparent)
                        .clickable { onSelect(value) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSel) LavenderBright else MutedText
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderCycleRow(
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RowBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(value, fontSize = 12.sp, color = Lavender)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MutedText)
    }
}
