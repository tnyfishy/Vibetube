package vibe.tube.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import vibe.tube.R
import vibe.tube.account.AuthState
import vibe.tube.account.SessionManager
import vibe.tube.data.repo.AccountActions
import vibe.tube.data.settings.AppSettings
import vibe.tube.data.settings.SettingsRepository
import vibe.tube.feature.common.UiEvents
import vibe.tube.player.PlayerController
import vibe.tube.player.PlayerEngine
import vibe.tube.youtube.model.VideoItem
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val session: SessionManager,
    private val actions: AccountActions,
    val events: UiEvents,
    val playerController: PlayerController,
    val engine: PlayerEngine,
    private val downloads: vibe.tube.download.DownloadManager,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.latest.value)
    val authState: StateFlow<AuthState> = session.authState
    val avatarUrl: StateFlow<String?> = session.sessions.map { sessions ->
        session.currentSession()?.let { it.activeIdentity?.avatarUrl ?: it.avatarUrl }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val activeSession = session

    fun hideChannel(video: VideoItem) {
        val id = video.channelId ?: return
        val name = video.channelName?.takeIf { it.isNotBlank() } ?: id
        viewModelScope.launch {
            settingsRepository.update { it.copy(hiddenChannels = it.hiddenChannels.filterNot { e -> e.substringBefore('|') == id }.toSet() + "$id|$name") }
            events.post(
                vibe.tube.feature.common.UiMessage(
                    R.string.msg_channel_hidden,
                    listOf(name),
                    R.string.action_undo,
                ) { showChannelAgain(id) },
            )
        }
    }

    private fun showChannelAgain(id: String) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(hiddenChannels = it.hiddenChannels.filterNot { e -> e.substringBefore('|') == id }.toSet()) }
        }
    }

    val downloadJobs: StateFlow<List<vibe.tube.download.DownloadJob>> = downloads.jobs
    fun toggleDownloadPause(id: Long) = downloads.togglePause(id)
    fun cancelDownload(id: Long) {
        downloads.cancel(id)
        events.hideDownloadToast(id)
    }

    fun download(video: VideoItem, choice: vibe.tube.download.DownloadPlanner.Choice) {
        downloads.enqueue(video, choice)
    }

    suspend fun downloadOptions(videoId: String): Result<vibe.tube.download.DownloadPlanner.Options> =
        runCatching { downloads.loadOptions(videoId) }

    fun watchLater(video: VideoItem) {
        viewModelScope.launch {
            runCatching { actions.addToPlaylist("WL", video.id) }
                .onSuccess { events.post(R.string.msg_added_to_watch_later) }
                .onFailure(events::postError)
        }
    }
}
