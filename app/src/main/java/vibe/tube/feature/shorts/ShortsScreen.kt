package vibe.tube.feature.shorts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import vibe.tube.R
import vibe.tube.account.AuthState
import vibe.tube.data.repo.FeedPhase
import vibe.tube.feature.watch.CommentItem
import vibe.tube.feature.watch.CommentsEmptyStates
import vibe.tube.feature.watch.CommentsHeader
import vibe.tube.feature.watch.compactCount
import vibe.tube.player.ShortsPlayState
import vibe.tube.player.ShortsPlayer
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.Avatar
import vibe.tube.ui.components.CenterLoading
import vibe.tube.ui.components.FeedMessage
import vibe.tube.ui.components.LocalVideoActions
import vibe.tube.ui.components.RemoteImage
import vibe.tube.ui.components.StateMessage
import vibe.tube.ui.components.VIconImage
import vibe.tube.ui.components.thumbnailCandidates
import vibe.tube.ui.theme.Spacing
import vibe.tube.youtube.model.VideoItem
import kotlin.math.abs
import kotlin.math.exp

/** The Shorts tab: a vertical pager of short videos taken from YouTube, one playing at a time (the next one already buffered). */
@Composable
fun ShortsScreen(onLogin: () -> Unit, viewModel: ShortsViewModel = hiltViewModel()) {
    val feed by viewModel.controller.state.collectAsStateWithLifecycle()
    val videos = remember(feed.items) { feed.items.filterIsInstance<VideoItem>().distinctBy { it.id } }
    val player = viewModel.shorts
    val playState by player.state.collectAsStateWithLifecycle()
    val metas by viewModel.metas.collectAsStateWithLifecycle()
    val surface = MaterialTheme.colorScheme.surface
    var commentsFor by remember { mutableStateOf<VideoItem?>(null) }

    // Leaving the tab (or the app) stops the sound; coming back resumes the short that was on screen.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player.pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { player.resume() }
    DisposableEffect(Unit) { onDispose { player.pause() } }

    Column(Modifier.fillMaxSize()) {
        // Black video area starts under the status bar, which keeps the theme's own background so its icons stay readable.
        Box(Modifier.fillMaxWidth().background(surface).windowInsetsPadding(WindowInsets.statusBars))
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when {
                videos.isNotEmpty() -> ShortsPager(
                    videos = videos,
                    viewModel = viewModel,
                    playState = playState,
                    metas = metas,
                    onLogin = onLogin,
                    onOpenComments = { commentsFor = it },
                )
                feed.failure != null && feed.items.isEmpty() ->
                    FeedMessage(feed.failure!!, onRetry = viewModel::refresh, onLogin = onLogin, modifier = Modifier.align(Alignment.Center))
                feed.phase == FeedPhase.LoadingFirst || feed.phase == FeedPhase.Refreshing -> CenterLoading()
                else -> StateMessage(VIcon.Info, stringResource(R.string.shorts_empty), null, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
    commentsFor?.let { video ->
        ShortsCommentsSheet(video, metas[video.id], viewModel, onLogin) { commentsFor = null }
    }
}

@Composable
private fun ShortsPager(
    videos: List<VideoItem>,
    viewModel: ShortsViewModel,
    playState: ShortsPlayState,
    metas: Map<String, ShortMeta>,
    onLogin: () -> Unit,
    onOpenComments: (VideoItem) -> Unit,
) {
    val player = viewModel.shorts
    val latest by rememberUpdatedState(videos)
    val pager = rememberPagerState { videos.size }
    // The short starts as soon as its page is (more than half) on screen, and the one after it is prepared in the background.
    LaunchedEffect(pager, videos.firstOrNull()?.id) {
        snapshotFlow { pager.currentPage }.collect { page ->
            latest.getOrNull(page)?.let { video ->
                player.play(video, latest.getOrNull(page + 1))
                viewModel.ensureMeta(video)
            }
            if (page >= latest.size - 3) viewModel.loadMore()
        }
    }
    // More shorts arrived: make sure the next one is being prepared.
    LaunchedEffect(videos.size) {
        latest.getOrNull(pager.currentPage + 1)?.let { next ->
            if (player.state.value.videoId == latest.getOrNull(pager.currentPage)?.id) player.preload(next)
        }
    }
    VerticalPager(
        state = pager,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 0,
        key = { videos[it].id },
    ) { page ->
        val video = videos[page]
        ShortPage(
            video = video,
            isCurrent = pager.currentPage == page,
            playState = playState,
            meta = metas[video.id],
            viewModel = viewModel,
            onLogin = onLogin,
            onOpenComments = { onOpenComments(video) },
        )
    }
}

@Composable
private fun ShortPage(
    video: VideoItem,
    isCurrent: Boolean,
    playState: ShortsPlayState,
    meta: ShortMeta?,
    viewModel: ShortsViewModel,
    onLogin: () -> Unit,
    onOpenComments: () -> Unit,
) {
    val actions = LocalVideoActions.current
    val player = viewModel.shorts
    val haptic = LocalHapticFeedback.current
    val active = isCurrent && playState.videoId == video.id
    val posterAlpha by animateFloatAsState(if (active && playState.ready) 0f else 1f, tween(if (active && playState.ready) 220 else 0), label = "shortPoster")

    // Pause / play pop: the icon springs in; when it was a "resume" it fades away again, when it was a "pause" it stays.
    var hintTick by remember(video.id) { mutableIntStateOf(0) }
    var userPaused by remember(video.id) { mutableStateOf(false) }
    val hintAlpha = remember(video.id) { Animatable(0f) }
    val hintScale = remember(video.id) { Animatable(0.7f) }
    LaunchedEffect(hintTick) {
        if (hintTick == 0) return@LaunchedEffect
        hintScale.snapTo(0.7f)
        hintAlpha.snapTo(1f)
        coroutineScope {
            launch { hintScale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow)) }
            if (!userPaused) {
                delay(360)
                hintAlpha.animateTo(0f, tween(320))
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(video.id, isCurrent) {
                detectTapGestures(
                    onPress = {
                        tryAwaitRelease()
                        if (player.state.value.fast) player.setFast(false)
                    },
                    onLongPress = {
                        if (isCurrent) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            player.setFast(true)
                        }
                    },
                    onTap = {
                        if (isCurrent) {
                            userPaused = !player.toggle()
                            hintTick++
                        }
                    },
                )
            },
    ) {
        if (active) {
            AndroidView(
                factory = { ctx ->
                    (android.view.LayoutInflater.from(ctx).inflate(R.layout.vibe_player_view, null) as PlayerView).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setKeepContentOnPlayerReset(true)
                    }
                },
                update = { view ->
                    val p = player.playerFor(video.id)
                    if (view.player !== p) view.player = p
                    // Vertical video fills the screen (a few percent is cropped instead of leaving black bars); anything wider is fitted.
                    val mode = if (playState.aspect in 0.05f..0.85f) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                    if (view.resizeMode != mode) view.resizeMode = mode
                },
                onRelease = { view -> view.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (posterAlpha > 0.001f) {
            RemoteImage(
                urls = remember(video.id) { thumbnailCandidates(video) },
                contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = posterAlpha },
                contentScale = ContentScale.Crop,
            )
        }
        if (active && (playState.loading || (!playState.ready && !playState.error))) {
            ContainedLoadingIndicator(Modifier.align(Alignment.Center).size(48.dp))
        }
        if (active && playState.error) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(R.string.feed_error_generic_title), color = Color.White, style = MaterialTheme.typography.titleMedium)
                androidx.compose.material3.FilledTonalButton(onClick = player::retry) { Text(stringResource(R.string.action_retry)) }
            }
        }

        // Pause / play pop
        Box(
            Modifier
                .align(Alignment.Center)
                .size(80.dp)
                .graphicsLayer {
                    alpha = hintAlpha.value
                    scaleX = hintScale.value
                    scaleY = hintScale.value
                }
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(userPaused, animationSpec = tween(140), label = "hintIcon") { paused ->
                VIconImage(if (paused) VIcon.Pause else VIcon.PlayArrowFilled, null, Modifier.size(42.dp), tint = Color.White)
            }
        }

        // 2x while held
        AnimatedVisibility(
            visible = active && playState.fast,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
            enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.8f),
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.8f),
        ) {
            Row(
                Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("2x", color = Color.White, style = MaterialTheme.typography.labelLarge)
                VIconImage(VIcon.FastForward, null, Modifier.size(18.dp), tint = Color.White)
            }
        }

        // Bottom scrim
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(280.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))),
        )
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.sm), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f).padding(bottom = Spacing.md, end = Spacing.sm), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChannelRow(video, meta, viewModel, onLogin)
                    Text(video.title, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Column(
                    Modifier.padding(bottom = Spacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val liked = meta?.liked == true
                    ShortAction(if (liked) VIcon.ThumbUpFilled else VIcon.ThumbUp, stringResource(R.string.action_like), null) {
                        viewModel.toggleLike(video, onLogin)
                    }
                    ShortAction(VIcon.Comment, stringResource(R.string.comments_title), compactCount(meta?.commentsCount), onOpenComments)
                    ShortAction(VIcon.Schedule, stringResource(R.string.action_watch_later), null) { actions.watchLater(video) }
                    ShortAction(VIcon.Download, stringResource(R.string.action_download), null) { actions.download(video) }
                    ShortAction(VIcon.Share, stringResource(R.string.action_share), null) { actions.share(video) }
                }
            }
            ShortsSeekBar(player, active)
        }
    }
}

