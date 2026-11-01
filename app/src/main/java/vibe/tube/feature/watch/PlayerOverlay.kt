package vibe.tube.feature.watch

import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.heightIn

import androidx.compose.foundation.selection.toggleable

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onGloballyPositioned
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

fun usesClassicPlayer(state: PlayerUiState, widthDp: Int, heightDp: Int): Boolean =
    usesClassicPlayer(state.fullscreen, state.inPip, widthDp, heightDp)

fun usesClassicPlayer(fullscreen: Boolean, inPip: Boolean, widthDp: Int, heightDp: Int): Boolean =
    fullscreen || inPip || widthDp >= 840 || (heightDp < 480 && widthDp > heightDp)

internal var fullscreenToggledAt = 0L

@Composable
fun PlayerOverlay(
    player: Player,
    controller: PlayerController,
    watch: WatchViewModel,
    onLogin: () -> Unit,
    onEnterPip: (() -> Unit)?,
    contentBottom: () -> Int = { 0 },
    miniAway: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val config = LocalConfiguration.current
    val layoutFlags by remember { controller.state.map { it.fullscreen to it.inPip }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = false to false)
    if (!usesClassicPlayer(layoutFlags.first, layoutFlags.second, config.screenWidthDp, config.screenHeightDp)) {
        MorphPlayer(player, controller, watch, onLogin, onEnterPip, contentBottom, miniAway, modifier)
        return
    }
    val state by controller.state.collectAsStateWithLifecycle()
    val expanded = state.visible && state.expanded
    BackHandler(enabled = expanded && !state.screenLocked) {
        if (state.fullscreen) controller.setFullscreen(false) else controller.collapse()
    }
    AnimatedVisibility(
        visible = expanded,
        enter = if (state.fullscreen || config.screenWidthDp > config.screenHeightDp) androidx.compose.animation.EnterTransition.None else slideInVertically(VibeMotion.spatial()) { it } + fadeIn(VibeMotion.effects()),
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
    val config = LocalConfiguration.current
    val ui by watch.state.collectAsStateWithLifecycle()
    val auth by watch.authState.collectAsStateWithLifecycle()
    val captionCues by controller.captionCues.collectAsStateWithLifecycle()
    var options by remember { mutableStateOf(false) }
    var chaptersOpen by remember { mutableStateOf(false) }
    var lastPanelWasOptions by remember { mutableStateOf(false) }
    LaunchedEffect(options, chaptersOpen) { if (options || chaptersOpen) lastPanelWasOptions = options }
    val panelIsOptions = if (options || chaptersOpen) options else lastPanelWasOptions
    val knownDuration = state.durationMs.takeIf { it > 0 } ?: 0L
    val chapters = remember(ui.info, knownDuration) {
        ui.info?.let { vibe.tube.player.Chapters.of(it.chapters, it.description, knownDuration) } ?: emptyList()
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
    val fsEnter = remember { Animatable(1f) }
    LaunchedEffect(Unit) { if (fsEnter.value < 1f) fsEnter.animateTo(1f, tween(320)) }
    val exitFullscreenDrag = Modifier.draggable(
        orientation = Orientation.Vertical,
        state = rememberDraggableState { delta -> scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { fsPull.snapTo((fsPull.value + delta).coerceAtLeast(0f)) } },
        onDragStopped = { velocity ->
            if (fsPull.value > 80f || velocity > 900f) {
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
                alpha = fsEnter.value
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
                androidx.compose.runtime.CompositionLocalProvider(LocalFullscreenVideoOffset provides { if (fullscreen) { if (fsPull.value <= 80f) fsPull.value else 80f + 140f * (fsPull.value - 80f) / (fsPull.value - 80f + 140f) } else 0f }) {
                VideoSurface(
                    player = player,
                    state = state.copy(fullscreen = fullscreen),
                    showControls = true,
                    onCollapse = controller::collapse,
                    onToggleFullscreen = { controller.setFullscreen(!state.fullscreen) },
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
                    modifier = m,
                    dragModifier = if (state.fullscreen) exitFullscreenDrag else if (fullscreen) Modifier else dragModifier,
                    chapters = activeChapters,
                    onChapters = { chaptersOpen = true },
                    captionCues = captionCues,
                    captionsAvailable = state.captions.isNotEmpty(),
                    captionsOn = state.selectedCaption != null,
                    onToggleCaptions = controller::toggleCaptions,
                    onUnlock = { controller.setScreenLocked(!controller.state.value.screenLocked) },
                )
                }
            }

            when {
                state.inPip -> surface(Modifier.fillMaxSize())
                fullscreen -> FullscreenSideStage(options || (chaptersOpen && activeChapters.isNotEmpty()),
                    { options = false; chaptersOpen = false }, {
                        if (panelIsOptions) PlayerOptionsSheet(state, controller, embedded = true) { options = false }
                        else ChapterSideContent(activeChapters, state.positionMs, controller::seekTo) { chaptersOpen = false }
                    }, surface)
                wide -> Row(Modifier.fillMaxSize().then(insets)) {
                    Column(Modifier.weight(0.62f).fillMaxHeight()) {
                        surface(Modifier.fillMaxWidth().aspectRatio(state.videoAspect.coerceIn(1.2f, 2.4f)))
                        SheetUnderVideo(Modifier.weight(1f)) {
                            Details(ui, state, controller, watch, auth, onLogin, includeTabs = false, modifier = Modifier.fillMaxSize())
                        }
                    }
                    SidePanel(ui, state, controller, watch, auth, onLogin, Modifier.weight(0.38f).fillMaxHeight())
                }
                else -> Column(Modifier.fillMaxSize().then(insets)) {
                    surface(Modifier.fillMaxWidth().aspectRatio(Sizing.THUMBNAIL_ASPECT))
                    SheetUnderVideo(Modifier.weight(1f)) {
                        Details(ui, state, controller, watch, auth, onLogin, includeTabs = true, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
    if (options && !(state.fullscreen || (config.screenHeightDp < 480 && config.screenWidthDp > config.screenHeightDp))) PlayerOptionsSheet(state, controller) { options = false }
    if (chaptersOpen && activeChapters.isNotEmpty() && !(state.fullscreen || (config.screenHeightDp < 480 && config.screenWidthDp > config.screenHeightDp))) {
        ChaptersSheet(activeChapters, state.positionMs, controller::seekTo,
            maxPanelHeight = if (state.fullscreen) null else (LocalConfiguration.current.screenHeightDp.dp - LocalConfiguration.current.screenWidthDp.dp / state.videoAspect.coerceIn(1.2f, 2.4f)).coerceAtLeast(100.dp)) { chaptersOpen = false }
    }
}

@Composable
private fun LikeDislikePill(
    status: LikeStatus?,
    likeText: String,
    dislikeText: String?,
    enabled: Boolean,
    onLike: () -> Unit,
    onDislike: () -> Unit,
) {
    val container = MaterialTheme.colorScheme.secondaryContainer
    val content = MaterialTheme.colorScheme.onSecondaryContainer
    val liked = status == LikeStatus.Like
    val disliked = status == LikeStatus.Dislike
    Row(
        Modifier.height(32.dp).clip(CircleShape).background(container),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.fillMaxHeight().clickable(enabled = enabled, onClick = onLike).padding(start = 12.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val likeTint by androidx.compose.animation.animateColorAsState(if (liked) MaterialTheme.colorScheme.primary else content, MaterialTheme.motionScheme.fastEffectsSpec(), label = "likeTint")
            vibe.tube.ui.components.SelectableSymbol(VIcon.ThumbUp, VIcon.ThumbUpFilled, liked, null, Modifier.size(18.dp), tint = likeTint)
            Text(likeText, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
        }
        Box(Modifier.width(1.dp).height(18.dp).background(content.copy(alpha = 0.25f)))
        Row(
            Modifier.fillMaxHeight().clickable(enabled = enabled, onClick = onDislike).padding(start = 10.dp, end = if (dislikeText != null) 12.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val dislikeTint by androidx.compose.animation.animateColorAsState(if (disliked) MaterialTheme.colorScheme.primary else content, MaterialTheme.motionScheme.fastEffectsSpec(), label = "dislikeTint")
            vibe.tube.ui.components.SelectableSymbol(
                VIcon.ThumbDown,
                VIcon.ThumbDownFilled,
                disliked,
                stringResource(R.string.action_dislike),
                Modifier.size(18.dp),
                tint = dislikeTint,
            )
            if (dislikeText != null) Text(dislikeText, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
        }
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
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val base = ui.likeBaseline ?: vibe.tube.youtube.model.LikeStatus.Indifferent
        val now = ui.likeStatus ?: base
        val likeDelta = (if (now == vibe.tube.youtube.model.LikeStatus.Like) 1 else 0) - (if (base == vibe.tube.youtube.model.LikeStatus.Like) 1 else 0)
        val dislikeDelta = (if (now == vibe.tube.youtube.model.LikeStatus.Dislike) 1 else 0) - (if (base == vibe.tube.youtube.model.LikeStatus.Dislike) 1 else 0)
        LikeDislikePill(
            status = ui.likeStatus,
            
            likeText = ui.votes?.likes?.let { compactCount((it + likeDelta).coerceAtLeast(0).toString()) }
                ?: ui.info?.likeCountText?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.action_like),
            dislikeText = ui.votes?.dislikes?.let { compactCount((it + dislikeDelta).coerceAtLeast(0).toString()) },
            enabled = !ui.likeBusy,
            onLike = { watch.toggleLike(onLogin) },
            onDislike = { watch.toggleDislike(onLogin) },
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
    val comments = remember { CommentsTransition() }
    val commentsScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val hiddenChannels = vibe.tube.ui.components.LocalHiddenChannels.current
    LaunchedEffect(listState, ui.related.size) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 4
        }.distinctUntilChanged().filter { it }.collect {
            if (includeTabs) watch.loadMoreRelated()
        }
    }
    
    var shownVideo by remember { androidx.compose.runtime.mutableStateOf(state.current?.id) }
    LaunchedEffect(state.current?.id) {
        val id = state.current?.id
        if (id != null && id != shownVideo) {
            shownVideo = id
            if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) listState.animateScrollToItem(0)
        }
    }
    val highlightComment = rememberCommentsRequest(controller, state, comments)
    val mix = remember { MixPanelTransition() }
    val queueView = QueueView.of(state)
    var mixFooterPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val mixFooterHeight = with(androidx.compose.ui.platform.LocalDensity.current) { mixFooterPx.toDp() }
    Box(
        modifier.fillMaxWidth().onGloballyPositioned {
            comments.containerPositioned(it)
            mix.container = it
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 1f - comments.progress.value },
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.sm, bottom = if (queueView != null) mixFooterHeight + Spacing.sm else Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            detailsHeader(ui, state, watch, onLogin)
            if (includeTabs) {
                item(key = "comments-card") {
                    CommentsPreviewCard(ui.comments) { commentsScope.launch { comments.show() } }
                }
                upNext(ui, state, controller, watch, hiddenChannels)
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = includeTabs && state.expanded && queueView != null,
            enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { mixFooterPx = it.height }.windowInsetsPadding(WindowInsets.navigationBars).padding(Spacing.sm),
        ) {
            if (queueView != null) MixQueueCard(queueView, mix)
        }
        VideoInformationPanel(ui, state) { watch.toggleDescription() }
        CommentsPanel(comments, ui, auth, watch, onLogin, highlightComment)
        if (includeTabs) MixQueuePanel(mix, queueView, state.expanded, remember(controller) { controller.queueControls() })
    }
}

@Composable
internal fun SheetUnderVideo(modifier: Modifier, stripAlpha: () -> Float = { 1f }, content: @Composable () -> Unit) {
    val surface = MaterialTheme.colorScheme.surface
    Box(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(SHEET_RADIUS)
                .graphicsLayer { alpha = stripAlpha() }
                .background(Color.Black),
        )
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(topStart = SHEET_RADIUS, topEnd = SHEET_RADIUS))
                .background(surface),
        ) { content() }
    }
}

private val SHEET_RADIUS = 18.dp

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
    val comments = remember { CommentsTransition() }
    val commentsScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val hiddenChannels = vibe.tube.ui.components.LocalHiddenChannels.current
    LaunchedEffect(listState, ui.related.size) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 4
        }.distinctUntilChanged().filter { it }.collect { watch.loadMoreRelated() }
    }
    val highlightComment = rememberCommentsRequest(controller, state, comments)
    val mix = remember { MixPanelTransition() }
    val queueView = QueueView.of(state)
    var mixFooterPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val mixFooterHeight = with(androidx.compose.ui.platform.LocalDensity.current) { mixFooterPx.toDp() }
    Box(
        modifier.windowInsetsPadding(WindowInsets.statusBars).onGloballyPositioned {
            comments.containerPositioned(it)
            mix.container = it
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 1f - comments.progress.value },
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.gutter, bottom = if (queueView != null) mixFooterHeight + Spacing.gutter else Spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item(key = "comments-card") {
                CommentsPreviewCard(ui.comments) { commentsScope.launch { comments.show() } }
            }
            upNext(ui, state, controller, watch, hiddenChannels)
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = state.expanded && queueView != null,
            enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { mixFooterPx = it.height }.windowInsetsPadding(WindowInsets.navigationBars).padding(Spacing.sm),
        ) {
            if (queueView != null) MixQueueCard(queueView, mix)
        }
        VideoInformationPanel(ui, state) { watch.toggleDescription() }
        CommentsPanel(comments, ui, auth, watch, onLogin, highlightComment)
        MixQueuePanel(mix, queueView, state.expanded, remember(controller) { controller.queueControls() })
    }
}

@Composable
private fun CommentsPreviewCard(comments: CommentsUiState, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val first = comments.featured
    val disabled = comments.disabledMessage != null
    Surface(
        onClick = onOpen,
        enabled = !disabled,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(R.string.comments_title), style = MaterialTheme.typography.titleSmall)
                compactCount(comments.totalText)?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when {
                disabled -> Text(stringResource(R.string.comments_disabled_title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                first != null -> Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Avatar(first.authorAvatar, Sizing.avatarSmall + 8.dp, channelId = first.authorChannelId)
                    Text(first.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                comments.error -> Text(stringResource(R.string.comments_error_title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                comments.loading -> Box(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), contentAlignment = Alignment.CenterStart) {
                    androidx.compose.material3.LinearWavyProgressIndicator(Modifier.fillMaxWidth(0.4f))
                }
                else -> Text(stringResource(R.string.comments_empty_title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun LazyListScope.detailsHeader(ui: WatchUiState, state: PlayerUiState, watch: WatchViewModel, onLogin: () -> Unit) {
    val info = ui.info
    item(key = "title") {
        Column(Modifier.clickable { watch.toggleDescription() }, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(info?.title?.ifBlank { null } ?: state.current?.title.orEmpty(), style = MaterialTheme.typography.titleLarge)
            val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0] ?: java.util.Locale.getDefault()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(listOfNotNull(vibe.tube.ui.metadata.detailedViews(info?.viewCountText, locale),
                    vibe.tube.ui.metadata.displayDate(info?.dateText, locale)).joinToString(" · "), Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.action_show_more).lowercase(), Modifier.padding(start = 6.dp).clickable { watch.toggleDescription() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    item(key = "actions") { ActionRow(ui, state, watch, onLogin) }
    item(key = "channel") {
        val channel = info?.channel
        val actions = LocalVideoActions.current
        if (channel != null) {
            // A collaboration names all its channels and opens a list of them; the subscribe button stays the uploader's.
            val collaborators = channel.collaborators
            var showCollaborators by remember { mutableStateOf(false) }
            if (showCollaborators) {
                vibe.tube.ui.components.CollaboratorsSheet(collaborators, actions::openChannel) { showCollaborators = false }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Row(
                    Modifier.weight(1f).clickable(enabled = channel.id != null || collaborators.size > 1) {
                        if (collaborators.size > 1) showCollaborators = true else channel.id?.let(actions::openChannel)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    if (collaborators.size > 1) {
                        vibe.tube.ui.components.CollaboratorAvatars(collaborators, Sizing.avatarLarge, MaterialTheme.colorScheme.surface)
                    } else {
                        Avatar(channel.avatar, Sizing.avatarLarge, channelId = channel.id)
                    }
                    Column {
                        vibe.tube.ui.components.ChannelMetaText(
                            if (collaborators.size > 1) vibe.tube.ui.components.collaboratorsTitle(collaborators)
                            else channel.name.ifBlank { state.current?.channelName.orEmpty() },
                            channel.badge, emptyList(),
                            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                        )
                        channel.subscriberText?.let { raw ->
                            val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0] ?: java.util.Locale.getDefault()
                            val value = vibe.tube.ui.metadata.displayCount(raw, locale).orEmpty()
                            Text(if (raw.toLongOrNull() != null) stringResource(R.string.metadata_subscribers, value) else value,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } ?: Text(stringResource(R.string.metadata_subscribers_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                vibe.tube.ui.components.SubscribeButton(
                    subscribed = ui.subscribed == true,
                    enabled = !ui.subscribeBusy && channel.id != null,
                    notifyLevels = channel.notify?.params?.keys.orEmpty(),
                    notifyLevel = ui.notifyLevel,
                    onToggle = { watch.toggleSubscribe(onLogin) },
                    onNotify = watch::setNotify,
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
    item(key = "autoplay-toggle") {
        val on by controller.autoplay.collectAsStateWithLifecycle()
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().clickable { controller.setAutoplay(!on) },
        ) {
            Row(
                Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                VIconImage(VIcon.SkipNext, null, Modifier.size(22.dp), tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.player_autoplay), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(if (on) R.string.player_autoplay_on else R.string.player_autoplay_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(checked = on, onCheckedChange = { controller.setAutoplay(it) })
            }
        }
    }
    
    if (state.playlistId != null) {
        item(key = "related-title") { Text(stringResource(R.string.player_up_next), style = MaterialTheme.typography.titleSmall) }
    }
    val related = upNextRelated(ui.related, state.queue, state.current?.id, state.playlistId, hiddenChannels)
    items(related.size, key = { "r:${related[it].id}" }) { index ->
        when (val item = related[index]) {
            is VideoItem -> VideoRow(item, emptyList(), animateLaunch = false)
            is PlaylistItem -> vibe.tube.ui.components.PlaylistRow(item, animateLaunch = false)
            else -> Unit
        }
    }
    if (ui.relatedLoading) item(key = "related-loading") { Box(Modifier.fillMaxWidth().padding(Spacing.md), contentAlignment = Alignment.Center) { androidx.compose.material3.ContainedLoadingIndicator() } }
}

internal fun LazyListScope.commentsList(
    ui: WatchUiState,
    auth: AuthState,
    watch: WatchViewModel,
    onLogin: () -> Unit,
    showTitle: Boolean = true,
    showComposer: Boolean = true,
    highlightId: String? = null,
) {
    val c = ui.comments
    item(key = "comments-header") {
        CommentsHeader(c, auth, onSort = watch::changeSort, onPost = { text, done -> watch.postComment(text, done) }, onLogin = onLogin, showTitle = showTitle, showComposer = showComposer)
    }
    item(key = "comments-state") { CommentsEmptyStates(c, watch::retryComments) }
    items(c.comments.size, key = { "c:${c.comments[it].id}" }) { index ->
        val comment = c.comments[index]
        
        val highlighted = comment.id == highlightId
        Box(
            if (highlighted) {
                Modifier.background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f), MaterialTheme.shapes.medium).padding(Spacing.xs)
            } else {
                Modifier
            },
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalRateComment provides { comment, heart -> watch.rateComment(comment, heart) },
                LocalDislikeComment provides { comment -> watch.rateComment(comment, dislike = true) },
                LocalReplyComment provides { comment, text, done -> watch.postReply(comment, text, done) },
                LocalPreviewReplies provides watch::previewReplies,
                LocalCommentSignedIn provides (auth is AuthState.Verified),
                LocalCommentNotice provides watch::commentNotice,
                LocalBusyComments provides c.busyComments,
                LocalHighlightComment provides highlightId,
                LocalCommentOwner provides ui.info?.channel?.id,
            ) {
            CommentItem(
                comment = if (comment.creatorHeart && comment.creatorHeartAvatar == null) comment.copy(creatorHeartAvatar = ui.info?.channel?.avatar) else comment,
                replies = c.replies[comment.id],
                canReply = auth is AuthState.Verified,
                onToggleReplies = { watch.toggleReplies(comment) },
                onMoreReplies = { watch.loadMoreReplies(comment.id) },
                onReply = { text, done -> watch.postReply(comment, text, done) },
            )
            }
        }
    }
    if (c.loadingMore) item(key = "comments-more") { Box(Modifier.fillMaxWidth().padding(Spacing.md), contentAlignment = Alignment.Center) { androidx.compose.material3.ContainedLoadingIndicator() } }
    if (c.loadMoreError) item(key = "comments-more-error") { TextButton(shapes = ButtonDefaults.shapes(), onClick = watch::loadMoreComments) { Text(stringResource(R.string.feed_load_more_failed)) } }
}

@Composable
private fun rememberCommentsRequest(controller: PlayerController, state: PlayerUiState, comments: CommentsTransition): String? {
    val pending by controller.commentsRequest.collectAsStateWithLifecycle()
    var highlight by remember { androidx.compose.runtime.mutableStateOf<PlayerController.CommentsRequest?>(null) }
    val videoId = state.current?.id
    LaunchedEffect(pending, videoId, state.expanded) {
        if (videoId == null || !state.expanded) return@LaunchedEffect
        val request = pending?.takeIf { it.videoId == videoId } ?: return@LaunchedEffect
        highlight = request
        comments.show()

        controller.takeCommentsRequest(videoId)
    }
    
    return highlight?.takeIf { it.videoId == videoId }?.commentId
}
