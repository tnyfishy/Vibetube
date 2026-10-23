@file:OptIn(UnstableApi::class)

package vibe.tube.feature.watch

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import vibe.tube.R
import vibe.tube.core.PlaybackFailure
import vibe.tube.player.PlayerPhase
import vibe.tube.player.PlayerUiState
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.TooltipIconButton
import vibe.tube.ui.components.VIconImage
import vibe.tube.ui.theme.MediaOverlayColors
import vibe.tube.ui.theme.Spacing
import vibe.tube.ui.theme.VibeMotion
import java.util.Locale

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

@Composable
fun failureText(failure: PlaybackFailure): Pair<String, String> {
    val (title, body) = when (failure) {
        PlaybackFailure.LoginRequired -> R.string.play_err_login_title to R.string.play_err_login_body
        PlaybackFailure.BotCheck -> R.string.play_err_bot_title to R.string.play_err_bot_body
        PlaybackFailure.AgeRestricted -> R.string.play_err_age_title to R.string.play_err_age_body
        PlaybackFailure.RegionBlocked -> R.string.play_err_region_title to R.string.play_err_region_body
        PlaybackFailure.Private -> R.string.play_err_private_title to R.string.play_err_private_body
        PlaybackFailure.Removed -> R.string.play_err_removed_title to R.string.play_err_removed_body
        PlaybackFailure.Unavailable -> R.string.play_err_unavailable_title to R.string.play_err_unavailable_body
        PlaybackFailure.LiveUnsupported -> R.string.play_err_live_title to R.string.play_err_live_body
        PlaybackFailure.DrmUnsupported -> R.string.play_err_drm_title to R.string.play_err_drm_body
        PlaybackFailure.NoCompatibleStream -> R.string.play_err_nostream_title to R.string.play_err_nostream_body
        PlaybackFailure.CipherUnsupported -> R.string.play_err_cipher_title to R.string.play_err_cipher_body
        PlaybackFailure.Expired -> R.string.play_err_expired_title to R.string.play_err_expired_body
    }
    return stringResource(title) to stringResource(body)
}

@Composable
fun VideoSurface(
    player: Player,
    state: PlayerUiState,
    showControls: Boolean,
    onCollapse: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onOptions: () -> Unit,
    onPip: (() -> Unit)?,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onRetry: () -> Unit,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
    dragModifier: Modifier = Modifier,
    chapters: List<vibe.tube.player.Chapter> = emptyList(),
    onChapters: () -> Unit = {},
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var interactions by remember { mutableFloatStateOf(0f) }
    val seekSeconds = LocalSeekSeconds.current
    // In fullscreen the left/right thirds adjust brightness/volume; the middle keeps the swipe that leaves fullscreen.
    val sideGestures = state.fullscreen && LocalFullscreenGestures.current
    var flashSide by remember { mutableStateOf(0) }
    var flashTotal by remember { mutableStateOf(0) }
    var flashKey by remember { mutableStateOf(0) }
    var flashVisible by remember { mutableStateOf(false) }
    var lastFlashAt by remember { mutableStateOf(0L) }
    LaunchedEffect(flashKey) {
        if (flashKey > 0) {
            flashVisible = true
            delay(700)
            flashVisible = false
        }
    }
    LaunchedEffect(controlsVisible, state.isPlaying, interactions) {
        if (controlsVisible && state.isPlaying) {
            delay(3_000)
            controlsVisible = false
        }
    }
    Box(
        modifier = modifier
            .background(MediaOverlayColors.videoBackground)
            .then(if (sideGestures) Modifier else dragModifier)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { offset ->
                        val side = if (offset.x < size.width / 2) -1 else 1
                        onSeekBy(side * seekSeconds * 1000L)
                        val now = System.currentTimeMillis()
                        flashTotal = if (side == flashSide && now - lastFlashAt < 900) flashTotal + seekSeconds else seekSeconds
                        flashSide = side
                        lastFlashAt = now
                        flashKey += 1
                        controlsVisible = true
                        interactions += 1f
                    },
                )
            },
    ) {
        AndroidView(
            factory = { ctx ->
                (android.view.LayoutInflater.from(ctx).inflate(R.layout.vibe_player_view, null) as PlayerView).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setKeepContentOnPlayerReset(true)
                }
            },
            update = { view -> if (view.player !== player) view.player = player },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        if (sideGestures) FullscreenGestureZones(centerDrag = dragModifier)
        AnimatedVisibility(
            visible = flashVisible,
            enter = fadeIn(tween(80)),
            exit = fadeOut(tween(260)),
            modifier = Modifier.align(if (flashSide < 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = Spacing.xxl),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                VIconImage(if (flashSide < 0) VIcon.FastRewind else VIcon.FastForward, null, Modifier.size(36.dp), tint = MediaOverlayColors.onScrim)
                Text(
                    stringResource(R.string.player_seek_flash, flashTotal),
                    style = MaterialTheme.typography.labelLarge,
                    color = MediaOverlayColors.onScrim,
                )
            }
        }
        val busy = state.phase == PlayerPhase.Resolving || state.phase == PlayerPhase.Buffering
        if (busy && state.error == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularWavyProgressIndicator(
                    modifier = Modifier.size(52.dp),
                    color = androidx.compose.ui.graphics.Color.White,
                    trackColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.25f),
                )
            }
        }
        val error = state.error
        if (state.phase == PlayerPhase.Error && error != null && showControls) {
            val (title, body) = failureText(error.failure)
            Column(
                Modifier
                    .fillMaxSize()
                    .background(MediaOverlayColors.scrim)
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterVertically),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MediaOverlayColors.onScrim, textAlign = TextAlign.Center)
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MediaOverlayColors.onScrim,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Button(shapes = ButtonDefaults.shapes(), onClick = { if (error.failure == PlaybackFailure.LoginRequired) onLogin() else onRetry() }) {
                    Text(
                        stringResource(
                            if (error.failure == PlaybackFailure.LoginRequired) R.string.action_sign_in else R.string.action_retry,
                        ),
                    )
                }
            }
        }
        if (!state.inPip) {
            // The controls stay composed (hidden) so that showing them is a fade, not the composition of a whole
            // control overlay in the last frames of the expand animation.
            val shown = showControls && controlsVisible && state.phase != PlayerPhase.Error
            val controlsAlpha = androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (shown) 1f else 0f,
                animationSpec = tween(if (shown) 150 else 200),
                label = "controlsAlpha",
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) { if (controlsAlpha.value > 0.001f) placeable.place(0, 0) }
                    }
                    .graphicsLayer {
                        alpha = controlsAlpha.value
                        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                    },
            ) {
                Controls(
                    state, onCollapse, onToggleFullscreen, onOptions, onPip, onTogglePlay, onNext, onPrevious, onSeek,
                    onInteract = { interactions += 1f },
                    chapters = chapters,
                    onChapters = onChapters,
                )
            }
        }
    }
}

