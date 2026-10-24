package vibe.tube.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vibe.tube.R
import vibe.tube.ui.VIcon
import vibe.tube.ui.theme.MediaOverlayColors
import vibe.tube.ui.theme.Sizing
import vibe.tube.ui.theme.Spacing
import vibe.tube.ui.theme.VibeShape
import vibe.tube.youtube.model.ChannelItem
import vibe.tube.youtube.model.PlaylistItem
import vibe.tube.youtube.model.VideoAvailability
import vibe.tube.youtube.model.VideoItem
import vibe.tube.youtube.model.best
import vibe.tube.youtube.model.bestFor

@Composable
private fun OverlayBadge(text: String, modifier: Modifier = Modifier, container: Color = MediaOverlayColors.scrim) {
    Text(
        text = text,
        modifier = modifier
            .clip(VibeShape.badge)
            .background(container)
            .padding(horizontal = Spacing.xs + 2.dp, vertical = Spacing.xxs),
        style = MaterialTheme.typography.labelMedium,
        color = MediaOverlayColors.onScrim,
        maxLines = 1,
    )
}

/** Watched percent per video id from this device's own history, for videos YouTube sent no progress for (guests). */
val LocalWatchProgress = androidx.compose.runtime.compositionLocalOf<Map<String, Int>> { emptyMap() }

/** Highest-resolution thumbnail first (maxres when it exists), then what the API returned. */
internal fun thumbnailCandidates(video: VideoItem): List<String> {
    val given = video.thumbnails.best()
    val maxres = if (video.isShort || video.isLive) null else "https://i.ytimg.com/vi/${video.id}/maxresdefault.jpg"
    return listOfNotNull(maxres, given, video.thumbnails.bestFor(480)).distinct()
}

@Composable
fun VideoThumbnail(video: VideoItem, modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape? = null) {
    val aspect = if (video.isShort) 9f / 16f else Sizing.THUMBNAIL_ASPECT
    Box(
        modifier = modifier
            .aspectRatio(Sizing.THUMBNAIL_ASPECT)
            .clip(shape ?: VibeShape.thumbnail)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        // Note: `Modifier.align(..).then(matchParentSize())` lets `align` win and the image then
        // renders at its intrinsic size (the "small picture in a grey box" bug), so size it explicitly.
        RemoteImage(
            urls = remember(video.id, video.isShort, video.isLive) { thumbnailCandidates(video) },
            contentDescription = null,
            modifier = if (video.isShort) {
                Modifier.align(Alignment.Center).fillMaxHeight().aspectRatio(aspect)
            } else {
                Modifier.fillMaxSize()
            },
        )
        val badge = when {
            video.isLive -> stringResource(R.string.badge_live)
            video.availability == VideoAvailability.Private -> stringResource(R.string.badge_private)
            video.availability == VideoAvailability.Deleted -> stringResource(R.string.badge_deleted)
            else -> video.durationText
        }
        // Local (guest) watch progress fills in where YouTube did not send any.
        val watched = video.watchedPercent ?: LocalWatchProgress.current[video.id]
        val hasBar = watched != null && watched > 0
        if (badge != null) {
            OverlayBadge(
                badge,
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(start = Spacing.sm, end = Spacing.sm, top = Spacing.sm, bottom = if (hasBar) Spacing.sm + 10.dp else Spacing.sm),
                container = if (video.isLive) MaterialTheme.colorScheme.error else MediaOverlayColors.scrim,
            )
        }
        if (video.membersOnly) {
            OverlayBadge(
                stringResource(R.string.badge_members),
                Modifier
                    .align(Alignment.TopStart)
                    .padding(Spacing.sm),
                container = androidx.compose.ui.graphics.Color(0xFF1B7F4B).copy(alpha = 0.92f),
            )
        }
        if (watched != null && watched > 0) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp)
                    .height(5.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.45f)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth((watched / 100f).coerceIn(0.02f, 1f))
                        .background(MaterialTheme.colorScheme.error),
                )
            }
        }
    }
}

private fun metaLine(vararg parts: String?): String = parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")

