// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Rational
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.kestrel.core.designsystem.KestrelOutlinedButton
import com.kestrel.core.designsystem.KestrelPrimaryButton
import com.kestrel.core.designsystem.KestrelTheme
import com.kestrel.core.designsystem.glassPanel
import com.kestrel.domain.model.PlayerOrientationMode
import com.kestrel.domain.model.StreamFormat
import com.kestrel.domain.model.StreamSource
import com.kestrel.domain.model.SubtitleTrack
import com.kestrel.domain.playback.PlaybackSession
import kotlinx.coroutines.delay
import java.util.Locale

private enum class PlayerSheetMode(val tabTitle: String) {
    NONE(""),
    SOURCES("Sources"),
    QUALITY("Quality"),
    AUDIO("Audio"),
    SUBTITLES("Subtitles"),
    SPEED("Speed"),
    MORE("More")
}

private enum class EdgeDragHud {
    NONE,
    BRIGHTNESS,
    VOLUME
}

private val DrawerTabs = listOf(
    PlayerSheetMode.SOURCES,
    PlayerSheetMode.QUALITY,
    PlayerSheetMode.AUDIO,
    PlayerSheetMode.SUBTITLES,
    PlayerSheetMode.SPEED,
    PlayerSheetMode.MORE
)

private val DrawerPanelBg = Color(0xF0121216)
private val DrawerPillTrackBg = Color(0xFF1B1B21)
private val DrawerPillSelectedBg = Color(0xFF3E3E48)
private val DrawerCardIdleBg = Color(0xFF1B1B21)
private val DrawerCardActiveBg = Color(0xFF5D4018)
private val DrawerAmber = Color(0xFFD99B38)
private val DrawerInkPrimary = Color(0xFFF5F5F7)
private val DrawerInkSecondary = Color(0xFFA0A0AA)

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
    val activity = remember(context) { context.findActivity() }
    val configuration = LocalConfiguration.current
    val session = state.session

    // Fullscreen immersive bars + orientation lock according to Player settings.
    DisposableEffect(activity, state.orientationMode) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())

        if (activity != null) {
            activity.requestedOrientation = when (state.orientationMode) {
                PlayerOrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                PlayerOrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (activity != null) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                val lp = activity.window?.attributes
                if (lp != null && lp.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                    lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    activity.window.attributes = lp
                }
            }
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

    val isLandscapeLayout = state.orientationMode == PlayerOrientationMode.LANDSCAPE ||
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var sheetMode by remember { mutableStateOf(PlayerSheetMode.NONE) }
    var controlsVisible by remember { mutableStateOf(true) }
    var isControlsLocked by remember { mutableStateOf(false) }
    var interactionNonce by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var retryCounter by remember { mutableIntStateOf(0) }
    var disableExternalSubtitles by remember(session.selectedSource.id, session.selectedEpisode?.id) {
        mutableStateOf(false)
    }
    var hasReachedReadyForSource by remember(session.selectedSource.id, session.selectedEpisode?.id) {
        mutableStateOf(false)
    }
    var hasCustomAudioOverride by remember(session.selectedSource.id, session.selectedEpisode?.id) {
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
    val resizeLabels = remember { listOf("Fit", "Fill", "Zoom") }

    var positionMs by remember { mutableLongStateOf(session.resumePositionMs) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var currentTracks by remember { mutableStateOf(Tracks.EMPTY) }

    // Brightness & volume gesture state
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    val maxVolume = remember(audioManager) {
        audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15
    }
    var brightnessLevel by remember {
        val current = activity?.window?.attributes?.screenBrightness ?: -1f
        mutableFloatStateOf(if (current in 0.05f..1f) current else 0.55f)
    }
    var volumeFraction by remember {
        val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 8
        mutableFloatStateOf((currentVol.toFloat() / maxVolume.toFloat()).coerceIn(0f, 1f))
    }
    var activeDragHud by remember { mutableStateOf(EdgeDragHud.NONE) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(activeDragHud, brightnessLevel, volumeFraction) {
        if (activeDragHud != EdgeDragHud.NONE) {
            delay(1_100L)
            activeDragHud = EdgeDragHud.NONE
        }
    }

    // Gentle auto-hide when playing and no drawer is open.
    LaunchedEffect(controlsVisible, isPlaying, sheetMode, isControlsLocked, interactionNonce) {
        if (controlsVisible && isPlaying && sheetMode == PlayerSheetMode.NONE && !isControlsLocked) {
            delay(4_500L)
            controlsVisible = false
        }
    }

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
                    hasReachedReadyForSource = true
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
                    disableExternalSubtitles = true
                } else if (!hasReachedReadyForSource && viewModel.tryNextStreamSource(0L) != null) {
                    playerError = null
                    isBuffering = true
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
    val episodeSubtitle = session.selectedEpisode?.let { ep ->
        if (ep.season != null && ep.episodeNumber != null) {
            "S${ep.season} · E${ep.episodeNumber} ${ep.name}"
        } else {
            ep.name
        }
    } ?: session.selectedSource.extractorName

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSize = it }
            .pointerInput(isControlsLocked, sheetMode) {
                if (isControlsLocked || sheetMode != PlayerSheetMode.NONE) return@pointerInput
                var dragMode = EdgeDragHud.NONE
                detectVerticalDragGestures(
                    onDragStart = { startOffset ->
                        val widthPx = containerSize.width.toFloat().coerceAtLeast(1f)
                        dragMode = when {
                            startOffset.x < widthPx * 0.35f -> EdgeDragHud.BRIGHTNESS
                            startOffset.x > widthPx * 0.65f -> EdgeDragHud.VOLUME
                            else -> EdgeDragHud.NONE
                        }
                    },
                    onDragEnd = { dragMode = EdgeDragHud.NONE },
                    onDragCancel = { dragMode = EdgeDragHud.NONE },
                    onVerticalDrag = { change, dragAmount ->
                        if (dragMode == EdgeDragHud.NONE) return@detectVerticalDragGestures
                        change.consume()
                        val heightPx = containerSize.height.toFloat().coerceAtLeast(300f)
                        val delta = -dragAmount / (heightPx * 0.65f)
                        when (dragMode) {
                            EdgeDragHud.BRIGHTNESS -> {
                                brightnessLevel = (brightnessLevel + delta).coerceIn(0.05f, 1f)
                                val window = activity?.window
                                val lp = window?.attributes
                                if (lp != null) {
                                    lp.screenBrightness = brightnessLevel
                                    window.attributes = lp
                                }
                                activeDragHud = EdgeDragHud.BRIGHTNESS
                            }
                            EdgeDragHud.VOLUME -> {
                                volumeFraction = (volumeFraction + delta).coerceIn(0f, 1f)
                                val targetVol = (volumeFraction * maxVolume).toInt().coerceIn(0, maxVolume)
                                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                                activeDragHud = EdgeDragHud.VOLUME
                            }
                            EdgeDragHud.NONE -> Unit
                        }
                    }
                )
            }
            .clickable(interactionSource = clickInteraction, indication = null) {
                if (sheetMode != PlayerSheetMode.NONE) {
                    sheetMode = PlayerSheetMode.NONE
                } else {
                    controlsVisible = !controlsVisible
                    interactionNonce++
                }
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

        // Edge vertical brightness & volume HUD pills
        if (activeDragHud == EdgeDragHud.BRIGHTNESS || (controlsVisible && !isControlsLocked && sheetMode == PlayerSheetMode.NONE && isLandscapeLayout)) {
            EdgeVerticalMeter(
                icon = Icons.Outlined.WbSunny,
                level = brightnessLevel,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 24.dp)
            )
        }
        if (activeDragHud == EdgeDragHud.VOLUME) {
            EdgeVerticalMeter(
                icon = Icons.Outlined.VolumeUp,
                level = volumeFraction,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp)
            )
        }

        // Center buffering spinner (when controls hidden or buffering during playback)
        if (isBuffering && playerError == null && (!controlsVisible || isControlsLocked)) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Error dialog overlay
        if (playerError != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .glassPanel(
                        cornerRadius = 22.dp,
                        tint = Color(0xD812141A),
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
                        if (session.links.streams.size > 1) {
                            KestrelPrimaryButton(
                                text = "Next Server",
                                onClick = {
                                    val switched = viewModel.tryNextStreamSource(exoPlayer.currentPosition)
                                    if (switched != null) {
                                        playerError = null
                                        isBuffering = true
                                    } else {
                                        sheetMode = PlayerSheetMode.SOURCES
                                    }
                                }
                            )
                            KestrelOutlinedButton(
                                text = "Sources",
                                onClick = { sheetMode = PlayerSheetMode.SOURCES }
                            )
                        } else {
                            KestrelPrimaryButton(
                                text = "Retry",
                                onClick = { retryCounter++ }
                            )
                        }
                    }
                }
            }
        }

        // Locked Controls Unlock Pill
        if (controlsVisible && isControlsLocked && sheetMode == PlayerSheetMode.NONE) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 28.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCC16161B))
                    .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(50))
                    .clickable {
                        isControlsLocked = false
                        controlsVisible = true
                        interactionNonce++
                    }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.LockOpen,
                    contentDescription = "Unlock",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Unlock Controls",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White
                )
            }
        }

        // Main Player HUD (when unlocked and drawer closed)
        AnimatedVisibility(
            visible = controlsVisible && !isControlsLocked && sheetMode == PlayerSheetMode.NONE,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0xB8000000),
                            0.26f to Color(0x1F000000),
                            0.72f to Color(0x1F000000),
                            1f to Color(0xD0000000)
                        )
                    )
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = if (isLandscapeLayout) 22.dp else 14.dp, vertical = 12.dp)
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
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = session.details.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = episodeSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(onClick = {
                            resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
                            interactionNonce++
                        }) {
                            Icon(
                                imageVector = Icons.Outlined.AspectRatio,
                                contentDescription = "Resize Mode",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        IconButton(onClick = { enterPip(context) }) {
                            Icon(
                                imageVector = Icons.Outlined.PictureInPictureAlt,
                                contentDescription = "Picture in Picture",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        IconButton(onClick = { sheetMode = PlayerSheetMode.SOURCES }) {
                            Icon(
                                imageVector = Icons.Outlined.Dns,
                                contentDescription = "Sources",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                // Center Transport Controls (-10s | Play/Pause or Buffering | +10s)
                if (playerError == null) {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(if (isLandscapeLayout) 44.dp else 28.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                exoPlayer.seekTo((exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L))
                                interactionNonce++
                            },
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Replay10,
                                contentDescription = "Rewind 10s",
                                tint = Color.White,
                                modifier = Modifier.size(34.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .clickable {
                                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                    interactionNonce++
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isBuffering) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(40.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(44.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                exoPlayer.seekTo(exoPlayer.currentPosition + 10_000L)
                                interactionNonce++
                            },
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Forward10,
                                contentDescription = "Forward 10s",
                                tint = Color.White,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }
                }

                // Bottom Timeline & Quick Action Row
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = if (isLandscapeLayout) 8.dp else 2.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = formatTime(positionMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                        Slider(
                            value = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f,
                            onValueChange = { frac ->
                                if (durationMs > 0L) {
                                    exoPlayer.seekTo((frac * durationMs).toLong())
                                    interactionNonce++
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.24f)
                            )
                        )
                        val remainingLabel = if (durationMs > 0L) {
                            "-${formatTime((durationMs - positionMs).coerceAtLeast(0L))}"
                        } else {
                            "0:00"
                        }
                        Text(
                            text = remainingLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }

                    val speedLabel = if (state.playbackSpeed == 1.0f) "1×" else "${state.playbackSpeed}×"
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item(key = "act_speed") {
                            PlayerBottomAction(
                                icon = Icons.Outlined.Speed,
                                label = "Speed ($speedLabel)",
                                onClick = { sheetMode = PlayerSheetMode.SPEED }
                            )
                        }
                        item(key = "act_quality") {
                            PlayerBottomAction(
                                icon = Icons.Outlined.HighQuality,
                                label = "Quality (${session.selectedSource.extractorName} ${session.selectedSource.qualityLabel})",
                                accentSubstring = session.selectedSource.extractorName,
                                onClick = { sheetMode = PlayerSheetMode.SOURCES }
                            )
                        }
                        item(key = "act_audio_subs") {
                            PlayerBottomAction(
                                icon = Icons.Outlined.Subtitles,
                                label = "Audio & Subtitles",
                                onClick = { sheetMode = PlayerSheetMode.AUDIO }
                            )
                        }
                        if (session.details.episodes.size > 1) {
                            item(key = "act_episodes") {
                                PlayerBottomAction(
                                    icon = Icons.AutoMirrored.Outlined.PlaylistPlay,
                                    label = "Episodes",
                                    onClick = { sheetMode = PlayerSheetMode.MORE }
                                )
                            }
                        }
                        item(key = "act_lock") {
                            PlayerBottomAction(
                                icon = Icons.Outlined.Lock,
                                label = "Lock",
                                onClick = { isControlsLocked = true }
                            )
                        }
                        if (nextEp != null) {
                            item(key = "act_next") {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(DrawerCardActiveBg)
                                        .border(1.dp, DrawerAmber.copy(alpha = 0.55f), RoundedCornerShape(50))
                                        .clickable { viewModel.playEpisode(nextEp) }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Next Ep",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = DrawerAmber
                                    )
                                    Icon(
                                        imageVector = Icons.Outlined.SkipNext,
                                        contentDescription = "Next Episode",
                                        tint = DrawerAmber,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Landscape Right-Side Docked Panel Drawer (matches Nuvio/Cinema side drawer UI)
        AnimatedVisibility(
            visible = sheetMode != PlayerSheetMode.NONE && isLandscapeLayout,
            enter = fadeIn() + slideInHorizontally { it / 2 },
            exit = fadeOut() + slideOutHorizontally { it / 2 }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x55000000))
            ) {
                // Left ~42% video preview + title + mini progress bar
                Box(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { sheetMode = PlayerSheetMode.NONE }
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Row(
                        modifier = Modifier.align(Alignment.TopStart),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                        IconButton(onClick = { sheetMode = PlayerSheetMode.NONE }) {
                            Icon(
                                imageVector = Icons.Outlined.KeyboardArrowDown,
                                contentDescription = "Hide Drawer",
                                tint = Color.White
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = session.details.title,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = DrawerInkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = buildAnnotatedString {
                                append(session.selectedSource.extractorName)
                                append("  ")
                                withStyle(SpanStyle(color = DrawerAmber, fontWeight = FontWeight.SemiBold)) {
                                    append(session.selectedSource.qualityLabel)
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = DrawerInkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val progress = if (durationMs > 0L) {
                            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.16f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progress.coerceAtLeast(0.06f))
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White.copy(alpha = 0.65f))
                            )
                        }
                    }
                }

                // Right ~58% Docked Drawer with pill tabs
                Column(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp))
                        .background(DrawerPanelBg)
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
                        )
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Top pill tabs: Sources | Quality | Audio | Subtitles | Speed | More
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(DrawerPillTrackBg)
                            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(DrawerTabs, key = { it.name }) { tab ->
                            val selected = sheetMode == tab
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (selected) DrawerPillSelectedBg else Color.Transparent)
                                    .clickable { sheetMode = tab }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = tab.tabTitle,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (selected) DrawerInkPrimary else DrawerInkSecondary,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        PlayerDrawerContent(
                            mode = sheetMode,
                            session = session,
                            currentTracks = currentTracks,
                            playbackSpeed = state.playbackSpeed,
                            hasCustomAudioOverride = hasCustomAudioOverride,
                            resizeLabel = resizeLabels[resizeModeIndex],
                            onSelectSource = { src ->
                                viewModel.selectStreamSource(src, exoPlayer.currentPosition)
                                sheetMode = PlayerSheetMode.NONE
                            },
                            onResetAudioDefault = {
                                hasCustomAudioOverride = false
                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                    .buildUpon()
                                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                    .build()
                                sheetMode = PlayerSheetMode.NONE
                            },
                            onSelectAudio = { opt ->
                                val group = currentTracks.groups.getOrNull(opt.groupIndex)
                                if (group != null) {
                                    hasCustomAudioOverride = true
                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                        .buildUpon()
                                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, opt.trackIndex))
                                        .build()
                                }
                                sheetMode = PlayerSheetMode.NONE
                            },
                            onDisableSubtitles = {
                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                    .build()
                                sheetMode = PlayerSheetMode.NONE
                            },
                            onSelectSubtitle = { opt ->
                                val group = currentTracks.groups.getOrNull(opt.groupIndex)
                                if (group != null) {
                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                        .buildUpon()
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, opt.trackIndex))
                                        .build()
                                }
                                sheetMode = PlayerSheetMode.NONE
                            },
                            onSelectSpeed = { spd ->
                                viewModel.setPlaybackSpeed(spd)
                                sheetMode = PlayerSheetMode.NONE
                            },
                            onSelectEpisode = { ep ->
                                viewModel.playEpisode(ep)
                                sheetMode = PlayerSheetMode.NONE
                            },
                            onCycleResize = {
                                resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
                            },
                            onReload = {
                                retryCounter++
                                sheetMode = PlayerSheetMode.NONE
                            },
                            onEnterPip = {
                                sheetMode = PlayerSheetMode.NONE
                                enterPip(context)
                            }
                        )
                    }
                }
            }
        }

        // Portrait / Adaptive ModalBottomSheet fallback
        if (sheetMode != PlayerSheetMode.NONE && !isLandscapeLayout) {
            ModalBottomSheet(
                onDismissRequest = { sheetMode = PlayerSheetMode.NONE },
                containerColor = DrawerPanelBg
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(DrawerPillTrackBg)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(DrawerTabs, key = { "sheet_${it.name}" }) { tab ->
                            val selected = sheetMode == tab
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) DrawerPillSelectedBg else Color.Transparent)
                                    .clickable { sheetMode = tab }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = tab.tabTitle,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) DrawerInkPrimary else DrawerInkSecondary
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        PlayerDrawerContent(
                            mode = sheetMode,
                            session = session,
                            currentTracks = currentTracks,
                            playbackSpeed = state.playbackSpeed,
                            hasCustomAudioOverride = hasCustomAudioOverride,
                            resizeLabel = resizeLabels[resizeModeIndex],
                            onSelectSource = { src ->
                                viewModel.selectStreamSource(src, exoPlayer.currentPosition)
                                sheetMode = PlayerSheetMode.NONE
                            },
                            onResetAudioDefault = {
                                hasCustomAudioOverride = false
                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                    .buildUpon()
                                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                    .build()
                                sheetMode = PlayerSheetMode.NONE
                            },
                            onSelectAudio = { opt ->
                                val group = currentTracks.groups.getOrNull(opt.groupIndex)
                                if (group != null) {
                                    hasCustomAudioOverride = true
                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                        .buildUpon()
                                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, opt.trackIndex))
                                        .build()
                                }
                                sheetMode = PlayerSheetMode.NONE
                            },
                            onDisableSubtitles = {
                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                    .build()
                                sheetMode = PlayerSheetMode.NONE
                            },
                            onSelectSubtitle = { opt ->
                                val group = currentTracks.groups.getOrNull(opt.groupIndex)
                                if (group != null) {
                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                        .buildUpon()
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, opt.trackIndex))
                                        .build()
                                }
                                sheetMode = PlayerSheetMode.NONE
                            },
                            onSelectSpeed = { spd ->
                                viewModel.setPlaybackSpeed(spd)
                                sheetMode = PlayerSheetMode.NONE
                            },
                            onSelectEpisode = { ep ->
                                viewModel.playEpisode(ep)
                                sheetMode = PlayerSheetMode.NONE
                            },
                            onCycleResize = {
                                resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
                            },
                            onReload = {
                                retryCounter++
                                sheetMode = PlayerSheetMode.NONE
                            },
                            onEnterPip = {
                                sheetMode = PlayerSheetMode.NONE
                                enterPip(context)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EdgeVerticalMeter(
    icon: ImageVector,
    level: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
        Box(
            modifier = Modifier
                .width(5.dp)
                .height(110.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.24f)),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(level.coerceIn(0.05f, 1f))
                    .clip(RoundedCornerShape(50))
                    .background(Color.White)
            )
        }
    }
}

