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
    settingsRepository: SettingsRepository,
    private val session: SessionManager,
    private val actions: AccountActions,
    val events: UiEvents,
    val playerController: PlayerController,
    val engine: PlayerEngine,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.latest.value)
    val authState: StateFlow<AuthState> = session.authState
    val avatarUrl: StateFlow<String?> = session.sessions.map { sessions ->
        session.currentSession()?.let { it.activeIdentity?.avatarUrl ?: it.avatarUrl }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val activeSession = session

    fun watchLater(video: VideoItem) {
        viewModelScope.launch {
            runCatching { actions.addToPlaylist("WL", video.id) }
                .onSuccess { events.post(R.string.msg_added_to_watch_later) }
                .onFailure(events::postError)
        }
    }
}
