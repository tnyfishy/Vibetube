package vibe.tube.feature.watch

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import vibe.tube.R
import vibe.tube.account.AuthState
import vibe.tube.player.PlayerController
import vibe.tube.player.PlayerUiState
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.Avatar
import vibe.tube.ui.components.LocalVideoActions
import vibe.tube.ui.components.VIconImage
import vibe.tube.ui.components.VideoRow
import vibe.tube.ui.components.StateMessage
import vibe.tube.ui.theme.Sizing
import vibe.tube.ui.theme.Spacing
import vibe.tube.ui.theme.VibeMotion
import vibe.tube.youtube.model.LikeStatus
import vibe.tube.youtube.model.PlaylistItem
import vibe.tube.youtube.model.VideoItem
import kotlin.math.roundToInt

/** Wide windows, landscape phones, fullscreen and picture-in-picture keep the classic page + mini bar. */
fun usesClassicPlayer(state: PlayerUiState, widthDp: Int, heightDp: Int): Boolean =
    usesClassicPlayer(state.fullscreen, state.inPip, widthDp, heightDp)

fun usesClassicPlayer(fullscreen: Boolean, inPip: Boolean, widthDp: Int, heightDp: Int): Boolean =
    fullscreen || inPip || widthDp >= 840 || (heightDp < 480 && widthDp > heightDp)

/** Wall-clock time of the last fullscreen toggle by swipe; the incoming layout fades in when it is recent. */
internal var fullscreenToggledAt = 0L

@Composable
fun PlayerOverlay(
    player: Player,
    controller: PlayerController,
    watch: WatchViewModel,
    onLogin: () -> Unit,
    onEnterPip: (() -> Unit)?,
    contentBottomPx: Int = 0,
    modifier: Modifier = Modifier,
) {
    val config = LocalConfiguration.current
    // Which layout is used only depends on these two flags; keep this scope from recomposing on every position tick.
    val layoutFlags by remember { controller.state.map { it.fullscreen to it.inPip }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = false to false)
    if (!usesClassicPlayer(layoutFlags.first, layoutFlags.second, config.screenWidthDp, config.screenHeightDp)) {
        MorphPlayer(player, controller, watch, onLogin, onEnterPip, contentBottomPx, modifier)
        return
    }
    val state by controller.state.collectAsStateWithLifecycle()
    val expanded = state.visible && state.expanded
    BackHandler(enabled = expanded) {
        if (state.fullscreen) controller.setFullscreen(false) else controller.collapse()
    }
    AnimatedVisibility(
        visible = expanded,
        enter = slideInVertically(VibeMotion.spatial()) { it } + fadeIn(VibeMotion.effects()),
        exit = slideOutVertically(VibeMotion.spatial()) { it } + fadeOut(VibeMotion.effects()),
        modifier = modifier,
    ) {
        PlayerPage(player, controller, watch, state, onLogin, onEnterPip)
    }
}

