package vibe.tube.feature.shorts

import vibe.tube.feature.watch.clearFocusOnOutsideTap

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
import androidx.compose.ui.draw.blur
import vibe.tube.ui.components.pressScale
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
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

@Composable
fun ShortsScreen(onLogin: () -> Unit, viewModel: ShortsViewModel = hiltViewModel(), bottomInset: Boolean = false) {
    val feed by viewModel.controller.state.collectAsStateWithLifecycle()
    val player = viewModel.shorts
    val picked by player.picked.collectAsStateWithLifecycle()
    val videos = remember(feed.items, picked) {
        (picked?.shorts.orEmpty() + feed.items.filterIsInstance<VideoItem>()).distinctBy { it.id }
    }
    val playState by player.state.collectAsStateWithLifecycle()
    val metas by viewModel.metas.collectAsStateWithLifecycle()
    val surface = MaterialTheme.colorScheme.surface
    var commentsFor by remember { mutableStateOf<VideoItem?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player.pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshIfStale(SHORTS_STALE_MS)
        player.resume()
    }
    DisposableEffect(Unit) { onDispose { player.pause() } }

    Column(Modifier.fillMaxSize()) {
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
                    bottomInset = bottomInset,
                    pickStamp = picked?.stamp,
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
    bottomInset: Boolean,
    pickStamp: Long?,
) {
    val player = viewModel.shorts
    val latest by rememberUpdatedState(videos)
    val context = androidx.compose.ui.platform.LocalContext.current
    val pager = rememberPagerState { videos.size }
    val firstId = videos.firstOrNull()?.id
    // A new pick starts at its first short, even when that short was already first.
    LaunchedEffect(firstId, pickStamp) {
        if (pager.currentPage != 0) pager.scrollToPage(0)
    }
    LaunchedEffect(pager, firstId, pickStamp) {
        snapshotFlow { pager.currentPage }.collect { page ->
            latest.getOrNull(page)?.let { video ->
                val upcoming = latest.drop(page + 1).take(UPCOMING)
                player.play(video, upcoming)
                viewModel.ensureMeta(video)
                viewModel.prefetchMetas(upcoming)
                launch { prefetchPosters(context, upcoming.take(2)) }
            }
            if (page >= latest.size - 3) viewModel.loadMore()
        }
    }
    
    LaunchedEffect(videos.size) {
        val page = pager.currentPage
        val current = latest.getOrNull(page) ?: return@LaunchedEffect
        if (player.state.value.videoId == current.id) {
            val upcoming = latest.drop(page + 1).take(UPCOMING)
            player.play(current, upcoming)
            viewModel.prefetchMetas(upcoming)
        }
    }
    var uiHidden by remember { mutableStateOf(false) }
    VerticalPager(
        state = pager,
        modifier = Modifier.fillMaxSize(),
        // Compose the neighbouring pages ahead so their thumbnails are loaded before they slide in (otherwise they show black).
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
            bottomInset = bottomInset,
            keepFrame = pager.settledPage == page,
            uiHidden = uiHidden,
            onUiHidden = { uiHidden = it },
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
    uiHidden: Boolean,
    onUiHidden: (Boolean) -> Unit,
    bottomInset: Boolean = false,
    keepFrame: Boolean = false,
) {
    val actions = LocalVideoActions.current
    val player = viewModel.shorts
    val haptic = LocalHapticFeedback.current
    val active = isCurrent && playState.videoId == video.id
    // The page being swiped away keeps its last frame until the swipe settles, instead of flashing back to the poster.
    var shown by remember(video.id) { mutableStateOf(false) }
    LaunchedEffect(active, playState.ready, isCurrent, keepFrame) {
        if (active && playState.ready) shown = true else if (!isCurrent && !keepFrame) shown = false
    }
    val showVideo = active || (shown && keepFrame && !isCurrent)
    val frameVisible = (active && playState.ready) || (shown && keepFrame)
    val posterAlpha by animateFloatAsState(if (frameVisible) 0f else 1f, tween(if (frameVisible) 220 else 0), label = "shortPoster")

    var hintTick by remember(video.id) { mutableIntStateOf(0) }
    var userPaused by remember(video.id) { mutableStateOf(false) }
    val hintAlpha = remember(video.id) { Animatable(0f) }
    val hintScale = remember(video.id) { Animatable(0.7f) }
    var likeTick by remember(video.id) { mutableIntStateOf(0) }
    val likeAlpha = remember(video.id) { Animatable(0f) }
    val likeScale = remember(video.id) { Animatable(0.6f) }
    LaunchedEffect(likeTick) {
        if (likeTick == 0) return@LaunchedEffect
        likeScale.snapTo(0.6f)
        likeAlpha.snapTo(1f)
        coroutineScope {
            launch { likeScale.animateTo(1.15f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow)) }
            delay(420)
            likeAlpha.animateTo(0f, tween(300))
        }
    }
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
            // The blurred backdrop is scaled up; without clipping it spills over the neighbouring page while swiping.
            .clipToBounds()
            .background(Color.Black)
            .pointerInput(video.id, isCurrent, uiHidden, meta?.liked) {
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
                    onDoubleTap = {
                        if (isCurrent) {
                            if (meta?.liked != true) viewModel.toggleLike(video, onLogin)
                            likeTick++
                        }
                    },
                    onTap = {
                        if (isCurrent) {
                            if (uiHidden) {
                                onUiHidden(false)
                            } else {
                                userPaused = !player.toggle()
                                hintTick++
                            }
                        }
                    },
                )
            },
    ) {
        // The blurred backdrop only shows beside a landscape video; vertical Shorts and the cropped poster
        // cover it completely, so skip the full-screen blur there.
        if (playState.aspect > 0.85f) {
            RemoteImage(
                urls = remember(video.id) { thumbnailCandidates(video) },
                contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.35f; scaleY = 1.35f }.blur(36.dp),
                contentScale = ContentScale.Crop,
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
        if (showVideo) {
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
                contentScale = if (playState.aspect in 0.05f..0.85f || playState.aspect <= 0f) ContentScale.Crop else ContentScale.Fit,
            )
        }
        if (active && !playState.scrubbing && (playState.loading || (!playState.ready && !playState.error))) {
            ContainedLoadingIndicator(Modifier.align(Alignment.Center).size(48.dp))
        }
        if (active && playState.error) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(R.string.feed_error_generic_title), color = Color.White, style = MaterialTheme.typography.titleMedium)
                androidx.compose.material3.FilledTonalButton(onClick = player::retry) { Text(stringResource(R.string.action_retry)) }
            }
        }

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

        Box(
            Modifier
                .align(Alignment.Center)
                .size(112.dp)
                .graphicsLayer {
                    alpha = likeAlpha.value
                    scaleX = likeScale.value
                    scaleY = likeScale.value
                },
            contentAlignment = Alignment.Center,
        ) {
            VIconImage(VIcon.ThumbUpFilled, null, Modifier.size(96.dp), tint = Color.White)
        }

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

        val uiAlpha by animateFloatAsState(if (uiHidden) 0f else 1f, tween(if (uiHidden) 380 else 180), label = "shortUi")
        if (uiAlpha > 0.001f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = uiAlpha }) {
        
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(280.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))),
        )
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().then(if (bottomInset) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier)) {
            Row(Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.sm), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f).offset(y = 10.dp).padding(end = Spacing.sm), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChannelRow(video, meta, viewModel, onLogin)
                    Text(video.title.ifBlank { meta?.title.orEmpty() }, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Column(
                    Modifier.padding(bottom = Spacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val liked = meta?.liked == true
                    val disliked = meta?.disliked == true
                    ShortAction(VIcon.ThumbUp, stringResource(R.string.action_like), compactCount(meta?.likeCount), filledIcon = VIcon.ThumbUpFilled, selected = liked) {
                        viewModel.toggleLike(video, onLogin)
                    }
                    ShortAction(
                        VIcon.ThumbDown,
                        stringResource(R.string.action_dislike),
                        compactCount(meta?.dislikeCount) ?: stringResource(R.string.action_dislike),
                        filledIcon = VIcon.ThumbDownFilled,
                        selected = disliked,
                    ) {
                        viewModel.toggleDislike(video, onLogin)
                    }
                    ShortAction(VIcon.Comment, stringResource(R.string.comments_title), compactCount(meta?.commentsCount), onClick = onOpenComments)
                    ShortAction(VIcon.Share, stringResource(R.string.action_share), null) { actions.share(video) }
                    var moreOpen by remember { mutableStateOf(false) }
                    Box {
                        ShortAction(VIcon.MoreVert, stringResource(R.string.action_more), null) { moreOpen = true }
                        androidx.compose.material3.DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_download)) },
                                leadingIcon = { VIconImage(VIcon.Download, null, Modifier.size(20.dp)) },
                                onClick = { moreOpen = false; viewModel.download(video) },
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(R.string.shorts_hide_ui)) },
                                leadingIcon = { VIconImage(VIcon.VisibilityOff, null, Modifier.size(20.dp)) },
                                onClick = { moreOpen = false; onUiHidden(true) },
                            )
                        }
                    }
                }
            }
            ShortsSeekBar(player, active, playing = active && playState.playing)
        }
        }
        
        if (uiHidden && active) HairlineProgress(player, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ChannelRow(video: VideoItem, meta: ShortMeta?, viewModel: ShortsViewModel, onLogin: () -> Unit) {
    val actions = LocalVideoActions.current
    val channelId = meta?.channel?.id ?: video.channelId
    // A collaboration shows all its channels and opens a list of them, as YouTube does; the subscribe button stays the uploader's.
    val collaborators = meta?.channel?.collaborators.orEmpty()
    var showCollaborators by remember { mutableStateOf(false) }
    val name = if (collaborators.size > 1) {
        vibe.tube.ui.components.collaboratorsTitle(collaborators) { it.handle ?: it.name }
    } else vibe.tube.youtube.parser.ItemParsers.decodeHandle(meta?.channel?.handle ?: video.channelName ?: meta?.channel?.name)
    val open = when {
        collaborators.size > 1 -> Modifier.clickable { showCollaborators = true }
        channelId != null -> Modifier.clickable { actions.openChannel(channelId) }
        else -> Modifier
    }
    if (showCollaborators) {
        vibe.tube.ui.components.CollaboratorsSheet(collaborators, actions::openChannel) { showCollaborators = false }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (collaborators.size > 1) {
            vibe.tube.ui.components.CollaboratorAvatars(collaborators, 36.dp, Color.Black, Modifier.then(open))
        } else {
            Avatar(meta?.channel?.avatar ?: video.channelAvatar, 36.dp, Modifier.clip(CircleShape).then(open))
        }
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
            // A collaboration's button asks which of its channels to follow; it reads as subscribed once all of them are.
            val collab = collaborators.size > 1
            val collabState = meta?.collabSubscribed.orEmpty()
            val subscribed = if (collab) collaborators.all { collabState[it.id] == true } else meta?.subscribed == true
            val container by animateColorAsState(if (subscribed) Color.White.copy(alpha = 0.22f) else Color.White, tween(200), label = "subContainer")
            val content by animateColorAsState(if (subscribed) Color.White else Color.Black, tween(200), label = "subContent")
            var menu by remember { mutableStateOf(false) }
            var collabMenu by remember { mutableStateOf(false) }
            Box {
                if (collab) {
                    androidx.compose.material3.DropdownMenu(
                        expanded = collabMenu,
                        onDismissRequest = { collabMenu = false },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    ) {
                        collaborators.forEach { channel ->
                            val id = channel.id ?: return@forEach
                            val following = collabState[id] == true
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Text(
                                        if (following) stringResource(R.string.action_unsubscribe) + " " + (channel.handle ?: channel.name)
                                        else stringResource(R.string.action_subscribe) + " " + (channel.handle ?: channel.name),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                leadingIcon = { Avatar(channel.avatar, 28.dp, channelId = id) },
                                trailingIcon = if (following) {
                                    { VIconImage(VIcon.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) }
                                } else null,
                                onClick = {
                                    collabMenu = false
                                    viewModel.setCollaboratorsSubscribed(video, listOf(id), !following, onLogin)
                                },
                            )
                        }
                        val missing = collaborators.mapNotNull { it.id }.filter { collabState[it] != true }
                        if (missing.size > 1) {
                            androidx.compose.material3.HorizontalDivider()
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Text(stringResource(if (collaborators.size == 2) R.string.channel_collab_subscribe_both else R.string.channel_collab_subscribe_all))
                                },
                                leadingIcon = { vibe.tube.ui.components.CollaboratorAvatars(collaborators, 24.dp, MaterialTheme.colorScheme.surfaceContainer) },
                                onClick = {
                                    collabMenu = false
                                    viewModel.setCollaboratorsSubscribed(video, missing, true, onLogin)
                                },
                            )
                        }
                    }
                }
                Surface(
                    onClick = {
                        when {
                            collab -> collabMenu = true
                            subscribed -> menu = true
                            else -> viewModel.toggleSubscribe(video, onLogin)
                        }
                    },
                    shape = CircleShape,
                    color = container,
                    contentColor = content,
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (subscribed) VIconImage(vibe.tube.ui.components.notifyIcon(meta?.notifyLevel), null, Modifier.size(16.dp))
                        Text(
                            stringResource(if (subscribed) R.string.action_subscribed else R.string.action_subscribe),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                    }
                }
                vibe.tube.ui.components.SubscribeMenu(
                    expanded = menu,
                    onDismiss = { menu = false },
                    available = meta?.channel?.notify?.params?.keys.orEmpty(),
                    level = meta?.notifyLevel,
                    onLevel = { viewModel.setNotify(video, it) },
                    onUnsubscribe = { viewModel.toggleSubscribe(video, onLogin) },
                )
            }
        }
    }
}

