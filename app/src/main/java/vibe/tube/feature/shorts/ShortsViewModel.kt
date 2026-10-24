package vibe.tube.feature.shorts

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import vibe.tube.account.AuthState
import vibe.tube.account.SessionManager
import vibe.tube.data.repo.AccountActions
import vibe.tube.data.repo.FeedRepository
import vibe.tube.data.settings.SettingsRepository
import vibe.tube.feature.common.UiEvents
import vibe.tube.feature.home.FeedViewModel
import vibe.tube.player.ShortsPlayer
import javax.inject.Inject

@HiltViewModel
class ShortsViewModel @Inject constructor(
    feeds: FeedRepository,
    settings: SettingsRepository,
    private val session: SessionManager,
    private val actions: AccountActions,
    private val events: UiEvents,
    val shorts: ShortsPlayer,
) : FeedViewModel(settings, session) {
    override val controller = feeds.shorts(viewModelScope)

    /** Likes (or un-likes) a short; guests are sent to sign in first. */
    fun like(videoId: String, liked: Boolean, onLogin: () -> Unit) {
        if (session.authState.value !is AuthState.Verified) {
            onLogin()
            return
        }
        viewModelScope.launch { runCatching { actions.like(videoId, liked) }.onFailure(events::postError) }
    }
}
