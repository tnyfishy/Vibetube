package vibe.tube.feature.watch

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import kotlinx.coroutines.launch
import vibe.tube.R
import vibe.tube.player.PlayerController
import vibe.tube.player.PlayerUiState
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.TooltipIconButton
import vibe.tube.ui.theme.Sizing
import vibe.tube.ui.theme.Spacing
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.roundToInt

/** Critically damped: glides to the end without overshooting or bouncing. */
private val MORPH_SPEC = spring<Float>(dampingRatio = 1f, stiffness = 380f)

private fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** Positions and sizes a child in absolute pixels; every lambda is read in the layout phase (no recomposition per frame). */
private fun Modifier.placedRect(x: () -> Float, y: () -> Float, w: () -> Float, h: () -> Float): Modifier =
    this
        .offset { IntOffset(x().roundToInt(), y().roundToInt()) }
        .layout { measurable, _ ->
            val width = w().roundToInt().coerceAtLeast(0)
            val height = h().roundToInt().coerceAtLeast(0)
            val placeable = measurable.measure(Constraints.fixed(width, height))
            layout(width, height) { placeable.place(0, 0) }
        }

/**
 * The player as one card that morphs between a full-screen page (progress 1) and the mini player
 * (progress 0). The finger drives `progress` directly while dragging; releasing springs it to the
 * nearest end, carrying the fling velocity with no overshoot. Dragging the
 * minimized card further down fades it out and stops playback.
 */