@Composable
private fun ShortAction(icon: Int, label: String, count: String?, filledIcon: Int? = null, selected: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Box(
            Modifier
                .size(46.dp)
                .pressScale(source, 0.86f)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.26f))
                .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (filledIcon != null) {
                vibe.tube.ui.components.SelectableSymbol(icon, filledIcon, selected, label, Modifier.size(26.dp), tint = Color.White)
            } else {
                VIconImage(icon, label, Modifier.size(26.dp), tint = Color.White)
            }
        }
        if (count != null) {
            Text(
                count,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium.copy(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 2f), 4f)),
            )
        }
    }
}

@Composable
private fun ShortsSeekBar(player: ShortsPlayer, active: Boolean, playing: Boolean, modifier: Modifier = Modifier) {
    val playingNow by rememberUpdatedState(playing)
    var fraction by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var touchTick by remember { mutableIntStateOf(0) }
    var handleShown by remember { mutableStateOf(false) }
    var angle by remember { mutableFloatStateOf(0f) }
    var angularVelocity by remember { mutableFloatStateOf(0f) }
    var targetVelocity by remember { mutableFloatStateOf(0f) }
    var spinning by remember { mutableStateOf(false) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    var boxTopLeft by remember { mutableStateOf(Offset.Zero) }
    val overlay = vibe.tube.ui.components.LocalScrubThumb.current
    val density = androidx.compose.ui.platform.LocalDensity.current

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
    // The scrub handle shows while the bar is touched, then fades out slowly after the finger has been up for a moment.
    LaunchedEffect(dragging, touchTick) {
        if (dragging) {
            handleShown = true
        } else if (touchTick > 0) {
            handleShown = true
            delay(HANDLE_HOLD_MS)
            handleShown = false
        }
    }
    LaunchedEffect(playing) { if (playing) spinning = true }
    LaunchedEffect(spinning) {
        if (!spinning) return@LaunchedEffect
        var last = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
            last = now

            if (dragging) targetVelocity *= (1f - 5f * dt).coerceAtLeast(0f)
            val idle = if (playingNow && !dragging) IDLE_SPIN_DEG_PER_SEC else 0f
            angularVelocity += ((if (dragging) targetVelocity else idle) - angularVelocity) * (1f - exp(-9f * dt))
            angle = (angle + angularVelocity * dt) % 360f
            if (!dragging && !playingNow && abs(angularVelocity) < 4f && abs(targetVelocity) < 4f) {
                angularVelocity = 0f
                targetVelocity = 0f
                spinning = false
                break
            }
        }
    }

    val handleAlpha by animateFloatAsState(if (handleShown) 1f else 0f, tween(if (handleShown) 160 else HANDLE_FADE_MS), label = "handleAlpha")
    val thumbSize by animateDpAsState(if (dragging) 22.dp else 15.dp, spring(dampingRatio = 0.6f, stiffness = 500f), label = "thumb")
    val trackHeight by animateDpAsState(if (dragging) 6.dp else if (handleShown) 4.dp else 2.dp, spring(stiffness = 500f), label = "track")
    val thumbShape = MaterialShapes.Sunny.toShape()

    // Full width and sitting on the footer's top edge; the touch area reaches up above the thin track.
    Box(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .onGloballyPositioned { boxTopLeft = it.positionInRoot() }
            .pointerInput(active) {
                detectTapGestures(onTap = { offset ->
                    if (!active) return@detectTapGestures
                    touchTick++
                    fraction = (offset.x / size.width).coerceIn(0f, 1f)
                    player.scrubStart()
                    player.scrubTo(fraction, final = true)
                })
            }
            .pointerInput(active) {
                if (!active) return@pointerInput
                fun toFraction(x: Float) = (x / size.width).coerceIn(0f, 1f)
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        touchTick++
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
                        val speed = dragAmount / dtMs * 1000f
                        val wanted = (speed * 0.6f).coerceIn(-1600f, 1600f)
                        targetVelocity += (wanted - targetVelocity) * 0.4f
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = trackHeight.toPx()
            val r = thumbSize.toPx() / 2f
            val x = r + (w - 2 * r).coerceAtLeast(0f) * fraction
            val gap = (r + 3.dp.toPx()) * handleAlpha
            val top = size.height - h
            val corner = CornerRadius(h / 2f, h / 2f)
            val activeEnd = x - gap
            if (activeEnd > 0f) {
                drawRoundRect(ShortsRed, Offset(0f, top), Size(activeEnd, h), corner)
            }
            val inactiveStart = x + gap
            if (inactiveStart < w) {
                drawRoundRect(Color.White.copy(alpha = 0.3f), Offset(inactiveStart, top), Size(w - inactiveStart, h), corner)
            }
        }
        if (overlay == null) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .size(thumbSize)
                    .graphicsLayer {
                        val r = thumbSize.toPx() / 2f
                        val center = r + (widthPx - 2 * r).coerceAtLeast(0f) * fraction
                        translationX = center - r
                        translationY = -4.dp.toPx()
                        rotationZ = angle
                        alpha = handleAlpha
                    }
                    .background(ShortsRed, thumbShape),
            )
        } else {
            // Published to the app-level overlay so the handle is drawn over the footer, centred on the track.
            // Only the current page's bar publishes, so neighbouring pages cannot overwrite the handle.
            if (active) LaunchedEffect(overlay) {
                snapshotFlow { listOf(fraction, handleAlpha, thumbSize.value, trackHeight.value, angle, widthPx, boxTopLeft.x, boxTopLeft.y) }.collect {
                    val sizePx = with(density) { thumbSize.toPx() }
                    val boxH = with(density) { 28.dp.toPx() }
                    val trackH = with(density) { trackHeight.toPx() }
                    overlay.sizePx = sizePx
                    overlay.centerX = boxTopLeft.x + sizePx / 2f + (widthPx - sizePx).coerceAtLeast(0f) * fraction
                    overlay.centerY = boxTopLeft.y + boxH - trackH / 2f
                    overlay.rotation = angle
                    overlay.alpha = handleAlpha
                }
            }
            androidx.compose.runtime.DisposableEffect(active) { onDispose { if (active) overlay.alpha = 0f } }
        }
        AnimatedVisibility(
            visible = dragging,
            modifier = Modifier.align(Alignment.TopCenter).offset { IntOffset(0, -84.dp.roundToPx()) },
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

private const val HANDLE_HOLD_MS = 2_000L
private val ShortsRed = vibe.tube.ui.components.ShortsProgressRed
private const val HANDLE_FADE_MS = 900

private const val IDLE_SPIN_DEG_PER_SEC = 90f
private const val UPCOMING = 4

@Composable
private fun HairlineProgress(player: ShortsPlayer, modifier: Modifier = Modifier) {
    var fraction by remember { mutableFloatStateOf(0f) }
    // Sampled once per frame and read only while drawing, so progress is smooth and never re-lays out.
    LaunchedEffect(Unit) {
        while (true) {
            androidx.compose.runtime.withFrameMillis { fraction = player.fraction() }
        }
    }
    Box(
        modifier.fillMaxWidth().height(2.dp).drawBehind {
            drawRect(Color.White.copy(alpha = 0.25f))
            drawRect(ShortsRed, size = size.copy(width = size.width * fraction.coerceIn(0f, 1f)))
        },
    )
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun ShortsCommentsSheet(video: VideoItem, meta: ShortMeta?, viewModel: ShortsViewModel, onLogin: () -> Unit, onDismiss: () -> Unit) {
    val actions = LocalVideoActions.current
    val comments = viewModel.comments
    val state by comments.state.collectAsStateWithLifecycle()
    val auth by viewModel.authState.collectAsStateWithLifecycle()
    LaunchedEffect(video.id, meta?.loaded, meta?.commentsToken, auth) {
        if (meta?.loaded == true) comments.open(video.id, meta.commentsToken, meta.sorts, meta.commentsCount, authKey = auth.toString())
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        val listState = rememberLazyListState()
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        LaunchedEffect(listState, state.comments.size) {
            snapshotFlow {
                val info = listState.layoutInfo
                (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 4
            }.distinctUntilChanged().filter { it }.collect { comments.loadMore() }
        }
        val input = remember(video.id) { vibe.tube.feature.watch.CommentInputState() }
        androidx.compose.runtime.CompositionLocalProvider(vibe.tube.feature.watch.LocalCommentInput provides input) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.72f)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f).clearFocusOnOutsideTap(focusManager),
            contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item(key = "header") {
                CommentsHeader(state, auth, onSort = comments::changeSort, onPost = { text, done -> comments.postComment(text, done) }, onLogin = onLogin, showComposer = false)
            }
            if (meta?.loaded != true) {
                item(key = "meta-loading") {
                    Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
                }
            } else {
                item(key = "state") { CommentsEmptyStates(state, comments::retry) }
                items(state.comments.size, key = { "c:${state.comments[it].id}" }) { index ->
                    val comment = state.comments[index]
                    androidx.compose.runtime.CompositionLocalProvider(
                        vibe.tube.feature.watch.LocalMediaSeek provides { ms ->
                            viewModel.shorts.playerFor(video.id)?.let { p -> p.seekTo(ms.coerceIn(0, p.duration.coerceAtLeast(0))) }
                        },
                        vibe.tube.feature.watch.LocalRateComment provides { c, heart -> comments.rateComment(c, heart) },
                        vibe.tube.feature.watch.LocalDislikeComment provides { c -> comments.rateComment(c, dislike = true) },
                        vibe.tube.feature.watch.LocalReplyComment provides { c, text, done -> comments.postReply(c, text, done) },
                        vibe.tube.feature.watch.LocalPreviewReplies provides comments::previewReplies,
                        vibe.tube.feature.watch.LocalCommentSignedIn provides (auth is AuthState.Verified),
                        vibe.tube.feature.watch.LocalCommentNotice provides comments::commentNotice,
                        vibe.tube.feature.watch.LocalBusyComments provides state.busyComments,
                    ) {
                    CommentItem(
                        comment = comment,
                        replies = state.replies[comment.id],
                        canReply = auth is AuthState.Verified,
                        onToggleReplies = { comments.toggleReplies(comment) },
                        onMoreReplies = { comments.loadMoreReplies(comment.id) },
                        onReply = { text, done -> comments.postReply(comment, text, done) },
                        onOpenAuthor = { id -> onDismiss(); actions.openChannel(id) },
                    )
                    }
                }
                if (state.loadingMore) item(key = "more") {
                    Box(Modifier.fillMaxWidth().padding(Spacing.md), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
                }
                if (state.loadMoreError) item(key = "more-error") {
                    TextButton(shapes = ButtonDefaults.shapes(), onClick = comments::loadMore) { Text(stringResource(R.string.feed_load_more_failed)) }
                }
            }
        }
        vibe.tube.feature.watch.CommentsFooter(state, auth, onPost = { text, done -> comments.postComment(text, done) }, onLogin = onLogin)
        }
    }
}

}

private const val SHORTS_STALE_MS = 30L * 60 * 1000

/** Loads the thumbnails of the next shorts ahead of time so they are not black while sliding in. */
private suspend fun prefetchPosters(context: android.content.Context, videos: List<VideoItem>) {
    val loader = coil3.SingletonImageLoader.get(context)
    for (video in videos) {
        val candidates = thumbnailCandidates(video).let { all -> all.filterNot { it in vibe.tube.ui.components.MissingImages.urls }.ifEmpty { all } }
        for (url in candidates) {
            val result = loader.execute(coil3.request.ImageRequest.Builder(context).data(url).build())
            if (result is coil3.request.SuccessResult) break
            if (candidates.size > 1) vibe.tube.ui.components.MissingImages.urls.add(url)
        }
    }
}
