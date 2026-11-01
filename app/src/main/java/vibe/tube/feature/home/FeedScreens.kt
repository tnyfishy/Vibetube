package vibe.tube.feature.home

import kotlinx.coroutines.flow.collectLatest

import vibe.tube.youtube.model.bestFor
import androidx.compose.foundation.layout.size
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import vibe.tube.ui.components.bouncyClickable
import androidx.compose.material3.toShape
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import vibe.tube.ui.components.HeaderSurface
import vibe.tube.ui.components.SearchHeaderRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vibe.tube.R
import vibe.tube.account.AuthState
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.Avatar
import vibe.tube.ui.components.VIconImage
import vibe.tube.ui.components.ChannelRow
import vibe.tube.ui.components.FeedContent
import vibe.tube.ui.components.PlaylistCard
import vibe.tube.ui.components.TooltipIconButton
import vibe.tube.ui.components.VideoCard
import vibe.tube.ui.theme.Sizing
import vibe.tube.feature.welcome.appRevealRise
import androidx.compose.animation.togetherWith
import vibe.tube.ui.theme.Spacing
import vibe.tube.youtube.model.ChannelItem
import vibe.tube.youtube.model.PlaylistItem
import vibe.tube.youtube.model.VideoItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onSearch: () -> Unit,
    onLogin: () -> Unit,
    onAccount: () -> Unit,
    onNotifications: () -> Unit,
    avatarUrl: String?,
    contentPadding: PaddingValues,
    onHistory: () -> Unit = {},
    visibilityScope: AnimatedVisibilityScope? = null,
    viewModel: HomeViewModel = hiltViewModel(),
    badge: vibe.tube.feature.notifications.NotificationBadgeViewModel = hiltViewModel(),
) {
    val unseen by badge.unseen.collectAsStateWithLifecycle()
    val unfinished by viewModel.unfinished.collectAsStateWithLifecycle()
    androidx.lifecycle.compose.LifecycleResumeEffect(badge) {
        badge.refresh()
        onPauseOrDispose { }
    }
    androidx.lifecycle.compose.LifecycleResumeEffect(viewModel) {
        viewModel.refreshIfStale(HOME_STALE_MS)
        onPauseOrDispose { }
    }
    val selectedFeed by viewModel.selectedFeed.collectAsStateWithLifecycle()
    val topic by viewModel.topic.collectAsStateWithLifecycle()
    val homeChips by viewModel.homeChips.collectAsStateWithLifecycle()
    val selectedChip by viewModel.selectedChip.collectAsStateWithLifecycle()
    val state by selectedFeed.state.collectAsStateWithLifecycle()
    val hideShorts by viewModel.hideShorts.collectAsStateWithLifecycle()
    val auth by viewModel.authState.collectAsStateWithLifecycle()
    val gridState = savedFeedGrid(topic.name)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val statusTop = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val surface = MaterialTheme.colorScheme.surface
    var headerHeightPx by remember { androidx.compose.runtime.mutableIntStateOf(with(density) { (statusTop + 64.dp).roundToPx() }) }
    var headerOffset by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var returningToTop by remember { androidx.compose.runtime.mutableStateOf(false) }
    val headerMotionOwner = remember { intArrayOf(0) }
    val headerNested = remember(gridState) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (returningToTop) return androidx.compose.ui.geometry.Offset.Zero
                headerMotionOwner[0]++
                val h = headerHeightPx.toFloat()
                if (h <= 0f) return androidx.compose.ui.geometry.Offset.Zero
                val atTop = gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
                headerOffset = if (atTop && available.y > 0f) 0f else (headerOffset + available.y).coerceIn(-h, 0f)
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (returningToTop) return androidx.compose.ui.unit.Velocity.Zero
                
                val h = headerHeightPx.toFloat()
                val target = if (headerOffset < -h / 2f) -h else 0f
                val owner = headerMotionOwner[0]
                if (headerOffset != target && headerOffset != 0f && headerOffset != -h) {
                    androidx.compose.animation.core.animate(headerOffset, target, animationSpec = androidx.compose.animation.core.tween(180)) { v, _ -> if (!returningToTop && owner == headerMotionOwner[0]) headerOffset = v }
                }
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(viewModel, gridState) {
        viewModel.events.homeReselect.collectLatest {
            val atTop = gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
            headerMotionOwner[0]++
            headerOffset = 0f
            if (!atTop) {
                returningToTop = true
                try { gridState.animateScrollToItem(0) }
                finally { headerOffset = 0f; returningToTop = false }
            }
            if (atTop) viewModel.refresh()
        }
    }
    val homeShorts = androidx.compose.runtime.remember(state.items) { state.items.filterIsInstance<vibe.tube.youtube.model.VideoItem>().filter { it.isShort } }
    val headerHeightDp = with(density) { headerHeightPx.toDp() }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        FeedContent(
            state = state,
            hideShorts = false,
            itemFilter = { item -> !(item is vibe.tube.youtube.model.VideoItem && item.isShort && item.id != homeShorts.firstOrNull()?.id) },
            onRefresh = viewModel::refresh,
            onLoadMore = viewModel::loadMore,
            onLogin = onLogin,
            modifier = Modifier.nestedScroll(headerNested).appRevealRise(28.dp, lag = 0.15f),
            gridState = gridState,
            contentPadding = PaddingValues(top = headerHeightDp, bottom = contentPadding.calculateBottomPadding()),
            guestBanner = false,
            header = {
                if (unfinished.isNotEmpty()) item(key = "continue-watching", span = { GridItemSpan(maxLineSpan) }) {
                    ContinueWatching(unfinished, onHistory)
                }
            },
        ) { item, queue ->
            if (item is vibe.tube.youtube.model.VideoItem && item.isShort) vibe.tube.ui.components.ShortsShelf(homeShorts) else FeedItemContent(item, queue)
        }
        Box(Modifier.fillMaxWidth().height(statusTop).background(surface).zIndex(2f).pointerInput(Unit) { detectTapGestures { } })
        Box(
            Modifier
                .fillMaxWidth()
                .appRevealRise((-14).dp, lag = 0.05f)
                .onSizeChanged { if (it.height > 0) headerHeightPx = it.height }
                .graphicsLayer {
                    translationY = headerOffset
                }
                .background(surface)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(top = statusTop),
        ) {
            Column {
            SearchHeaderRow(
                title = stringResource(R.string.app_name),
                onSearch = onSearch,
                modifier = Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.xs, bottom = Spacing.sm),
                visibilityScope = visibilityScope,
                wordmark = true,
                shown = { headerOffset > -headerHeightPx / 2f },
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                val cookie = androidx.compose.material3.MaterialShapes.Cookie12Sided.toShape()
                if (auth is AuthState.Guest) {
                    
                    Box(
                        Modifier
                            .size(Sizing.minTouchTarget)
                            .clickable(
                                interactionSource = null,
                                
                                indication = androidx.compose.material3.ripple(bounded = false, radius = HeaderAvatarSize / 2),
                                role = androidx.compose.ui.semantics.Role.Button,
                                onClickLabel = stringResource(R.string.action_sign_in),
                                onClick = onLogin,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(HeaderAvatarSize).clip(cookie).background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            VIconImage(VIcon.Person, stringResource(R.string.action_sign_in), Modifier.size(HeaderIconSize - 2.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                } else {
                    TooltipIconButton(
                        VIcon.Notifications,
                        stringResource(R.string.notifications_title),
                        { badge.clear(); onNotifications() },
                        iconSize = HeaderIconSize,
                        badge = if (unseen > 0) { { CountBadge(unseen) } } else null,
                    )
                    
                    Box(
                        Modifier
                            .size(Sizing.minTouchTarget)
                            .clickable(
                                interactionSource = null,
                                indication = androidx.compose.material3.ripple(bounded = false, radius = HeaderAvatarSize / 2),
                                role = androidx.compose.ui.semantics.Role.Button,
                                onClickLabel = stringResource(R.string.nav_account),
                                onClick = onAccount,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Avatar(avatarUrl, HeaderAvatarSize, description = stringResource(R.string.nav_account), shape = cookie)
                    }
                }
              }
            }
            androidx.compose.foundation.lazy.LazyRow(
                contentPadding = PaddingValues(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (homeChips.isNotEmpty()) {
                    item(key = "native_all") { androidx.compose.material3.FilterChip(selected = selectedChip == null, onClick = { viewModel.select(HomeTopic.All) }, label = { Text(stringResource(R.string.topic_all)) }) }
                    val choices = homeChips.filterNot { it.selected }
                    items(choices.size, key = { "native:" + choices[it].key }) { i ->
                        val chip = choices[i]
                        androidx.compose.material3.FilterChip(selected = selectedChip == chip.key, onClick = { viewModel.selectChip(chip) }, label = { Text(chip.title, maxLines = 1) })
                    }
                } else items(HomeTopic.entries.size, key = { HomeTopic.entries[it].name }) { index ->
                    val value = HomeTopic.entries[index]
                    androidx.compose.material3.FilterChip(
                        selected = topic == value, onClick = { viewModel.select(value) },
                        label = { Text(stringResource(value.labelRes), maxLines = 1) },
                    )
                }
            }

            }
        }
    }
}

internal val HeaderIconSize = vibe.tube.ui.components.HeaderGlyphSize
internal val HeaderAvatarSize = 34.dp

@Composable
private fun CountBadge(count: Int) {
    androidx.compose.material3.Badge {
        androidx.compose.animation.AnimatedContent(
            targetState = if (count > 99) "99+" else count.toString(),
            transitionSpec = {
                (androidx.compose.animation.slideInVertically(androidx.compose.animation.core.spring(stiffness = 900f)) { it / 2 } +
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.spring(stiffness = 900f))) togetherWith
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.spring(stiffness = 1400f)) using
                    androidx.compose.animation.SizeTransform(clip = false)
            },
            label = "badgeCount",
        ) { Text(it) }
    }
}

@Composable
fun FeedItemContent(item: vibe.tube.youtube.model.FeedItem, queue: List<VideoItem>) {
    when (item) {
        is VideoItem -> VideoCard(item, queue)
        is PlaylistItem -> PlaylistCard(item)
        is ChannelItem -> ChannelRow(item)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    onSearch: () -> Unit,
    onLogin: () -> Unit,
    contentPadding: PaddingValues,
    visibilityScope: AnimatedVisibilityScope? = null,
    feedViewModel: SubscriptionsFeedViewModel = hiltViewModel(),
    channelsViewModel: SubscribedChannelsViewModel = hiltViewModel(),
) {
    var showChannels by remember { androidx.compose.runtime.mutableStateOf(false) }
    val selected by feedViewModel.selectedFeed.collectAsStateWithLifecycle()
    val feedState by selected.state.collectAsStateWithLifecycle()
    val channelsState by channelsViewModel.controller.state.collectAsStateWithLifecycle()
    val channel by feedViewModel.channel.collectAsStateWithLifecycle()
    val grid = savedFeedGrid(channel ?: "all")
    val scrolled by remember(grid) { derivedStateOf { grid.firstVisibleItemIndex > 0 || grid.firstVisibleItemScrollOffset > 0 } }
    Scaffold(contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0), topBar = {
        HeaderSurface(scrolled) {
            Column {
                SearchHeaderRow(title = stringResource(R.string.nav_subscriptions), onSearch = onSearch,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), visibilityScope = visibilityScope)
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.subscriptions_your_channels), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    androidx.compose.material3.TextButton(onClick = { showChannels = true }) { Text(stringResource(R.string.subscriptions_see_all)) }
                }
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = PaddingValues(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val channels = channelsState.items.filterIsInstance<ChannelItem>().distinctBy { it.id }
                    items(channels.size, key = { channels[it].id }) { i ->
                        val ch = channels[i]
                        val chosen = channel == ch.id
                        Column(Modifier.size(width = 88.dp, height = 100.dp)
                            .clip(MaterialTheme.shapes.large)
                            .background(if (chosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .bouncyClickable(MaterialTheme.shapes.large) { feedViewModel.select(if (chosen) null else ch.id) }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Avatar(ch.avatars.bestFor(128), 52.dp, channelId = ch.id)
                            Text(ch.name, Modifier.padding(top = Spacing.xs), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        if (i >= channels.lastIndex - 3) androidx.compose.runtime.LaunchedEffect(channels.size) { channelsViewModel.loadMore() }
                    }
                    // Unkeyed on purpose: a keyed tail item is the only thing visible before channels load, and
                    // LazyRow would keep it anchored in view, scrolling the row to the end once channels arrive.
                    item {
                        Column(Modifier.size(width = 88.dp, height = 100.dp).bouncyClickable(MaterialTheme.shapes.large) { showChannels = true }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(52.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) { VIconImage(VIcon.ArrowForward, null) }
                            Text(stringResource(R.string.subscriptions_see_all), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }
                if (channel != null) {
                    val chosen = channelsState.items.filterIsInstance<ChannelItem>().firstOrNull { it.id == channel }
                    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                        Text(chosen?.name.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        val actions = vibe.tube.ui.components.LocalVideoActions.current
                        androidx.compose.material3.TextButton(onClick = { channel?.let(actions::openChannel) }) { Text(stringResource(R.string.subscriptions_open_channel)) }
                    }
                }
            }
        }
    }) { inner ->
        FeedContent(state = feedState, hideShorts = true, onRefresh = feedViewModel::refresh,
            onLoadMore = feedViewModel::loadMore, onLogin = onLogin, gridState = grid,
            contentPadding = PaddingValues(top = inner.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()),
            emptyTitle = stringResource(R.string.subs_empty_title), emptyBody = stringResource(R.string.subs_empty_body),
        ) { item, queue -> FeedItemContent(item, queue) }
    }
    if (showChannels) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showChannels = false }) {
            Text(stringResource(R.string.nav_subscriptions), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(Spacing.lg))
            val directory = channelsState.items.filterIsInstance<ChannelItem>().distinctBy { it.id }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth()) {
                items(directory.size, key = { directory[it].id }) { i ->
                    val ch = directory[i]
                    Row(Modifier.fillMaxWidth().bouncyClickable(MaterialTheme.shapes.large) { feedViewModel.select(ch.id); showChannels = false }.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Avatar(ch.avatars.bestFor(128), 52.dp, channelId = ch.id)
                        Text(ch.name, Modifier.weight(1f))
                        val actions = vibe.tube.ui.components.LocalVideoActions.current
                        TooltipIconButton(VIcon.ArrowForward, stringResource(R.string.subscriptions_open_channel), { showChannels = false; actions.openChannel(ch.id) })
                    }
                    if (i >= directory.lastIndex - 3) androidx.compose.runtime.LaunchedEffect(directory.size) { channelsViewModel.loadMore() }
                }
                if (channelsState.phase != vibe.tube.data.repo.FeedPhase.Idle) item { androidx.compose.material3.CircularProgressIndicator(Modifier.padding(Spacing.lg)) }
                if (channelsState.failure != null || channelsState.loadMoreFailed) item { androidx.compose.material3.TextButton(onClick = { if (directory.isEmpty()) channelsViewModel.refresh() else channelsViewModel.loadMore() }) { Text(stringResource(R.string.action_retry)) } }
                if (directory.isEmpty() && channelsState.phase == vibe.tube.data.repo.FeedPhase.Idle && channelsState.failure == null) item { Text(stringResource(R.string.subs_empty_title), Modifier.padding(Spacing.lg)) }
            }
        }
    }

}

private const val HOME_STALE_MS = 20L * 60 * 1000
