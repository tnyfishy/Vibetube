package vibe.tube.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ListItem
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
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = inner.calculateTopPadding() + Spacing.sm,
                bottom = contentPadding.calculateBottomPadding() + Spacing.lg,
                start = Spacing.gutter,
                end = Spacing.gutter,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (isGuest) {
                item {
                    Banner(
                        text = stringResource(R.string.library_guest_banner),
                        icon = VIcon.Person,
                        actionLabel = stringResource(R.string.action_sign_in),
                        onAction = onLogin,
                    )
                }
            }
            item { LibraryEntry(VIcon.PlaylistPlay, R.string.library_playlists, R.string.library_playlists_desc, onOpenPlaylists, enabled = !isGuest) }
            item { LibraryEntry(VIcon.ThumbUp, R.string.library_liked, R.string.library_liked_desc, onOpenLiked, enabled = !isGuest) }
            item { LibraryEntry(VIcon.Schedule, R.string.library_watch_later, R.string.library_watch_later_desc, onOpenWatchLater, enabled = !isGuest) }
            item { LibraryEntry(VIcon.History, R.string.library_youtube_history, R.string.library_youtube_history_desc, onOpenHistory, enabled = !isGuest) }
            item { LibraryEntry(VIcon.Storage, R.string.library_local_history, R.string.library_local_history_desc, onOpenLocalHistory, enabled = true) }
            if (recent.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.library_continue_watching),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.xs),
                    )
                }
                items(recent.take(6), key = { it.videoId }) { entry ->
                    LocalHistoryRow(entry, viewModel::asVideo, onRemove = null)
                }
            }
        }
    }
}

@Composable
private fun LibraryEntry(icon: Int, title: Int, body: Int, onClick: () -> Unit, enabled: Boolean) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        leadingContent = { VIconImage(icon, null) },
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(body)) },
        trailingContent = { VIconImage(VIcon.ArrowForward, null) },
    )
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
                TextButton(onClick = { confirmClear = false; viewModel.clearAll() }) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
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
            header = {
                item(key = "history-note", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Banner(stringResource(R.string.youtube_history_note), VIcon.Info)
                }
            },
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
