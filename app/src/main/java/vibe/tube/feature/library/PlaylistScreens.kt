package vibe.tube.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vibe.tube.R
import vibe.tube.account.SessionManager
import vibe.tube.feature.home.PlaylistFeedViewModel
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.FeedContent
import vibe.tube.ui.components.LocalVideoActions
import vibe.tube.ui.components.MenuEntry
import vibe.tube.ui.components.TooltipIconButton
import vibe.tube.ui.components.VIconImage
import vibe.tube.ui.components.VideoRow
import vibe.tube.ui.theme.Spacing
import vibe.tube.youtube.model.PlaylistPrivacy
import vibe.tube.youtube.model.VideoItem

@Composable
fun CreatePlaylistDialog(onDismiss: () -> Unit, onCreate: (String, PlaylistPrivacy) -> Unit) {
    var title by remember { mutableStateOf("") }
    var privacy by remember { mutableStateOf(PlaylistPrivacy.Private) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_create)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.playlist_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(Modifier.selectableGroup()) {
                    PlaylistPrivacy.entries.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = privacy == option, role = Role.RadioButton, onClick = { privacy = option })
                                .padding(vertical = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = privacy == option, onClick = null)
                            Text(
                                stringResource(
                                    when (option) {
                                        PlaylistPrivacy.Private -> R.string.privacy_private
                                        PlaylistPrivacy.Unlisted -> R.string.privacy_unlisted
                                        PlaylistPrivacy.Public -> R.string.privacy_public
                                    },
                                ),
                                modifier = Modifier.padding(start = Spacing.md),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(shapes = ButtonDefaults.shapes(), onClick = { onCreate(title, privacy) }, enabled = title.isNotBlank()) { Text(stringResource(R.string.action_create)) }
        },
        dismissButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    onBack: () -> Unit,
    onLogin: () -> Unit,
    session: SessionManager,
    viewModel: PlaylistFeedViewModel = hiltViewModel(),
    manage: PlaylistManageViewModel = hiltViewModel(),
) {
    val state by viewModel.controller.state.collectAsStateWithLifecycle()
    val hideShorts by viewModel.hideShorts.collectAsStateWithLifecycle()
    val actions = LocalVideoActions.current
    val profile = session.currentSession()
    val id = viewModel.playlistId
    val systemList = id == "LL" || id == "WL"
    val ownerNames = listOfNotNull(profile?.name, profile?.handle, profile?.activeIdentity?.name, profile?.activeIdentity?.handle)
    val owned = systemList || (state.subtitle != null && ownerNames.any { it.equals(state.subtitle, ignoreCase = true) })
    val title = state.title ?: viewModel.initialTitle
        ?: stringResource(when (id) { "LL" -> R.string.library_liked; "WL" -> R.string.library_watch_later; else -> R.string.playlist_default_title })
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val canEditMeta = owned && !systemList

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TooltipIconButton(VIcon.ArrowBack, stringResource(R.string.action_back), onBack) },
                actions = {
                    if (canEditMeta) {
                        androidx.compose.foundation.layout.Box {
                            TooltipIconButton(VIcon.MoreVert, stringResource(R.string.action_more), { menuOpen = true })
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_rename)) },
                                    leadingIcon = { VIconImage(VIcon.Edit, null) },
                                    onClick = { menuOpen = false; renaming = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_delete_playlist)) },
                                    leadingIcon = { VIconImage(VIcon.Delete, null) },
                                    onClick = { menuOpen = false; confirmDelete = true },
                                )
                            }
                        }
                    }
                },
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
            singleColumn = true,
            emptyTitle = stringResource(R.string.playlist_empty_title),
            emptyBody = stringResource(R.string.playlist_empty_body),
            header = {
                item(key = "playlist-header", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(title, style = MaterialTheme.typography.headlineSmall)
                        val meta = listOfNotNull(state.subtitle, state.totalText).joinToString(" · ")
                        if (meta.isNotBlank()) {
                            Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Button(
                               shapes = ButtonDefaults.shapes(),
                                onClick = { actions.playPlaylist(id, shuffle = false) },
                                enabled = state.items.isNotEmpty(),
                            ) {
                                VIconImage(VIcon.PlayArrowFilled, null)
                                Text(stringResource(R.string.action_play_all), Modifier.padding(start = Spacing.sm))
                            }
                            FilledTonalButton(
                               shapes = ButtonDefaults.shapes(),
                                onClick = { actions.playPlaylist(id, shuffle = true) },
                                enabled = state.items.isNotEmpty(),
                            ) {
                                VIconImage(VIcon.Shuffle, null)
                                Text(stringResource(R.string.action_shuffle), Modifier.padding(start = Spacing.sm))
                            }
                        }
                    }
                }
            },
        ) { item, queue ->
            if (item is VideoItem) {
                val extra = if (owned) {
                    listOf(
                        MenuEntry(
                            stringResource(if (id == "LL") R.string.action_unlike else R.string.action_remove_from_playlist),
                            VIcon.Delete,
                        ) { manage.removeVideo(id, item, onDone = viewModel::refresh) },
                    )
                } else emptyList()
                VideoRow(item, queue, extraMenu = extra)
            }
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.action_rename)) },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.playlist_name)) })
            },
            confirmButton = {
                TextButton(
                    shapes = ButtonDefaults.shapes(),
                    enabled = name.isNotBlank(),
                    onClick = { renaming = false; manage.rename(id, name, null, onDone = viewModel::refresh) },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = { renaming = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.dialog_delete_playlist_title)) },
            text = { Text(stringResource(R.string.dialog_delete_playlist_body, title)) },
            confirmButton = {
                TextButton(shapes = ButtonDefaults.shapes(), onClick = { confirmDelete = false; manage.delete(id, onDone = onBack) }) {
                    Text(stringResource(R.string.action_delete_playlist))
                }
            },
            dismissButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
