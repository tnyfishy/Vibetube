package vibe.tube.feature.library

import androidx.compose.foundation.layout.Arrangement
import vibe.tube.ui.components.bouncyClickable
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vibe.tube.R
import vibe.tube.data.db.PlaybackHistoryEntity
import vibe.tube.feature.home.AccountHistoryViewModel
import vibe.tube.feature.home.FeedItemContent
import vibe.tube.feature.home.UserPlaylistsViewModel
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.Banner
import vibe.tube.ui.components.FeedContent
import vibe.tube.ui.components.LocalVideoActions
import vibe.tube.ui.components.MenuEntry
import vibe.tube.ui.components.StateMessage
import vibe.tube.ui.components.TooltipIconButton
import vibe.tube.ui.components.VIconImage
import vibe.tube.ui.components.VideoRow
import vibe.tube.ui.theme.Spacing
import vibe.tube.youtube.model.VideoItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenHistory: () -> Unit,
    onOpenLocalHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenLiked: () -> Unit,
    onOpenWatchLater: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onLogin: () -> Unit,
    isGuest: Boolean,
    contentPadding: PaddingValues,
    historyViewModel: MergedHistoryViewModel = hiltViewModel(),
) {
    val history by historyViewModel.entries.collectAsStateWithLifecycle()
    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_library)) }) },
    ) { inner ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = inner.calculateTopPadding() + Spacing.sm,
                bottom = contentPadding.calculateBottomPadding() + Spacing.lg,
                start = Spacing.gutter,
                end = Spacing.gutter,
            ),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (isGuest) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Banner(
                        text = stringResource(R.string.library_guest_banner),
                        icon = VIcon.Person,
                        actionLabel = stringResource(R.string.action_sign_in),
                        onAction = onLogin,
                    )
                }
            }
            // Device + account history in one strip, right at the top of the tab.
            item(key = "history", span = { GridItemSpan(maxLineSpan) }) {
                HistoryStrip(history, onSeeAll = onOpenLocalHistory)
            }
            item { LibraryCard(VIcon.PlaylistPlay, R.string.library_playlists, onOpenPlaylists, enabled = !isGuest) }
            item { LibraryCard(VIcon.ThumbUp, R.string.library_liked, onOpenLiked, enabled = !isGuest) }
            item { LibraryCard(VIcon.Schedule, R.string.library_watch_later, onOpenWatchLater, enabled = !isGuest) }
            item { LibraryCard(VIcon.Download, R.string.library_downloads, onOpenDownloads, enabled = true) }
        }
    }
}

@Composable
private fun HistoryStrip(entries: List<HistoryEntry>, onSeeAll: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.library_history), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (entries.isNotEmpty()) {
                TextButton(shapes = ButtonDefaults.shapes(), onClick = onSeeAll) { Text(stringResource(R.string.library_see_all)) }
            }
        }
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.local_history_empty_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.sm),
            )
        } else {
            // Bleeds into the screen gutters so the strip scrolls edge to edge.
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier.layout { measurable, constraints ->
                    val extra = Spacing.gutter.roundToPx()
                    val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + 2 * extra, maxWidth = constraints.maxWidth + 2 * extra))
                    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
                },
                contentPadding = PaddingValues(horizontal = Spacing.gutter),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                items(entries.take(HISTORY_STRIP_LIMIT), key = { it.video.id }) { entry -> HistoryCard(entry.video) }
            }
        }
    }
}

private const val HISTORY_STRIP_LIMIT = 20

