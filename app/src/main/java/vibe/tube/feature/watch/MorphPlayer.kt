package vibe.tube.feature.watch

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
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
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.ExperimentalMaterial3Api
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
import vibe.tube.ui.theme.Spacing
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.roundToInt

/** Share of the screen width the minimized player takes (keep in sync with the list padding in AppRoot). */
internal const val MINI_WIDTH_FRACTION = 0.53f

/** Critically damped: glides to the end without overshooting or bouncing. */
private val MORPH_SPEC = spring<Float>(dampingRatio = 1f, stiffness = 250f)

private fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** Positions and sizes a child in absolute pixels; every lambda is read in the layout phase (no recomposition per frame). */
private fun Modifier.placedRect(x: () -> Float, y: () -> Float, w: () -> Float, h: () -> Float, show: () -> Boolean = { true }): Modifier =
    this
        .offset { IntOffset(x().roundToInt(), y().roundToInt()) }
        .layout { measurable, _ ->
            val width = w().roundToInt().coerceAtLeast(0)
            val height = h().roundToInt().coerceAtLeast(0)
            val placeable = measurable.measure(Constraints.fixed(width, height))
            layout(width, height) { if (show()) placeable.place(0, 0) }
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
    contentBottom: () -> Int,
    miniAway: Boolean,
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
    // Where the minimized player is docked, as 0..1 along the free range of each axis (0 = left/top edge, 1 = right/bottom
    // edge). Fractions, not pixels, so it stays glued to its edge when the video's size or the screen changes.
    val anchorX = remember { Animatable(1f) }
    val anchorY = remember { Animatable(1f) }
    var miniDragging by remember { mutableStateOf(false) }
    // Flinging the minimized player hard sends it flying off the screen in that direction and stops playback.
    val flyP = remember { Animatable(0f) }
    var flyDir by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    // Slides the minimized player off to the right (and fades it) while a page like Settings is open.
    val away = androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (miniAway && !expanded) 1f else 0f,
        animationSpec = tween(360, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "miniAway",
    )
    // Shared-element launch: when a video is started from a thumbnail the video flies out of that thumbnail's bounds.
    // `originMix` is 1 while the card's bounds stand in for the mini card's, and fades to 0 once that is no longer needed.
    val launchSeq = vibe.tube.ui.components.PlayerLaunch.seq
    val launchRect = remember(launchSeq) { vibe.tube.ui.components.PlayerLaunch.take() }
    val originMix = remember(launchRect) { Animatable(if (launchRect != null) 1f else 0f) }

    val target = if (visible && expanded) 1f else 0f
    // Whenever the player is minimized by swiping down (or any other way) it returns to the bottom-right corner. The
    // anchors are reset while fully expanded, where the mini card has no visual weight, so nothing jumps.
    LaunchedEffect(Unit) {
        snapshotFlow { progress.value >= 0.999f }.collect { full ->
            if (full) {
                anchorX.snapTo(1f)
                anchorY.snapTo(1f)
            }
        }
    }
    LaunchedEffect(originMix) {
        if (originMix.value > 0f) {
            // Once fully expanded the mini rect has no weight, so the switch is invisible.
            snapshotFlow { progress.value >= 1f }.first { it }
            originMix.snapTo(0f)
            flyP.snapTo(0f)
        }
    }
    LaunchedEffect(target) {
        if (target == 0f && originMix.value > 0f) originMix.animateTo(0f, tween(260))
        if (target == 0f && !miniDragging) {
            launch { if (anchorX.value != 1f) anchorX.animateTo(1f, spring(dampingRatio = 1f, stiffness = 380f)) }
            launch { if (anchorY.value != 1f) anchorY.animateTo(1f, spring(dampingRatio = 1f, stiffness = 380f)) }
        }
        val velocity = pendingVelocity
        pendingVelocity = 0f
        progress.animateTo(target, MORPH_SPEC, initialVelocity = velocity)
    }
    LaunchedEffect(visible) {
        if (!visible) {
            progress.snapTo(0f)
            dismiss.snapTo(0f)
            originMix.snapTo(0f)
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
        val imeInsets = WindowInsets.ime
        // Read lazily (layout phase): the bar sliding or the keyboard opening moves the mini player without recomposing.
        val bottomInset: () -> Float = {
            val cb = contentBottom()
            max(max(navInsetPx, imeInsets.getBottom(density).toFloat()), if (cb > 0) hPx - cb else 0f)
        }

        val sidePx = with(density) { 8.dp.toPx() }
        val gapPx = with(density) { 8.dp.toPx() }
        // Minimized, the player is just the video: a small floating card (53% of the width at 16:9, narrower for
        // taller videos) that can be dragged around and always rests against an edge of the screen.
        val aspect = videoAspect
        val maxMiniW = wPx * MINI_WIDTH_FRACTION
        val miniW = minOf(maxMiniW, maxMiniW * 9f / 16f * aspect)
        val miniH = miniW / aspect
        val minX = sidePx
        val maxX = max(wPx - sidePx - miniW, minX)
        val minY = topInsetPx + sidePx
        val maxY: () -> Float = { max(hPx - bottomInset() - miniH - gapPx, minY) }
        val miniX: () -> Float = { minX + anchorX.value.coerceIn(0f, 1f) * (maxX - minX) }
        val miniY: () -> Float = { minY + anchorY.value.coerceIn(0f, 1f) * (maxY() - minY) }
        val thumbX: () -> Float = { lerpF(miniX(), launchRect?.left ?: miniX(), originMix.value) }
        val thumbY: () -> Float = { lerpF(miniY(), launchRect?.top ?: miniY(), originMix.value) }
        val thumbW: () -> Float = { lerpF(miniW, launchRect?.width ?: miniW, originMix.value) }
        val thumbH: () -> Float = { thumbW() / aspect }
        val videoH = wPx / aspect
        val dragRange: () -> Float = { max(maxY(), 1f) }
        val slide: () -> Float = { away.value * (wPx - thumbX()) * (1f - progress.value.coerceIn(0f, 1f)) }
        val awayAlpha: () -> Float = { 1f - away.value * (1f - progress.value.coerceIn(0f, 1f)) }
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

        val swipeToggleState = androidx.compose.runtime.rememberUpdatedState(LocalFullscreenSwipeToggle.current)
        val dragState = rememberDraggableState { delta ->
            // UNDISPATCHED: apply the delta in this very frame instead of queueing a coroutine per touch event.
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                if (swipeToggleState.value && progress.value >= 0.999f && !stateHolder.value.fullscreen && (delta < 0f || fsOffset.value < 0f)) {
                    fsOffset.snapTo((fsOffset.value + delta).coerceIn(-fsRange, 0f))
                } else if (progress.value <= 0.002f && (delta > 0f || dismiss.value > 0f)) {
                    dismiss.snapTo((dismiss.value + delta).coerceAtLeast(0f))
                } else {
                    progress.snapTo((progress.value - delta / dragRange()).coerceIn(0f, 1f))
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
                        val v = -velocity / dragRange()
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

        val pageColor = MaterialTheme.colorScheme.surface

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = (1f - dismiss.value / fadeRange).coerceIn(0f, 1f) * enterFade.value * (1f - flyP.value).coerceIn(0f, 1f)
                    translationX = flyDir.x * flyP.value * wPx
                    translationY = dismiss.value * 0.5f + flyDir.y * flyP.value * wPx
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
                        translationY = (1f - progress.value.coerceIn(0f, 1f)) * hPx * 0.16f
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

            // 2. The video is always laid out at its full size and only scaled/moved by the graphics layer, so the
            //    TextureView never re-lays out or resizes while the card morphs.
            Box(
                Modifier
                    .layout { measurable, _ ->
                        val placeable = measurable.measure(Constraints.fixed(wPx.roundToInt(), videoH.roundToInt()))
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                    .graphicsLayer {
                        val sc = lerpF(thumbW(), wPx, p()) / wPx
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        scaleX = sc
                        scaleY = sc
                        translationX = lerpF(thumbX(), 0f, p()) + slide()
                        translationY = lerpF(thumbY(), topInsetPx, p()) + fsOffset.value
                        shape = RoundedCornerShape(CornerSize(lerpF(12.dp.toPx(), 0f, t()) / sc))
                        clip = true
                        alpha = (1f + fsOffset.value / fsRange).coerceIn(0f, 1f) * awayAlpha()
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
                // The tapped thumbnail stays visible inside the flying video until the first frame is there.
                val video = current
                if (video != null) {
                    var firstFrame by remember(video.id) { mutableStateOf(false) }
                    LaunchedEffect(video.id) {
                        snapshotFlow { stateHolder.value.phase == vibe.tube.player.PlayerPhase.Ready }.first { it }
                        firstFrame = true
                    }
                    val posterAlpha by androidx.compose.animation.core.animateFloatAsState(
                        if (firstFrame) 0f else 1f,
                        tween(if (firstFrame) 320 else 0),
                        label = "poster",
                    )
                    if (posterAlpha > 0.001f) {
                        vibe.tube.ui.components.RemoteImage(
                            urls = vibe.tube.ui.components.thumbnailCandidates(video),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = posterAlpha },
                        )
                    }
                }
            }
            // Playback progress: a straight line flush with the bottom edge of the minimized video.
            run {
                val track = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.35f)
                val fill = MaterialTheme.colorScheme.primary
                Box(
                    Modifier
                        .placedRect(
                            x = { lerpF(thumbX(), 0f, p()) + slide() },
                            y = { lerpF(thumbY(), topInsetPx, p()) },
                            w = { lerpF(thumbW(), wPx, p()) },
                            h = { lerpF(thumbH(), videoH, p()) },
                            show = { miniish && originMix.value < 0.01f },
                        )
                        .graphicsLayer {
                            alpha = ((0.5f - t()) / 0.5f).coerceIn(0f, 1f) * awayAlpha()
                            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                            shape = RoundedCornerShape(lerpF(12.dp.toPx(), 0f, t()))
                            clip = true
                        },
                ) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(3.dp)
                            .drawBehind {
                                val s0 = stateHolder.value
                                val frac = if (s0.durationMs > 0) (s0.positionMs.toFloat() / s0.durationMs).coerceIn(0f, 1f) else 0f
                                drawRect(track)
                                drawRect(fill, size = androidx.compose.ui.geometry.Size(size.width * frac, size.height))
                            },
                    )
                }
            }

            // 3. Minimized: drag to move (it settles against the nearest screen edge on release), tap to expand.
            //    Play/pause and close are small icon buttons in the top corners.
            run {
                val miniSettled by remember { derivedStateOf { progress.value < 0.01f } }
                // The buttons appear only once the card has shrunk all the way down, and fade in then.
                val btnAlpha = androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (miniSettled && !miniDragging) 1f else 0f,
                    animationSpec = tween(if (miniSettled && !miniDragging) 200 else 70, delayMillis = if (miniSettled && !miniDragging) 60 else 0),
                    label = "miniButtons",
                )
                val miniRect = Modifier.placedRect({ miniX() + slide() }, miniY, { miniW }, { miniH }, show = { (miniish || dragging) && originMix.value < 0.01f && away.value < 0.5f })
                val buttonsRect = Modifier.placedRect({ miniX() + slide() }, miniY, { miniW }, { miniH }, show = { btnAlpha.value > 0.01f && originMix.value < 0.01f && away.value < 0.5f })
                val geo = remember { MiniGeo() }
                val expandLabel = stringResource(R.string.action_expand_player)
                geo.minX = minX; geo.maxX = maxX; geo.minY = minY; geo.maxYf = maxY
                Box(
                    miniRect
                        .pointerInput(Unit) {
                            var total = androidx.compose.ui.geometry.Offset.Zero
                            val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                            fun settle(velocity: androidx.compose.ui.unit.Velocity) {
                                val speed = kotlin.math.hypot(velocity.x, velocity.y)
                                if (speed > with(density) { 1500.dp.toPx() }) {
                                    val horizontal = kotlin.math.abs(velocity.x) >= kotlin.math.abs(velocity.y)
                                    // Only a fling towards the screen edge the player is resting against sends it away.
                                    val atLeft = anchorX.value <= 0.12f
                                    val atRight = anchorX.value >= 0.88f
                                    val atTop = anchorY.value <= 0.12f
                                    val atBottom = anchorY.value >= 0.88f
                                    val outward = if (horizontal) {
                                        (velocity.x > 0f && atRight) || (velocity.x < 0f && atLeft)
                                    } else {
                                        (velocity.y > 0f && atBottom) || (velocity.y < 0f && atTop)
                                    }
                                    if (outward) {
                                        // Like the close button: it flies off in that direction and playback stops.
                                        flyDir = androidx.compose.ui.geometry.Offset(velocity.x / speed, velocity.y / speed)
                                        scope.launch {
                                            flyP.animateTo(1.15f, tween(260, easing = androidx.compose.animation.core.FastOutLinearInEasing))
                                            controller.dismiss()
                                        }
                                    } else {
                                        // The other way: it is thrown across to the opposite edge and stays on screen.
                                        val toX = if (horizontal) (if (velocity.x > 0f) 1f else 0f) else anchorX.value
                                        val toY = if (horizontal) anchorY.value else (if (velocity.y > 0f) 1f else 0f)
                                        val throwSpec = spring<Float>(dampingRatio = 0.72f, stiffness = 380f)
                                        scope.launch { anchorX.animateTo(toX, throwSpec) }
                                        scope.launch { anchorY.animateTo(toY, throwSpec) }
                                    }
                                    return
                                }
                                val rx = (geo.maxX - geo.minX).coerceAtLeast(1f)
                                val ry = (geo.maxYf() - geo.minY).coerceAtLeast(1f)
                                // Where it would coast to; the edge nearest to that point wins.
                                val fx = (anchorX.value + velocity.x * 0.12f / rx).coerceIn(0f, 1f)
                                val fy = (anchorY.value + velocity.y * 0.12f / ry).coerceIn(0f, 1f)
                                val dl = fx * rx
                                val dr = (1f - fx) * rx
                                val dt = fy * ry
                                val db = (1f - fy) * ry
                                var tx = fx
                                var ty = fy
                                when (minOf(minOf(dl, dr), minOf(dt, db))) {
                                    dl -> tx = 0f
                                    dr -> tx = 1f
                                    dt -> ty = 0f
                                    else -> ty = 1f
                                }
                                val spec = spring<Float>(dampingRatio = 0.72f, stiffness = 380f)
                                scope.launch { anchorX.animateTo(tx, spec) }
                                scope.launch { anchorY.animateTo(ty, spec) }
                            }
                            detectDragGestures(
                                onDragStart = {
                                    total = androidx.compose.ui.geometry.Offset.Zero
                                    tracker.resetTracking()
                                    miniDragging = true
                                    scope.launch { anchorX.stop(); anchorY.stop() }
                                },
                                onDragEnd = {
                                    miniDragging = false
                                    settle(tracker.calculateVelocity())
                                },
                                onDragCancel = {
                                    miniDragging = false
                                    settle(androidx.compose.ui.unit.Velocity.Zero)
                                },
                            ) { change, drag ->
                                change.consume()
                                total += drag
                                tracker.addPosition(change.uptimeMillis, total)
                                val rx = (geo.maxX - geo.minX).coerceAtLeast(1f)
                                val ry = (geo.maxYf() - geo.minY).coerceAtLeast(1f)
                                scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                                    anchorX.snapTo((anchorX.value + drag.x / rx).coerceIn(0f, 1f))
                                    anchorY.snapTo((anchorY.value + drag.y / ry).coerceIn(0f, 1f))
                                }
                            }
                        }
                        .pointerInput(Unit) { detectTapGestures { controller.expand() } }
                        .semantics {
                            role = Role.Button
                            onClick(label = expandLabel) { controller.expand(); true }
                        },
                )
                Box(
                    buttonsRect.graphicsLayer {
                        alpha = btnAlpha.value * awayAlpha()
                        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                    },
                ) {
                    MiniIconButton(
                        Modifier.align(Alignment.TopStart).padding(4.dp),
                        icon = if (isPlaying) VIcon.Pause else VIcon.PlayArrowFilled,
                        label = stringResource(if (isPlaying) R.string.action_pause else R.string.action_play),
                        onClick = controller::togglePlay,
                    )
                    MiniIconButton(
                        Modifier.align(Alignment.TopEnd).padding(4.dp),
                        icon = VIcon.Close,
                        label = stringResource(R.string.action_close_player),
                        onClick = { scope.launch { fadeOutAndDismiss() } },
                    )
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

private class MiniGeo {
    var minX = 0f
    var maxX = 0f
    var minY = 0f
    var maxYf: () -> Float = { 0f }
}

/** Small M3 filled-tonal icon button (32dp container) for the corners of the minimized video. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MiniIconButton(
    modifier: Modifier,
    icon: Int,
    label: String,
    onClick: () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified,
    ) {
        androidx.compose.material3.FilledTonalIconButton(
            onClick = onClick,
            modifier = modifier.size(32.dp),
            colors = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            vibe.tube.ui.components.SwappingIcon(
                icon,
                label,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
