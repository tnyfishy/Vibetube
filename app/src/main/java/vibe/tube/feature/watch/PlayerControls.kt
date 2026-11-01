@file:OptIn(UnstableApi::class)

package vibe.tube.feature.watch

import vibe.tube.ui.theme.Sizing

import androidx.compose.foundation.layout.windowInsetsPadding

import androidx.compose.foundation.layout.union

import androidx.compose.foundation.layout.only

import androidx.compose.foundation.layout.navigationBars

import androidx.compose.foundation.layout.displayCutout

import androidx.compose.foundation.layout.WindowInsetsSides

import androidx.compose.foundation.layout.WindowInsets

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
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
        PlaybackFailure.MembersOnly -> R.string.play_err_members_title to R.string.play_err_members_body
        PlaybackFailure.Removed -> R.string.play_err_removed_title to R.string.play_err_removed_body
        PlaybackFailure.Unavailable -> R.string.play_err_unavailable_title to R.string.play_err_unavailable_body
        PlaybackFailure.Upcoming -> R.string.play_err_upcoming_title to R.string.play_err_upcoming_body
        PlaybackFailure.LiveUnsupported -> R.string.play_err_live_title to R.string.play_err_live_body
        PlaybackFailure.DrmUnsupported -> R.string.play_err_drm_title to R.string.play_err_drm_body
        PlaybackFailure.NoCompatibleStream -> R.string.play_err_nostream_title to R.string.play_err_nostream_body
        PlaybackFailure.CipherUnsupported -> R.string.play_err_cipher_title to R.string.play_err_cipher_body
        PlaybackFailure.Expired -> R.string.play_err_expired_title to R.string.play_err_expired_body
        PlaybackFailure.Network -> R.string.play_err_network_title to R.string.play_err_network_body
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
    onSeekBy: (Long) -> Long,
    onRetry: () -> Unit,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
    dragModifier: Modifier = Modifier,
    chapters: List<vibe.tube.player.Chapter> = emptyList(),
    onChapters: () -> Unit = {},
    onSelectQuality: (Int?) -> Unit = {},
    captionCues: List<vibe.tube.player.CaptionCue> = emptyList(),
    captionsAvailable: Boolean = false,
    captionsOn: Boolean = false,
    onToggleCaptions: () -> Unit = {},
    onUnlock: () -> Unit = {},
    poster: @Composable () -> Unit = {},
) {
    var castOpen by remember { mutableStateOf(false) }
    if (castOpen) CastDialog(state.current?.id, state.current?.title.orEmpty(), { if (state.isPlaying) onTogglePlay() }) { castOpen = false }
    val surfaceBounds = LocalPlayerBounds.current
    var unlockVisible by remember { mutableStateOf(false) }
    var lockTap by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(state.screenLocked, lockTap) {
        if (state.screenLocked && unlockVisible) { delay(3_000); unlockVisible = false }
    }
    androidx.activity.compose.BackHandler(enabled = state.screenLocked) { onUnlock() }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactions by remember { mutableFloatStateOf(0f) }
    val seekSeconds = LocalSeekSeconds.current
    val sideGestures = state.fullscreen && LocalFullscreenGestures.current
    val swipeToggle = LocalFullscreenSwipeToggle.current
    val fullscreenZones = state.fullscreen && (sideGestures || swipeToggle)
    var flashSide by remember { mutableStateOf(0) }
    var flashTotal by remember { mutableStateOf(0) }
    var flashKey by remember { mutableStateOf(0) }
    var flashVisible by remember { mutableStateOf(false) }
    var lastFlashAt by remember { mutableStateOf(0L) }
    val latestSeekBy by androidx.compose.runtime.rememberUpdatedState(onSeekBy)
    val latestSeekSeconds by androidx.compose.runtime.rememberUpdatedState(seekSeconds)
    LaunchedEffect(flashKey) {
        if (flashKey > 0) {
            flashVisible = true
            delay(SEEK_CHAIN_MS)
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
            .onGloballyPositioned { surfaceBounds.coordinates = it; surfaceBounds.measured = it.boundsInWindow() }
            .background(MediaOverlayColors.videoBackground)
            .then(if (state.fullscreen || state.screenLocked) Modifier else dragModifier)
            .pointerInput(state.screenLocked) {
                if (state.screenLocked) return@pointerInput
                val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
                fun seekAt(x: Float) {
                    val side = if (x < size.width / 2) -1 else 1
                    val now = android.os.SystemClock.uptimeMillis()
                    val continuing = flashVisible && side == flashSide && now - lastFlashAt < SEEK_CHAIN_MS
                    val applied = latestSeekBy(side * latestSeekSeconds * 1000L)
                    val seconds = kotlin.math.abs(applied).let { (it / 1000f).roundToInt() }
                    if (seconds == 0 && !continuing) return
                    flashTotal = if (continuing) flashTotal + seconds else seconds
                    flashSide = side
                    lastFlashAt = now
                    flashKey += 1
                    // Seeking leaves the controls as they were: hidden ones stay hidden while the video plays on,
                    // shown ones stay up a little longer.
                    if (controlsVisible) interactions += 1f
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    waitForUpOrCancellation() ?: return@awaitEachGesture
                    // While a double-tap seek is still on screen, each further tap seeks again rather than toggling the controls.
                    if (flashVisible && android.os.SystemClock.uptimeMillis() - lastFlashAt < SEEK_CHAIN_MS) {
                        seekAt(down.position.x)
                        return@awaitEachGesture
                    }
                    val second = withTimeoutOrNull(doubleTapTimeout) { awaitFirstDown(requireUnconsumed = false) }
                    if (second == null) {
                        controlsVisible = !controlsVisible
                        return@awaitEachGesture
                    }
                    seekAt(second.position.x)
                    waitForUpOrCancellation()
                }
            },
    ) {
        val sharedVideo = LocalPlayerVideo.current
        if (sharedVideo != null) sharedVideo(player, Modifier.fillMaxSize(), state.fullscreen && state.ambientFullscreen) else PlayerVideoView(player, Modifier.fillMaxSize(), state.fullscreen && state.ambientFullscreen)
        poster()
        if (fullscreenZones && !state.screenLocked) FullscreenGestureZones(brightnessVolume = sideGestures, centerDrag = if (swipeToggle) dragModifier else null)
        val flashBias by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (flashSide < 0) -0.5f else 0.5f,
            animationSpec = tween(if (flashVisible) 160 else 0),
            label = "seekCardSide",
        )
        AnimatedVisibility(
            visible = flashVisible,
            enter = fadeIn(tween(80)),
            exit = fadeOut(tween(260)),
            modifier = Modifier.align(BiasAlignment(flashBias, 0f)),
        ) {
            val appear = remember { androidx.compose.animation.core.Animatable(0.88f) }
            LaunchedEffect(Unit) {
                appear.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.4f, stiffness = 520f))
            }
            
            val numberStyle = (if (state.fullscreen) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleSmall)
                .copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontFeatureSettings = "tnum")
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .graphicsLayer { scaleX = appear.value; scaleY = appear.value }
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(if (state.fullscreen) 28.dp else 18.dp))
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = if (state.fullscreen) 22.dp else 12.dp, vertical = if (state.fullscreen) 14.dp else 7.dp),
            ) {
                VIconImage(if (flashSide < 0) VIcon.FastRewind else VIcon.FastForward, null, Modifier.size(if (state.fullscreen) 54.dp else 30.dp), tint = androidx.compose.ui.graphics.Color.White)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clipToBounds(), contentAlignment = Alignment.CenterEnd) {
                        androidx.compose.animation.AnimatedContent(
                            targetState = flashTotal,
                            transitionSpec = {
                                (androidx.compose.animation.slideInVertically(tween(180)) { it } + fadeIn(tween(180))) togetherWith
                                    (androidx.compose.animation.slideOutVertically(tween(180)) { -it } + fadeOut(tween(120))) using
                                    androidx.compose.animation.SizeTransform(clip = false)
                            },
                            contentAlignment = Alignment.CenterEnd,
                            label = "seekCardNumber",
                        ) { total ->
                            Text(total.toString(), style = numberStyle, color = androidx.compose.ui.graphics.Color.White)
                        }
                    }
                    Spacer(Modifier.width(if (state.fullscreen) 5.dp else 3.dp))
                    Text(
                        stringResource(R.string.player_seek_unit),
                        style = numberStyle,
                        color = androidx.compose.ui.graphics.Color.White,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        val busy = state.phase.isBusy
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
        if (!state.inPip && !state.screenLocked) {
            val shown = !state.screenLocked && showControls && controlsVisible && state.phase != PlayerPhase.Error
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
                    onSelectQuality = onSelectQuality,
                    captionsAvailable = captionsAvailable,
                    captionsOn = captionsOn,
                    onToggleCaptions = onToggleCaptions,
                    onCast = { castOpen = true },
                    onLock = onUnlock,
                    skipFlash = flashVisible,
                    player = player,
                )
            }
        }
        if (showControls && captionsOn && captionCues.isNotEmpty() && !state.inPip) {
            CaptionOverlay(captionCues, player, raised = controlsVisible)
        }
        if (state.screenLocked && !state.inPip) {
            Box(Modifier.fillMaxSize().clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { unlockVisible = true; lockTap++ }, contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(visible = unlockVisible, enter = fadeIn(), exit = fadeOut()) {
                    Button(shapes = ButtonDefaults.shapes(), onClick = { unlockVisible = false; onUnlock() }) {
                        VIconImage(VIcon.Lock, null)
                        Text(stringResource(R.string.player_unlock), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

    }
}


private val ControlsEdge = 20.dp

val LocalSeekSeconds = androidx.compose.runtime.compositionLocalOf { 10 }

private const val SEEK_CHAIN_MS = 900L

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
    onSelectQuality: (Int?) -> Unit = {},
    onCast: () -> Unit = {},
    captionsAvailable: Boolean = false,
    captionsOn: Boolean = false,
    onToggleCaptions: () -> Unit = {},
    onLock: () -> Unit = {},
    skipFlash: Boolean = false,
    player: androidx.media3.common.Player,
) {
    val controlDensity = androidx.compose.ui.platform.LocalDensity.current
    val controlDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val safeInsets = WindowInsets.displayCutout.union(WindowInsets.navigationBars)
    val balancedInset = with(controlDensity) { maxOf(safeInsets.getLeft(controlDensity, controlDirection), safeInsets.getRight(controlDensity, controlDirection)).toDp() }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = state.durationMs.coerceAtLeast(1)
    val fraction = if (dragging) dragValue else (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val latestDuration by androidx.compose.runtime.rememberUpdatedState(duration)
    val latestSeek by androidx.compose.runtime.rememberUpdatedState(onSeek)
    val latestInteract by androidx.compose.runtime.rememberUpdatedState(onInteract)
    val busy = state.phase.isBusy
    val playAlpha by androidx.compose.animation.core.animateFloatAsState(if (busy) 0f else 1f, tween(160), label = "playAlpha")
    val sideAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (skipFlash) 0f else 1f,
        animationSpec = if (skipFlash) tween(90) else tween(420, delayMillis = 140),
        label = "sideAlpha",
    )
    Box(Modifier.fillMaxSize().background(MediaOverlayColors.controlsScrim)) {
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TooltipIconButton(VIcon.KeyboardArrowDown, stringResource(R.string.action_minimize), onCollapse, tint = MediaOverlayColors.onScrim)
            Spacer(Modifier.weight(1f))
            if (state.fullscreen) TooltipIconButton(VIcon.Lock, stringResource(R.string.player_lock), { onInteract(); onLock() }, tint = MediaOverlayColors.onScrim, animateIcon = true)
            TooltipIconButton(VIcon.Cast, "Cast", { onInteract(); onCast() }, tint = MediaOverlayColors.onScrim)
            if (captionsAvailable) {
                vibe.tube.ui.components.StateIconButton(vibe.tube.ui.components.StateIcons.Subtitles, captionsOn,
                    { onInteract(); onToggleCaptions() }, tint = MediaOverlayColors.onScrim)
            }
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
            Box(Modifier.graphicsLayer { alpha = sideAlpha }) {
                TooltipIconButton(VIcon.SkipPrevious, stringResource(R.string.action_previous), { if (sideAlpha > 0.5f) { onInteract(); onPrevious() } }, tint = MediaOverlayColors.onScrim)
            }
            var playPressed by remember { mutableStateOf(false) }
            val playScale by androidx.compose.animation.core.animateFloatAsState(
                if (playPressed) 0.86f else 1f,
                androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 600f),
                label = "playScale",
            )
            val playCorner by androidx.compose.animation.core.animateDpAsState(
                if (playPressed) 20.dp else 32.dp,
                androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 600f),
                label = "playCorner",
            )
            val playLabel = stringResource(if (state.isPlaying) R.string.action_pause else R.string.action_play)
            val latestToggle by androidx.compose.runtime.rememberUpdatedState(onTogglePlay)
            val latestInteractPlay by androidx.compose.runtime.rememberUpdatedState(onInteract)
            Box(
                Modifier
                    .size(64.dp)
                    
                    .graphicsLayer { scaleX = playScale; scaleY = playScale; alpha = playAlpha * sideAlpha }
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(playCorner))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .pointerInput(busy) {
                        if (!busy) {
                            detectTapGestures(
                                onPress = {
                                    playPressed = true
                                    val downAt = System.currentTimeMillis()
                                    try { tryAwaitRelease() } finally {
                                        val held = System.currentTimeMillis() - downAt
                                        if (held < 90L) kotlinx.coroutines.delay(90L - held)
                                        playPressed = false
                                    }
                                },
                                onTap = { latestInteractPlay(); latestToggle() },
                            )
                        }
                    }
                    .semantics {
                        role = androidx.compose.ui.semantics.Role.Button
                        contentDescription = playLabel
                    },
                contentAlignment = Alignment.Center,
            ) {
                vibe.tube.ui.components.SwappingIcon(
                    if (state.isPlaying) VIcon.Pause else VIcon.PlayArrowFilled,
                    null,
                    Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Box(Modifier.graphicsLayer { alpha = sideAlpha }) {
                TooltipIconButton(VIcon.SkipNext, stringResource(R.string.action_next), { if (sideAlpha > 0.5f) { onInteract(); onNext() } }, tint = MediaOverlayColors.onScrim)
            }
        }
        

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .then(if (state.fullscreen) Modifier.padding(horizontal = balancedInset).windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)) else Modifier)
                .padding(start = ControlsEdge, end = ControlsEdge, bottom = if (state.fullscreen) 8.dp else 0.dp),
        ) {
            
            Row(Modifier.fillMaxWidth().padding(start = vibe.tube.ui.components.WavyInset), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
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
                                .background(Color.White.copy(alpha = 0.18f))
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
                }
            Row(verticalAlignment = Alignment.CenterVertically) {
                var qualityOpen by remember { mutableStateOf(false) }
                LaunchedEffect(qualityOpen) {
                    while (qualityOpen) { onInteract(); kotlinx.coroutines.delay(1000) }
                }
                Box {
                    QualityPill(state.selectedHeight, state.activeHeight) { onInteract(); qualityOpen = true }
                    androidx.compose.material3.DropdownMenu(expanded = qualityOpen, onDismissRequest = { qualityOpen = false }) {
                        @Composable
                        fun QualityItem(label: String, chosen: Boolean, onClick: () -> Unit) = androidx.compose.material3.DropdownMenuItem(
                            text = { Text(label, fontWeight = if (chosen) androidx.compose.ui.text.font.FontWeight.Bold else null) },
                            onClick = { qualityOpen = false; onClick() },
                            trailingIcon = if (chosen) { { VIconImage(VIcon.Check, null, Modifier.size(18.dp)) } } else null,
                        )
                        QualityItem(
                            state.activeHeight?.takeIf { state.selectedHeight == null }?.let { stringResource(R.string.quality_auto_active, it) }
                                ?: stringResource(R.string.quality_auto),
                            state.selectedHeight == null,
                        ) { onSelectQuality(null) }
                        state.qualities.forEach { height -> QualityItem(qualityLabel(height), state.selectedHeight == height) { onSelectQuality(height) } }
                    }
                }
                vibe.tube.ui.components.StateIconButton(vibe.tube.ui.components.StateIcons.Fullscreen, state.fullscreen,
                    { onInteract(); onToggleFullscreen() }, tint = MediaOverlayColors.onScrim)
            }
            }
            if (!state.isLive) {
                val label = stringResource(R.string.player_seek_bar)
                val insetPx = with(androidx.compose.ui.platform.LocalDensity.current) { vibe.tube.ui.components.WavyInset.toPx() }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = if (state.fullscreen) Spacing.md else 4.dp)
                        .height(20.dp)
                        .semantics { contentDescription = label }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                fun at(x: Float) = ((x - insetPx) / (size.width - 2 * insetPx)).coerceIn(0f, 1f)
                                val mediaId = player.currentMediaItem?.mediaId
                                val resume = player.playWhenReady
                                player.pause()
                                dragging = true
                                dragValue = at(down.position.x)
                                var lastPreview = 0L
                                try {
                                    player.seekTo((dragValue * latestDuration).toLong())
                                    latestInteract()
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) { change.consume(); break }
                                        dragValue = at(change.position.x)
                                        val now = android.os.SystemClock.elapsedRealtime()
                                        if (now - lastPreview >= 100L && player.currentMediaItem?.mediaId == mediaId) {
                                            player.seekTo((dragValue * latestDuration).toLong())
                                            lastPreview = now
                                        }
                                        change.consume()
                                        latestInteract()
                                    }
                                } finally {
                                    if (player.currentMediaItem?.mediaId == mediaId) {
                                        latestSeek((dragValue * latestDuration).toLong())
                                        player.playWhenReady = resume
                                    }
                                    dragging = false
                                }
                            }
                        },
                ) {
                    val hasGaps = chapters.size > 1
                    Box(Modifier.fillMaxSize().then(if (hasGaps) Modifier.graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen } else Modifier)) {
                        val progressColor = MaterialTheme.colorScheme.primary.let { pr ->
                            val grey = pr.red == pr.green && pr.green == pr.blue
                            if (grey || MaterialTheme.colorScheme.surface.luminance() > 0.5f) vibe.tube.ui.components.WordmarkRed else pr
                        }
                        val trackColor = MediaOverlayColors.onScrim.copy(alpha = 0.3f)
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            val left = insetPx
                            val right = size.width - insetPx
                            val x = left + (right - left) * fraction.coerceIn(0f, 1f)
                            val center = size.height / 2f
                            drawLine(trackColor, androidx.compose.ui.geometry.Offset(left, center), androidx.compose.ui.geometry.Offset(right, center), 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                            drawLine(progressColor, androidx.compose.ui.geometry.Offset(left, center), androidx.compose.ui.geometry.Offset(x, center), 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                            drawRoundRect(progressColor, topLeft = androidx.compose.ui.geometry.Offset(x - 2.dp.toPx(), center - 10.dp.toPx()), size = androidx.compose.ui.geometry.Size(4.dp.toPx(), 20.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                        }
                        if (chapters.size > 1) {
                            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                                val usable = size.width - 2 * insetPx
                                val gap = 3.dp.toPx()
                                val thumbX = insetPx + usable * fraction
                                chapters.drop(1).forEach { chapter ->
                                    val x = insetPx + usable * (chapter.startMs.toFloat() / duration).coerceIn(0f, 1f)
                                    
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

internal val LocalFullscreenVideoOffset = androidx.compose.runtime.staticCompositionLocalOf<() -> Float> { { 0f } }

internal val LocalPlayerVideo = androidx.compose.runtime.staticCompositionLocalOf<(@Composable (Player, Modifier, Boolean) -> Unit)?> { null }

@Composable
internal fun PlayerVideoHost(content: @Composable () -> Unit) {
    val video = remember { androidx.compose.runtime.movableContentOf<Player, Modifier, Boolean> { player, modifier, ambient -> PlayerVideoView(player, modifier, ambient) } }
    val bounds = remember { PlayerBounds() }
    androidx.compose.runtime.CompositionLocalProvider(LocalPlayerVideo provides video, LocalPlayerBounds provides bounds, content = content)
}

@Composable
private fun PlayerVideoView(player: Player, modifier: Modifier, ambient: Boolean) {
    var hostedView by remember { mutableStateOf<PlayerView?>(null) }
    val videoOffset = LocalFullscreenVideoOffset.current
    Box(modifier) {
        hostedView?.let { view ->
            val frame = vibe.tube.feature.watch.ambient.rememberAmbientFrame(view, ambient) { player.isPlaying }
            if (ambient) vibe.tube.feature.watch.ambient.VideoAmbientBackground(frame.frame,
                player.videoSize.let { if (it.height > 0) it.width.toFloat() * it.pixelWidthHeightRatio / it.height else null }, Modifier.fillMaxSize())
        }
        AndroidView(factory = { ctx ->
            (android.view.LayoutInflater.from(ctx).inflate(R.layout.vibe_player_view, null) as PlayerView).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                setKeepContentOnPlayerReset(true)
                hostedView = this
            }
        }, update = { view -> if (view.player !== player) view.player = player },
            onRelease = { view -> view.player = null; hostedView = null }, modifier = Modifier.fillMaxSize().graphicsLayer { translationY = videoOffset() })
    }
}

internal fun qualityLabel(height: Int): String = when {
    height >= 2160 -> "4K"
    height >= 1440 -> "2K"
    else -> "${height}p"
}

/**
 * The quality button names the resolution on screen: a filled pill when a quality is pinned, an outlined one marked
 * "Auto" while the player picks, so which of the two is in force reads at a glance.
 */
@Composable
private fun QualityPill(selected: Int?, active: Int?, onClick: () -> Unit) {
    val description = stringResource(R.string.player_quality)
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(50)
    val shown = selected ?: active
    val pinned = selected != null
    val hd = (shown ?: 0) >= 720
    Row(
        Modifier
            .padding(horizontal = Spacing.xs)
            .height(28.dp)
            .clip(shape)
            .then(
                if (pinned) Modifier.background(MediaOverlayColors.onScrim, shape)
                else Modifier.border(1.5.dp, MediaOverlayColors.onScrim.copy(alpha = 0.85f), shape),
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val ink = if (pinned) androidx.compose.ui.graphics.Color.Black else MediaOverlayColors.onScrim
        if (!pinned) Text(
            stringResource(R.string.quality_auto_short),
            style = MaterialTheme.typography.labelSmall,
            color = ink.copy(alpha = 0.75f),
        )
        Text(
            shown?.let(::qualityLabel) ?: stringResource(R.string.quality_auto),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = ink,
        )
        if (hd && shown != null && shown < 1440) Text(
            "HD",
            Modifier.background(vibe.tube.ui.components.WordmarkRed, androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = androidx.compose.ui.graphics.Color.White,
        )
    }
}