@Composable
private fun HistoryCard(video: VideoItem) {
    val actions = LocalVideoActions.current
    val bounds = remember { vibe.tube.ui.components.ThumbnailBounds() }
    Column(
        Modifier
            .width(176.dp)
            .bouncyClickable(vibe.tube.ui.theme.VibeShape.card) { vibe.tube.ui.components.PlayerLaunch.set(bounds.rect); actions.play(video, listOf(video)) },
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        vibe.tube.ui.components.VideoThumbnail(video, Modifier.fillMaxWidth().onPlaced { bounds.coordinates = it })
        // Explicit line boxes (no trimming): with the app's own fonts the default trimmed first line cut the top off the channel name.
        val roomy = androidx.compose.ui.text.style.LineHeightStyle(
            androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
            androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
        )
        Text(
            video.title,
            style = MaterialTheme.typography.labelLarge.copy(lineHeight = 21.sp, lineHeightStyle = roomy),
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        video.channelName?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp, lineHeightStyle = roomy),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun LibraryCard(icon: Int, title: Int, onClick: () -> Unit, enabled: Boolean) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            VIconImage(icon, null)
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                // Always two lines tall, so a short label ("Downloaded") and a long one ("History on this device")
                // give cards of the same height.
                minLines = 2,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun LocalHistoryRow(entry: PlaybackHistoryEntity, asVideo: (PlaybackHistoryEntity) -> VideoItem, onRemove: (() -> Unit)?) {
    val video = asVideo(entry)
    VideoRow(
        video = video,
        queue = listOf(video),
        extraMenu = if (onRemove != null) {
            listOf(MenuEntry(stringResource(R.string.action_remove_from_device_history), VIcon.Delete, onRemove))
        } else emptyList(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalHistoryScreen(onBack: () -> Unit, viewModel: MergedHistoryViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(listState, entries.size) {
        androidx.compose.runtime.snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 4
        }.collect { if (it) viewModel.loadMore() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_history)) },
                navigationIcon = { TooltipIconButton(VIcon.ArrowBack, stringResource(R.string.action_back), onBack) },
                actions = {
                    if (entries.any { it.onDevice }) {
                        TooltipIconButton(VIcon.DeleteSweep, stringResource(R.string.action_clear_device_history), { confirmClear = true })
                    }
                },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            if (entries.isEmpty()) {
                StateMessage(VIcon.History, stringResource(R.string.local_history_empty_title), stringResource(R.string.local_history_empty_body))
            } else {
                Text(
                    androidx.compose.ui.res.pluralStringResource(R.plurals.video_count, entries.size, entries.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
                )
                LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.sm)) {
                    items(entries, key = { it.video.id }) { entry ->
                        VideoRow(
                            video = entry.video,
                            queue = listOf(entry.video),
                            extraMenu = if (entry.onDevice) {
                                listOf(MenuEntry(stringResource(R.string.action_remove_from_device_history), VIcon.Delete, { viewModel.remove(entry.video.id) }))
                            } else emptyList(),
                        )
                    }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.dialog_clear_device_history_title)) },
            text = { Text(stringResource(R.string.dialog_clear_device_history_body)) },
            confirmButton = {
                TextButton(shapes = ButtonDefaults.shapes(), onClick = { confirmClear = false; viewModel.clearAll() }) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YoutubeHistoryScreen(onBack: () -> Unit, onLogin: () -> Unit, viewModel: AccountHistoryViewModel = hiltViewModel()) {
    val state by viewModel.controller.state.collectAsStateWithLifecycle()
    val hideShorts by viewModel.hideShorts.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_youtube_history)) },
                navigationIcon = { TooltipIconButton(VIcon.ArrowBack, stringResource(R.string.action_back), onBack) },
            )
        },
    ) { inner ->
        FeedContent(
            state = state,
            hideShorts = hideShorts,
            onRefresh = viewModel::refresh,
            onLoadMore = viewModel::loadMore,
            onLogin = onLogin,
            contentPadding = inner,
            singleColumn = true,
            emptyTitle = stringResource(R.string.youtube_history_empty_title),
            emptyBody = stringResource(R.string.youtube_history_empty_body),
        ) { item, queue ->
            if (item is VideoItem) VideoRow(item, queue) else FeedItemContent(item, queue)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    onBack: () -> Unit,
    onLogin: () -> Unit,
    viewModel: UserPlaylistsViewModel = hiltViewModel(),
    createViewModel: PlaylistManageViewModel = hiltViewModel(),
) {
    val state by viewModel.controller.state.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_playlists)) },
                navigationIcon = { TooltipIconButton(VIcon.ArrowBack, stringResource(R.string.action_back), onBack) },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { VIconImage(VIcon.Add, null) },
                text = { Text(stringResource(R.string.playlist_create)) },
            )
        },
    ) { inner ->
        FeedContent(
            state = state,
            hideShorts = false,
            onRefresh = viewModel::refresh,
            onLoadMore = viewModel::loadMore,
            onLogin = onLogin,
            contentPadding = inner,
            emptyTitle = stringResource(R.string.playlists_empty_title),
            emptyBody = stringResource(R.string.playlists_empty_body),
        ) { item, queue -> FeedItemContent(item, queue) }
    }
    if (creating) {
        CreatePlaylistDialog(
            onDismiss = { creating = false },
            onCreate = { title, privacy ->
                creating = false
                createViewModel.create(title, privacy) { viewModel.refresh() }
            },
        )
    }
}
