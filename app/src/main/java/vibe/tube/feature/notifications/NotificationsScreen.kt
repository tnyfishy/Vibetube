package vibe.tube.feature.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vibe.tube.R
import vibe.tube.account.AuthState
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.Avatar
import vibe.tube.ui.components.LocalVideoActions
import vibe.tube.ui.components.RemoteImage
import vibe.tube.ui.components.StateMessage
import vibe.tube.ui.components.TooltipIconButton
import vibe.tube.ui.theme.Spacing
import vibe.tube.youtube.model.NotificationItem
import vibe.tube.youtube.model.Thumb
import vibe.tube.youtube.model.VideoItem

/** The channel notifications YouTube keeps for the account: new uploads, live streams, replies. Tapping one opens it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(onBack: () -> Unit, onLogin: () -> Unit, viewModel: NotificationsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val auth by viewModel.authState.collectAsStateWithLifecycle()
    val actions = LocalVideoActions.current
    val listState = rememberLazyListState()
    LaunchedEffect(listState, state.items.size) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 4
        }.collect { if (it) viewModel.loadMore() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notifications_title)) },
                navigationIcon = { TooltipIconButton(VIcon.ArrowBack, stringResource(R.string.action_back), onBack) },
            )
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when {
                auth is AuthState.Guest -> StateMessage(
                    VIcon.Notifications,
                    stringResource(R.string.notifications_title),
                    stringResource(R.string.notifications_login),
                    primaryLabel = stringResource(R.string.action_sign_in),
                    onPrimary = onLogin,
                )
                state.loading && state.items.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.items.isEmpty() -> StateMessage(
                    VIcon.Notifications,
                    stringResource(R.string.notifications_empty),
                    null,
                    primaryLabel = if (state.error != null) stringResource(R.string.action_retry) else null,
                    onPrimary = if (state.error != null) viewModel::refresh else null,
                )
                else -> LazyColumn(state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = Spacing.sm)) {
                    items(state.items, key = { it.id }) { item ->
                        NotificationRow(item) { open(item, actions) }
                    }
                    if (state.loadingMore) {
                        item(key = "more") {
                            Box(Modifier.fillMaxWidth().padding(Spacing.lg), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                        }
                    }
                }
            }
        }
    }
}

private fun open(item: NotificationItem, actions: vibe.tube.ui.components.VideoActions) {
    val videoId = item.videoId
    val channelId = item.channelPath?.takeIf { it.startsWith("/channel/") }?.removePrefix("/channel/")?.substringBefore('/')
    when {
        videoId != null -> {
            val video = VideoItem(
                id = videoId,
                title = item.title,
                channelName = null,
                channelId = channelId,
                channelAvatar = item.avatar,
                thumbnails = listOfNotNull(item.thumbnail?.let { Thumb(it, 480, 270) }),
                durationText = null,
                durationSeconds = null,
                viewCountText = null,
                publishedText = item.timeText,
            )
            actions.play(video, listOf(video))
        }
        channelId != null -> actions.openChannel(channelId)
    }
}

@Composable
private fun NotificationRow(item: NotificationItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // A dot in front of what has not been read yet.
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (!item.read) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent),
        )
        Avatar(item.avatar, 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            item.timeText?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (item.thumbnail != null) {
            RemoteImage(
                item.thumbnail,
                null,
                Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(8.dp)),
            )
        }
    }
}
