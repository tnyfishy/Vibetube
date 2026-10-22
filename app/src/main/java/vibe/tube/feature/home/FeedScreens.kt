package vibe.tube.feature.home

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vibe.tube.R
import vibe.tube.account.AuthState
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.Avatar
import vibe.tube.ui.components.ChannelRow
import vibe.tube.ui.components.FeedContent
import vibe.tube.ui.components.PlaylistCard
import vibe.tube.ui.components.TooltipIconButton
import vibe.tube.ui.components.VideoCard
import vibe.tube.ui.theme.Sizing
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
    avatarUrl: String?,
    contentPadding: PaddingValues,
    visibilityScope: AnimatedVisibilityScope? = null,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.controller.state.collectAsStateWithLifecycle()
    val hideShorts by viewModel.hideShorts.collectAsStateWithLifecycle()
    val auth by viewModel.authState.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val scrolled by remember { derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0 } }
    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            HeaderSurface(scrolled) {
                SearchHeaderRow(
                    title = stringResource(R.string.app_name),
                    onSearch = onSearch,
                    modifier = Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.xs, bottom = Spacing.sm),
                    visibilityScope = visibilityScope,
                ) {
                    if (auth is AuthState.Guest) {
                        TooltipIconButton(VIcon.Login, stringResource(R.string.action_sign_in), onLogin)
                    } else {
                        androidx.compose.foundation.layout.Box(
                            Modifier
                                .size(44.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = stringResource(R.string.nav_account), onClick = onAccount),
                            contentAlignment = Alignment.Center,
                        ) {
                            Avatar(avatarUrl, 36.dp, description = stringResource(R.string.nav_account))
                        }
                    }
                }
            }
        },
    ) { inner ->
        FeedContent(
            state = state,
            hideShorts = hideShorts,
            onRefresh = viewModel::refresh,
            onLoadMore = viewModel::loadMore,
            onLogin = onLogin,
            gridState = gridState,
            contentPadding = PaddingValues(top = inner.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()),
            guestBanner = state.guest,
        ) { item, queue -> FeedItemContent(item, queue) }
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val feedState by feedViewModel.controller.state.collectAsStateWithLifecycle()
    val channelsState by channelsViewModel.controller.state.collectAsStateWithLifecycle()
    val hideShorts by feedViewModel.hideShorts.collectAsStateWithLifecycle()
    val feedGrid = rememberLazyGridState()
    val channelGrid = rememberLazyGridState()
    val scrolled by remember {
        derivedStateOf {
            val g = if (tab == 0) feedGrid else channelGrid
            g.firstVisibleItemIndex > 0 || g.firstVisibleItemScrollOffset > 0
        }
    }
    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            Column {
                HeaderSurface(scrolled) {
                    Column {
                        SearchHeaderRow(
                            title = stringResource(R.string.nav_subscriptions),
                            onSearch = onSearch,
                            modifier = Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.xs, bottom = Spacing.sm),
                            visibilityScope = visibilityScope,
                        )
                        PrimaryTabRow(selectedTabIndex = tab, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
                            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.subs_tab_latest)) })
                            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.subs_tab_channels)) })
                        }
                    }
                }
            }
        },
    ) { inner ->
        val padding = PaddingValues(top = inner.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding())
        if (tab == 0) {
            FeedContent(
                state = feedState,
                hideShorts = hideShorts,
                onRefresh = feedViewModel::refresh,
                onLoadMore = feedViewModel::loadMore,
                onLogin = onLogin,
                gridState = feedGrid,
                contentPadding = padding,
                emptyTitle = stringResource(R.string.subs_empty_title),
                emptyBody = stringResource(R.string.subs_empty_body),
            ) { item, queue -> FeedItemContent(item, queue) }
        } else {
            FeedContent(
                state = channelsState,
                hideShorts = false,
                onRefresh = channelsViewModel::refresh,
                onLoadMore = channelsViewModel::loadMore,
                onLogin = onLogin,
                gridState = channelGrid,
                contentPadding = padding,
                singleColumn = false,
                emptyTitle = stringResource(R.string.subs_channels_empty_title),
                emptyBody = stringResource(R.string.subs_channels_empty_body),
            ) { item, queue -> FeedItemContent(item, queue) }
        }
    }
}
