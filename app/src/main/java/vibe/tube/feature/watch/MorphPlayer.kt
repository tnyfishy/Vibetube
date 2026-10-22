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

/** Lays the child out at a fixed size regardless of the (animating) parent size. */
private fun Modifier.fixedTopStart(width: Int, height: Int): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(constraints.maxWidth.coerceAtMost(width), constraints.maxHeight.coerceAtMost(height)) { placeable.place(0, 0) }
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
    state: PlayerUiState,
    onLogin: () -> Unit,
    onEnterPip: (() -> Unit)?,
    contentBottomPx: Int,
    modifier: Modifier = Modifier,
) {
    val ui by watch.state.collectAsStateWithLifecycle()
    val auth by watch.authState.collectAsStateWithLifecycle()
    var options by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val progress = remember { Animatable(if (state.visible && state.expanded) 1f else 0f).apply { updateBounds(0f, 1f) } }
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

    val target = if (state.visible && state.expanded) 1f else 0f
    LaunchedEffect(target) {
        val velocity = pendingVelocity
        pendingVelocity = 0f
        progress.animateTo(target, MORPH_SPEC, initialVelocity = velocity)
    }
    LaunchedEffect(state.visible) {
        if (!state.visible) {
            progress.snapTo(0f)
            dismiss.snapTo(0f)
        }
    }

    PredictiveBackHandler(enabled = state.visible && state.expanded) { events ->
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
        val thumbW = with(density) { 104.dp.toPx() }
        val thumbH = thumbW * 9f / 16f
        val thumbX = sidePx * 2f
        val thumbY = miniTop + (miniHPx - thumbH) / 2f
        val videoH = wPx * 9f / 16f
        val dragRange = max(miniTop, 1f)
        val fadeRange = with(density) { 140.dp.toPx() }

        val p: () -> Float = { progress.value.coerceIn(0f, 1f) }
        val t: () -> Float = { progress.value.coerceIn(0f, 1f) }

        val showPage by remember { derivedStateOf { progress.value > 0.05f } }
        val miniish by remember { derivedStateOf { progress.value < 0.35f } }
        val controlsOn by remember { derivedStateOf { progress.value > 0.92f } }

        suspend fun fadeOutAndDismiss() {
            dismiss.animateTo(fadeRange * 1.1f, tween(180))
            controller.dismiss()
        }

        val dragState = rememberDraggableState { delta ->
            // UNDISPATCHED: apply the delta in this very frame instead of queueing a coroutine per touch event.
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                if (progress.value >= 0.999f && !state.fullscreen && (delta < 0f || fsOffset.value < 0f)) {
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
                        if (goExpanded == state.expanded) {
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

        if (!state.visible) return@BoxWithConstraints

        val cardColor = MaterialTheme.colorScheme.surfaceContainerHigh
        val pageColor = MaterialTheme.colorScheme.surface
        val containerX = { lerpF(sidePx, 0f, p()) }
        val containerY = { lerpF(miniTop, 0f, p()) }
        val containerW = { lerpF(wPx - 2f * sidePx, wPx, p()) }
        val containerH = { lerpF(miniHPx, hPx, p()) }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = (1f - dismiss.value / fadeRange).coerceIn(0f, 1f) * enterFade.value
                    translationY = dismiss.value * 0.5f
                },
        ) {
            // 1. Card / page background. Its content (details, comments) is laid out at full size and clipped.
            Box(
                Modifier
                    .placedRect(containerX, containerY, containerW, containerH)
                    .graphicsLayer {
                        val tt = t()
                        shape = RoundedCornerShape(CornerSize(lerpF(16.dp.toPx(), deviceCorner, tt)))
                        clip = true
                        shadowElevation = (1f - tt) * 6.dp.toPx()
                    }
                    .drawBehind { drawRect(lerp(cardColor, pageColor, t())) }
                    .pointerInput(Unit) {},
            ) {
                if (showPage) {
                    Column(
                        Modifier
                            .fixedTopStart(wPx.roundToInt(), hPx.roundToInt())
                            .graphicsLayer { alpha = ((p() - 0.55f) / 0.45f).coerceIn(0f, 1f) },
                    ) {
                        Spacer(Modifier.height(with(density) { (topInsetPx + videoH).toDp() }))
                        Details(ui, state, controller, watch, auth, onLogin, includeTabs = true, modifier = Modifier.weight(1f))
                    }
                }
            }

            // 2. The video: full width at the top when expanded, the small thumbnail in the mini card.
            Box(
                Modifier
                    .placedRect(
                        x = { lerpF(thumbX, 0f, p()) },
                        y = { lerpF(thumbY, topInsetPx, p()) },
                        w = { lerpF(thumbW, wPx, p()) },
                        h = { lerpF(thumbH, videoH, p()) },
                    )
                    .graphicsLayer {
                        shape = RoundedCornerShape(CornerSize(lerpF(8.dp.toPx(), 0f, t())))
                        clip = true
                        translationY = fsOffset.value
                        alpha = (1f + fsOffset.value / fsRange).coerceIn(0f, 1f)
                    },
            ) {
                VideoSurface(
                    player = player,
                    state = state,
                    showControls = controlsOn,
                    onCollapse = controller::collapse,
                    onToggleFullscreen = { controller.setFullscreen(!state.fullscreen) },
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
                )
                // Playback progress rides inside the thumbnail while minimized.
                val fraction = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = 6.dp, end = 6.dp, bottom = 3.dp)
                        .graphicsLayer { alpha = ((0.5f - t()) / 0.5f).coerceIn(0f, 1f) },
                ) {
                    val miniStroke = androidx.compose.ui.graphics.drawscope.Stroke(width = with(density) { 2.dp.toPx() }, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    val miniAmpAnim = remember { Animatable(0f) }
                    val miniFlat = !state.isPlaying || state.phase != vibe.tube.player.PlayerPhase.Ready
                    LaunchedEffect(miniFlat) { miniAmpAnim.animateTo(if (miniFlat) 0f else 0.6f, if (miniFlat) tween(140) else tween(900)) }
                    LinearWavyProgressIndicator(
                        progress = { fraction },
                        wavelength = 9.dp,
                        stroke = miniStroke,
                        trackStroke = miniStroke,
                        amplitude = { _ -> miniAmpAnim.value },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // 3. Minimized card: tap to expand, drag up to expand, drag down to dismiss.
            if (miniish || dragging) {
                Box(
                    Modifier
                        .placedRect(containerX, containerY, containerW, containerH)
                        .then(dragModifier)
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.action_expand_player)) { controller.expand() },
                )
                val video = state.current
                Row(
                    Modifier
                        .placedRect(
                            x = { containerX() + sidePx + thumbW + with(density) { 12.dp.toPx() } + sidePx },
                            y = containerY,
                            w = { wPx - 2f * sidePx - (sidePx + thumbW + with(density) { 12.dp.toPx() } + sidePx) },
                            h = { miniHPx },
                        )
                        .graphicsLayer { alpha = ((0.3f - t()) / 0.3f).coerceIn(0f, 1f) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(video?.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        video?.channelName?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    TooltipIconButton(
                        if (state.isPlaying) VIcon.Pause else VIcon.PlayArrowFilled,
                        stringResource(if (state.isPlaying) R.string.action_pause else R.string.action_play),
                        controller::togglePlay,
                    )
                    TooltipIconButton(VIcon.Close, stringResource(R.string.action_close_player), { scope.launch { fadeOutAndDismiss() } })
                }
            }
        }
        if (options) PlayerOptionsSheet(state, controller) { options = false }
    }
}