@Composable
private fun PlayerPage(
    player: Player,
    controller: PlayerController,
    watch: WatchViewModel,
    state: PlayerUiState,
    onLogin: () -> Unit,
    onEnterPip: (() -> Unit)?,
) {
    val ui by watch.state.collectAsStateWithLifecycle()
    val auth by watch.authState.collectAsStateWithLifecycle()
    var options by remember { mutableStateOf(false) }
    var chaptersOpen by remember { mutableStateOf(false) }
    val chapters = remember(ui.info?.videoId, ui.info?.description) {
        ui.info?.let { vibe.tube.player.Chapters.parse(it.description) } ?: emptyList()
    }
    val activeChapters = if (ui.info?.videoId != null && ui.info?.videoId == state.current?.id) chapters else emptyList()
    val scope = rememberCoroutineScope()
    val dragOffset = remember { Animatable(0f) }
    val spring = VibeMotion.spatial<Float>()

    val dragModifier = Modifier.draggable(
        orientation = Orientation.Vertical,
        state = rememberDraggableState { delta -> scope.launch { dragOffset.snapTo((dragOffset.value + delta).coerceAtLeast(0f)) } },
        onDragStopped = { velocity ->
            if (dragOffset.value > 220f || velocity > 1800f) {
                controller.collapse()
                dragOffset.snapTo(0f)
            } else {
                dragOffset.animateTo(0f, spring)
            }
        },
    )

    val fsPull = remember { Animatable(0f) }
    val fsEnter = remember { Animatable(if (System.currentTimeMillis() - fullscreenToggledAt < 900) 0f else 1f) }
    LaunchedEffect(Unit) { if (fsEnter.value < 1f) fsEnter.animateTo(1f, tween(320)) }
    val fsRangePx = with(androidx.compose.ui.platform.LocalDensity.current) { 120.dp.toPx() }
    val exitFullscreenDrag = Modifier.draggable(
        orientation = Orientation.Vertical,
        state = rememberDraggableState { delta -> scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { fsPull.snapTo((fsPull.value + delta).coerceIn(0f, fsRangePx)) } },
        onDragStopped = { velocity ->
            if (fsPull.value > fsRangePx * 0.45f || velocity > 900f) {
                fsPull.animateTo(fsRangePx, tween(160))
                fullscreenToggledAt = System.currentTimeMillis()
                controller.setFullscreen(false)
            } else {
                fsPull.animateTo(0f, tween(220))
            }
        },
    )

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = (1f - fsPull.value / fsRangePx).coerceIn(0f, 1f) * fsEnter.value
                translationY = fsPull.value
            }
            .offset { IntOffset(0, dragOffset.value.roundToInt()) },
        color = MaterialTheme.colorScheme.surface,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            val landscapePhone = maxHeight < 480.dp && maxWidth > maxHeight
            val fullscreen = state.fullscreen || landscapePhone
            val insets = if (fullscreen || state.inPip) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)

            val surface: @Composable (Modifier) -> Unit = { m ->
                VideoSurface(
                    player = player,
                    state = state,
                    showControls = true,
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
                    modifier = m,
                    dragModifier = if (state.fullscreen) exitFullscreenDrag else if (fullscreen) Modifier else dragModifier,
                    chapters = activeChapters,
                    onChapters = { chaptersOpen = true },
                )
            }

            when {
                state.inPip || fullscreen -> surface(Modifier.fillMaxSize())
                wide -> Row(Modifier.fillMaxSize().then(insets)) {
                    Column(Modifier.weight(0.62f).fillMaxHeight()) {
                        surface(Modifier.fillMaxWidth().aspectRatio(state.videoAspect.coerceIn(1.2f, 2.4f)))
                        Details(ui, state, controller, watch, auth, onLogin, includeTabs = false, modifier = Modifier.weight(1f))
                    }
                    SidePanel(ui, state, controller, watch, auth, onLogin, Modifier.weight(0.38f).fillMaxHeight())
                }
                else -> Column(Modifier.fillMaxSize().then(insets)) {
                    surface(Modifier.fillMaxWidth().aspectRatio(Sizing.THUMBNAIL_ASPECT))
                    Details(ui, state, controller, watch, auth, onLogin, includeTabs = true, modifier = Modifier.weight(1f))
                }
            }
        }
    }
    if (options) PlayerOptionsSheet(state, controller) { options = false }
    if (chaptersOpen && activeChapters.isNotEmpty()) {
        ChaptersSheet(activeChapters, state.positionMs, controller::seekTo) { chaptersOpen = false }
    }
}

@Composable
private fun ActionRow(
    ui: WatchUiState,
    state: PlayerUiState,
    watch: WatchViewModel,
    onLogin: () -> Unit,
) {
    val actions = LocalVideoActions.current
    val liked = ui.likeStatus == LikeStatus.Like
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = liked,
            enabled = !ui.likeBusy,
            onClick = { watch.toggleLike(onLogin) },
            label = { Text(ui.info?.likeCountText ?: stringResource(R.string.action_like)) },
            leadingIcon = { VIconImage(if (liked) VIcon.ThumbUpFilled else VIcon.ThumbUp, null) },
        )
        AssistChip(
            onClick = watch::requestSave,
            label = { Text(stringResource(R.string.action_save_short)) },
            leadingIcon = { VIconImage(VIcon.PlaylistAdd, null) },
        )
        AssistChip(
            onClick = { state.current?.let(actions::watchLater) },
            label = { Text(stringResource(R.string.action_watch_later)) },
            leadingIcon = { VIconImage(VIcon.Schedule, null) },
        )
        AssistChip(
            onClick = { state.current?.let(actions::download) },
            label = { Text(stringResource(R.string.action_download)) },
            leadingIcon = { VIconImage(VIcon.Download, null) },
        )
        AssistChip(
            onClick = { state.current?.let(actions::share) },
            label = { Text(stringResource(R.string.action_share)) },
            leadingIcon = { VIconImage(VIcon.OpenInNew, null) },
        )
    }
}

