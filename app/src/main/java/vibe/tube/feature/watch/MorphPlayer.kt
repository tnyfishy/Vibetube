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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
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
import kotlinx.coroutines.cancelChildren
import vibe.tube.R
import vibe.tube.player.PlayerController
import vibe.tube.player.PlayerUiState
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.TooltipIconButton
import vibe.tube.ui.theme.Spacing
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.roundToInt

internal const val MINI_WIDTH_FRACTION = 0.53f

private val MORPH_SPEC = spring<Float>(dampingRatio = 1f, stiffness = 309f, visibilityThreshold = 0.0004f)

private val DOCK_SPEC = spring<Float>(dampingRatio = 0.88f, stiffness = 260f, visibilityThreshold = 0.0004f)
private val DOCK_SPEC_CALM = spring<Float>(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = 0.0004f)

private fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun Modifier.placedRect(x: () -> Float, y: () -> Float, w: () -> Float, h: () -> Float, show: () -> Boolean = { true }): Modifier =
    this
        .offset { IntOffset(x().roundToInt(), y().roundToInt()) }
        .layout { measurable, _ ->
            val width = w().roundToInt().coerceAtLeast(0)
            val height = h().roundToInt().coerceAtLeast(0)
            val placeable = measurable.measure(Constraints.fixed(width, height))
            layout(width, height) { if (show()) placeable.place(0, 0) }
        }

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
    val captionCues by controller.captionCues.collectAsStateWithLifecycle()
    val stateHolder = controller.state.collectAsStateWithLifecycle()
    val visible by remember { derivedStateOf { stateHolder.value.visible } }
    val expanded by remember { derivedStateOf { stateHolder.value.expanded } }
    val isPlaying by remember { derivedStateOf { stateHolder.value.isPlaying } }
    val failed by remember { derivedStateOf { stateHolder.value.phase == vibe.tube.player.PlayerPhase.Error } }
    val resumeMarked by remember { derivedStateOf { stateHolder.value.staged } }
    val current by remember { derivedStateOf { stateHolder.value.current } }
    val videoAspect by remember { derivedStateOf { stateHolder.value.videoAspect.coerceIn(1.2f, 2.4f) } }
    val detailState by remember { derivedStateOf { stateHolder.value.copy(positionMs = 0, bufferedMs = 0) } }
    var options by remember { mutableStateOf(false) }
    var chaptersOpen by remember { mutableStateOf(false) }
    
    val knownDuration by remember { derivedStateOf { stateHolder.value.durationMs.takeIf { it > 0 } ?: 0L } }
    val chapters = remember(ui.info, knownDuration) {
        val info = ui.info
        if (info != null) vibe.tube.player.Chapters.of(info.chapters, info.description, knownDuration) else emptyList()
    }
    val activeChapters = if (ui.info?.videoId != null && ui.info?.videoId == current?.id) chapters else emptyList()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val reducedMotion = vibe.tube.ui.theme.LocalReducedMotion.current

    val progress = remember { Animatable(if (visible && expanded) 1f else 0f).apply { updateBounds(0f, 1f) } }
    val dismiss = remember { Animatable(0f) }
    val closeP = remember { Animatable(0f) }
    val fsOffset = remember { Animatable(0f) }
    val enterFade = remember { Animatable(1f) }
    LaunchedEffect(Unit) { if (enterFade.value < 1f) enterFade.animateTo(1f, tween(320)) }
    var pendingVelocity by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    
    var miniPull by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(visible) { if (!visible) miniPull = 0f }
    var dragStartedCollapsed by remember { mutableStateOf(false) }
    val anchorX = remember { Animatable(1f) }
    val anchorY = remember { Animatable(1f) }
    var miniDragging by remember { mutableStateOf(false) }
    val flyP = remember { Animatable(0f) }
    var flyDir by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val away = androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (miniAway && !expanded) 1f else 0f,
        animationSpec = tween(360, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "miniAway",
    )
    val launchSeq = vibe.tube.ui.components.PlayerLaunch.seq

    val launch = remember(launchSeq, visible) { if (visible) vibe.tube.ui.components.PlayerLaunch.take() else null }
    val launchRect = launch?.rect
    val originMix = remember(launchSeq, visible) { Animatable(if (launchRect != null) 1f else 0f) }
    var wasVisible by remember { mutableStateOf(false) }
    var handledLaunchSeq by remember { mutableIntStateOf(-1) }
    var handledVideoId by remember { mutableStateOf<String?>(null) }

    val dragX = remember { Animatable(0f) }
    val parkMix = remember { Animatable(0f) }
    val tabP = remember { Animatable(0f) }
    var parkDir by remember { mutableIntStateOf(0) }
    var parkCy by remember { mutableFloatStateOf(0f) }

    val target = if (visible && expanded) 1f else 0f
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
            snapshotFlow { progress.value >= 1f }.first { it }
            originMix.snapTo(0f)
            flyP.snapTo(0f)
        }
    }
    LaunchedEffect(target, visible, launchSeq, current?.id) {
        scope.coroutineContext.cancelChildren()
        dragging = false
        miniDragging = false
        if (!visible) {
            wasVisible = false
            progress.snapTo(0f)
            dismiss.snapTo(0f)
            closeP.snapTo(0f)
            flyP.snapTo(0f)
            miniPull = 0f
            originMix.snapTo(0f)
            return@LaunchedEffect
        }
        if (!wasVisible) {
            wasVisible = true
            
            progress.snapTo(if (launchRect != null) 0f else target)
        }
        if (handledLaunchSeq != launchSeq || handledVideoId != current?.id) {
            handledLaunchSeq = launchSeq
            handledVideoId = current?.id
            dismiss.snapTo(0f); closeP.snapTo(0f); flyP.snapTo(0f)
            miniPull = 0f
            dragStartedCollapsed = false
            if (launchRect != null && originMix.value > 0f) progress.snapTo(0f)
        }
        if (target == 0f && originMix.value > 0f) originMix.snapTo(0f)
        if (target == 0f && !miniDragging) {
            launch { if (anchorX.value != 1f) anchorX.animateTo(1f, DOCK_SPEC_CALM) }
            launch { if (anchorY.value != 1f) anchorY.animateTo(1f, DOCK_SPEC_CALM) }
        }
        val velocity = pendingVelocity
        pendingVelocity = 0f
        
        if (reducedMotion) progress.snapTo(target)
        else progress.animateTo(target, MORPH_SPEC, initialVelocity = velocity)
    }
    LaunchedEffect(target, visible) {
        if (target == 1f || !visible) {
            parkMix.snapTo(0f); tabP.snapTo(0f); dragX.snapTo(0f); parkDir = 0
        }
    }

    PredictiveBackHandler(enabled = visible && expanded && !detailState.screenLocked) { events ->
        val startValue = progress.value.coerceIn(0.05f, 1f)
        try {
            events.collect { event -> progress.snapTo(startValue * (1f - event.progress * 0.9f)) }
            controller.collapse()
        } catch (cancelled: CancellationException) {
            scope.launch { progress.animateTo(if (stateHolder.value.expanded) 1f else 0f, MORPH_SPEC) }
            throw cancelled
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val topInsetPx = WindowInsets.safeDrawing.getTop(density).toFloat()
        val navInsetPx = WindowInsets.navigationBars.getBottom(density).toFloat()
        val imeInsets = WindowInsets.ime
        val rawBottomInset: () -> Float = {
            val cb = contentBottom()
            max(max(navInsetPx, imeInsets.getBottom(density).toFloat()), if (cb > 0) hPx - cb else 0f)
        }
        val bottomInset: () -> Float = rawBottomInset

        val sidePx = with(density) { 8.dp.toPx() }
        val gapPx = with(density) { 8.dp.toPx() }
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
        val tabW = with(density) { 26.dp.toPx() }
        val tabH = with(density) { 76.dp.toPx() }
        val parkedW = tabW
        val parkedX: () -> Float = { if (parkDir >= 0) wPx - parkedW else 0f }
        val parkedY: () -> Float = { parkCy - parkedW / aspect / 2f }
        val thumbX: () -> Float = { lerpF(lerpF(miniX(), launchRect?.left ?: miniX(), originMix.value), parkedX(), parkMix.value) + dragX.value }
        val thumbY: () -> Float = { lerpF(lerpF(miniY(), launchRect?.top ?: miniY(), originMix.value), parkedY(), parkMix.value) + miniPull }
        val thumbW: () -> Float = { lerpF(lerpF(miniW, launchRect?.width ?: miniW, originMix.value), parkedW, parkMix.value) }
        val thumbH: () -> Float = { thumbW() / aspect }
        val videoH = wPx / aspect
        val dragRange: () -> Float = { max(maxY(), 1f) }
        val slide: () -> Float = { away.value * (wPx - thumbX()) * (1f - progress.value.coerceIn(0f, 1f)) }
        val parkAlpha: () -> Float = {
            val d = 1f - kotlin.math.abs(dragX.value) / (miniW * 1.1f).coerceAtLeast(1f)
            d.coerceIn(0f, 1f) * ((1f - parkMix.value) / 0.5f).coerceIn(0f, 1f)
        }
        val awayAlpha: () -> Float = { (1f - away.value * (1f - progress.value.coerceIn(0f, 1f))) * parkAlpha() }
        val fadeRange = with(density) { 140.dp.toPx() }

        val p: () -> Float = { progress.value.coerceIn(0f, 1f) }
        val t: () -> Float = { progress.value.coerceIn(0f, 1f) }

        var pageComposed by remember { mutableStateOf(false) }
        val opened by remember { derivedStateOf { progress.value > 0.97f || (dragging && progress.value > 0.05f) } }
        val detailsIn = remember { Animatable(0f) }
        LaunchedEffect(opened, visible) { pageComposed = opened || (pageComposed && visible) }
        
        val showPage = pageComposed
        LaunchedEffect(showPage) { if (showPage) detailsIn.animateTo(1f, tween(240)) else detailsIn.snapTo(0f) }
        val miniish by remember { derivedStateOf { progress.value < 0.35f } }
        val pageVisible by remember { derivedStateOf { progress.value > 0.1f } }
        val controlsOn by remember { derivedStateOf { progress.value > 0.97f } }

        suspend fun shrinkAndDismiss() {
            val owner = vibe.tube.ui.components.PlayerLaunch.seq
            val videoId = stateHolder.value.current?.id
            closeP.animateTo(1f, tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            if (owner == vibe.tube.ui.components.PlayerLaunch.seq && videoId == stateHolder.value.current?.id) controller.dismiss()
        }

        suspend fun fadeOutAndDismiss() {
            val owner = vibe.tube.ui.components.PlayerLaunch.seq
            val videoId = stateHolder.value.current?.id
            dismiss.animateTo(fadeRange * 1.1f, tween(180))
            if (owner == vibe.tube.ui.components.PlayerLaunch.seq && videoId == stateHolder.value.current?.id) controller.dismiss()
        }

        val geo = remember { MiniGeo() }
        geo.minX = minX; geo.maxX = maxX; geo.minY = minY; geo.maxYf = maxY
        geo.wPx = wPx; geo.miniW = miniW; geo.miniH = miniH; geo.miniX = miniX; geo.miniY = miniY

        suspend fun parkMini(dir: Int) {
            val cy = geo.miniY() + geo.miniH / 2f
            val travel = if (dir > 0) geo.wPx - geo.miniX() else -(geo.miniX() + geo.miniW)
            dragX.animateTo(travel, tween(170, easing = androidx.compose.animation.core.FastOutLinearInEasing))
            if (stateHolder.value.isPlaying) controller.togglePlay()
            parkDir = dir
            parkCy = cy
            parkMix.snapTo(1f)
            dragX.snapTo(0f)
            tabP.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 420f))
        }

        fun unparkMini() {
            if (parkDir == 0) return
            scope.launch {
                anchorX.snapTo(if (parkDir > 0) 1f else 0f)
                launch { tabP.animateTo(0f, tween(240, easing = vibe.tube.ui.components.EmphasizedDecelerate)) }
                parkMix.animateTo(0f, spring(dampingRatio = 0.92f, stiffness = 160f, visibilityThreshold = 0.0004f))
            }
        }

        val swipeToggleState = androidx.compose.runtime.rememberUpdatedState(LocalFullscreenSwipeToggle.current)
        val dragOwner = remember { IntArray(1) { vibe.tube.ui.components.PlayerLaunch.seq } }
        val dragVideoOwner = remember { arrayOfNulls<String>(1) }
        val dragState = rememberDraggableState { delta ->
            if (dragOwner[0] != vibe.tube.ui.components.PlayerLaunch.seq || dragVideoOwner[0] != stateHolder.value.current?.id) return@rememberDraggableState
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                if (swipeToggleState.value && progress.value >= 0.999f && !stateHolder.value.fullscreen && (delta < 0f || fsOffset.value < 0f)) {
                    fsOffset.snapTo((fsOffset.value + delta).coerceAtMost(0f))
                } else if (progress.value <= 0.002f && dragStartedCollapsed && (delta > 0f || dismiss.value > 0f)) {
                    dismiss.snapTo((dismiss.value + delta).coerceAtLeast(0f))
                } else {
                    progress.snapTo((progress.value - delta / dragRange()).coerceIn(0f, 1f))
                }
            }
        }
        val dragToken = remember { IntArray(1) }
        
        val flingPx = with(density) { 300.dp.toPx() }
        val dismissFlingPx = with(density) { 520.dp.toPx() }
        val dragModifier = Modifier.draggable(
            orientation = Orientation.Vertical,
            state = dragState,
            onDragStarted = {
                dragOwner[0] = vibe.tube.ui.components.PlayerLaunch.seq
                dragVideoOwner[0] = stateHolder.value.current?.id
                dragToken[0]++
                dragging = true
                if (originMix.value > 0f) originMix.snapTo(0f)
                
                dragStartedCollapsed = progress.value <= 0.002f
            },
            onDragStopped = { velocity ->
                val myToken = dragToken[0]
                scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    if (dragOwner[0] != vibe.tube.ui.components.PlayerLaunch.seq || dragVideoOwner[0] != stateHolder.value.current?.id) return@launch
                    try {
                    if (dismiss.value > 0f) {
                        val threshold = with(density) { 72.dp.toPx() }
                        if (dismiss.value > threshold || velocity > dismissFlingPx) {
                            fadeOutAndDismiss()
                        } else {
                            dismiss.animateTo(0f, tween(220, easing = vibe.tube.ui.components.EmphasizedDecelerate))
                        }
                    } else {
                        if (fsOffset.value < 0f && velocity > flingPx) fsOffset.snapTo(0f)
                        if (fsOffset.value < 0f) {
                            if (-fsOffset.value > 80f || velocity < -800f) {
                                fullscreenToggledAt = System.currentTimeMillis()
                                controller.setFullscreen(true)
                            } else {
                                fsOffset.animateTo(0f, tween(220, easing = vibe.tube.ui.components.EmphasizedDecelerate))
                            }
                            return@launch
                        }
                        val v = -velocity / dragRange()
                        val projected = progress.value + v * 0.14f
                        val goExpanded = when {
                            velocity > flingPx -> false
                            velocity < -flingPx -> true
                            else -> projected > 0.5f
                        }
                        if (goExpanded != stateHolder.value.expanded) {
                            pendingVelocity = v
                            if (goExpanded) controller.expand() else controller.collapse()
                        }
                        progress.animateTo(if (goExpanded) 1f else 0f, MORPH_SPEC, initialVelocity = v)
                    }
                    } finally {
                        if (dragToken[0] == myToken) dragging = false
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
                    val cp = closeP.value
                    alpha = (1f - dismiss.value / fadeRange).coerceIn(0f, 1f) * enterFade.value * (1f - flyP.value).coerceIn(0f, 1f) * (1f - cp)
                    val closeScale = 1f - 0.45f * cp
                    scaleX = closeScale
                    scaleY = closeScale
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                    if (cp > 0f) {
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                            ((miniX() + miniW / 2f) / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f),
                            ((miniY() + miniH / 2f) / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f),
                        )
                    }
                    translationX = flyDir.x * flyP.value * wPx
                    translationY = dismiss.value * 0.5f + flyDir.y * flyP.value * wPx
                },
        ) {

            Box(
                Modifier
                    .fillMaxSize()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) { if (pageVisible) placeable.place(0, 0) }
                    }
                    .graphicsLayer {
                        alpha = ((p() - 0.1f) / 0.45f).coerceIn(0f, 1f)
                        scaleX = 1f; scaleY = 1f
                        translationY = (1f - progress.value.coerceIn(0f, 1f)) * hPx * 0.16f
                        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                    }
                    .drawBehind {
                        val extra = hPx * 0.2f
                        drawRect(
                            pageColor,
                            topLeft = androidx.compose.ui.geometry.Offset(-extra, -extra),
                            size = androidx.compose.ui.geometry.Size(size.width + 2f * extra, size.height + 2f * extra),
                        )
                    }
                    .pointerInput(Unit) {},
            ) {
                if (showPage) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = ((p() - 0.55f) / 0.3f).coerceIn(0f, 1f) * detailsIn.value
                                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                            },
                    ) {
                        Spacer(Modifier.height(with(density) { (topInsetPx + videoH).toDp() }))
                        SheetUnderVideo(Modifier.weight(1f), stripAlpha = { ((p() - 0.975f) / 0.025f).coerceIn(0f, 1f) }) {
                            Details(ui, detailState, controller, watch, auth, onLogin, includeTabs = true, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }

            Box(
                Modifier
                    .layout { measurable, _ ->
                        val placeable = measurable.measure(Constraints.fixed(wPx.roundToInt(), videoH.roundToInt()))
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                    .graphicsLayer {
                        val sc = (lerpF(thumbW(), wPx, p()) / wPx).coerceAtLeast(0.001f)
                        var tx = lerpF(thumbX(), 0f, p()) + slide()
                        var ty = lerpF(thumbY(), topInsetPx, p())
                        var corner = lerpF(12.dp.toPx(), 0f, t())
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        scaleX = sc
                        scaleY = sc
                        translationX = tx
                        translationY = ty
                        shape = RoundedCornerShape(CornerSize(corner / sc))
                        clip = true
                        val upwardTravel = -fsOffset.value
                        val renderedScale = fullscreenEntryScale(sc, p(), upwardTravel)
                        scaleX = renderedScale; scaleY = renderedScale
                        alpha = awayAlpha()
                    },
            ) {
                val surfaceState by remember { derivedStateOf { if (controlsOn) stateHolder.value else detailState } }
                VideoSurface(
                    player = player,
                    state = surfaceState,
                    showControls = controlsOn,
                    onCollapse = controller::collapse,
                    onToggleFullscreen = { controller.setFullscreen(!stateHolder.value.fullscreen) },
                    onOptions = { options = true },
                    onSelectQuality = controller::selectQuality,
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
                    captionCues = captionCues,
                    captionsAvailable = surfaceState.captions.isNotEmpty(),
                    captionsOn = surfaceState.selectedCaption != null,
                    onToggleCaptions = controller::toggleCaptions,
                    onUnlock = { controller.setScreenLocked(!controller.state.value.screenLocked) },
                    poster = {
                val video = current
                if (video != null) {
                    var firstFrame by remember(video.id) { mutableStateOf(false) }
                    LaunchedEffect(video.id) {
                        snapshotFlow { stateHolder.value.phase == vibe.tube.player.PlayerPhase.Ready }.first { it }
                        firstFrame = true
                    }
                    val posterAlpha by androidx.compose.animation.core.animateFloatAsState(
                        if (firstFrame) 0f else 1f,
                        tween(if (firstFrame) 120 else 0),
                        label = "poster",
                    )
                    if (posterAlpha > 0.001f) {

                        val launchImage = launch?.image?.takeIf { launch.videoId == video.id }
                        val posterUrls = remember(video.id, launchImage) {
                            (listOfNotNull(launchImage) + vibe.tube.ui.components.thumbnailCandidates(video)).distinct()
                        }
                        vibe.tube.ui.components.RemoteImage(
                            urls = posterUrls,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = posterAlpha },
                        )
                    }
                }
                    },
                )

            }
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
                            show = { t() < 0.5f && originMix.value < 0.01f },
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

            run {
                val miniSettled by remember {
                    derivedStateOf { visible && !expanded && progress.value <= 0.0004f && !progress.isRunning &&
                        !dragging && !miniDragging && !anchorX.isRunning && !anchorY.isRunning &&
                        miniPull == 0f && parkMix.value < 0.01f && away.value < 0.01f }
                }
                val btnAlpha = androidx.compose.animation.core.animateFloatAsState(
                    if (miniSettled) 1f else 0f, tween(if (miniSettled) 180 else 50), label = "miniButtonsAfterArrival")
                val miniRect = Modifier.placedRect({ thumbX() + slide() }, thumbY, thumbW, thumbH, show = { (miniish || dragging) && originMix.value < 0.01f && away.value < 0.5f && parkMix.value < 0.01f })
                val buttonsRect = Modifier.placedRect({ thumbX() + slide() }, thumbY, thumbW, thumbH, show = { btnAlpha.value > 0.01f && originMix.value < 0.01f && away.value < 0.5f })
                val expandLabel = stringResource(R.string.action_expand_player)
                Box(
                    miniRect
                        .pointerInput(launchSeq, current?.id) {
                            var total = androidx.compose.ui.geometry.Offset.Zero
                            var gestureOwner = vibe.tube.ui.components.PlayerLaunch.seq
                            var gestureVideo = stateHolder.value.current?.id
                            fun ownsGesture() = gestureOwner == vibe.tube.ui.components.PlayerLaunch.seq && gestureVideo == stateHolder.value.current?.id
                            var startedAt = 0L
                            var startAx = 1f
                            var startAy = 1f
                            val tapSlop = with(density) { 28.dp.toPx() }
                            val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                            fun settle(velocity: androidx.compose.ui.unit.Velocity) {
                                
                                if (miniPull > with(density) { 56.dp.toPx() }) {
                                    scope.launch { fadeOutAndDismiss() }
                                    return
                                }
                                if (miniPull > 0f) {
                                    scope.launch { androidx.compose.animation.core.animate(miniPull, 0f, animationSpec = DOCK_SPEC) { v, _ -> miniPull = v } }
                                }
                                val speed = kotlin.math.hypot(velocity.x, velocity.y)
                                if (speed > with(density) { 1500.dp.toPx() }) {
                                    val horizontal = kotlin.math.abs(velocity.x) >= kotlin.math.abs(velocity.y)
                                    val atLeft = anchorX.value <= 0.12f
                                    val atRight = anchorX.value >= 0.88f
                                    val atBottom = anchorY.value >= 0.88f
                                    if (horizontal && ((velocity.x > 0f && atRight) || (velocity.x < 0f && atLeft))) {
                                        scope.launch { parkMini(if (velocity.x > 0f) 1 else -1) }
                                        return
                                    }
                                    if (!horizontal && velocity.y > 0f && atBottom) {
                                        scope.launch { fadeOutAndDismiss() }
                                        return
                                    }
                                    val toX = if (horizontal) (if (velocity.x > 0f) 1f else 0f) else anchorX.value
                                    val toY = if (horizontal) anchorY.value else (if (velocity.y > 0f) 1f else 0f)
                                    scope.launch { anchorX.animateTo(toX, DOCK_SPEC) }
                                    scope.launch { anchorY.animateTo(toY, DOCK_SPEC) }
                                    return
                                }
                                val rx = (geo.maxX - geo.minX).coerceAtLeast(1f)
                                val ry = (geo.maxYf() - geo.minY).coerceAtLeast(1f)
                                
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
                                scope.launch { anchorX.animateTo(tx, DOCK_SPEC) }
                                scope.launch { anchorY.animateTo(ty, DOCK_SPEC) }
                            }
                            detectDragGestures(
                                onDragStart = {
                                    gestureOwner = vibe.tube.ui.components.PlayerLaunch.seq
                                    gestureVideo = stateHolder.value.current?.id
                                    startedAt = System.currentTimeMillis()
                                    startAx = anchorX.value
                                    startAy = anchorY.value
                                    total = androidx.compose.ui.geometry.Offset.Zero
                                    tracker.resetTracking()
                                    miniDragging = true
                                    scope.launch { anchorX.stop(); anchorY.stop() }
                                },
                                onDragEnd = {
                                    miniDragging = false
                                    if (!ownsGesture()) return@detectDragGestures
                                    if (System.currentTimeMillis() - startedAt < 400L && kotlin.math.hypot(total.x, total.y) < tapSlop) {
                                        scope.launch { anchorX.snapTo(startAx); anchorY.snapTo(startAy) }
                                        controller.expand()
                                    } else {
                                        settle(tracker.calculateVelocity())
                                    }
                                },
                                onDragCancel = {
                                    miniDragging = false
                                    if (ownsGesture()) settle(androidx.compose.ui.unit.Velocity.Zero)
                                },
                            ) { change, drag ->
                                if (!ownsGesture()) return@detectDragGestures
                                change.consume()
                                total += drag
                                tracker.addPosition(change.uptimeMillis, total)
                                val rx = (geo.maxX - geo.minX).coerceAtLeast(1f)
                                val ry = (geo.maxYf() - geo.minY).coerceAtLeast(1f)
                                scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                                    anchorX.snapTo((anchorX.value + drag.x / rx).coerceIn(0f, 1f))
                                    if (miniPull > 0f || (anchorY.value >= 0.999f && drag.y > 0f)) {
                                        miniPull = (miniPull + drag.y).coerceAtLeast(0f)
                                    } else {
                                        anchorY.snapTo((anchorY.value + drag.y / ry).coerceIn(0f, 1f))
                                    }
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
                        icon = when {
                            failed -> VIcon.Refresh
                            isPlaying -> VIcon.Pause
                            else -> VIcon.PlayArrowFilled
                        },
                        label = stringResource(
                            when {
                                failed -> R.string.action_retry
                                isPlaying -> R.string.action_pause
                                else -> R.string.action_play
                            },
                        ),
                        onClick = controller::togglePlay,
                    )
                    if (failed) {
                        androidx.compose.material3.Surface(
                            modifier = Modifier.align(Alignment.Center),
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ) {
                            vibe.tube.ui.components.VIconImage(VIcon.Error, null, Modifier.padding(6.dp).size(20.dp))
                        }
                    }
                    MiniIconButton(
                        Modifier.align(Alignment.TopEnd).padding(4.dp),
                        icon = VIcon.Close,
                        label = stringResource(R.string.action_close_player),
                        onClick = { scope.launch { shrinkAndDismiss() } },
                    )
                    if (resumeMarked) {
                        ResumeChip(Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 9.dp))
                    }
                }
                val tabColor = MaterialTheme.colorScheme.secondaryContainer
                val tabContent = MaterialTheme.colorScheme.onSecondaryContainer
                val tabShape = if (parkDir >= 0) {
                    RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
                } else {
                    RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
                }
                Box(
                    Modifier
                        .placedRect(
                            x = { (if (parkDir >= 0) wPx - tabW else 0f) + (1f - tabP.value) * tabW * (if (parkDir >= 0) 1f else -1f) },
                            y = { parkCy - tabH / 2f },
                            w = { tabW },
                            h = { tabH },
                            show = { parkDir != 0 && tabP.value > 0.01f && away.value < 0.5f },
                        )
                        .graphicsLayer { alpha = (1f - away.value) * tabP.value.coerceIn(0f, 1f) }
                        .clip(tabShape)
                        .background(tabColor)
                        .pointerInput(Unit) { detectTapGestures { unparkMini() } }
                        .semantics {
                            role = Role.Button
                            onClick(label = expandLabel) { unparkMini(); true }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        painter = androidx.compose.ui.res.painterResource(VIcon.KeyboardArrowDown),
                        contentDescription = null,
                        tint = tabContent,
                        modifier = Modifier
                            .size(22.dp)
                            .graphicsLayer { rotationZ = if (parkDir >= 0) 90f else -90f },
                    )
                }
            }
        }
        if (options) OptionsHost(stateHolder, controller) { options = false }
        if (chaptersOpen && activeChapters.isNotEmpty()) {
            ChaptersHost(stateHolder, activeChapters, controller::seekTo, with(density) { (hPx - topInsetPx - videoH).coerceAtLeast(80.dp.toPx()).toDp() }) { chaptersOpen = false }
        }
    }
}

