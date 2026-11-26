// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Rational
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.kestrel.core.designsystem.EmptyStateView
import com.kestrel.core.designsystem.GlassCircleButton
import com.kestrel.core.designsystem.KestrelCard
import com.kestrel.core.designsystem.KestrelOutlinedButton
import com.kestrel.core.designsystem.KestrelPrimaryButton
import com.kestrel.core.designsystem.KestrelTheme
import com.kestrel.core.designsystem.SourceBadge
import com.kestrel.core.designsystem.glassPanel
import com.kestrel.domain.model.StreamFormat
import com.kestrel.domain.model.StreamSource
import com.kestrel.domain.model.SubtitleTrack
import kotlinx.coroutines.delay
import java.util.Locale

private enum class PlayerSheetMode {
    NONE, QUALITY, AUDIO, SUBTITLES, SPEED, EPISODES
}

private const val DEFAULT_PLAYER_UA =
    "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = KestrelTheme.colors
    val context = LocalContext.current
    val session = state.session

    // Immersive fullscreen while watching video.
    DisposableEffect(context) {
        val activity = context.findActivity()
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    if (session == null) {
        EmptyStateView(
            icon = Icons.Outlined.PlayArrow,
            title = "No active playback session",
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
    var isBuffering by remember { mutableStateOf(true) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var retryCounter by remember { mutableIntStateOf(0) }
    var disableExternalSubtitles by remember(session.selectedSource.id, session.selectedEpisode?.id) {
        mutableStateOf(false)
    }
    var resizeModeIndex by remember { mutableIntStateOf(0) }
    val resizeModes = remember {
        listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        )
    }
    var positionMs by remember { mutableLongStateOf(session.resumePositionMs) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var currentTracks by remember { mutableStateOf(Tracks.EMPTY) }

    val exoPlayer = remember(session.selectedSource.id) {
        val rawHeaders = buildMap {
            putAll(session.selectedSource.headers)
            session.selectedSource.referer?.takeIf { it.isNotBlank() }?.let { ref ->
                if (keys.none { it.equals("referer", ignoreCase = true) }) {
                    put("Referer", ref)
                }
            }
        }
        val userAgent = rawHeaders.entries
            .firstOrNull { it.key.equals("user-agent", ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_PLAYER_UA

        val requestHeaders = rawHeaders.filterKeys { !it.equals("user-agent", ignoreCase = true) }

        val okHttpFactory = OkHttpDataSource.Factory(viewModel.okHttpClient)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(requestHeaders)

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 2_000, 4_000)
            .build()

        val audioAttrs = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .build()

        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(okHttpFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttrs, true)
            .build()
    }

    LaunchedEffect(
        session.selectedSource.id,
        session.selectedEpisode?.id,
        disableExternalSubtitles,
        retryCounter
    ) {
        val src = session.selectedSource
        playerError = null
        isBuffering = true
        val mimeType = inferStreamMime(src)

        val subtitleConfigs = if (disableExternalSubtitles) {
            emptyList()
        } else {
            (session.links.subtitles + src.subtitles)
                .distinctBy { it.url }
                .filter { it.url.startsWith("http", ignoreCase = true) }
                .map { sub ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                        .setMimeType(inferSubtitleMime(sub))
                        .setLanguage(sub.language)
                        .setLabel(sub.label ?: sub.language)
                        .build()
                }
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

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    playerError = null
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                currentTracks = tracks
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("KestrelPlayer", "PlaybackException: ${error.errorCodeName}", error)
                val hasExternalSubs = !disableExternalSubtitles &&
                    (session.links.subtitles.isNotEmpty() || session.selectedSource.subtitles.isNotEmpty())
                if (hasExternalSubs) {
                    // Recover automatically if a third-party subtitle URL failed to parse/load.
                    disableExternalSubtitles = true
                } else {
                    isBuffering = false
                    playerError = error.localizedMessage ?: error.errorCodeName
                }
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
    val clickInteraction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = clickInteraction, indication = null) {
                controlsVisible = !controlsVisible
            }
    ) {
        // TextureView-backed PlayerView avoids Compose NavHost / SurfaceView black-screen occlusion.
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.kestrel_player_view, null, false) as PlayerView)
                    .apply {
                        player = exoPlayer
                        useController = false
                        keepScreenOn = true
                        resizeMode = resizeModes[resizeModeIndex]
                    }
            },
            update = { view ->
                if (view.player !== exoPlayer) {
                    view.player = exoPlayer
                }
                view.resizeMode = resizeModes[resizeModeIndex]
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isBuffering && playerError == null) {
            CircularProgressIndicator(
                color = colors.accentAmber,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        if (playerError != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .glassPanel(
                        cornerRadius = 22.dp,
                        tint = Color(0xCC12151E),
                        rimAlpha = 0.55f
                    )
                    .padding(20.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = colors.statusError,
                        modifier = Modifier.size(34.dp)
                    )
                    Text(
                        text = "Stream Playback Error",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary
                    )
                    Text(
                        text = playerError.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        KestrelPrimaryButton(
                            text = "Retry",
                            onClick = { retryCounter++ }
                        )
                        if (session.links.streams.size > 1) {
                            KestrelOutlinedButton(
                                text = "Switch Source",
                                onClick = { sheetMode = PlayerSheetMode.QUALITY }
                            )
                        }
                    }
                }
            }
        }

        if (controlsVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0xBB07090E),
                            0.25f to Color(0x3307090E),
                            0.72f to Color(0x3307090E),
                            1f to Color(0xCC07090E)
                        )
                    )
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Top Glass Bar
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .glassPanel(
                            cornerRadius = 24.dp,
                            tint = Color.White.copy(alpha = 0.08f),
                            rimAlpha = 0.45f
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        GlassCircleButton(
                            icon = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                            onClick = onBack,
                            diameter = 38.dp
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = session.details.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val sub = session.selectedEpisode?.let { ep ->
                                if (ep.season != null && ep.episodeNumber != null) {
                                    "S${ep.season} E${ep.episodeNumber} • ${ep.name}"
                                } else ep.name
                            } ?: session.selectedSource.extractorName
                            Text(
                                text = sub,
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (session.details.episodes.isNotEmpty()) {
                            GlassCircleButton(
                                icon = Icons.Outlined.List,
                                contentDescription = "Episodes",
                                onClick = { sheetMode = PlayerSheetMode.EPISODES },
                                diameter = 38.dp
                            )
                        }
                        GlassCircleButton(
                            icon = Icons.Outlined.AspectRatio,
                            contentDescription = "Resize Mode",
                            onClick = { resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size },
                            diameter = 38.dp
                        )
                        GlassCircleButton(
                            icon = Icons.Outlined.PictureInPictureAlt,
                            contentDescription = "Picture in Picture",
                            onClick = { enterPip(context) },
                            diameter = 38.dp
                        )
                    }
                }

                // Center Glass Transport Controls
                if (playerError == null) {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassCircleButton(
                            icon = Icons.Outlined.Replay10,
                            contentDescription = "Rewind 10s",
                            onClick = {
                                exoPlayer.seekTo((exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L))
                            },
                            diameter = 50.dp
                        )
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(CircleShape)
                                .glassPanel(
                                    cornerRadius = 34.dp,
                                    tint = colors.accentAmber.copy(alpha = 0.28f),
                                    rimColor = colors.accentAmber,
                                    rimAlpha = 0.85f
                                )
                                .clickable {
                                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(38.dp)
                            )
                        }
                        GlassCircleButton(
                            icon = Icons.Outlined.Forward10,
                            contentDescription = "Forward 10s",
                            onClick = {
                                exoPlayer.seekTo(exoPlayer.currentPosition + 10_000L)
                            },
                            diameter = 50.dp
                        )
                    }
                }

                // Bottom Liquid Glass Control Dock
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .glassPanel(
                            cornerRadius = 24.dp,
                            tint = Color.White.copy(alpha = 0.08f),
                            rimAlpha = 0.45f
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTime(positionMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textPrimary
                        )
                        SourceBadge(
                            label = "${session.selectedSource.extractorName} • ${session.selectedSource.qualityLabel}",
                            accent = colors.accentAmber,
                            container = colors.accentAmberDim
                        )
                        Text(
                            text = formatTime(durationMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary
                        )
                    }
                    Slider(
                        value = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f,
                        onValueChange = { frac ->
                            if (durationMs > 0L) exoPlayer.seekTo((frac * durationMs).toLong())
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = colors.accentAmber,
                            activeTrackColor = colors.accentAmber,
                            inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassCircleButton(
                                icon = Icons.Outlined.HighQuality,
                                contentDescription = "Quality",
                                onClick = { sheetMode = PlayerSheetMode.QUALITY },
                                diameter = 38.dp
                            )
                            GlassCircleButton(
                                icon = Icons.Outlined.Subtitles,
                                contentDescription = "Subtitles",
                                onClick = { sheetMode = PlayerSheetMode.SUBTITLES },
                                diameter = 38.dp
                            )
                            GlassCircleButton(
                                icon = Icons.Outlined.Audiotrack,
                                contentDescription = "Audio",
                                onClick = { sheetMode = PlayerSheetMode.AUDIO },
                                diameter = 38.dp
                            )
                            GlassCircleButton(
                                icon = Icons.Outlined.Speed,
                                contentDescription = "Speed",
                                onClick = { sheetMode = PlayerSheetMode.SPEED },
                                diameter = 38.dp
                            )
                            GlassCircleButton(
                                icon = Icons.Outlined.Refresh,
                                contentDescription = "Reload Stream",
                                onClick = { retryCounter++ },
                                diameter = 38.dp
                            )
                        }

                        if (nextEp != null) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .glassPanel(
                                        cornerRadius = 50.dp,
                                        tint = colors.accentAmber.copy(alpha = 0.22f),
                                        rimColor = colors.accentAmber,
                                        rimAlpha = 0.75f
                                    )
                                    .clickable { viewModel.playEpisode(nextEp) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Next",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.accentAmber
                                )
                                Icon(
                                    Icons.Outlined.SkipNext,
                                    contentDescription = "Next Episode",
                                    tint = colors.accentAmber,
                                    modifier = Modifier.size(18.dp)
                                )
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
            containerColor = Color(0xEB10141F)
        ) {
            when (sheetMode) {
                PlayerSheetMode.QUALITY -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Text("Quality & Sources", style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
                        }
                        items(session.links.streams, key = { it.id }) { src ->
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
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = src.extractorName,
                                        color = colors.textPrimary,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
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

private fun inferStreamMime(src: StreamSource): String? {
    val lower = src.url.lowercase()
    return when {
        src.format == StreamFormat.HLS || ".m3u8" in lower || "/hls/" in lower || "m3u8" in lower ->
            MimeTypes.APPLICATION_M3U8
        src.format == StreamFormat.DASH || ".mpd" in lower ->
            MimeTypes.APPLICATION_MPD
        else -> null
    }
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
