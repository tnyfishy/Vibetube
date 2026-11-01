package vibe.tube.feature.watch

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import vibe.tube.R
import vibe.tube.player.PlayerController
import vibe.tube.player.PlayerUiState
import vibe.tube.player.RepeatMode
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.VIconImage
import vibe.tube.ui.components.LocalVideoActions
import vibe.tube.ui.components.VideoActions
import vibe.tube.ui.components.VideoRow
import vibe.tube.ui.theme.LocalReducedMotion
import vibe.tube.ui.theme.Spacing
import vibe.tube.youtube.model.VideoItem
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class QueueView(
    val playlistId: String,
    val title: String?,
    val queue: List<VideoItem>,
    val index: Int,
    val shuffled: Boolean,
    val repeat: RepeatMode,
) {
    val isMix: Boolean get() = playlistId.startsWith("RD")
    val next: VideoItem? get() = queue.getOrNull(index + 1)

    companion object {
                                                                                                                      
        fun of(s: PlayerUiState): QueueView? {
            val id = s.playlistId ?: return null
            if (s.queue.isEmpty()) return null
            return QueueView(id, s.queueTitle, s.queue, s.queueIndex, s.shuffled, s.repeatMode)
        }
    }
}

internal interface QueueControls {
    fun playQueueIndex(index: Int)
    fun toggleShuffle()
    fun setRepeat(mode: RepeatMode)

    val panelRequests: kotlinx.coroutines.flow.StateFlow<Int> get() = kotlinx.coroutines.flow.MutableStateFlow(0)
    fun takePanelRequest(): Boolean = false
}

internal fun PlayerController.queueControls(): QueueControls = object : QueueControls {
    override fun playQueueIndex(index: Int) = this@queueControls.playQueueIndex(index)
    override fun toggleShuffle() = this@queueControls.toggleShuffle()
    override fun setRepeat(mode: RepeatMode) = this@queueControls.setRepeat(mode)
    override val panelRequests get() = this@queueControls.queuePanelRequest
    override fun takePanelRequest() = this@queueControls.takeQueuePanelRequest()
}

@Stable
internal class MixPanelTransition {
                                                               
    var open by mutableStateOf(false)
        private set

    var dragging by mutableStateOf(false)
        internal set

    val progress = Animatable(0f).apply { updateBounds(0f, 1f) }

    internal var container: LayoutCoordinates? = null
    internal var card: LayoutCoordinates? = null
    var cardSize by mutableStateOf(IntSize.Zero)
        internal set
    private var lastCard = Rect.Zero

    fun cardRect(): Rect {
        val c = container
        val k = card
        if (c != null && k != null && c.isAttached && k.isAttached) lastCard = c.localBoundingBoxOf(k, clipBounds = false)
        return lastCard
    }

    fun travel(panelHeight: Float): Float = (panelHeight - cardRect().height).coerceAtLeast(1f)

    suspend fun show(reduced: Boolean, velocity: Float = 0f) {
        open = true
        if (reduced) progress.snapTo(1f) else progress.animateTo(1f, OpenSpring, initialVelocity = velocity)
    }

    suspend fun hide(reduced: Boolean, velocity: Float = 0f) {
        open = false
        if (reduced) progress.snapTo(0f) else progress.animateTo(0f, CloseSpring, initialVelocity = velocity)
    }

    suspend fun snapClosed() {
        open = false
        dragging = false
        progress.snapTo(0f)
    }

    internal fun startDrag() {
        dragging = true
    }
}

private val OpenSpring = spring<Float>(dampingRatio = 0.92f, stiffness = 380f, visibilityThreshold = 0.001f)
private val CloseSpring = spring<Float>(dampingRatio = 1f, stiffness = 520f, visibilityThreshold = 0.001f)

private const val FLING_VELOCITY = 1200f

private fun cardContentAlpha(p: Float) = 1f - (p / 0.22f).coerceIn(0f, 1f)
private fun panelContentAlpha(p: Float) = ((p - 0.18f) / 0.5f).coerceIn(0f, 1f)

private val CardCorner = 16.dp
private val PanelCorner = 18.dp
private val PanelMargin = 0.dp

