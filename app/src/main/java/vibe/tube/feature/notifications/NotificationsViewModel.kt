package vibe.tube.feature.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vibe.tube.account.AuthState
import vibe.tube.account.SessionManager
import vibe.tube.data.repo.AccountActions
import vibe.tube.youtube.model.NotificationItem
import javax.inject.Inject

data class NotificationsUiState(
    val items: List<NotificationItem> = emptyList(),
    val continuation: String? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: Throwable? = null,
)

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val actions: AccountActions,
    session: SessionManager,
) : ViewModel() {
    val authState: StateFlow<AuthState> = session.authState

    private val mutable = MutableStateFlow(NotificationsUiState())
    val state: StateFlow<NotificationsUiState> = mutable.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        mutable.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { actions.notifications(null) }
                .onSuccess { page -> mutable.value = NotificationsUiState(items = page.items, continuation = page.continuation, loading = false) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    mutable.update { it.copy(loading = false, error = e) }
                }
        }
    }

    fun loadMore() {
        val s = mutable.value
        val token = s.continuation ?: return
        if (s.loading || s.loadingMore) return
        mutable.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            runCatching { actions.notifications(token) }
                .onSuccess { page ->
                    mutable.update { cur ->
                        val known = cur.items.mapTo(HashSet()) { it.id }
                        cur.copy(items = cur.items + page.items.filter { it.id !in known }, continuation = page.continuation, loadingMore = false)
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    mutable.update { it.copy(loadingMore = false, continuation = null) }
                }
        }
    }
}

/** The number on the bell in the Home header. Zero (and hidden) for guests or when YouTube cannot be reached. */
@HiltViewModel
class NotificationBadgeViewModel @Inject constructor(
    private val actions: AccountActions,
    session: SessionManager,
) : ViewModel() {
    val authState: StateFlow<AuthState> = session.authState

    private val mutable = MutableStateFlow(0)
    val unseen: StateFlow<Int> = mutable.asStateFlow()

    fun refresh() {
        if (authState.value !is AuthState.Verified) {
            mutable.value = 0
            return
        }
        viewModelScope.launch {
            runCatching { actions.unseenNotificationCount() }
                .onSuccess { mutable.value = it }
                .onFailure { e -> if (e is CancellationException) throw e }
        }
    }

    /** Opening the list is what marks them seen. */
    fun clear() {
        mutable.value = 0
    }
}
