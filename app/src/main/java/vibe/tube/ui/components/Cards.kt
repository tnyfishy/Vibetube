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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onPlaced
import coil3.request.transformations
import coil3.request.allowHardware
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
import vibe.tube.youtube.model.mixFirstVideoId
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

val LocalWatchProgress = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.State<Map<String, Int>>> { androidx.compose.runtime.mutableStateOf(emptyMap()) }

/** The thumbnail size picked in settings; read when a thumbnail is first composed. */
object ThumbnailPreference {
    @Volatile var quality: vibe.tube.data.settings.ThumbnailQuality = vibe.tube.data.settings.ThumbnailQuality.Highest
}

internal fun thumbnailCandidates(video: VideoItem): List<String> {
    val base = "https://i.ytimg.com/vi/${video.id}"
    val always = "$base/hqdefault.jpg"
    return when (ThumbnailPreference.quality) {
        vibe.tube.data.settings.ThumbnailQuality.Highest -> {
            val maxres = if (video.isLive) null else "$base/maxresdefault.jpg"
            listOfNotNull(maxres, if (video.isShort) "$base/sddefault.jpg" else null, video.thumbnails.best(), video.thumbnails.bestFor(480), always)
        }
        // 480×360: sharp enough for a card, a fraction of the full-size image.
        vibe.tube.data.settings.ThumbnailQuality.Medium -> listOfNotNull(always, video.thumbnails.bestFor(480), video.thumbnails.best())
        // 320×180, the smallest image that still fills a feed card.
        vibe.tube.data.settings.ThumbnailQuality.Low -> listOfNotNull("$base/mqdefault.jpg", video.thumbnails.bestFor(320), always)
    }.distinct()
}

private val WatchBarHeight = 4.dp
private val WatchBarGap = 4.dp
private val WatchBarInset = 10.dp