@Composable
internal fun Details(
    ui: WatchUiState,
    state: PlayerUiState,
    controller: PlayerController,
    watch: WatchViewModel,
    auth: AuthState,
    onLogin: () -> Unit,
    includeTabs: Boolean,
    modifier: Modifier,
) {
    var tab by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val hiddenChannels = vibe.tube.ui.components.LocalHiddenChannels.current
    val comments = ui.comments
    LaunchedEffect(listState, tab, comments.comments.size, ui.related.size) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 4
        }.distinctUntilChanged().filter { it }.collect {
            if (includeTabs && tab == 1) watch.loadMoreComments() else if (includeTabs) watch.loadMoreRelated()
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        detailsHeader(ui, state, watch, onLogin)
        if (includeTabs) {
            item(key = "tabs") {
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.player_tab_up_next)) })
                    Tab(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        text = { Text(comments.totalText?.let { stringResource(R.string.comments_title_with_total, it) } ?: stringResource(R.string.comments_title)) },
                    )
                }
            }
            if (tab == 0) upNext(ui, state, controller, watch, hiddenChannels) else commentsList(ui, auth, watch, onLogin)
        }
    }
}

@Composable
private fun SidePanel(
    ui: WatchUiState,
    state: PlayerUiState,
    controller: PlayerController,
    watch: WatchViewModel,
    auth: AuthState,
    onLogin: () -> Unit,
    modifier: Modifier,
) {
    var tab by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val hiddenChannels = vibe.tube.ui.components.LocalHiddenChannels.current
    LaunchedEffect(listState, tab, ui.comments.comments.size, ui.related.size) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 4
        }.distinctUntilChanged().filter { it }.collect { if (tab == 1) watch.loadMoreComments() else watch.loadMoreRelated() }
    }
    Column(modifier.windowInsetsPadding(WindowInsets.statusBars)) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.player_tab_up_next)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.comments_title)) })
        }
        LazyColumn(
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (tab == 0) upNext(ui, state, controller, watch, hiddenChannels) else commentsList(ui, auth, watch, onLogin)
        }
    }
}