internal fun LazyListScope.mixCardItem(view: QueueView?, tr: MixPanelTransition) {
    if (view == null) return
    item(key = "mix-card") { MixQueueCard(view, tr) }
}

@Composable
internal fun MixQueueCard(view: QueueView, tr: MixPanelTransition) {
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val openLabel = stringResource(R.string.queue_open)
    Surface(
        onClick = { scope.launch { tr.show(reduced) } },
        shape = RoundedCornerShape(CardCorner),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { onClick(label = openLabel) { scope.launch { tr.show(reduced) }; true } }
            .onGloballyPositioned {
                tr.card = it
                tr.cardSize = it.size
            }
            
            .graphicsLayer { alpha = if (tr.open || tr.dragging || tr.progress.value > 0f) 0f else 1f }
            .pointerInput(tr) {
                
                fun panelSpan() = (tr.container?.size?.height ?: 0) - 2 * PanelMargin.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dy = 0f
                    var drag = false
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val delta = change.positionChange().y
                        if (!drag) {
                            if (change.isConsumed) return@awaitEachGesture
                            dy += delta
                            if (dy < -touchSlop) {
                                drag = true
                                tr.startDrag()
                            } else if (dy > touchSlop) {
                                return@awaitEachGesture
                            } else {
                                continue
                            }
                        }
                        change.consume()
                        val range = tr.travel(panelSpan())
                        val target = (tr.progress.value - delta / range).coerceIn(0f, 1f)
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { tr.progress.snapTo(target) }
                    }
                    if (drag) {
                        val v = tracker.calculateVelocity().y
                        val range = tr.travel(panelSpan())
                        tr.dragging = false
                        scope.launch {
                            if (tr.progress.value > 0.25f || v < -FLING_VELOCITY) tr.show(reduced, -v / range) else tr.hide(reduced, -v / range)
                        }
                    }
                }
            },
    ) {
        MixCardContent(view)
    }
}