@Composable
internal fun MorphPlayer(
    player: Player,
    controller: PlayerController,
    watch: WatchViewModel,
    onLogin: () -> Unit,
    onEnterPip: (() -> Unit)?,
    contentBottomPx: Int,
    modifier: Modifier = Modifier,
) {
    val ui by watch.state.collectAsStateWithLifecycle()
    val auth by watch.authState.collectAsStateWithLifecycle()
    // The full state ticks with the playback position several times a second. Nothing structural may read it directly,
    // otherwise the whole card (and the lists in it) would recompose while it morphs; each piece below reads a derived
    // value that only changes when that piece actually has something new to show.
    val stateHolder = controller.state.collectAsStateWithLifecycle()
    val visible by remember { derivedStateOf { stateHolder.value.visible } }
    val expanded by remember { derivedStateOf { stateHolder.value.expanded } }
    val isPlaying by remember { derivedStateOf { stateHolder.value.isPlaying } }
    val current by remember { derivedStateOf { stateHolder.value.current } }
    val videoAspect by remember { derivedStateOf { stateHolder.value.videoAspect.coerceIn(1.2f, 2.4f) } }
    val detailState by remember { derivedStateOf { stateHolder.value.copy(positionMs = 0, bufferedMs = 0) } }
    var options by remember { mutableStateOf(false) }
    var chaptersOpen by remember { mutableStateOf(false) }
    val chapters = remember(ui.info?.videoId, ui.info?.description) {
        val info = ui.info
        if (info != null) vibe.tube.player.Chapters.parse(info.description) else emptyList()
    }
    // Only trust the chapters while they belong to the video that is actually playing.
    val activeChapters = if (ui.info?.videoId != null && ui.info?.videoId == current?.id) chapters else emptyList()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val progress = remember { Animatable(if (visible && expanded) 1f else 0f).apply { updateBounds(0f, 1f) } }
    val deviceCorner = with(density) { vibe.tube.ui.rememberDeviceCornerRadius().toPx() }
    val dismiss = remember { Animatable(0f) }
    // Swipe-up-to-fullscreen: the video follows the finger and fades; negative while pulled up.
    val fsOffset = remember { Animatable(0f) }
    val fsRange = with(density) { 120.dp.toPx() }
    val enterFade = remember { Animatable(if (System.currentTimeMillis() - fullscreenToggledAt < 900) 0f else 1f) }
    LaunchedEffect(Unit) { if (enterFade.value < 1f) enterFade.animateTo(1f, tween(320)) }
    var pendingVelocity by remember { mutableFloatStateOf(0f) }
    // True while a finger is dragging the card: the node that owns the gesture must stay composed until it ends.
    var dragging by remember { mutableStateOf(false) }

    val target = if (visible && expanded) 1f else 0f
    LaunchedEffect(target) {
        val velocity = pendingVelocity
        pendingVelocity = 0f
        progress.animateTo(target, MORPH_SPEC, initialVelocity = velocity)
    }
    LaunchedEffect(visible) {
        if (!visible) {
            progress.snapTo(0f)
            dismiss.snapTo(0f)
        }
    }

    PredictiveBackHandler(enabled = visible && expanded) { events ->
        try {
            events.collect { event -> progress.snapTo(1f - event.progress * 0.9f) }
            controller.collapse()
        } catch (cancelled: CancellationException) {
            scope.launch { progress.animateTo(1f, MORPH_SPEC) }
            throw cancelled
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val topInsetPx = WindowInsets.safeDrawing.getTop(density).toFloat()
        val navInsetPx = WindowInsets.navigationBars.getBottom(density).toFloat()
        val bottomInsetPx = max(navInsetPx, if (contentBottomPx > 0) hPx - contentBottomPx else 0f)

        val sidePx = with(density) { 8.dp.toPx() }
        val miniHPx = with(density) { Sizing.miniPlayerHeight.toPx() }
        val gapPx = with(density) { 6.dp.toPx() }
        val miniTop = hPx - bottomInsetPx - miniHPx - gapPx
        // The video box follows the real aspect ratio of the video (clamped), and so does the minimized thumbnail:
        // it fits a 104x58.5dp slot, so a 4:3 clip gets a narrower thumbnail instead of letterboxing.
        val aspect = videoAspect
        val slotW = with(density) { 104.dp.toPx() }
        val slotH = slotW * 9f / 16f
        val thumbW = minOf(slotW, slotH * aspect)
        val thumbH = thumbW / aspect
        val thumbX = sidePx * 2f + (slotW - thumbW) / 2f
        val thumbY = miniTop + (miniHPx - thumbH) / 2f
        val videoH = wPx / aspect
        val dragRange = max(miniTop, 1f)
        val fadeRange = with(density) { 140.dp.toPx() }

        val p: () -> Float = { progress.value.coerceIn(0f, 1f) }
        val t: () -> Float = { progress.value.coerceIn(0f, 1f) }

        var pageComposed by remember { mutableStateOf(false) }
        val opened by remember { derivedStateOf { progress.value > 0.05f } }
        LaunchedEffect(opened, visible) { pageComposed = opened || (pageComposed && visible) }
        val showPage = pageComposed
        val miniish by remember { derivedStateOf { progress.value < 0.35f } }
        val pageVisible by remember { derivedStateOf { progress.value > 0.1f } }
        val controlsOn by remember { derivedStateOf { progress.value > 0.92f } }

        suspend fun fadeOutAndDismiss() {
            dismiss.animateTo(fadeRange * 1.1f, tween(180))
            controller.dismiss()
        }

        val dragState = rememberDraggableState { delta ->
            // UNDISPATCHED: apply the delta in this very frame instead of queueing a coroutine per touch event.
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                if (progress.value >= 0.999f && !stateHolder.value.fullscreen && (delta < 0f || fsOffset.value < 0f)) {
                    fsOffset.snapTo((fsOffset.value + delta).coerceIn(-fsRange, 0f))
                } else if (progress.value <= 0.002f && (delta > 0f || dismiss.value > 0f)) {
                    dismiss.snapTo((dismiss.value + delta).coerceAtLeast(0f))
                } else {
                    progress.snapTo((progress.value - delta / dragRange).coerceIn(0f, 1f))
                }
            }
        }
        val dragModifier = Modifier.draggable(
            orientation = Orientation.Vertical,
            state = dragState,
            onDragStarted = { dragging = true },
            onDragStopped = { velocity ->
                scope.launch {
                    try {
                    if (dismiss.value > 0f) {
                        val threshold = with(density) { 72.dp.toPx() }
                        if (dismiss.value > threshold || velocity > 1400f) {
                            fadeOutAndDismiss()
                        } else {
                            dismiss.animateTo(0f, tween(220, easing = vibe.tube.ui.components.EmphasizedDecelerate))
                        }
                    } else {
                        if (fsOffset.value < 0f) {
                            if (-fsOffset.value > fsRange * 0.45f || velocity < -900f) {
                                fsOffset.animateTo(-fsRange, tween(160))
                                fullscreenToggledAt = System.currentTimeMillis()
                                controller.setFullscreen(true)
                            } else {
                                fsOffset.animateTo(0f, tween(220, easing = vibe.tube.ui.components.EmphasizedDecelerate))
                            }
                            return@launch
                        }
                        val goExpanded = when {
                            velocity > 900f -> false
                            velocity < -900f -> true
                            else -> progress.value > 0.5f
                        }
                        val v = -velocity / dragRange
                        if (goExpanded == stateHolder.value.expanded) {
                            progress.animateTo(if (goExpanded) 1f else 0f, MORPH_SPEC, initialVelocity = v)
                        } else {
                            pendingVelocity = v
                            if (goExpanded) controller.expand() else controller.collapse()
                        }
                    }
                    } finally {
                        dragging = false
                    }
                }
            },
        )

        if (!visible) return@BoxWithConstraints

        val cardColor = MaterialTheme.colorScheme.surfaceContainerHigh
        val pageColor = MaterialTheme.colorScheme.surface
        val cardRadius = with(density) { 16.dp.toPx() }
        val infoX = sidePx * 2f + slotW + with(density) { 12.dp.toPx() }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = (1f - dismiss.value / fadeRange).coerceIn(0f, 1f) * enterFade.value
                    translationY = dismiss.value * 0.5f
                },
        ) {
            // Nothing below changes size while the card morphs: the page and the mini card only fade (and the page
            // drifts down), the video is one fixed-size layer that is scaled and moved. Layout never re-runs per frame.

            // 1. Page: background + details. It fades out as the player is pulled down and is not placed (so it neither
            //    draws nor takes touches) while minimized; it stays composed so expanding again is instant.
            Box(
                Modifier
                    .fillMaxSize()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) { if (pageVisible) placeable.place(0, 0) }
                    }
                    .graphicsLayer {
                        alpha = ((p() - 0.12f) / 0.55f).coerceIn(0f, 1f)
                        translationY = (1f - p()) * hPx * 0.16f
                        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                    }
                    .background(pageColor)
                    .pointerInput(Unit) {},
            ) {
                if (showPage) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = ((p() - 0.5f) / 0.45f).coerceIn(0f, 1f)
                                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                            },
                    ) {
                        Spacer(Modifier.height(with(density) { (topInsetPx + videoH).toDp() }))
                        Details(ui, detailState, controller, watch, auth, onLogin, includeTabs = true, modifier = Modifier.weight(1f))
                    }
                }
            }

            // 2. Mini card background: fixed rectangle at the bottom, fades in as the page leaves.
            Box(
                Modifier
                    .placedRect({ sidePx }, { miniTop }, { wPx - 2f * sidePx }, { miniHPx })
                    .graphicsLayer {
                        alpha = (1f - p() / 0.45f).coerceIn(0f, 1f)
                        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                    }
                    .drawBehind { drawRoundRect(cardColor, cornerRadius = androidx.compose.ui.geometry.CornerRadius(cardRadius)) },
            )

            // 3. The video is always laid out at its full size and only scaled/moved by the graphics layer, so the
            //    TextureView never re-lays out or resizes while the card morphs.
            Box(
                Modifier
                    .layout { measurable, _ ->
                        val placeable = measurable.measure(Constraints.fixed(wPx.roundToInt(), videoH.roundToInt()))
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                    .graphicsLayer {
                        val sc = lerpF(thumbW, wPx, p()) / wPx
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        scaleX = sc
                        scaleY = sc
                        translationX = lerpF(thumbX, 0f, p())
                        translationY = lerpF(thumbY, topInsetPx, p()) + fsOffset.value
                        shape = RoundedCornerShape(CornerSize(lerpF(8.dp.toPx(), 0f, t()) / sc))
                        clip = true
                        alpha = (1f + fsOffset.value / fsRange).coerceIn(0f, 1f)
                    },
            ) {
                // Position only matters to the controls; while they are hidden the surface sees a state that does not tick.
                val surfaceState by remember { derivedStateOf { if (controlsOn) stateHolder.value else detailState } }
                VideoSurface(
                    player = player,
                    state = surfaceState,
                    showControls = controlsOn,
                    onCollapse = controller::collapse,
                    onToggleFullscreen = { controller.setFullscreen(!stateHolder.value.fullscreen) },
                    onOptions = { options = true },
                    onPip = onEnterPip,
                    onTogglePlay = controller::togglePlay,
                    onNext = controller::next,
                    onPrevious = controller::previous,
                    onSeek = controller::seekTo,
                    onSeekBy = controller::seekBy,
                    onRetry = controller::retry,
                    onLogin = onLogin,
                    modifier = Modifier.fillMaxSize(),
                    dragModifier = if (controlsOn || !miniish || dragging) dragModifier else Modifier,
                    chapters = activeChapters,
                    onChapters = { chaptersOpen = true },
                )
            }
            // Playback progress rides along the bottom of the thumbnail while minimized.
            if (miniish) {
                val miniAmpAnim = remember { Animatable(0f) }
                val miniFlat by remember { derivedStateOf { !stateHolder.value.isPlaying || stateHolder.value.phase != vibe.tube.player.PlayerPhase.Ready } }
                LaunchedEffect(miniFlat) { if (miniFlat) miniAmpAnim.snapTo(0f) else miniAmpAnim.animateTo(1f, tween(900)) }
                Box(
                    Modifier
                        .placedRect(
                            x = { lerpF(thumbX, 0f, p()) },
                            y = { lerpF(thumbY, topInsetPx, p()) },
                            w = { lerpF(thumbW, wPx, p()) },
                            h = { lerpF(thumbH, videoH, p()) },
                        )
                        .graphicsLayer {
                            alpha = ((0.5f - t()) / 0.5f).coerceIn(0f, 1f)
                            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                        },
                ) {
                    vibe.tube.ui.components.WavyProgress(
                        progress = { stateHolder.value.let { if (it.durationMs > 0) (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f) else 0f } },
                        amplitude = { miniAmpAnim.value },
                        animate = isPlaying,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(10.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.35f),
                        strokeWidth = 2.dp,
                        wavelength = 9.dp,
                        maxAmplitude = 1.dp,
                    )
                }
            }

            // 4. Minimized card: tap to expand, drag up to expand, drag down to dismiss.
            if (miniish || dragging) {
                Box(
                    Modifier
                        .placedRect({ sidePx }, { miniTop }, { wPx - 2f * sidePx }, { miniHPx })
                        .then(dragModifier)
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.action_expand_player)) { controller.expand() },
                )
                val video = current
                Row(
                    Modifier
                        .placedRect(
                            x = { infoX },
                            y = { miniTop },
                            w = { wPx - sidePx - infoX },
                            h = { miniHPx },
                        )
                        .graphicsLayer {
                            alpha = ((0.3f - t()) / 0.3f).coerceIn(0f, 1f)
                            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(video?.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        video?.channelName?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    TooltipIconButton(
                        if (isPlaying) VIcon.Pause else VIcon.PlayArrowFilled,
                        stringResource(if (isPlaying) R.string.action_pause else R.string.action_play),
                        controller::togglePlay,
                        animateIcon = true,
                    )
                    TooltipIconButton(VIcon.Close, stringResource(R.string.action_close_player), { scope.launch { fadeOutAndDismiss() } })
                }
            }
        }
        if (options) OptionsHost(stateHolder, controller) { options = false }
        if (chaptersOpen && activeChapters.isNotEmpty()) {
            ChaptersHost(stateHolder, activeChapters, controller::seekTo) { chaptersOpen = false }
        }
    }
}

@Composable
private fun OptionsHost(state: androidx.compose.runtime.State<PlayerUiState>, controller: PlayerController, onDismiss: () -> Unit) {
    PlayerOptionsSheet(state.value, controller, onDismiss)
}

@Composable
private fun ChaptersHost(state: androidx.compose.runtime.State<PlayerUiState>, chapters: List<vibe.tube.player.Chapter>, onSeek: (Long) -> Unit, onDismiss: () -> Unit) {
    // The position is read once, when the sheet opens, to scroll to the current chapter.
    val position = remember { state.value.positionMs }
    ChaptersSheet(chapters, position, onSeek, onDismiss)
}