private fun LazyListScope.detailsHeader(ui: WatchUiState, state: PlayerUiState, watch: WatchViewModel, onLogin: () -> Unit) {
    val info = ui.info
    item(key = "title") {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(info?.title?.ifBlank { null } ?: state.current?.title.orEmpty(), style = MaterialTheme.typography.titleLarge)
            val meta = listOfNotNull(info?.viewCountText, info?.dateText).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    item(key = "actions") { ActionRow(ui, state, watch, onLogin) }
    item(key = "channel") {
        val channel = info?.channel
        val actions = LocalVideoActions.current
        if (channel != null) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Row(
                    Modifier.weight(1f).clickable(enabled = channel.id != null) { channel.id?.let(actions::openChannel) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Avatar(channel.avatar, Sizing.avatarLarge)
                    Column {
                        vibe.tube.ui.components.ChannelMetaText(
                            channel.name, channel.badge, emptyList(),
                            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                        )
                        channel.subscriberText?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (ui.subscribed == true) {
                    FilledTonalButton(shapes = ButtonDefaults.shapes(), onClick = { watch.toggleSubscribe(onLogin) }, enabled = !ui.subscribeBusy) { Text(stringResource(R.string.action_subscribed)) }
                } else {
                    Button(shapes = ButtonDefaults.shapes(), onClick = { watch.toggleSubscribe(onLogin) }, enabled = !ui.subscribeBusy && channel.id != null) { Text(stringResource(R.string.action_subscribe)) }
                }
            }
        }
    }
    item(key = "description") {
        var open by remember(info?.videoId) { mutableStateOf(false) }
        val text = info?.description
        if (!text.isNullOrBlank()) {
            Column(
                Modifier.fillMaxWidth().clickable { open = !open },
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (open) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(if (open) R.string.action_show_less else R.string.action_show_more),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    if (ui.error != null && ui.info == null) {
        item(key = "watch-error") {
            StateMessage(
                VIcon.Error,
                stringResource(R.string.player_details_error),
                null,
                primaryLabel = stringResource(R.string.action_retry),
                onPrimary = watch::retry,
            )
        }
    }
}

private fun LazyListScope.upNext(ui: WatchUiState, state: PlayerUiState, controller: PlayerController, watch: WatchViewModel, hiddenChannels: Set<String>) {
    if (state.queue.size > 1) {
        item(key = "queue-title") {
            Text(
                stringResource(R.string.player_queue, state.queueIndex + 1, state.queue.size),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        items(state.queue.size, key = { "q:${state.queue[it].id}:$it" }) { index ->
            val video = state.queue[index]
            val current = index == state.queueIndex
            // The highlight reaches 8dp beyond the row on every side, so the thumbnail sits inside it with
            // concentric corners (outer 16dp, inner 8dp) instead of poking out of the rounded frame.
            Surface(
                color = if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.bleedHorizontally(8.dp),
            ) {
                Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    VideoRow(
                        video = video,
                        queue = emptyList(),
                        thumbnailShape = RoundedCornerShape(8.dp),
                        actions = object : vibe.tube.ui.components.VideoActions by LocalVideoActionsDelegate.current {
                            override fun play(video: VideoItem, queue: List<VideoItem>) = controller.playQueueIndex(index)
                        },
                    )
                }
            }
        }
        item(key = "related-title") { Text(stringResource(R.string.player_related), style = MaterialTheme.typography.titleSmall) }
    }
    val queueIds = state.queue.mapTo(HashSet()) { it.id }
    val related = ui.related.filterNot { it is VideoItem && (it.id in queueIds || it.channelId in hiddenChannels) }
    items(related.size, key = { "r:${related[it].id}:$it" }) { index ->
        when (val item = related[index]) {
            is VideoItem -> VideoRow(item, emptyList())
            is PlaylistItem -> vibe.tube.ui.components.PlaylistRow(item)
            else -> Unit
        }
    }
    if (ui.relatedLoading) item(key = "related-loading") { Box(Modifier.fillMaxWidth().padding(Spacing.md), contentAlignment = Alignment.Center) { androidx.compose.material3.ContainedLoadingIndicator() } }
}

private object LocalVideoActionsDelegate {
    val current: vibe.tube.ui.components.VideoActions
        @Composable get() = LocalVideoActions.current
}

private fun LazyListScope.commentsList(ui: WatchUiState, auth: AuthState, watch: WatchViewModel, onLogin: () -> Unit) {
    val c = ui.comments
    item(key = "comments-header") {
        CommentsHeader(c, auth, onSort = watch::changeSort, onPost = { text, done -> watch.postComment(text, done) }, onLogin = onLogin)
    }
    item(key = "comments-state") { CommentsEmptyStates(c, watch::retryComments) }
    items(c.comments.size, key = { "c:${c.comments[it].id}" }) { index ->
        val comment = c.comments[index]
        CommentItem(
            comment = comment,
            replies = c.replies[comment.id],
            canReply = auth is AuthState.Verified,
            onToggleReplies = { watch.toggleReplies(comment) },
            onMoreReplies = { watch.loadMoreReplies(comment.id) },
            onReply = { text, done -> watch.postReply(comment, text, done) },
        )
    }
    if (c.loadingMore) item(key = "comments-more") { Box(Modifier.fillMaxWidth().padding(Spacing.md), contentAlignment = Alignment.Center) { androidx.compose.material3.ContainedLoadingIndicator() } }
    if (c.loadMoreError) item(key = "comments-more-error") { TextButton(shapes = ButtonDefaults.shapes(), onClick = watch::loadMoreComments) { Text(stringResource(R.string.feed_load_more_failed)) } }
}

/** Lets a child extend [by] beyond the parent's horizontal padding (the content is not clipped, so it just overflows). */
private fun Modifier.bleedHorizontally(by: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val px = by.roundToPx()
    val width = constraints.maxWidth + 2 * px
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-px, 0) }
}