@Composable
private fun MixCardContent(
    view: QueueView,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {
        VIconImage(VIcon.ExpandMore, null, Modifier.size(24.dp).graphicsLayer { rotationZ = 180f }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    },
) {
    val label = stringResource(if (view.isMix) R.string.queue_mix else R.string.queue_playlist)
    val next = view.next
    Row(
        modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        MixBadge()
        Column(Modifier.weight(1f)) {
            Text(view.title ?: label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val position = if (view.queue.size > 1) stringResource(R.string.queue_position, view.index + 1, view.queue.size) else null
            val line = listOfNotNull(label.takeIf { view.title != null }, position, next?.let { stringResource(R.string.queue_next, it.title) })
                .joinToString(" · ")
            if (line.isNotEmpty()) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}


internal fun Modifier.mixBarReveal(shown: () -> Float, onHeight: (Int) -> Unit): Modifier = this
    .clipToBounds()
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0))
        onHeight(placeable.height)
        val visible = (placeable.height * shown().coerceIn(0f, 1f)).roundToInt()
        layout(placeable.width, visible) { placeable.place(0, 0) }
    }

@Composable
private fun MixBadge() {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        VIconImage(VIcon.PlaylistPlay, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

private class ContainerShape(private val rect: RoundRect) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = Outline.Rounded(rect)
}

@Composable
internal fun MixQueuePanel(tr: MixPanelTransition, view: QueueView?, expanded: Boolean, controller: QueueControls) {
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    
    LaunchedEffect(expanded) { if (!expanded && (tr.open || tr.progress.value > 0f)) tr.hide(reduced) }
    LaunchedEffect(view == null) { if (view == null) tr.snapClosed() }
    
    LaunchedEffect(controller, view != null, expanded) {
        if (view == null || !expanded) return@LaunchedEffect
        controller.panelRequests.collect { if (it > 0 && controller.takePanelRequest()) tr.show(reduced) }
    }
    val shown by remember { derivedStateOf { tr.open || tr.dragging || tr.progress.value > 0f } }
    BackHandler(enabled = tr.open && view != null) { scope.launch { tr.hide(reduced) } }
    if (!shown || view == null) return

    val density = LocalDensity.current
    val cardCorner = with(density) { CardCorner.toPx() }
    val panelCorner = with(density) { PanelCorner.toPx() }
    val margin = with(density) { PanelMargin.toPx() }
    val lift = with(density) { 6.dp.toPx() }
    val rise = with(density) { 16.dp.toPx() }
    val cardColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val panelColor = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) Color.White else MaterialTheme.colorScheme.surface
    val scrim = MaterialTheme.colorScheme.scrim

    var panelHeight by remember { mutableStateOf(1f) }
    fun range() = tr.travel(panelHeight)
    fun pull(delta: Float): Float {
        val before = tr.progress.value
        val after = (before - delta / range()).coerceIn(0f, 1f)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { tr.progress.snapTo(after) }
        return (before - after) * range()
    }
    suspend fun settle(velocity: Float) {
        tr.dragging = false
        if (tr.progress.value < 0.6f || velocity > FLING_VELOCITY) tr.hide(reduced, -velocity / range())
        else tr.show(reduced, -velocity / range())
    }
    val nested = remember(tr) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (available.y < 0f && tr.progress.value < 1f && source == NestedScrollSource.UserInput) Offset(0f, pull(available.y)) else Offset.Zero

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && source == NestedScrollSource.UserInput) {
                    tr.dragging = true
                    return Offset(0f, pull(available.y))
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (tr.progress.value >= 1f) {
                    tr.dragging = false
                    return Velocity.Zero
                }
                settle(available.y)
                return available
            }
        }
    }
    val dragState = rememberDraggableState { delta -> pull(delta) }

    fun target(host: Size) = Rect(margin, margin, host.width - margin, host.height - margin)
    fun containerRect(p: Float, host: Size): RoundRect {
        val card = tr.cardRect()
        val r = lerp(card, target(host), p)
        return RoundRect(r, CornerRadius(cardCorner + (panelCorner - cardCorner) * p))
    }

    Box(Modifier.fillMaxSize()) {
        
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = tr.progress.value }
                .background(scrim.copy(alpha = 0.32f))
                .pointerInput(tr) { detectTapGestures { scope.launch { tr.hide(reduced) } } },
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = tr.progress.value
                    shape = ContainerShape(containerRect(p, size))
                    clip = true
                    shadowElevation = lift * (p * 3f).coerceAtMost(1f)
                }
                .drawBehind { drawRect(androidx.compose.ui.graphics.lerp(cardColor, panelColor, tr.progress.value.coerceIn(0f, 1f))) },
        ) {
            
            val cardSize = tr.cardSize
            if (cardSize.width > 0) {
                Box(
                    Modifier
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(Constraints.fixed(cardSize.width, cardSize.height))
                            layout(placeable.width, placeable.height) {
                                val host = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                                val r = lerp(tr.cardRect(), target(host), tr.progress.value)
                                placeable.placeWithLayer(r.left.roundToInt(), r.top.roundToInt()) { alpha = cardContentAlpha(tr.progress.value) }
                            }
                        },
                ) { MixCardContent(view) }
            }
            
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(PanelMargin)
                    .onGloballyPositioned { panelHeight = it.size.height.toFloat().coerceAtLeast(1f) }
                    .graphicsLayer {
                        val a = panelContentAlpha(tr.progress.value)
                        alpha = a
                        translationY = (1f - a) * rise
                    }
                    .pointerInput(Unit) {}
                    .nestedScroll(nested),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = dragState,
                            onDragStarted = { tr.startDrag() },
                            onDragStopped = { velocity -> scope.launch { settle(velocity) } },
                        ),
                ) {
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 8.dp)
                            .size(width = 32.dp, height = 4.dp)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape),
                    )
                    MixPanelHeader(view, controller, onClose = { scope.launch { tr.hide(reduced) } })
                }
                MixQueueList(view, controller)
            }
        }
    }
}

