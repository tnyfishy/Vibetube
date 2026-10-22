package vibe.tube.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import vibe.tube.R
import vibe.tube.account.AccountSession
import vibe.tube.account.AuthState
import vibe.tube.account.SessionManager
import vibe.tube.data.repo.FeedCache
import vibe.tube.data.repo.LocalHistoryRepository
import vibe.tube.feature.common.UiEvents
import javax.inject.Inject

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val session: SessionManager,
    private val cache: FeedCache,
    private val history: LocalHistoryRepository,
    private val events: UiEvents,
) : ViewModel() {

    val sessions: StateFlow<Map<String, AccountSession>> = session.sessions
    val authState: StateFlow<AuthState> = session.authState
    val activeId: StateFlow<vibe.tube.account.AccountContext?> = session.context

    fun refresh() {
        viewModelScope.launch {
            when (session.verifyActive()) {
                is AuthState.Verified -> events.post(R.string.msg_account_refreshed)
                is AuthState.Expired -> events.post(R.string.msg_session_expired)
                is AuthState.Unverified -> events.post(R.string.msg_account_unverified)
                else -> Unit
            }
        }
    }

    fun switchAccount(id: String) {
        viewModelScope.launch { session.switchAccount(id) }
    }

    fun switchIdentity(key: String) {
        viewModelScope.launch { session.switchIdentity(key) }
    }

    fun logout(id: String) {
        viewModelScope.launch {
            session.logout(id)
            events.post(R.string.msg_signed_out)
        }
    }

    fun logoutAll() {
        viewModelScope.launch {
            session.logoutAll()
            events.post(R.string.msg_signed_out_all)
        }
    }

    fun clearLocalData(id: String) {
        viewModelScope.launch {
            cache.clearAccount(id)
            history.clearAccount(id)
            events.post(R.string.msg_local_data_cleared)
        }
    }
}