@Composable
fun VideoThumbnail(video: VideoItem, modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape? = null) {
    val aspect = if (video.isShort) 9f / 16f else Sizing.THUMBNAIL_ASPECT
    Box(
        modifier = modifier
            .aspectRatio(Sizing.THUMBNAIL_ASPECT)
            .clip(shape ?: VibeShape.thumbnail)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (video.isShort) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val ambient = remember(video.id, video.thumbnails) { coil3.request.ImageRequest.Builder(context)
                .data(thumbnailCandidates(video).firstOrNull()).size(160, 90).allowHardware(false).transformations(ShortsAmbient).build() }
            coil3.compose.AsyncImage(
                model = ambient,
                contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.16f)))
        }
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
        val watched = video.watchedPercent ?: LocalWatchProgress.current.value[video.id]
        val hasBar = watched != null && watched > 0
        if (badge != null) {
            OverlayBadge(
                badge,
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(start = Spacing.sm, end = Spacing.sm, top = Spacing.sm, bottom = if (hasBar) Spacing.sm + WatchBarHeight + WatchBarGap else Spacing.sm),
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
                    
                    .padding(start = WatchBarInset, end = WatchBarInset, bottom = WatchBarGap)
                    .height(WatchBarHeight)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.45f)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth((watched / 100f).coerceIn(0.02f, 1f))
                        .background(WordmarkRed),
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
            if (channelId != null && channelId != LocalCurrentChannelId.current) {
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
    uniformTitle: Boolean = false,
    separateMetadata: Boolean = false,
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
                PlayerLaunch.set(thumbBounds.rect, thumbnailCandidates(video).firstOrNull(), video.id)
                actions.play(video, if (queueMode) queue else listOf(video))
            }
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        VideoThumbnail(video, Modifier.fillMaxWidth().onPlaced { thumbBounds.coordinates = it })
        Row(
            Modifier.padding(start = Spacing.xs, top = Spacing.sm, bottom = if (uniformTitle) 0.dp else Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            val channelId = video.channelId
            Avatar(
                video.channelAvatar,
                Sizing.avatarMedium,
                channelId = video.channelId,
                modifier =
                Modifier.then(if (channelId != null && channelId != LocalCurrentChannelId.current) Modifier.clickable(role = Role.Button) { actions.openChannel(channelId) } else Modifier),
            )
            Column(Modifier.weight(1f)) {
                Text(video.title, style = MaterialTheme.typography.titleSmall, minLines = if (uniformTitle) 2 else 1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (separateMetadata) {
                    ChannelMetaText(video.channelName, video.channelBadge, emptyList(), maxLines = 1)
                    ChannelMetaText(null, vibe.tube.youtube.model.ChannelBadge.None, listOf(video.publishedText), viewCount = video.viewCountText, maxLines = 2)
                } else ChannelMetaText(
                    video.channelName, video.channelBadge, listOf(video.publishedText), viewCount = video.viewCountText, maxLines = if (uniformTitle) 1 else 2,
                    // Inline view/badge glyphs make a line taller than a plain or empty one; pin it so uniform cards match.
                    modifier = if (uniformTitle) Modifier.height(with(LocalDensity.current) { (MaterialTheme.typography.bodySmall.fontSize * 1.45f).toDp() }) else Modifier,
                )
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
    animateLaunch: Boolean = true,
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
                if (animateLaunch) PlayerLaunch.set(thumbBounds.rect, thumbnailCandidates(video).firstOrNull(), video.id) else PlayerLaunch.clear()
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
            ChannelMetaText(video.channelName, video.channelBadge, listOf(video.publishedText), viewCount = video.viewCountText)
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
    val thumbBounds = remember { ThumbnailBounds() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .bouncyClickable(VibeShape.card) { openOrPlay(playlist, thumbBounds, actions) }
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        StackedFrame(peek = 6.dp, inset = 14.dp, Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(Sizing.THUMBNAIL_ASPECT)
                    .onPlaced { thumbBounds.coordinates = it }
                    .clip(VibeShape.thumbnail)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                RemoteImage(playlist.thumbnails.best(), null, Modifier.matchParentSize())
                val badge = if (playlist.isMix) "Mix" else playlist.videoCountText
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

private val PlaylistItem.isMix: Boolean get() = id.startsWith("RD")

private fun openOrPlay(playlist: PlaylistItem, bounds: ThumbnailBounds, actions: VideoActions, animateLaunch: Boolean = true) {
    if (!playlist.isMix) {
        actions.openPlaylist(playlist.id, playlist.title)
        return
    }
    if (animateLaunch) PlayerLaunch.set(bounds.rect, image = playlist.thumbnails.best(), videoId = playlist.mixFirstVideoId ?: playlist.id) else PlayerLaunch.clear()
    actions.playMix(playlist)
}

@Composable
fun PlaylistRow(
    playlist: PlaylistItem,
    modifier: Modifier = Modifier,
    actions: VideoActions = LocalVideoActions.current,
    animateLaunch: Boolean = true,
) {
    val description = metaLine(playlist.title, playlist.ownerName, playlist.videoCountText)
    val badge = if (playlist.isMix) "Mix" else playlist.videoCountText
    val thumbBounds = remember { ThumbnailBounds() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .bouncyClickable(VibeShape.card) { openOrPlay(playlist, thumbBounds, actions, animateLaunch) }
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
                    .onPlaced { thumbBounds.coordinates = it }
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
        Avatar(channel.avatars.bestFor(96), Sizing.avatarLarge + 8.dp, channelId = channel.id)
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

@Composable
private fun StackedFrame(peek: androidx.compose.ui.unit.Dp, inset: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val near = androidx.compose.ui.graphics.lerp(cs.surfaceContainerHighest, cs.primary, 0.30f)
    val far = androidx.compose.ui.graphics.lerp(cs.surfaceContainerHigh, cs.primary, 0.14f)
    val gap = cs.surface
    val corner = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
    Box(modifier) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = inset * 2)
                .fillMaxWidth()
                .height(peek * 3 + 14.dp)
                .clip(corner)
                .background(far),
        )
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = peek, start = inset, end = inset)
                .fillMaxWidth()
                .height(peek * 3 + 14.dp)
                .clip(corner)
                .background(gap)
                .padding(top = 1.5.dp)
                .clip(corner)
                .background(near),
        )
        Box(Modifier.padding(top = peek * 2)) { content() }
    }
}
