package vibe.tube.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.SingletonImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import vibe.tube.R
import vibe.tube.account.SessionManager
import vibe.tube.data.repo.FeedCache
import vibe.tube.data.repo.FeedController
import vibe.tube.data.repo.LocalHistoryRepository
import vibe.tube.data.settings.AppSettings
import vibe.tube.data.settings.SettingsRepository
import vibe.tube.feature.common.UiEvents
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val cache: FeedCache,
    private val history: LocalHistoryRepository,
    private val session: SessionManager,
    private val events: UiEvents,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, repository.latest.value)

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    fun clearCache() {
        viewModelScope.launch {
            cache.clearAll()
            val loader = SingletonImageLoader.get(context)
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
            events.post(R.string.msg_cache_cleared)
        }
    }

    fun clearPlaybackHistory() {
        viewModelScope.launch {
            history.clearPlayback(session.currentContext()?.scopeKey ?: FeedController.GUEST_SCOPE)
            events.post(R.string.msg_device_history_cleared)
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            history.clearSearches(session.currentContext()?.scopeKey ?: FeedController.GUEST_SCOPE)
            events.post(R.string.msg_search_history_cleared)
        }
    }
}