@Composable
private fun ChannelRow(video: VideoItem, meta: ShortMeta?, viewModel: ShortsViewModel, onLogin: () -> Unit) {
    val actions = LocalVideoActions.current
    val channelId = meta?.channel?.id ?: video.channelId
    val name = meta?.channel?.handle ?: video.channelName ?: meta?.channel?.name
    val open = if (channelId != null) Modifier.clickable { actions.openChannel(channelId) } else Modifier
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Avatar(meta?.channel?.avatar ?: video.channelAvatar, 36.dp, Modifier.clip(CircleShape).then(open))
        if (name != null) {
            Text(
                name,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).then(open),
            )
        }
        if (channelId != null) {
            val subscribed = meta?.subscribed == true
            val container by animateColorAsState(if (subscribed) Color.White.copy(alpha = 0.22f) else Color.White, tween(200), label = "subContainer")
            val content by animateColorAsState(if (subscribed) Color.White else Color.Black, tween(200), label = "subContent")
            Surface(
                onClick = { viewModel.toggleSubscribe(video, onLogin) },
                shape = CircleShape,
                color = container,
                contentColor = content,
            ) {
                Text(
                    stringResource(if (subscribed) R.string.action_subscribed else R.string.action_subscribe),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ShortAction(icon: Int, label: String, count: String?, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            VIconImage(icon, label, Modifier.size(24.dp), tint = Color.White)
        }
        if (count != null) Text(count, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Straight progress line with a drag handle shaped like the Loading indicator's cookie (no container). Dragging scrubs the
 * short; the handle turns in the direction of the drag (right = clockwise), faster the faster the finger moves, and coasts
 * to a stop when released.
 */
@Composable
private fun ShortsSeekBar(player: ShortsPlayer, active: Boolean, modifier: Modifier = Modifier) {
    var fraction by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var angle by remember { mutableFloatStateOf(0f) }
    var angularVelocity by remember { mutableFloatStateOf(0f) }
    var targetVelocity by remember { mutableFloatStateOf(0f) }
    var spinning by remember { mutableStateOf(false) }
    var widthPx by remember { mutableFloatStateOf(0f) }

    // Follows playback while the finger is not on the bar.
    LaunchedEffect(active, dragging) {
        if (!active) {
            fraction = 0f
            return@LaunchedEffect
        }
        while (!dragging) {
            fraction = player.fraction()
            delay(33)
        }
    }
    // Spin: integrates the handle's angle each frame while dragging, and for a moment after letting go.
    LaunchedEffect(spinning) {
        if (!spinning) return@LaunchedEffect
        var last = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
            last = now
            // A finger that stops moving (but stays down) stops asking for speed.
            if (dragging) targetVelocity *= (1f - 5f * dt).coerceAtLeast(0f)
            angularVelocity += (targetVelocity - angularVelocity) * (1f - exp(-9f * dt))
            angle = (angle + angularVelocity * dt) % 360f
            if (!dragging && abs(angularVelocity) < 4f && abs(targetVelocity) < 4f) {
                angularVelocity = 0f
                targetVelocity = 0f
                spinning = false
                break
            }
        }
    }

    val thumbSize by animateDpAsState(if (dragging) 22.dp else 15.dp, spring(dampingRatio = 0.6f, stiffness = 500f), label = "thumb")
    val trackHeight by animateDpAsState(if (dragging) 6.dp else 4.dp, spring(stiffness = 500f), label = "track")
    val padding = 16.dp
    val thumbShape = remember { MaterialShapes.Sunny.toShape() }

    Box(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(active) {
                detectTapGestures(onTap = { offset ->
                    if (!active) return@detectTapGestures
                    val pad = padding.toPx()
                    val f = ((offset.x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
                    fraction = f
                    player.scrubStart()
                    player.scrubTo(f, final = true)
                })
            }
            .pointerInput(active) {
                if (!active) return@pointerInput
                val pad = padding.toPx()
                fun toFraction(x: Float) = ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        spinning = true
                        player.scrubStart()
                        fraction = toFraction(offset.x)
                    },
                    onDragEnd = {
                        dragging = false
                        targetVelocity = 0f
                        player.scrubTo(fraction, final = true)
                    },
                    onDragCancel = {
                        dragging = false
                        targetVelocity = 0f
                        player.scrubTo(fraction, final = true)
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        fraction = toFraction(change.position.x)
                        player.scrubTo(fraction, final = false)
                        val dtMs = (change.uptimeMillis - change.previousUptimeMillis).coerceAtLeast(1L)
                        val speed = dragAmount / dtMs * 1000f // px / s, signed
                        val wanted = (speed * 0.6f).coerceIn(-1600f, 1600f) // degrees / s
                        targetVelocity += (wanted - targetVelocity) * 0.4f
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pad = padding.toPx()
            val w = size.width - 2 * pad
            val cy = size.height / 2f
            val h = trackHeight.toPx()
            val r = thumbSize.toPx() / 2f
            val x = pad + w * fraction
            val gap = r + 3.dp.toPx()
            val corner = CornerRadius(h / 2f, h / 2f)
            val activeEnd = x - gap
            if (activeEnd > pad) {
                drawRoundRect(Color.White, Offset(pad, cy - h / 2f), Size(activeEnd - pad, h), corner)
            }
            val inactiveStart = x + gap
            if (inactiveStart < pad + w) {
                drawRoundRect(Color.White.copy(alpha = 0.3f), Offset(inactiveStart, cy - h / 2f), Size(pad + w - inactiveStart, h), corner)
            }
        }
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .size(thumbSize)
                .graphicsLayer {
                    val pad = padding.toPx()
                    translationX = pad + (widthPx - 2 * pad) * fraction - size.width / 2f
                    rotationZ = angle
                }
                .background(Color.White, thumbShape),
        )
        AnimatedVisibility(
            visible = dragging,
            modifier = Modifier.align(Alignment.TopCenter).offset { IntOffset(0, -34.dp.roundToPx()) },
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(160)),
        ) {
            Text(
                "${formatTime((fraction * player.durationMs()).toLong())} / ${formatTime(player.durationMs())}",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun ShortsCommentsSheet(video: VideoItem, meta: ShortMeta?, viewModel: ShortsViewModel, onLogin: () -> Unit, onDismiss: () -> Unit) {
    val comments = viewModel.comments
    val state by comments.state.collectAsStateWithLifecycle()
    val auth by viewModel.authState.collectAsStateWithLifecycle()
    LaunchedEffect(video.id, meta?.loaded, meta?.commentsToken) {
        if (meta?.loaded == true) comments.open(video.id, meta.commentsToken, meta.sorts, meta.commentsCount)
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        val listState = rememberLazyListState()
        LaunchedEffect(listState, state.comments.size) {
            snapshotFlow {
                val info = listState.layoutInfo
                (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 4
            }.distinctUntilChanged().filter { it }.collect { comments.loadMore() }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.72f),
            contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item(key = "header") {
                CommentsHeader(state, auth, onSort = comments::changeSort, onPost = { text, done -> comments.postComment(text, done) }, onLogin = onLogin)
            }
            if (meta?.loaded != true) {
                item(key = "meta-loading") {
                    Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
                }
            } else {
                item(key = "state") { CommentsEmptyStates(state, comments::retry) }
                items(state.comments.size, key = { "c:${state.comments[it].id}" }) { index ->
                    val comment = state.comments[index]
                    CommentItem(
                        comment = comment,
                        replies = state.replies[comment.id],
                        canReply = auth is AuthState.Verified,
                        onToggleReplies = { comments.toggleReplies(comment) },
                        onMoreReplies = { comments.loadMoreReplies(comment.id) },
                        onReply = { text, done -> comments.postReply(comment, text, done) },
                    )
                }
                if (state.loadingMore) item(key = "more") {
                    Box(Modifier.fillMaxWidth().padding(Spacing.md), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
                }
                if (state.loadMoreError) item(key = "more-error") {
                    TextButton(shapes = ButtonDefaults.shapes(), onClick = comments::loadMore) { Text(stringResource(R.string.feed_load_more_failed)) }
                }
            }
        }
    }
}