val LocalSeekSeconds = androidx.compose.runtime.compositionLocalOf { 10 }

@Composable
private fun Controls(
    state: PlayerUiState,
    onCollapse: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onOptions: () -> Unit,
    onPip: (() -> Unit)?,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onInteract: () -> Unit,
    chapters: List<vibe.tube.player.Chapter> = emptyList(),
    onChapters: () -> Unit = {},
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = state.durationMs.coerceAtLeast(1)
    val fraction = if (dragging) dragValue else (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    // The pointer handler below lives for as long as the controls do (across videos), so it must read the current
    // duration/callback instead of the ones from the first composition: a stale duration made a tap on the bar seek
    // past the end of the video.
    val latestDuration by androidx.compose.runtime.rememberUpdatedState(duration)
    val latestSeek by androidx.compose.runtime.rememberUpdatedState(onSeek)
    val latestInteract by androidx.compose.runtime.rememberUpdatedState(onInteract)
    val busy = state.phase == PlayerPhase.Resolving || state.phase == PlayerPhase.Buffering
    val playAlpha by androidx.compose.animation.core.animateFloatAsState(if (busy) 0f else 1f, tween(160), label = "playAlpha")
    Box(Modifier.fillMaxSize().background(MediaOverlayColors.controlsScrim)) {
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TooltipIconButton(VIcon.KeyboardArrowDown, stringResource(R.string.action_minimize), onCollapse, tint = MediaOverlayColors.onScrim)
            Spacer(Modifier.weight(1f))
            if (onPip != null) {
                TooltipIconButton(VIcon.PictureInPicture, stringResource(R.string.action_pip), onPip, tint = MediaOverlayColors.onScrim)
            }
            TooltipIconButton(VIcon.Settings, stringResource(R.string.player_options), { onInteract(); onOptions() }, tint = MediaOverlayColors.onScrim)
        }
        val centerAlpha by androidx.compose.animation.core.animateFloatAsState(if (dragging) 0f else 1f, androidx.compose.animation.core.tween(150), label = "centerAlpha")
        Row(
            Modifier.align(Alignment.Center).graphicsLayer { alpha = centerAlpha },
            horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TooltipIconButton(VIcon.SkipPrevious, stringResource(R.string.action_previous), { onInteract(); onPrevious() }, tint = MediaOverlayColors.onScrim)
            // While the player is loading (after a seek, say) only the loading indicator shows, not the play button on top of it.
            FilledIconButton(
                onClick = { onInteract(); onTogglePlay() },
                enabled = !busy,
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.size(64.dp).graphicsLayer { alpha = playAlpha },
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
            ) {
                vibe.tube.ui.components.SwappingIcon(
                    if (state.isPlaying) VIcon.Pause else VIcon.PlayArrowFilled,
                    stringResource(if (state.isPlaying) R.string.action_pause else R.string.action_play),
                    Modifier.size(36.dp),
                )
            }
            TooltipIconButton(VIcon.SkipNext, stringResource(R.string.action_next), { onInteract(); onNext() }, tint = MediaOverlayColors.onScrim)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.isLive) {
                    Text(stringResource(R.string.badge_live), color = MediaOverlayColors.onScrim, style = MaterialTheme.typography.labelLarge)
                } else {
                    val shownMs = if (dragging) (dragValue * duration).toLong() else state.positionMs
                    Text(
                        "${formatTime(shownMs)} / ${formatTime(state.durationMs)}",
                        color = MediaOverlayColors.onScrim,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    val chapterIndex = vibe.tube.player.Chapters.currentIndex(chapters, shownMs)
                    if (chapterIndex >= 0) {
                        // A small round separator, centred between the time and the chapter name with equal space on both sides.
                        Box(
                            Modifier
                                .padding(horizontal = Spacing.sm)
                                .size(3.dp)
                                .background(MediaOverlayColors.onScrim.copy(alpha = 0.8f), androidx.compose.foundation.shape.CircleShape),
                        )
                        Row(
                            Modifier
                                .weight(1f, fill = false)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .clickable(role = androidx.compose.ui.semantics.Role.Button) { onInteract(); onChapters() }
                                .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                chapters[chapterIndex].title,
                                color = MediaOverlayColors.onScrim,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(Spacing.xs))
                            VIconImage(VIcon.ArrowForward, null, Modifier.size(14.dp), tint = MediaOverlayColors.onScrim)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                TooltipIconButton(
                    VIcon.Fullscreen,
                    stringResource(if (state.fullscreen) R.string.action_exit_fullscreen else R.string.action_fullscreen),
                    { onInteract(); onToggleFullscreen() },
                    tint = MediaOverlayColors.onScrim,
                )
            }
            if (!state.isLive) {
                val label = stringResource(R.string.player_seek_bar)
                // Wavy (M3 Expressive) while playing, flattening to a straight line while the thumb is held.
                val waveAmp = remember { androidx.compose.animation.core.Animatable(0f) }
                val flat = dragging || !state.isPlaying || state.phase != PlayerPhase.Ready
                LaunchedEffect(flat, dragging) {
                    when {
                        dragging -> waveAmp.snapTo(0f)
                        flat -> waveAmp.animateTo(0f, tween(120))
                        else -> waveAmp.animateTo(1f, tween(900, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
                    }
                }
                val insetPx = with(androidx.compose.ui.platform.LocalDensity.current) { vibe.tube.ui.components.WavyInset.toPx() }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg)
                        .padding(bottom = Spacing.md)
                        .height(28.dp)
                        .semantics { contentDescription = label }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                fun at(x: Float) = ((x - insetPx) / (size.width - 2 * insetPx)).coerceIn(0f, 1f)
                                dragging = true
                                dragValue = at(down.position.x)
                                latestInteract()
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) { change.consume(); break }
                                    dragValue = at(change.position.x)
                                    change.consume()
                                    latestInteract()
                                }
                                latestSeek((dragValue * latestDuration).toLong())
                                dragging = false
                            }
                        },
                ) {
                    // The bar and its chapter gaps share one offscreen layer, so the gaps are real cuts through the line.
                    Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }) {
                        vibe.tube.ui.components.WavyProgress(
                            progress = { fraction },
                            amplitude = { waveAmp.value },
                            animate = state.isPlaying && !dragging,
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MediaOverlayColors.onScrim.copy(alpha = 0.3f),
                            thumb = androidx.compose.ui.unit.DpSize(4.dp, 20.dp),
                        )
                        if (chapters.size > 1) {
                            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                                val usable = size.width - 2 * insetPx
                                val gap = 3.dp.toPx()
                                val thumbX = insetPx + usable * fraction
                                chapters.drop(1).forEach { chapter ->
                                    val x = insetPx + usable * (chapter.startMs.toFloat() / duration).coerceIn(0f, 1f)
                                    // Leave the thumb whole when it sits on a gap.
                                    if (x <= insetPx + gap || x >= size.width - insetPx - gap) return@forEach
                                    if (kotlin.math.abs(x - thumbX) < 5.dp.toPx()) return@forEach
                                    drawRect(
                                        color = androidx.compose.ui.graphics.Color.Black,
                                        topLeft = androidx.compose.ui.geometry.Offset(x - gap / 2f, size.height / 2f - 8.dp.toPx()),
                                        size = androidx.compose.ui.geometry.Size(gap, 16.dp.toPx()),
                                        blendMode = androidx.compose.ui.graphics.BlendMode.Clear,
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