@Composable
fun VideoMenu(
    video: VideoItem,
    actions: VideoActions,
    extra: List<MenuEntry>,
    modifier: Modifier = Modifier,
    menuState: androidx.compose.runtime.MutableState<Boolean> = remember { mutableStateOf(false) },
) {
    var open by menuState
    Box(modifier) {
        TooltipIconButton(VIcon.MoreVert, stringResource(R.string.action_more), { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_save_to_playlist)) },
                leadingIcon = { VIconImage(VIcon.PlaylistAdd, null) },
                onClick = { open = false; actions.save(video) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_watch_later)) },
                leadingIcon = { VIconImage(VIcon.Schedule, null) },
                onClick = { open = false; actions.watchLater(video) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_share)) },
                leadingIcon = { VIconImage(VIcon.OpenInNew, null) },
                onClick = { open = false; actions.share(video) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_download)) },
                leadingIcon = { VIconImage(VIcon.Download, null) },
                onClick = { open = false; actions.download(video) },
            )
            val channelId = video.channelId
            if (channelId != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_open_channel)) },
                    leadingIcon = { VIconImage(VIcon.Person, null) },
                    onClick = { open = false; actions.openChannel(channelId) },
                )
            }
            if (channelId != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_hide_channel)) },
                    leadingIcon = { VIconImage(VIcon.VisibilityOff, null) },
                    onClick = { open = false; actions.hideChannel(video) },
                )
            }
            extra.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    leadingIcon = { VIconImage(entry.icon, null) },
                    onClick = { open = false; entry.onClick() },
                )
            }
        }
    }
}

