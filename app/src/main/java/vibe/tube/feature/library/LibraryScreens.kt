package vibe.tube.feature.library

import androidx.compose.foundation.layout.Arrangement
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
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val recent by viewModel.recent.collectAsStateWithLifecycle()
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
            item { LibraryCard(VIcon.PlaylistPlay, R.string.library_playlists, onOpenPlaylists, enabled = !isGuest) }
            item { LibraryCard(VIcon.ThumbUp, R.string.library_liked, onOpenLiked, enabled = !isGuest) }
            item { LibraryCard(VIcon.Schedule, R.string.library_watch_later, onOpenWatchLater, enabled = !isGuest) }
            item { LibraryCard(VIcon.History, R.string.library_youtube_history, onOpenHistory, enabled = !isGuest) }
            item { LibraryCard(VIcon.Download, R.string.library_downloads, onOpenDownloads, enabled = true) }
            item { LibraryCard(VIcon.Storage, R.string.library_local_history, onOpenLocalHistory, enabled = true) }
            if (recent.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(R.string.library_continue_watching),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = Spacing.md),
                    )
                }
                items(recent.take(6), key = { it.videoId }, span = { GridItemSpan(maxLineSpan) }) { entry ->
                    LocalHistoryRow(entry, viewModel::asVideo, onRemove = null)
                }
            }
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
fun LocalHistoryScreen(onBack: () -> Unit, viewModel: LibraryViewModel = hiltViewModel()) {
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_local_history)) },
                navigationIcon = { TooltipIconButton(VIcon.ArrowBack, stringResource(R.string.action_back), onBack) },
                actions = {
                    if (recent.isNotEmpty()) {
                        TooltipIconButton(VIcon.DeleteSweep, stringResource(R.string.action_clear_device_history), { confirmClear = true })
                    }
                },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            Banner(
                text = stringResource(R.string.local_history_scope_note),
                icon = VIcon.Info,
                modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
            )
            if (recent.isNotEmpty()) {
                Text(
                    androidx.compose.ui.res.pluralStringResource(R.plurals.video_count, recent.size, recent.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.gutter),
                )
            }
            if (recent.isEmpty()) {
                StateMessage(VIcon.History, stringResource(R.string.local_history_empty_title), stringResource(R.string.local_history_empty_body))
            } else {
                LazyColumn(contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.sm)) {
                    items(recent, key = { it.videoId }) { entry ->
                        LocalHistoryRow(entry, viewModel::asVideo, onRemove = { viewModel.remove(entry.videoId) })
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
