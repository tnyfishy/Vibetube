package vibe.tube.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import vibe.tube.R
import vibe.tube.data.repo.FeedFailure
import vibe.tube.data.repo.FeedPhase
import vibe.tube.data.repo.FeedSource
import vibe.tube.data.repo.FeedState
import vibe.tube.ui.VIcon
import vibe.tube.ui.theme.Sizing
import vibe.tube.ui.theme.Spacing
import vibe.tube.youtube.model.ChannelItem
import vibe.tube.youtube.model.FeedItem
import vibe.tube.youtube.model.PlaylistItem
import vibe.tube.youtube.model.VideoItem
import java.text.DateFormat
import java.util.Date

@Composable
fun FeedMessage(failure: FeedFailure, onRetry: () -> Unit, onLogin: () -> Unit, modifier: Modifier = Modifier) {
    when (failure) {
        FeedFailure.LoginRequired -> StateMessage(
            icon = VIcon.Lock,
            title = stringResource(R.string.feed_login_required_title),
            body = stringResource(R.string.feed_login_required_body),
            modifier = modifier,
            primaryLabel = stringResource(R.string.action_sign_in),
            onPrimary = onLogin,
        )
        FeedFailure.SessionExpired -> StateMessage(
            icon = VIcon.Lock,
            title = stringResource(R.string.feed_session_expired_title),
            body = stringResource(R.string.feed_session_expired_body),
            modifier = modifier,
            primaryLabel = stringResource(R.string.action_sign_in_again),
            onPrimary = onLogin,
        )
        is FeedFailure.Network -> StateMessage(
            icon = VIcon.WifiOff,
            title = stringResource(R.string.feed_error_network_title),
            body = failure.httpCode?.let { stringResource(R.string.feed_error_http_body, it) }
                ?: stringResource(R.string.feed_error_network_body),
            modifier = modifier,
            primaryLabel = stringResource(R.string.action_retry),
            onPrimary = onRetry,
        )
        is FeedFailure.Parse -> StateMessage(
            icon = VIcon.Error,
            title = stringResource(R.string.feed_error_parse_title),
            body = stringResource(R.string.feed_error_parse_body),
            modifier = modifier,
            primaryLabel = stringResource(R.string.action_retry),
            onPrimary = onRetry,
        )
        is FeedFailure.Other -> StateMessage(
            icon = VIcon.Error,
            title = stringResource(R.string.feed_error_generic_title),
            body = stringResource(R.string.feed_error_generic_body),
            modifier = modifier,
            primaryLabel = stringResource(R.string.action_retry),
            onPrimary = onRetry,
        )
    }
}

fun FeedItem.gridKey(): String = when (this) {
    is PlaylistItem -> "pl:$id"
    is ChannelItem -> "ch:$id"
    else -> id
}

@Composable
fun FeedContent(
    state: FeedState,
    hideShorts: Boolean,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    emptyTitle: String = stringResource(R.string.feed_empty_title),
    emptyBody: String? = stringResource(R.string.feed_empty_body),
    guestBanner: Boolean = false,
    singleColumn: Boolean = false,
    header: (LazyGridScope.() -> Unit)? = null,
    itemContent: @Composable (FeedItem, List<VideoItem>) -> Unit,
) {
    val visible = if (hideShorts) state.items.filterNot { it is VideoItem && it.isShort } else state.items
    val queue = visible.filterIsInstance<VideoItem>()
    val refreshing = state.phase == FeedPhase.Refreshing
    val blockingFailure = state.failure.takeIf { state.items.isEmpty() }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        val columns = if (singleColumn) GridCells.Fixed(1) else GridCells.Adaptive(Sizing.gridMinCardWidth)
        LazyVerticalGrid(
            columns = columns,
            state = gridState,
            contentPadding = PaddingValues(
                start = Spacing.gutter,
                end = Spacing.gutter,
                top = contentPadding.calculateTopPadding() + Spacing.sm,
                bottom = contentPadding.calculateBottomPadding() + LocalMiniPlayerPadding.current + Spacing.lg,
            ),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.fillMaxSize(),
        ) {
            header?.invoke(this)
            if (guestBanner) {
                item(key = "guest-banner", span = { GridItemSpan(maxLineSpan) }) {
                    Banner(
                        text = stringResource(R.string.guest_banner),
                        icon = VIcon.Person,
                        actionLabel = stringResource(R.string.action_sign_in),
                        onAction = onLogin,
                    )
                }
            }
            if (state.source == FeedSource.Cache && state.items.isNotEmpty()) {
                item(key = "cache-banner", span = { GridItemSpan(maxLineSpan) }) {
                    val time = state.cacheFetchedAt?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
                    val offline = state.failure is FeedFailure.Network
                    Banner(
                        text = if (offline) stringResource(R.string.feed_cached_offline, time.orEmpty()) else stringResource(R.string.feed_cached_updating, time.orEmpty()),
                        icon = if (offline) VIcon.CloudOff else VIcon.Cached,
                        actionLabel = if (offline) stringResource(R.string.action_retry) else null,
                        onAction = if (offline) onRefresh else null,
                    )
                }
            }
            if (blockingFailure != null) {
                item(key = "failure", span = { GridItemSpan(maxLineSpan) }) {
                    FeedMessage(blockingFailure, onRetry = onRefresh, onLogin = onLogin)
                }
            } else if (state.items.isEmpty() && state.phase == FeedPhase.LoadingFirst) {
                item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(vertical = Spacing.xxl * 2), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                }
            } else if (visible.isEmpty() && state.isEmptyContent) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    StateMessage(icon = VIcon.Info, title = emptyTitle, body = emptyBody)
                }
            } else {
                items(visible.size, key = { visible[it].gridKey() }, contentType = { visible[it]::class.simpleName }) { index ->
                    itemContent(visible[index], queue)
                }
            }
            if (state.phase == FeedPhase.LoadingMore) {
                item(key = "more-loading", span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(Spacing.lg), contentAlignment = Alignment.Center) { LoadingIndicator() }
                }
            }
            if (state.loadMoreFailed) {
                item(key = "more-failed", span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(shapes = ButtonDefaults.shapes(), onClick = onLoadMore) { Text(stringResource(R.string.feed_load_more_failed)) }
                    }
                }
            }
        }
    }

    val currentLoadMore by rememberUpdatedState(onLoadMore)
    val total = visible.size
    LaunchedEffect(gridState, total) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { currentLoadMore() }
    }
}

private const val LOAD_MORE_THRESHOLD = 6
