package vibe.tube.feature.shorts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import vibe.tube.R
import vibe.tube.data.repo.FeedPhase
import vibe.tube.player.ShortsPlayState
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.CenterLoading
import vibe.tube.ui.components.FeedMessage
import vibe.tube.ui.components.LocalVideoActions
import vibe.tube.ui.components.RemoteImage
import vibe.tube.ui.components.StateMessage
import vibe.tube.ui.components.VIconImage
import vibe.tube.ui.components.thumbnailCandidates
import vibe.tube.ui.theme.Spacing
import vibe.tube.youtube.model.VideoItem

/** The Shorts tab: a vertical pager of short videos taken from YouTube, one playing at a time. */
@Composable
fun ShortsScreen(onLogin: () -> Unit, viewModel: ShortsViewModel = hiltViewModel()) {
    val feed by viewModel.controller.state.collectAsStateWithLifecycle()
    val videos = remember(feed.items) { feed.items.filterIsInstance<VideoItem>().distinctBy { it.id } }
    val player = viewModel.shorts
    val playState by player.state.collectAsStateWithLifecycle()
    val surface = MaterialTheme.colorScheme.surface

    // Leaving the tab (or the app) stops the sound; coming back resumes the short that was on screen.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player.pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { player.resume() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { player.pause() } }

    Column(Modifier.fillMaxSize()) {
        // Black video area starts under the status bar, which keeps the theme's own background so its icons stay readable.
        Box(Modifier.fillMaxWidth().background(surface).windowInsetsPadding(WindowInsets.statusBars))
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when {
                videos.isNotEmpty() -> ShortsPager(videos, feed.phase == FeedPhase.LoadingMore, viewModel, playState, onLogin)
                feed.failure != null && feed.items.isEmpty() ->
                    FeedMessage(feed.failure!!, onRetry = viewModel::refresh, onLogin = onLogin, modifier = Modifier.align(Alignment.Center))
                feed.phase == FeedPhase.LoadingFirst || feed.phase == FeedPhase.Refreshing -> CenterLoading()
                else -> StateMessage(VIcon.Info, stringResource(R.string.shorts_empty), null, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun ShortsPager(
    videos: List<VideoItem>,
    loadingMore: Boolean,
    viewModel: ShortsViewModel,
    playState: ShortsPlayState,
    onLogin: () -> Unit,
) {
    val player = viewModel.shorts
    val latest by rememberUpdatedState(videos)
    val pager = rememberPagerState { videos.size }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            latest.getOrNull(page)?.let(player::play)
            if (page >= latest.size - 3) viewModel.loadMore()
        }
    }
    VerticalPager(
        state = pager,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 0,
        key = { videos[it].id },
    ) { page ->
        ShortPage(
            video = videos[page],
            isCurrent = pager.currentPage == page,
            playState = playState,
            viewModel = viewModel,
            onLogin = onLogin,
        )
    }
}

@Composable
private fun ShortPage(video: VideoItem, isCurrent: Boolean, playState: ShortsPlayState, viewModel: ShortsViewModel, onLogin: () -> Unit) {
    val actions = LocalVideoActions.current
    val player = viewModel.shorts
    val active = isCurrent && playState.videoId == video.id
    val posterAlpha by animateFloatAsState(if (active && playState.ready) 0f else 1f, tween(if (active && playState.ready) 220 else 0), label = "shortPoster")
    var liked by remember(video.id) { mutableStateOf(false) }
    var progress by remember(video.id) { mutableFloatStateOf(0f) }
    var pausedHint by remember(video.id) { mutableStateOf(false) }
    LaunchedEffect(isCurrent, video.id) {
        while (isCurrent) {
            val p = player.player
            progress = if (active && p.duration > 0) (p.currentPosition.toFloat() / p.duration).coerceIn(0f, 1f) else 0f
            delay(250)
        }
    }
    LaunchedEffect(pausedHint) {
        if (pausedHint) {
            delay(700)
            pausedHint = false
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(video.id) {
                detectTapGestures(onTap = {
                    if (isCurrent) {
                        player.toggle()
                        pausedHint = true
                    }
                })
            },
    ) {
        if (active) {
            AndroidView(
                factory = { ctx ->
                    (android.view.LayoutInflater.from(ctx).inflate(R.layout.vibe_player_view, null) as PlayerView).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                },
                update = { view -> if (view.player !== player.player) view.player = player.player },
                onRelease = { view -> view.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (posterAlpha > 0.001f) {
            RemoteImage(
                urls = remember(video.id) { thumbnailCandidates(video) },
                contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = posterAlpha },
                contentScale = ContentScale.Fit,
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
        // Pause / play hint
        if (active && pausedHint) {
            Box(
                Modifier.align(Alignment.Center).size(72.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                VIconImage(if (playState.playing) VIcon.PlayArrowFilled else VIcon.Pause, null, Modifier.size(40.dp), tint = Color.White)
            }
        }
        // Bottom scrim + title / channel
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(220.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)))),
        )
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = Spacing.lg, end = Spacing.sm, bottom = Spacing.lg),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                video.channelName?.let { name ->
                    Text(
                        name,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.then(
                            if (video.channelId != null) Modifier.clickable { actions.openChannel(video.channelId!!) } else Modifier,
                        ),
                    )
                }
                Text(video.title, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ShortAction(if (liked) VIcon.ThumbUpFilled else VIcon.ThumbUp, stringResource(R.string.action_like)) {
                    liked = !liked
                    viewModel.like(video.id, liked, onLogin)
                }
                ShortAction(VIcon.Schedule, stringResource(R.string.action_watch_later)) { actions.watchLater(video) }
                ShortAction(VIcon.OpenInNew, stringResource(R.string.action_share)) { actions.share(video) }
                ShortAction(VIcon.Fullscreen, stringResource(R.string.action_play)) { actions.play(video, listOf(video)) }
            }
        }
        // Thin progress line at the very bottom
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(2.dp)
                .drawBehind {
                    drawRect(Color.White.copy(alpha = 0.25f))
                    drawRect(Color.White, size = Size(size.width * progress, size.height))
                },
        )
    }
}

@Composable
private fun ShortAction(icon: Int, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        VIconImage(icon, label, Modifier.size(24.dp), tint = Color.White)
    }
}
