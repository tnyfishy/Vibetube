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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
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
 * Drives the comments panel: it slides up from the bottom over the area under the video and slides back down when
 * closed (X, Back, or pulled down by the finger). Read in the draw phase, so nothing recomposes per frame.
 */
@Stable
internal class CommentsTransition {
    var open by mutableStateOf(false)
        private set
    val progress = Animatable(0f).apply { updateBounds(0f, 1f) }

    var panelH = 1f
        private set

    fun containerPositioned(c: LayoutCoordinates) {
        panelH = c.size.height.toFloat().coerceAtLeast(1f)
    }

    suspend fun show() {
        open = true
        progress.animateTo(1f, tween(260, easing = vibe.tube.ui.components.EmphasizedDecelerate))
    }

    suspend fun hide() {
        progress.animateTo(0f, tween(200, easing = FastOutSlowInEasing))
        open = false
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
    val panelColor = MaterialTheme.colorScheme.surfaceContainerLow
    val topCorner = with(density) { 20.dp.toPx() }

    // The panel follows the finger one to one.
    fun range() = tr.panelH
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
                val p = tr.progress.value
                translationY = (1f - p) * size.height
                alpha = (p * 4f).coerceIn(0f, 1f)
                shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = topCorner, topEnd = topCorner)
                clip = true
            }
            .background(panelColor)
            .pointerInput(Unit) {}
            .nestedScroll(nested),
    ) {
        Column(Modifier.fillMaxSize()) {
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
