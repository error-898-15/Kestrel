// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import android.util.Rational
import androidx.annotation.OptIn
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.kestrel.core.designsystem.EmptyStateView
import com.kestrel.core.designsystem.KestrelCard
import com.kestrel.core.designsystem.KestrelTheme
import com.kestrel.core.designsystem.SourceBadge
import com.kestrel.domain.model.StreamFormat
import com.kestrel.domain.model.SubtitleTrack
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.util.Locale

private enum class PlayerSheetMode {
    NONE, QUALITY, AUDIO, SUBTITLES, SPEED, EPISODES
}

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val session = state.session
    val context = LocalContext.current
    val colors = KestrelTheme.colors

    if (session == null) {
        EmptyStateView(
            icon = Icons.Outlined.PlayArrow,
            title = "No active stream",
            description = "Pick a title and stream source from Details to start playback.",
            actionLabel = "Go Back",
            onAction = onBack,
            modifier = modifier
        )
        return
    }

    var sheetMode by remember { mutableStateOf(PlayerSheetMode.NONE) }
    var controlsVisible by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(session.resumePositionMs) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var currentTracks by remember { mutableStateOf(Tracks.EMPTY) }

    val exoPlayer = remember(session.selectedSource.id) {
        val headers = buildMap {
            putAll(session.selectedSource.headers)
            session.selectedSource.referer?.let { putIfAbsent("Referer", it) }
        }
        val okHttpFactory = OkHttpDataSource.Factory(OkHttpClient())
            .setDefaultRequestProperties(headers)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(okHttpFactory))
            .build()
    }

    LaunchedEffect(session.selectedSource.id, session.selectedEpisode?.id) {
        val src = session.selectedSource
        val mimeType = when (src.format) {
            StreamFormat.HLS -> MimeTypes.APPLICATION_M3U8
            StreamFormat.DASH -> MimeTypes.APPLICATION_MPD
            StreamFormat.MP4 -> MimeTypes.VIDEO_MP4
            StreamFormat.OTHER -> null
        }

        val allSubtitles = (session.links.subtitles + src.subtitles).distinctBy { it.url }
        val subtitleConfigs = allSubtitles.map { sub ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                .setMimeType(inferSubtitleMime(sub))
                .setLanguage(sub.language)
                .setLabel(sub.label ?: sub.language)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
        }

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(src.url)
            .setSubtitleConfigurations(subtitleConfigs)
        if (mimeType != null) mediaItemBuilder.setMimeType(mimeType)

        exoPlayer.setMediaItem(mediaItemBuilder.build())
        exoPlayer.prepare()
        if (session.resumePositionMs > 0L) {
            exoPlayer.seekTo(session.resumePositionMs)
        }
        exoPlayer.playWhenReady = true
    }

    LaunchedEffect(state.playbackSpeed) {
        exoPlayer.setPlaybackSpeed(state.playbackSpeed)
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onTracksChanged(tracks: Tracks) {
                currentTracks = tracks
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            viewModel.saveProgress(exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0L))
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            durationMs = exoPlayer.duration.coerceAtLeast(0L)
            if (exoPlayer.isPlaying && positionMs > 2_000L) {
                viewModel.saveProgress(positionMs, durationMs)
            }
            delay(1_000L)
        }
    }

    val nextEp = viewModel.nextEpisode()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { controlsVisible = !controlsVisible }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                }
            },
            update = { view -> view.player = exoPlayer },
            modifier = Modifier.fillMaxSize()
        )

        if (controlsVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x88090B10))
                    .padding(16.dp)
            ) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Back",
                                tint = colors.textPrimary
                            )
                        }
                        Column {
                            Text(
                                text = session.details.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            session.selectedEpisode?.let { ep ->
                                Text(
                                    text = ep.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (session.details.episodes.isNotEmpty()) {
                            IconButton(onClick = { sheetMode = PlayerSheetMode.EPISODES }) {
                                Icon(Icons.Outlined.List, contentDescription = "Episodes", tint = colors.textPrimary)
                            }
                        }
                        IconButton(onClick = { enterPip(context) }) {
                            Icon(Icons.Outlined.PictureInPictureAlt, contentDescription = "Picture in Picture", tint = colors.textPrimary)
                        }
                    }
                }

                // Center Transport Controls
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { exoPlayer.seekTo((exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)) }
                    ) {
                        Icon(
                            Icons.Outlined.Replay10,
                            contentDescription = "Rewind 10s",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    IconButton(
                        onClick = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = colors.accentAmber,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                    IconButton(
                        onClick = { exoPlayer.seekTo(exoPlayer.currentPosition + 10_000L) }
                    ) {
                        Icon(
                            Icons.Outlined.Forward10,
                            contentDescription = "Forward 10s",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                // Bottom Timeline & Track Controls
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatTime(positionMs), style = MaterialTheme.typography.labelMedium, color = colors.textPrimary)
                        Text(formatTime(durationMs), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                    }
                    Slider(
                        value = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f,
                        onValueChange = { frac ->
                            if (durationMs > 0L) exoPlayer.seekTo((frac * durationMs).toLong())
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = colors.accentAmber,
                            activeTrackColor = colors.accentAmber
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(onClick = { sheetMode = PlayerSheetMode.QUALITY }) {
                                Icon(Icons.Outlined.HighQuality, contentDescription = "Quality", tint = colors.textPrimary)
                            }
                            IconButton(onClick = { sheetMode = PlayerSheetMode.SUBTITLES }) {
                                Icon(Icons.Outlined.Subtitles, contentDescription = "Subtitles", tint = colors.textPrimary)
                            }
                            IconButton(onClick = { sheetMode = PlayerSheetMode.AUDIO }) {
                                Icon(Icons.Outlined.Audiotrack, contentDescription = "Audio", tint = colors.textPrimary)
                            }
                            IconButton(onClick = { sheetMode = PlayerSheetMode.SPEED }) {
                                Icon(Icons.Outlined.Speed, contentDescription = "Speed", tint = colors.textPrimary)
                            }
                        }

                        if (nextEp != null) {
                            IconButton(onClick = { viewModel.playEpisode(nextEp) }) {
                                Icon(Icons.Outlined.SkipNext, contentDescription = "Next Episode", tint = colors.accentAmber)
                            }
                        }
                    }
                }
            }
        }
    }

    if (sheetMode != PlayerSheetMode.NONE) {
        ModalBottomSheet(
            onDismissRequest = { sheetMode = PlayerSheetMode.NONE },
            containerColor = colors.surfaceLow
        ) {
            when (sheetMode) {
                PlayerSheetMode.QUALITY -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Quality & Sources", style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
                        session.links.streams.forEach { src ->
                            KestrelCard(
                                highlighted = src.id == session.selectedSource.id,
                                onClick = {
                                    viewModel.selectStreamSource(src, exoPlayer.currentPosition)
                                    sheetMode = PlayerSheetMode.NONE
                                },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(src.extractorName, color = colors.textPrimary)
                                    SourceBadge(label = src.qualityLabel)
                                }
                            }
                        }
                    }
                }

                PlayerSheetMode.AUDIO -> {
                    TrackSelectorSheet(
                        title = "Audio Tracks",
                        tracks = extractTracks(currentTracks, C.TRACK_TYPE_AUDIO),
                        onSelect = { opt ->
                            val group = currentTracks.groups.getOrNull(opt.groupIndex)
                            if (group != null) {
                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                    .buildUpon()
                                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, opt.trackIndex))
                                    .build()
                            }
                            sheetMode = PlayerSheetMode.NONE
                        }
                    )
                }

                PlayerSheetMode.SUBTITLES -> {
                    TrackSelectorSheet(
                        title = "Subtitles",
                        tracks = extractTracks(currentTracks, C.TRACK_TYPE_TEXT),
                        includeOff = true,
                        onDisable = {
                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .build()
                            sheetMode = PlayerSheetMode.NONE
                        },
                        onSelect = { opt ->
                            val group = currentTracks.groups.getOrNull(opt.groupIndex)
                            if (group != null) {
                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, opt.trackIndex))
                                    .build()
                            }
                            sheetMode = PlayerSheetMode.NONE
                        }
                    )
                }

                PlayerSheetMode.SPEED -> {
                    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Playback Speed", style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
                        speeds.forEach { spd ->
                            KestrelCard(
                                highlighted = spd == state.playbackSpeed,
                                onClick = {
                                    viewModel.setPlaybackSpeed(spd)
                                    sheetMode = PlayerSheetMode.NONE
                                },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(12.dp)
                            ) {
                                Text("${spd}x", color = colors.textPrimary)
                            }
                        }
                    }
                }

                PlayerSheetMode.EPISODES -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(session.details.episodes, key = { it.id }) { ep ->
                            KestrelCard(
                                highlighted = ep.id == session.selectedEpisode?.id,
                                onClick = {
                                    viewModel.playEpisode(ep)
                                    sheetMode = PlayerSheetMode.NONE
                                },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(12.dp)
                            ) {
                                Text(ep.name, color = colors.textPrimary)
                            }
                        }
                    }
                }

                PlayerSheetMode.NONE -> Unit
            }
        }
    }
}

