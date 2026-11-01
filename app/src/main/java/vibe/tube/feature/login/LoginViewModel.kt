package vibe.tube.feature.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vibe.tube.account.LoginOutcome
import vibe.tube.account.SessionCookieJar
import vibe.tube.account.SessionManager
import vibe.tube.account.WebSessionCleaner
import vibe.tube.core.Clock
import javax.inject.Inject

sealed interface LoginUiState {
    data object Browsing : LoginUiState
    data object Verifying : LoginUiState
    data class Blocked(val detail: String) : LoginUiState
    data object NotConfirmed : LoginUiState
    data class NetworkFailure(val canRetryVerify: Boolean) : LoginUiState
    data object Done : LoginUiState
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val session: SessionManager,
    private val clock: Clock,
) : ViewModel() {

    private val mutableState = MutableStateFlow<LoginUiState>(LoginUiState.Browsing)
    val state: StateFlow<LoginUiState> = mutableState.asStateFlow()

    private var pendingHeader: String? = null
    private var generation = 0

    fun hasSessionCookies(header: String?): Boolean {
        if (header.isNullOrBlank()) return false
        val names = header.split(';').map { it.substringBefore('=').trim() }
        return "SAPISID" in names || "__Secure-3PAPISID" in names
    }

    fun onCookieHeader(header: String) {
        if (mutableState.value is LoginUiState.Verifying || mutableState.value is LoginUiState.Done) return
        pendingHeader = header
        verify(header)
    }

    fun retryVerify() {
        pendingHeader?.let(::verify)
    }

    fun onBlocked(detail: String) {
        if (mutableState.value is LoginUiState.Verifying || mutableState.value is LoginUiState.Done) return
        mutableState.value = LoginUiState.Blocked(detail)
    }

    fun restart() {
        pendingHeader = null
        generation++
        viewModelScope.launch {
            WebSessionCleaner.clear()
            mutableState.value = LoginUiState.Browsing
        }
    }

    private fun verify(header: String) {
        mutableState.value = LoginUiState.Verifying
        val gen = ++generation
        viewModelScope.launch {
            val cookies = SessionCookieJar.fromWebViewHeader(header, "youtube.com", clock.nowMillis())
            val outcome = session.loginWithCookies(cookies)
            if (gen != generation) return@launch
            when (outcome) {
                is LoginOutcome.Success -> {
                    pendingHeader = null
                    mutableState.value = LoginUiState.Done
                    WebSessionCleaner.clear()
                }
                LoginOutcome.NotLoggedIn -> {
                    pendingHeader = null
                    mutableState.value = LoginUiState.NotConfirmed
                }
                is LoginOutcome.Failed -> mutableState.value = LoginUiState.NetworkFailure(canRetryVerify = true)
            }
        }
    }

    override fun onCleared() {
        pendingHeader = null
    }
}