@Composable
fun VideoCard(
    video: VideoItem,
    queue: List<VideoItem>,
    modifier: Modifier = Modifier,
    actions: VideoActions = LocalVideoActions.current,
    extraMenu: List<MenuEntry> = emptyList(),
) {
    val queueMode = LocalQueueMode.current
    val description = metaLine(video.title, video.channelName, video.viewCountText, video.publishedText, video.durationText)
    val menuOpen = remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val thumbBounds = remember { ThumbnailBounds() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .bouncyClickable(
                VibeShape.card,
                onLongClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    menuOpen.value = true
                },
            ) {
                PlayerLaunch.set(thumbBounds.rect)
                actions.play(video, if (queueMode) queue else listOf(video))
            }
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        VideoThumbnail(video, Modifier.fillMaxWidth().onPlaced { thumbBounds.coordinates = it })
        Row(
            Modifier.padding(start = Spacing.xs, top = Spacing.sm, bottom = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            val channelId = video.channelId
            Avatar(
                video.channelAvatar,
                Sizing.avatarMedium,
                Modifier.then(if (channelId != null) Modifier.clickable(role = Role.Button) { actions.openChannel(channelId) } else Modifier),
            )
            Column(Modifier.weight(1f)) {
                Text(video.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                ChannelMetaText(video.channelName, video.channelBadge, listOf(video.viewCountText, video.publishedText))
            }
            VideoMenu(video, actions, extraMenu, menuState = menuOpen)
        }
    }
}

@Composable
fun VideoRow(
    video: VideoItem,
    queue: List<VideoItem>,
    modifier: Modifier = Modifier,
    actions: VideoActions = LocalVideoActions.current,
    extraMenu: List<MenuEntry> = emptyList(),
    leading: @Composable (() -> Unit)? = null,
    thumbnailShape: androidx.compose.ui.graphics.Shape? = null,
) {
    val queueMode = LocalQueueMode.current
    val unavailable = video.availability != VideoAvailability.Available
    val description = metaLine(video.title, video.channelName, video.viewCountText, video.publishedText, video.durationText)
    val menuOpen = remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val thumbBounds = remember { ThumbnailBounds() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .bouncyClickable(
                VibeShape.card,
                enabled = !unavailable,
                onLongClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    menuOpen.value = true
                },
            ) {
                PlayerLaunch.set(thumbBounds.rect)
                actions.play(video, if (queueMode) queue else listOf(video))
            }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        leading?.invoke()
        VideoThumbnail(video, Modifier.width(Sizing.listThumbnailWidth).onPlaced { thumbBounds.coordinates = it }, thumbnailShape)
        Column(Modifier.weight(1f)) {
            Text(
                video.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = if (unavailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            ChannelMetaText(video.channelName, video.channelBadge, listOf(video.viewCountText, video.publishedText))
        }
        VideoMenu(video, actions, extraMenu, menuState = menuOpen)
    }
}

@Composable
fun PlaylistCard(
    playlist: PlaylistItem,
    modifier: Modifier = Modifier,
    actions: VideoActions = LocalVideoActions.current,
) {
    val description = metaLine(playlist.title, playlist.ownerName, playlist.videoCountText)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .bouncyClickable(VibeShape.card) { actions.openPlaylist(playlist.id, playlist.title) }
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        StackedFrame(peek = 6.dp, inset = 14.dp, Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(Sizing.THUMBNAIL_ASPECT)
                    .clip(VibeShape.thumbnail)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                RemoteImage(playlist.thumbnails.best(), null, Modifier.matchParentSize())
                val badge = if (playlist.id.startsWith("RD")) "Mix" else playlist.videoCountText
                badge?.let { OverlayBadge(it, Modifier.align(Alignment.BottomEnd).padding(Spacing.sm)) }
            }
        }
        Column(Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.sm)) {
            Text(playlist.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                metaLine(playlist.ownerName, playlist.updatedText),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Compact playlist entry (same footprint as [VideoRow]); Mix playlists carry a "Mix" badge. */
@Composable
fun PlaylistRow(
    playlist: PlaylistItem,
    modifier: Modifier = Modifier,
    actions: VideoActions = LocalVideoActions.current,
) {
    val description = metaLine(playlist.title, playlist.ownerName, playlist.videoCountText)
    val badge = if (playlist.id.startsWith("RD")) "Mix" else playlist.videoCountText
    Row(
        modifier = modifier
            .fillMaxWidth()
            .bouncyClickable(VibeShape.card) { actions.openPlaylist(playlist.id, playlist.title) }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        StackedFrame(peek = 4.dp, inset = 9.dp, Modifier.width(Sizing.listThumbnailWidth)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(Sizing.THUMBNAIL_ASPECT)
                    .clip(VibeShape.thumbnail)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                RemoteImage(playlist.thumbnails.best(), null, Modifier.matchParentSize())
                badge?.let { OverlayBadge(it, Modifier.align(Alignment.BottomEnd).padding(Spacing.sm)) }
            }
        }
        Column(Modifier.weight(1f).padding(top = 8.dp)) {
            Text(playlist.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(
                metaLine(playlist.ownerName, playlist.updatedText),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ChannelRow(
    channel: ChannelItem,
    modifier: Modifier = Modifier,
    actions: VideoActions = LocalVideoActions.current,
    trailing: @Composable (() -> Unit)? = null,
) {
    val description = metaLine(channel.name, channel.handle, channel.subscriberText)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .bouncyClickable(VibeShape.card) { actions.openChannel(channel.id) }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(vertical = Spacing.sm, horizontal = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(channel.avatars.bestFor(96), Sizing.avatarLarge + 8.dp)
        Column(Modifier.weight(1f)) {
            ChannelMetaText(
                channel.name, channel.badge, emptyList(),
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
            )
            Text(
                metaLine(channel.handle, channel.subscriberText),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
}

/**
 * Draws two shallow "cards" peeking out above [content], the usual way of saying "this is a playlist / mix, not a
 * single video". [peek] is the visible height of each layer, [inset] how much narrower the first one is per side.
 */
@Composable
private fun StackedFrame(peek: androidx.compose.ui.unit.Dp, inset: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val back = MaterialTheme.colorScheme.secondaryContainer
    val backFar = MaterialTheme.colorScheme.surfaceContainerHighest
    val gap = MaterialTheme.colorScheme.surface
    Box(modifier) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = inset * 2)
                .fillMaxWidth()
                .height(peek * 3)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(backFar),
        )
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = peek, start = inset, end = inset)
                .fillMaxWidth()
                .height(peek * 3)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(gap)
                .padding(top = 1.5.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(back),
        )
        Box(Modifier.padding(top = peek * 2)) { content() }
    }
}