@Composable
private fun TrackSelectorSheet(
    title: String,
    tracks: List<TrackOption>,
    includeOff: Boolean = false,
    onDisable: () -> Unit = {},
    onSelect: (TrackOption) -> Unit
) {
    val colors = KestrelTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
        if (includeOff) {
            KestrelCard(
                onClick = onDisable,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(12.dp)
            ) {
                Text("Off", color = colors.textSecondary)
            }
        }
        if (tracks.isEmpty()) {
            Text("No tracks reported for this stream.", color = colors.textMuted)
        } else {
            tracks.forEach { track ->
                KestrelCard(
                    highlighted = track.isSelected,
                    onClick = { onSelect(track) },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp)
                ) {
                    Text(track.label, color = colors.textPrimary)
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@OptIn(UnstableApi::class)
private fun extractTracks(tracks: Tracks, trackType: Int): List<TrackOption> {
    val out = mutableListOf<TrackOption>()
    tracks.groups.forEachIndexed { gIdx, group ->
        if (group.type == trackType) {
            for (tIdx in 0 until group.length) {
                val fmt = group.getTrackFormat(tIdx)
                val label = fmt.label ?: fmt.language ?: "Track ${out.size + 1}"
                out.add(
                    TrackOption(
                        id = "$gIdx:$tIdx",
                        label = label,
                        groupIndex = gIdx,
                        trackIndex = tIdx,
                        isSelected = group.isTrackSelected(tIdx)
                    )
                )
            }
        }
    }
    return out
}

private fun inferSubtitleMime(sub: SubtitleTrack): String = when {
    !sub.mimeType.isNullOrBlank() -> sub.mimeType!!
    sub.url.contains(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
    sub.url.contains(".ass", ignoreCase = true) || sub.url.contains(".ssa", ignoreCase = true) -> MimeTypes.TEXT_SSA
    else -> MimeTypes.TEXT_VTT
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val hrs = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return if (hrs > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}

private fun enterPip(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val activity = context.findActivity() ?: return
    val params = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .build()
    runCatching { activity.enterPictureInPictureMode(params) }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
