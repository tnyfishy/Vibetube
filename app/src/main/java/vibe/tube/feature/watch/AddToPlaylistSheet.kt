package vibe.tube.feature.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vibe.tube.R
import vibe.tube.data.repo.AccountActions
import vibe.tube.data.repo.ToggleResult
import vibe.tube.data.repo.optimisticToggle
import vibe.tube.feature.common.UiEvents
import vibe.tube.feature.library.CreatePlaylistDialog
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.CenterLoading
import vibe.tube.ui.components.StateMessage
import vibe.tube.ui.components.VIconImage
import vibe.tube.ui.theme.Spacing
import vibe.tube.youtube.model.AddToPlaylistOption
import vibe.tube.youtube.model.PlaylistPrivacy
import vibe.tube.youtube.model.VideoItem
import javax.inject.Inject

data class AddToPlaylistState(
    val loading: Boolean = true,
    val error: Throwable? = null,
    val options: List<AddToPlaylistOption> = emptyList(),
    val busy: Set<String> = emptySet(),
)

@HiltViewModel
class AddToPlaylistViewModel @Inject constructor(
    private val actions: AccountActions,
    private val events: UiEvents,
) : ViewModel() {

    private val mutable = MutableStateFlow(AddToPlaylistState())
    val state: StateFlow<AddToPlaylistState> = mutable.asStateFlow()
    private var videoId: String? = null

    fun load(id: String) {
        if (videoId == id && mutable.value.options.isNotEmpty()) return
        videoId = id
        mutable.value = AddToPlaylistState()
        viewModelScope.launch {
            runCatching { actions.playlistsForVideo(id) }
                .onSuccess { list -> mutable.value = AddToPlaylistState(loading = false, options = list) }
                .onFailure { e -> mutable.value = AddToPlaylistState(loading = false, error = e) }
        }
    }

    fun toggle(option: AddToPlaylistOption) {
        val id = videoId ?: return
        if (option.playlistId in mutable.value.busy) return
        viewModelScope.launch {
            mutable.update { it.copy(busy = it.busy + option.playlistId) }
            val result = optimisticToggle(
                previous = option.containsVideo,
                desired = !option.containsVideo,
                apply = { v -> mutable.update { s -> s.copy(options = s.options.map { if (it.playlistId == option.playlistId) it.copy(containsVideo = v) else it }) } },
                request = { v ->
                    if (v) actions.addToPlaylist(option.playlistId, id) else actions.removeFromPlaylist(option.playlistId, id, null)
                },
            )
            mutable.update { it.copy(busy = it.busy - option.playlistId) }
            when (result) {
                is ToggleResult.Confirmed -> events.post(if (result.value) R.string.msg_added_to_playlist else R.string.msg_removed_from_playlist)
                is ToggleResult.RolledBack -> events.postError(result.error)
            }
        }
    }

    fun createAndAdd(title: String, privacy: PlaylistPrivacy) {
        val id = videoId ?: return
        viewModelScope.launch {
            runCatching { actions.createPlaylist(title.trim(), privacy, id) }
                .onSuccess { playlistId ->
                    events.post(R.string.msg_playlist_created_with_video)
                    mutable.update { it.copy(options = listOf(AddToPlaylistOption(playlistId, title.trim(), true, privacy.wire)) + it.options) }
                }
                .onFailure(events::postError)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(video: VideoItem, onDismiss: () -> Unit, onLogin: () -> Unit, viewModel: AddToPlaylistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(video.id) { viewModel.load(video.id) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = Spacing.lg)) {
            Text(
                stringResource(R.string.action_save_to_playlist),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            )
            Text(
                video.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
            when {
                state.loading -> CenterLoading(Modifier.padding(Spacing.xl))
                state.error != null -> {
                    val loginProblem = state.error.isLoginProblem()
                    StateMessage(
                        icon = if (loginProblem) VIcon.Lock else VIcon.Error,
                        title = stringResource(if (loginProblem) R.string.msg_login_required else R.string.msg_network_error),
                        body = null,
                        primaryLabel = stringResource(if (loginProblem) R.string.action_sign_in else R.string.action_retry),
                        onPrimary = { if (loginProblem) { onDismiss(); onLogin() } else viewModel.load(video.id) },
                    )
                }
                else -> {
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(state.options, key = { it.playlistId }) { option ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = option.containsVideo,
                                        enabled = option.playlistId !in state.busy,
                                        role = Role.Checkbox,
                                        onValueChange = { viewModel.toggle(option) },
                                    )
                                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                            ) {
                                Checkbox(checked = option.containsVideo, onCheckedChange = null)
                                Text(option.title, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    OutlinedButton(
                       shapes = ButtonDefaults.shapes(),
                        onClick = { creating = true },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    ) {
                        VIconImage(VIcon.Add, null)
                        Text(stringResource(R.string.playlist_create), Modifier.padding(start = Spacing.sm))
                    }
                }
            }
        }
    }
    if (creating) {
        CreatePlaylistDialog(onDismiss = { creating = false }, onCreate = { t, p -> creating = false; viewModel.createAndAdd(t, p) })
    }
}
