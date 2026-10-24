package vibe.tube.feature.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import vibe.tube.account.AuthState
import vibe.tube.account.SessionManager
import vibe.tube.data.repo.FeedController
import vibe.tube.data.repo.FeedRepository
import vibe.tube.data.settings.SettingsRepository
import vibe.tube.feature.nav.ChannelRoute
import vibe.tube.feature.nav.PlaylistRoute
import javax.inject.Inject

abstract class FeedViewModel(settings: SettingsRepository, session: SessionManager) : ViewModel() {
    abstract val controller: FeedController
    /** "Hide Shorts" now hides the Shorts tab only; feeds are never filtered. */
    val hideShorts: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false)
    val authState: StateFlow<AuthState> = session.authState
    fun refresh() = controller.refresh()
    fun loadMore() = controller.loadMore()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    feeds: FeedRepository,
    settings: SettingsRepository,
    session: SessionManager,
    val events: vibe.tube.feature.common.UiEvents,
) : FeedViewModel(settings, session) {
    override val controller = feeds.home(viewModelScope)
}

@HiltViewModel
class SubscriptionsFeedViewModel @Inject constructor(
    feeds: FeedRepository,
    settings: SettingsRepository,
    session: SessionManager,
) : FeedViewModel(settings, session) {
    override val controller = feeds.subscriptions(viewModelScope)
}

@HiltViewModel
class SubscribedChannelsViewModel @Inject constructor(
    feeds: FeedRepository,
    settings: SettingsRepository,
    session: SessionManager,
) : FeedViewModel(settings, session) {
    override val controller = feeds.subscribedChannels(viewModelScope)
}

@HiltViewModel
class UserPlaylistsViewModel @Inject constructor(
    feeds: FeedRepository,
    settings: SettingsRepository,
    session: SessionManager,
) : FeedViewModel(settings, session) {
    override val controller = feeds.userPlaylists(viewModelScope)
}

@HiltViewModel
class AccountHistoryViewModel @Inject constructor(
    feeds: FeedRepository,
    settings: SettingsRepository,
    session: SessionManager,
) : FeedViewModel(settings, session) {
    override val controller = feeds.history(viewModelScope)
}

@HiltViewModel
class ChannelVideosViewModel @Inject constructor(
    savedState: SavedStateHandle,
    feeds: FeedRepository,
    settings: SettingsRepository,
    session: SessionManager,
) : FeedViewModel(settings, session) {
    val channelId: String = savedState.toRoute<ChannelRoute>().id
    override val controller = feeds.channelVideos(channelId, viewModelScope)
}

@HiltViewModel
class PlaylistFeedViewModel @Inject constructor(
    savedState: SavedStateHandle,
    feeds: FeedRepository,
    settings: SettingsRepository,
    session: SessionManager,
) : FeedViewModel(settings, session) {
    private val route = savedState.toRoute<PlaylistRoute>()
    val playlistId: String = route.id
    val initialTitle: String? = route.title
    override val controller = feeds.playlist(playlistId, viewModelScope)
}