@Composable
private fun MixPanelHeader(view: QueueView, controller: QueueControls, onClose: () -> Unit) {
    val label = stringResource(if (view.isMix) R.string.queue_mix else R.string.queue_playlist)
    Column(Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(view.title ?: label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val position = if (view.queue.size > 1) stringResource(R.string.queue_position, view.index + 1, view.queue.size) else null
                Text(
                    listOfNotNull(label, position).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            vibe.tube.ui.components.TooltipIconButton(VIcon.Close, stringResource(R.string.queue_collapse), onClose)
        }
        Row(Modifier.padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            val shuffleLabel = stringResource(R.string.action_shuffle)
            FilledTonalIconToggleButton(
                checked = view.shuffled,
                onCheckedChange = { controller.toggleShuffle() },
                modifier = Modifier.semantics { contentDescription = shuffleLabel },
            ) { VIconImage(VIcon.Shuffle, null, Modifier.size(20.dp)) }
            val repeatLabel = stringResource(R.string.player_repeat)
            val repeatState = stringResource(
                when (view.repeat) {
                    RepeatMode.Off -> R.string.player_repeat_off
                    RepeatMode.All -> R.string.player_repeat_all
                    RepeatMode.One -> R.string.player_repeat_one
                },
            )
            FilledTonalIconToggleButton(
                checked = view.repeat != RepeatMode.Off,
                onCheckedChange = {
                    controller.setRepeat(
                        when (view.repeat) {
                            RepeatMode.Off -> RepeatMode.All
                            RepeatMode.All -> RepeatMode.One
                            RepeatMode.One -> RepeatMode.Off
                        },
                    )
                },
                modifier = Modifier.semantics {
                    contentDescription = repeatLabel
                    stateDescription = repeatState
                },
            ) { VIconImage(if (view.repeat == RepeatMode.One) VIcon.RepeatOne else VIcon.Repeat, null, Modifier.size(20.dp)) }
        }
    }
}

internal fun queueKeys(queue: List<VideoItem>): List<String> {
    val seen = HashMap<String, Int>()
    return queue.map { v ->
        val n = seen.merge(v.id, 1, Int::plus) ?: 1
        if (n == 1) "q:${v.id}" else "q:${v.id}#$n"
    }
}

@Composable
private fun MixQueueList(view: QueueView, controller: QueueControls) {
    val keys = remember(view.queue) { queueKeys(view.queue) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (view.index - 1).coerceAtLeast(0))
    
    LaunchedEffect(view.index) {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isNotEmpty() && visible.none { it.index == view.index && it.offset >= 0 }) {
            listState.animateScrollToItem((view.index - 1).coerceAtLeast(0))
        }
    }
    val base = LocalVideoActions.current
    val nowPlaying = stringResource(R.string.queue_now_playing)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.sm, end = Spacing.sm, bottom = Spacing.md),
    ) {
        items(view.queue.size, key = { keys[it] }) { index ->
            val video = view.queue[index]
            val current = index == view.index
            val actions = remember(base, index) {
                object : VideoActions by base {
                    override fun play(video: VideoItem, queue: List<VideoItem>) = controller.playQueueIndex(index)
                }
            }
            Surface(
                color = if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().then(if (current) Modifier.semantics { stateDescription = nowPlaying } else Modifier),
            ) {
                Box(Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp)) {
                    VideoRow(
                        video = video,
                        queue = emptyList(),
                        animateLaunch = false,
                        thumbnailShape = RoundedCornerShape(8.dp),
                        actions = actions,
                        leading = {
                            Box(Modifier.width(22.dp).padding(top = 18.dp), contentAlignment = Alignment.Center) {
                                if (current) {
                                    VIconImage(VIcon.GraphicEq, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                } else {
                                    Text(
                                        "${index + 1}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

internal fun upNextRelated(
    related: List<vibe.tube.youtube.model.FeedItem>,
    queue: List<VideoItem>,
    currentId: String?,
    playlistId: String?,
    hiddenChannels: Set<String>,
): List<vibe.tube.youtube.model.FeedItem> {
    val visible = related.filterNot { it is VideoItem && (it.id == currentId || it.channelId in hiddenChannels) }
        .distinctBy { it.id }
    val queueIds = queue.mapTo(HashSet()) { it.id }
    val distinct = visible.filterNot { (it is VideoItem && it.id in queueIds) || (playlistId != null && it.id == playlistId) }
    return if (distinct.size >= MIN_UP_NEXT || distinct.size == visible.size) distinct else visible
}

private const val MIN_UP_NEXT = 6
