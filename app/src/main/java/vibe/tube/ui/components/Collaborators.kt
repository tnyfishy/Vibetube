package vibe.tube.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import vibe.tube.R
import vibe.tube.youtube.model.ChannelRef
import vibe.tube.ui.theme.Spacing

/** "A and B", or "A and 3 more", for a collaboration's channels; [label] picks what names each one. */
@Composable
fun collaboratorsTitle(channels: List<ChannelRef>, label: (ChannelRef) -> String = { it.name }): String = when {
    channels.isEmpty() -> ""
    channels.size == 1 -> label(channels[0])
    channels.size == 2 -> stringResource(R.string.channel_collab_two, label(channels[0]), label(channels[1]))
    else -> stringResource(R.string.channel_collab_more, label(channels[0]), channels.size - 1)
}

/** The first two avatars of a collaboration, the second tucked behind the first as YouTube shows them. */
@Composable
fun CollaboratorAvatars(channels: List<ChannelRef>, size: Dp, ring: Color, modifier: Modifier = Modifier) {
    val shown = channels.take(2)
    val overlap = size / 3
    Box(modifier.size(width = size + (size - overlap) * (shown.size - 1), height = size)) {
        shown.asReversed().forEachIndexed { reversedIndex, channel ->
            val index = shown.lastIndex - reversedIndex
            Avatar(
                channel.avatar, size,
                Modifier.offset(x = (size - overlap) * index).border(Dp(1.5f), ring, CircleShape),
                channelId = channel.id,
            )
        }
    }
}

/** Every channel of a collaboration, each opening its own page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollaboratorsSheet(channels: List<ChannelRef>, onOpenChannel: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.channel_collaborators), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(Spacing.lg))
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            items(channels.size) { i ->
                val channel = channels[i]
                Row(
                    Modifier.fillMaxWidth()
                        .bouncyClickable(MaterialTheme.shapes.large, enabled = channel.id != null) {
                            channel.id?.let { id -> onDismiss(); onOpenChannel(id) }
                        }
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Avatar(channel.avatar, Dp(48f), channelId = channel.id)
                    Column(Modifier.weight(1f)) {
                        Text(channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val subtitle = listOfNotNull(channel.handle, channel.subscriberText).joinToString(" • ")
                        if (subtitle.isNotEmpty()) {
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
