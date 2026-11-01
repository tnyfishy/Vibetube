package vibe.tube.feature.channel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vibe.tube.R
import vibe.tube.account.AuthState
import vibe.tube.account.SessionManager
import vibe.tube.data.repo.AccountActions
import vibe.tube.data.repo.ChannelSubscription
import vibe.tube.data.repo.ChannelSubscriptionStore
import vibe.tube.data.repo.FeedController
import vibe.tube.data.repo.ToggleResult
import vibe.tube.data.repo.optimisticToggle
import vibe.tube.data.settings.SettingsRepository
import vibe.tube.feature.common.UiEvents
import vibe.tube.feature.nav.ChannelRoute
import vibe.tube.youtube.YouTubeProvider
import vibe.tube.youtube.model.ChannelDetail
import javax.inject.Inject

data class ChannelUiState(
    val detail: ChannelDetail? = null,
    val subscribed: Boolean? = null,
    val notifyLevel: vibe.tube.youtube.model.NotifyLevel? = null,
    val busy: Boolean = false,
    val error: Throwable? = null,
    val aboutLoading: Boolean = false,
    val aboutError: Boolean = false,
)

@HiltViewModel
class ChannelDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val provider: YouTubeProvider,
    private val actions: AccountActions,
    private val session: SessionManager,
    private val events: UiEvents,
    private val subscriptions: ChannelSubscriptionStore,
) : ViewModel() {

    private val channelId = savedState.toRoute<ChannelRoute>().id
    private fun scope(): String = session.context.value?.scopeKey ?: FeedController.GUEST_SCOPE
    private val mutable = MutableStateFlow(ChannelUiState())
    val state: StateFlow<ChannelUiState> = mutable.asStateFlow()
    private var job: Job? = null
    private var aboutJob: Job? = null
    private var aboutLoaded = false
    private var aboutData: ChannelDetail? = null

    private fun withAbout(detail: ChannelDetail): ChannelDetail = aboutData?.let { about ->
        detail.copy(description = about.description ?: detail.description,
            aboutDetails = about.aboutDetails, links = about.links)
    } ?: detail

    fun loadAbout(force: Boolean = false) {
        if (aboutJob?.isActive == true || (aboutLoaded && !force)) return
        aboutJob = viewModelScope.launch {
            mutable.update { it.copy(aboutLoading = true, aboutError = false) }
            try {
                val about = provider.channelAbout(actions.publicCall(), channelId)
                aboutData = about
                mutable.update { it.copy(detail = it.detail?.copy(
                    description = about.description ?: it.detail.description,
                    aboutDetails = about.aboutDetails, links = about.links), aboutLoading = false) }
                aboutLoaded = true
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(aboutLoading = false, aboutError = true) } }
        }
    }

    init {
        load()
    }

    private fun rememberSubscription(detail: ChannelDetail) {
        val subscribed = detail.subscribed ?: return
        subscriptions.put(scope(), channelId, ChannelSubscription(subscribed, detail.notify?.current, detail.notify?.params.orEmpty()))
    }

    fun load() {
        job?.cancel()
        job = viewModelScope.launch {
            runCatching { provider.channel(actions.publicCall(), channelId) }
                .onSuccess { d ->
                    mutable.update { it.copy(detail = withAbout(d), subscribed = d.subscribed, notifyLevel = d.notify?.current, error = null) }
                    rememberSubscription(d)
                }
                .onFailure { e -> mutable.update { it.copy(error = e) } }
        }
    }

    fun toggleSubscribe(onLogin: () -> Unit) {
        if (session.authState.value !is AuthState.Verified) {
            events.post(R.string.msg_login_required)
            onLogin()
            return
        }
        val previous = mutable.value.subscribed ?: false
        viewModelScope.launch {
            mutable.update { it.copy(busy = true) }
            val result = optimisticToggle(
                previous = previous,
                desired = !previous,
                apply = { v ->
                    mutable.update { it.copy(subscribed = v) }
                    subscriptions.setSubscribed(scope(), channelId, v)
                },
                request = { v -> actions.subscribe(channelId, v) },
            )
            mutable.update { it.copy(busy = false) }
            when (result) {
                is ToggleResult.Confirmed -> {
                    events.post(if (result.value) R.string.msg_subscribed else R.string.msg_unsubscribed)
                    if (!result.value) {
                        mutable.update { it.copy(notifyLevel = null) }
                    } else {
                        runCatching { provider.channel(actions.publicCall(), channelId) }.onSuccess { d ->
                            mutable.update { it.copy(detail = withAbout(d), notifyLevel = d.notify?.current) }
                            rememberSubscription(d)
                        }
                    }
                }
                is ToggleResult.RolledBack -> events.postError(result.error)
            }
        }
    }

    fun setNotify(level: vibe.tube.youtube.model.NotifyLevel) {
        val params = mutable.value.detail?.notify?.params?.get(level) ?: return
        val previous = mutable.value.notifyLevel
        mutable.update { it.copy(notifyLevel = level) }
        subscriptions.update(scope(), channelId) { it.copy(notifyLevel = level) }
        viewModelScope.launch {
            runCatching { actions.setNotifyLevel(params) }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                mutable.update { it.copy(notifyLevel = previous) }
                subscriptions.update(scope(), channelId) { it.copy(notifyLevel = previous) }
                events.postError(e)
            }
        }
    }
}
