package vibe.tube.feature.watch

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import vibe.tube.R
import vibe.tube.account.AuthState
import vibe.tube.ui.VIcon
import vibe.tube.ui.theme.Spacing

/**
 * Drives the comments panel as a container transform: it grows out of the comments card (its rect, colour and corners)
 * to fill the area under the video, and shrinks back into exactly that card when closed (X, Back, or pulled down).
 * Everything the animation needs is kept in plain fields and read in the draw phase, so nothing recomposes per frame.
 */
@Stable
internal class CommentsTransition {
    var open by mutableStateOf(false)
        private set
    val progress = Animatable(0f).apply { updateBounds(0f, 1f) }

    private var cardRootX = 0f
    private var cardRootY = 0f
    var cardW = 0f
        private set
    var cardH = 0f
        private set
    private var originX = 0f
    private var originY = 0f
    var panelH = 1f
        private set

    val cardX: Float get() = cardRootX - originX
    val cardY: Float get() = cardRootY - originY

    fun cardPositioned(c: LayoutCoordinates) {
        val p = c.positionInRoot()
        cardRootX = p.x
        cardRootY = p.y
        cardW = c.size.width.toFloat()
        cardH = c.size.height.toFloat()
    }

    fun containerPositioned(c: LayoutCoordinates) {
        val p = c.positionInRoot()
        originX = p.x
        originY = p.y
        panelH = c.size.height.toFloat().coerceAtLeast(1f)
    }

    suspend fun show() {
        open = true
        progress.animateTo(1f, tween(380, easing = vibe.tube.ui.components.EmphasizedDecelerate))
    }

    suspend fun hide() {
        progress.animateTo(0f, tween(300, easing = FastOutSlowInEasing))
        open = false
    }
}

/** The shape of the growing container: a rounded rect interpolated between the card's bounds and the whole panel. */
private class ContainerShape(
    private val tr: CommentsTransition,
    private val p: Float,
    private val startRadius: Float,
    private val endRadius: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        fun l(a: Float, b: Float) = a + (b - a) * p
        val left = l(tr.cardX, 0f)
        val top = l(tr.cardY, 0f)
        val right = l(tr.cardX + tr.cardW, size.width)
        val bottom = l(tr.cardY + tr.cardH, size.height)
        val topR = CornerRadius(l(startRadius, endRadius))
        val bottomR = CornerRadius(l(startRadius, 0f))
        return Outline.Rounded(RoundRect(left, top, right, bottom, topR, topR, bottomR, bottomR))
    }
}

/** The comments panel; composed only while it is open or animating. */
@Composable
internal fun CommentsPanel(tr: CommentsTransition, ui: WatchUiState, auth: AuthState, watch: WatchViewModel, onLogin: () -> Unit) {
    val scope = rememberCoroutineScope()
    val shown by remember { derivedStateOf { tr.open || tr.progress.value > 0f } }
    BackHandler(enabled = tr.open) { scope.launch { tr.hide() } }
    if (!shown) return

    val density = LocalDensity.current
    val startRadius = with(density) { 16.dp.toPx() }
    val endRadius = with(density) { 20.dp.toPx() }
    val cardColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val panelColor = MaterialTheme.colorScheme.surfaceContainerLow

    // How far the finger has to travel to shrink the panel all the way back into the card.
    fun range() = tr.panelH * 0.6f
    fun pull(delta: Float): Float {
        val before = tr.progress.value
        val after = (before - delta / range()).coerceIn(0f, 1f)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { tr.progress.snapTo(after) }
        return (before - after) * range()
    }
    suspend fun settle(velocity: Float) {
        if (tr.progress.value < 0.72f || velocity > 1200f) tr.hide()
        else tr.progress.animateTo(1f, spring(dampingRatio = 1f, stiffness = 500f))
    }
    val nested = remember(tr) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                // Pushing back up while the panel is pulled down first gives the pull back.
                if (available.y < 0f && tr.progress.value < 1f && source == NestedScrollSource.UserInput) Offset(0f, pull(available.y)) else Offset.Zero

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                // The list is at its top and the finger keeps going down: that pulls the panel down.
                if (available.y > 0f && source == NestedScrollSource.UserInput) Offset(0f, pull(available.y)) else Offset.Zero

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (tr.progress.value >= 1f) return Velocity.Zero
                settle(available.y)
                return available
            }
        }
    }
    val dragState = rememberDraggableState { delta -> pull(delta) }

    val listState = rememberLazyListState()
    LaunchedEffect(listState, ui.comments.comments.size) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 4
        }.distinctUntilChanged().filter { it }.collect { watch.loadMoreComments() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                shape = ContainerShape(tr, tr.progress.value, startRadius, endRadius)
                clip = true
            }
            .drawBehind {
                val p = tr.progress.value
                drawRect(lerp(cardColor, panelColor, p), alpha = (p * 3f).coerceIn(0f, 1f))
            }
            .pointerInput(Unit) {}
            .nestedScroll(nested),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = ((tr.progress.value - 0.15f) / 0.45f).coerceIn(0f, 1f)
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = dragState,
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
                Row(Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.comments_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    vibe.tube.ui.components.TooltipIconButton(VIcon.Close, stringResource(R.string.action_close), { scope.launch { tr.hide() } })
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                commentsList(ui, auth, watch, onLogin)
            }
        }
    }
}
