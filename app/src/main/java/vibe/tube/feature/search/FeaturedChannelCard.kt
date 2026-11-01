package vibe.tube.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import vibe.tube.R
import vibe.tube.data.repo.ChannelSubscription
import vibe.tube.ui.components.*
import vibe.tube.youtube.model.ChannelItem
import vibe.tube.youtube.model.NotifyLevel
import vibe.tube.youtube.model.VideoItem

@Composable
internal fun FeaturedChannelCard(
    channel: ChannelItem,
    subscription: ChannelSubscription?,
    videos: List<VideoItem>,
    busy: Boolean,
    onSubscribe: () -> Unit,
    onNotify: (NotifyLevel) -> Unit,
) {
    val actions = LocalVideoActions.current
    // Only vertical padding here: the video row scrolls to the card's edge, so its padding lives inside the row.
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(role = Role.Button) { actions.openChannel(channel.id) },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Avatar(channel.avatars.maxByOrNull { it.width }?.url, 68.dp, channelId = channel.id)
                Column(Modifier.weight(1f)) {
                    ChannelMetaText(channel.name, channel.badge, emptyList(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    // Same formatting as the channel page: a bare number becomes "524 Tr người đăng ký".
                    val subscribers = channel.subscriberText?.let { raw -> if (raw.toLongOrNull() != null) stringResource(R.string.metadata_subscribers, vibe.tube.feature.watch.compactCount(raw).orEmpty()) else raw }
                    Text(listOfNotNull(channel.handle, subscribers).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                }
            }
            // Two equal, taller buttons filling the card's width.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                val subscribed = subscription?.subscribed == true
                var menu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                val outline = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSecondaryContainer)
                val buttonModifier = Modifier.weight(1f).height(52.dp)
                Box(buttonModifier) {
                    if (subscribed) {
                        OutlinedButton(
                            border = outline,
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            onClick = { menu = true },
                            enabled = !busy,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            VIconImage(notifyIcon(subscription?.notifyLevel), null, Modifier.size(20.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.action_subscribed), style = MaterialTheme.typography.titleSmall)
                            VIconImage(vibe.tube.ui.VIcon.ArrowDropDown, null, Modifier.size(22.dp))
                        }
                        SubscribeMenu(
                            expanded = menu,
                            onDismiss = { menu = false },
                            available = subscription?.notifyParams?.keys.orEmpty(),
                            level = subscription?.notifyLevel,
                            onLevel = onNotify,
                            onUnsubscribe = onSubscribe,
                        )
                    } else {
                        Button(onClick = onSubscribe, enabled = !busy && subscription != null, modifier = Modifier.fillMaxSize()) {
                            Text(stringResource(R.string.action_subscribe), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
                OutlinedButton(
                    border = outline,
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    onClick = { actions.openChannel(channel.id) },
                    modifier = buttonModifier,
                ) { Text(stringResource(R.string.open_channel), style = MaterialTheme.typography.titleSmall) }
            }
            if (videos.isNotEmpty()) {
                Text(stringResource(R.string.latest_from_channel, channel.name), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(videos, key = { it.id }) { video ->
                        Column(Modifier.width(280.dp)) { VideoCard(video, videos, actions = actions, separateMetadata = true) }
                    }
                }
            }
        }
    }
}