@Composable
private fun OptionsHost(state: androidx.compose.runtime.State<PlayerUiState>, controller: PlayerController, onDismiss: () -> Unit) {
    PlayerOptionsSheet(state.value, controller, onDismiss = onDismiss)
}

@Composable
private fun ChaptersHost(state: androidx.compose.runtime.State<PlayerUiState>, chapters: List<vibe.tube.player.Chapter>, onSeek: (Long) -> Unit, maxHeight: androidx.compose.ui.unit.Dp, onDismiss: () -> Unit) {
    val position = state.value.positionMs
    ChaptersSheet(chapters, position, onSeek, maxHeight, onDismiss)
}

@Composable
internal fun ResumeChip(modifier: Modifier = Modifier) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.95f),
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp),
        ) {
            vibe.tube.ui.components.VIconImage(VIcon.History, null, Modifier.size(12.dp))
            androidx.compose.material3.Text(
                stringResource(R.string.resume_watching),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

private class MiniGeo {
    var minX = 0f
    var maxX = 0f
    var minY = 0f
    var maxYf: () -> Float = { 0f }
    var wPx = 0f
    var miniW = 0f
    var miniH = 0f
    var miniX: () -> Float = { 0f }
    var miniY: () -> Float = { 0f }
}

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


internal fun fullscreenEntryScale(base: Float, progress: Float, upwardTravel: Float): Float =
    base * if (progress >= 0.999f && upwardTravel > 0f) 1f + 0.06f * upwardTravel / (upwardTravel + 60f) else 1f