@Composable
private fun PlayerBottomAction(
    icon: ImageVector,
    label: String,
    accentSubstring: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White.copy(alpha = 0.90f),
            modifier = Modifier.size(18.dp)
        )
        val text = if (!accentSubstring.isNullOrBlank() && label.contains(accentSubstring)) {
            val idx = label.indexOf(accentSubstring)
            buildAnnotatedString {
                append(label.substring(0, idx))
                withStyle(SpanStyle(color = Color(0xFFB388FF), fontWeight = FontWeight.SemiBold)) {
                    append(accentSubstring)
                }
                append(label.substring(idx + accentSubstring.length))
            }
        } else {
            buildAnnotatedString { append(label) }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.92f),
            maxLines = 1
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerDrawerContent(
    mode: PlayerSheetMode,
    session: PlaybackSession,
    currentTracks: Tracks,
    playbackSpeed: Float,
    hasCustomAudioOverride: Boolean,
    resizeLabel: String,
    onSelectSource: (StreamSource) -> Unit,
    onResetAudioDefault: () -> Unit,
    onSelectAudio: (TrackOption) -> Unit,
    onDisableSubtitles: () -> Unit,
    onSelectSubtitle: (TrackOption) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onSelectEpisode: (com.kestrel.domain.model.Episode) -> Unit,
    onCycleResize: () -> Unit,
    onReload: () -> Unit,
    onEnterPip: () -> Unit
) {
    when (mode) {
        PlayerSheetMode.SOURCES -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item(key = "hdr_sources") {
                    DrawerSectionLabel("IN YOUR ORDER (${session.links.streams.size})")
                }
                itemsIndexed(
                    items = session.links.streams,
                    key = { idx, src -> "${idx}_${src.id}" }
                ) { idx, src ->
                    val active = src.id == session.selectedSource.id
                    DrawerSelectableCard(
                        selected = active,
                        onClick = { onSelectSource(src) }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = "${idx + 1}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = DrawerAmber,
                                modifier = Modifier.width(24.dp)
                            )
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "${src.extractorName} • ${src.qualityLabel}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = DrawerInkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = src.providerName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = DrawerInkSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (active) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = "Selected",
                                    tint = DrawerAmber,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        PlayerSheetMode.QUALITY -> {
            val rankedStreams = remember(session.links.streams) {
                session.links.streams.sortedByDescending { it.qualityScore }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item(key = "hdr_quality") {
                    DrawerSectionLabel("AVAILABLE QUALITIES")
                }
                itemsIndexed(
                    items = rankedStreams,
                    key = { idx, src -> "q_${idx}_${src.id}" }
                ) { _, src ->
                    val active = src.id == session.selectedSource.id
                    DrawerSelectableCard(
                        selected = active,
                        onClick = { onSelectSource(src) }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = src.qualityLabel,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = DrawerInkPrimary
                                )
                                Text(
                                    text = "${src.extractorName} • ${src.providerName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = DrawerInkSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = src.format.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = DrawerAmber
                                )
                                if (active) {
                                    Icon(
                                        imageVector = Icons.Outlined.Check,
                                        contentDescription = "Selected",
                                        tint = DrawerAmber,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        PlayerSheetMode.AUDIO -> {
            val audioTracks = extractTracks(currentTracks, C.TRACK_TYPE_AUDIO)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item(key = "hdr_audio") {
                    DrawerSectionLabel("THIS STREAM")
                }
                item(key = "audio_default") {
                    DrawerSelectableCard(
                        selected = !hasCustomAudioOverride,
                        onClick = onResetAudioDefault
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "Default (adaptive)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = DrawerInkPrimary
                                )
                                Text(
                                    text = "Use the track this stream marks as default",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = DrawerInkSecondary
                                )
                            }
                            if (!hasCustomAudioOverride) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = "Selected",
                                    tint = DrawerAmber,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
                if (audioTracks.isEmpty()) {
                    item(key = "audio_empty") {
                        Text(
                            text = "No alternate audio tracks reported for this stream.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = DrawerInkSecondary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                } else {
                    itemsIndexed(audioTracks, key = { idx, track -> "audio_${track.id}_$idx" }) { _, track ->
                        val active = hasCustomAudioOverride && track.isSelected
                        DrawerSelectableCard(
                            selected = active,
                            onClick = { onSelectAudio(track) }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = track.label,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = DrawerInkPrimary
                                    )
                                    if (!track.subLabel.isNullOrBlank()) {
                                        Text(
                                            text = track.subLabel,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = DrawerInkSecondary
                                        )
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (!track.channelTag.isNullOrBlank()) {
                                        Text(
                                            text = track.channelTag,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = DrawerAmber
                                        )
                                    }
                                    if (active) {
                                        Icon(
                                            imageVector = Icons.Outlined.Check,
                                            contentDescription = "Selected",
                                            tint = DrawerAmber,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        PlayerSheetMode.SUBTITLES -> {
            val textTracks = extractTracks(currentTracks, C.TRACK_TYPE_TEXT)
            val anySelected = textTracks.any { it.isSelected }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item(key = "hdr_subs") {
                    DrawerSectionLabel("THIS STREAM")
                }
                item(key = "sub_off") {
                    DrawerSelectableCard(
                        selected = !anySelected,
                        onClick = onDisableSubtitles
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Off",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = DrawerInkPrimary
                            )
                            if (!anySelected) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = "Selected",
                                    tint = DrawerAmber,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
                if (textTracks.isEmpty()) {
                    item(key = "subs_empty") {
                        Text(
                            text = "No subtitle tracks reported for this stream.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = DrawerInkSecondary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                } else {
                    itemsIndexed(textTracks, key = { idx, track -> "text_${track.id}_$idx" }) { _, track ->
                        DrawerSelectableCard(
                            selected = track.isSelected,
                            onClick = { onSelectSubtitle(track) }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = track.label,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = DrawerInkPrimary
                                    )
                                    if (!track.subLabel.isNullOrBlank()) {
                                        Text(
                                            text = track.subLabel,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = DrawerInkSecondary
                                        )
                                    }
                                }
                                if (track.isSelected) {
                                    Icon(
                                        imageVector = Icons.Outlined.Check,
                                        contentDescription = "Selected",
                                        tint = DrawerAmber,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        PlayerSheetMode.SPEED -> {
            val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item(key = "hdr_speed") {
                    DrawerSectionLabel("PLAYBACK SPEED")
                }
                items(speeds, key = { it.toString() }) { spd ->
                    val active = spd == playbackSpeed
                    DrawerSelectableCard(
                        selected = active,
                        onClick = { onSelectSpeed(spd) }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (spd == 1.0f) "1.0× (Normal)" else "${spd}×",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = DrawerInkPrimary
                            )
                            if (active) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = "Selected",
                                    tint = DrawerAmber,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        PlayerSheetMode.MORE -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item(key = "hdr_tools") {
                    DrawerSectionLabel("PLAYER TOOLS")
                }
                item(key = "tool_resize") {
                    DrawerSelectableCard(selected = false, onClick = onCycleResize) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AspectRatio,
                                    contentDescription = null,
                                    tint = DrawerInkPrimary
                                )
                                Text(
                                    text = "Aspect Ratio",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = DrawerInkPrimary
                                )
                            }
                            Text(
                                text = resizeLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = DrawerAmber
                            )
                        }
                    }
                }
                item(key = "tool_reload") {
                    DrawerSelectableCard(selected = false, onClick = onReload) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                                tint = DrawerInkPrimary
                            )
                            Text(
                                text = "Reload Current Stream",
                                style = MaterialTheme.typography.titleMedium,
                                color = DrawerInkPrimary
                            )
                        }
                    }
                }
                item(key = "tool_pip") {
                    DrawerSelectableCard(selected = false, onClick = onEnterPip) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.PictureInPictureAlt,
                                contentDescription = null,
                                tint = DrawerInkPrimary
                            )
                            Text(
                                text = "Picture-in-Picture",
                                style = MaterialTheme.typography.titleMedium,
                                color = DrawerInkPrimary
                            )
                        }
                    }
                }

                if (session.details.episodes.isNotEmpty()) {
                    item(key = "hdr_episodes") {
                        Spacer(modifier = Modifier.height(6.dp))
                        DrawerSectionLabel("EPISODES (${session.details.episodes.size})")
                    }
                    itemsIndexed(session.details.episodes, key = { idx, ep -> "player_ep_${ep.id}_$idx" }) { _, ep ->
                        val active = ep.id == session.selectedEpisode?.id
                        DrawerSelectableCard(
                            selected = active,
                            onClick = { onSelectEpisode(ep) }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    val prefix = if (ep.season != null && ep.episodeNumber != null) {
                                        "S${ep.season} · E${ep.episodeNumber}"
                                    } else {
                                        "Episode"
                                    }
                                    Text(
                                        text = "$prefix — ${ep.name}",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = DrawerInkPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (active) {
                                    Icon(
                                        imageVector = Icons.Outlined.Check,
                                        contentDescription = "Playing",
                                        tint = DrawerAmber,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        PlayerSheetMode.NONE -> Unit
    }
}

@Composable
private fun DrawerSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = DrawerInkSecondary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@Composable
private fun DrawerSelectableCard(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val bg = if (selected) DrawerCardActiveBg else DrawerCardIdleBg
    val stroke = if (selected) DrawerAmber.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.05f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, stroke, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        content()
    }
}

@OptIn(UnstableApi::class)
private fun extractTracks(tracks: Tracks, trackType: Int): List<TrackOption> {
    val out = mutableListOf<TrackOption>()
    tracks.groups.forEachIndexed { gIdx, group ->
        if (group.type == trackType) {
            for (tIdx in 0 until group.length) {
                val fmt = group.getTrackFormat(tIdx)
                val langName = fmt.language
                    ?.takeIf { it.isNotBlank() && it != "und" }
                    ?.let { code ->
                        Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
                            .takeIf { it.isNotBlank() } ?: code.uppercase(Locale.US)
                    }
                val label = fmt.label?.takeIf { it.isNotBlank() }
                    ?: langName
                    ?: "Track ${out.size + 1}"
                val subLabel = when {
                    fmt.label != null && langName != null && !fmt.label.equals(langName, ignoreCase = true) ->
                        "${langName} (${fmt.language})"
                    fmt.language != null -> "track${tIdx}:${fmt.language}"
                    else -> null
                }
                val channelTag = if (trackType == C.TRACK_TYPE_AUDIO) {
                    when (fmt.channelCount) {
                        1 -> "Mono"
                        2 -> "Stereo"
                        6 -> "5.1"
                        8 -> "7.1"
                        else -> if (fmt.channelCount > 0) "${fmt.channelCount}ch" else null
                    }
                } else {
                    null
                }
                out.add(
                    TrackOption(
                        id = "$gIdx:$tIdx",
                        label = label,
                        groupIndex = gIdx,
                        trackIndex = tIdx,
                        isSelected = group.isTrackSelected(tIdx),
                        subLabel = subLabel,
                        channelTag = channelTag
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

private fun inferSubtitleMime(sub: SubtitleTrack): String =
    sub.mimeType?.takeIf { it.isNotBlank() } ?: when {
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
