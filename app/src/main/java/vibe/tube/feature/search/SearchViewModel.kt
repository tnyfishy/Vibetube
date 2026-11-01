package vibe.tube.feature.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vibe.tube.account.SessionManager
import vibe.tube.data.repo.AccountActions
import vibe.tube.data.repo.ChannelSubscription
import vibe.tube.data.repo.ChannelSubscriptionStore
import vibe.tube.data.repo.FeedController
import vibe.tube.data.repo.FeedRepository
import vibe.tube.data.repo.FeedState
import vibe.tube.data.repo.LocalHistoryRepository
import vibe.tube.data.repo.optimisticToggle
import vibe.tube.data.settings.SettingsRepository
import vibe.tube.feature.nav.SearchRoute
import vibe.tube.youtube.YouTubeProvider
import vibe.tube.youtube.model.SearchKind
import vibe.tube.youtube.model.SearchSort
import javax.inject.Inject

data class SearchUiState(
    val input: String = "",
    val submitted: String? = null,
    val kind: SearchKind = SearchKind.All,
    val sort: SearchSort = SearchSort.Relevance,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val feeds: FeedRepository,
    private val provider: YouTubeProvider,
    private val actions: AccountActions,
    private val history: LocalHistoryRepository,
    private val settings: SettingsRepository,
    private val session: SessionManager,
    private val subscriptions: ChannelSubscriptionStore,
) : ViewModel() {

    private val initial = savedState.toRoute<SearchRoute>().query
    private val mutable = MutableStateFlow(SearchUiState(input = initial))
    val ui: StateFlow<SearchUiState> = mutable.asStateFlow()

    private val controllerState = MutableStateFlow<FeedController?>(null)
    private var resultsJob: Job? = null

    val results: StateFlow<FeedState?> = controllerState
        .flatMapLatest { it?.state ?: kotlinx.coroutines.flow.flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val hideShorts: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false)

    val suggestions: StateFlow<List<String>> = mutable
        .map { if (it.submitted == null) it.input.trim() else "" }
        .debounce(200)
        .distinctUntilChanged()
        .flatMapLatest { q ->
            kotlinx.coroutines.flow.flow {
                if (q.length < 2) emit(emptyList()) else emit(runCatching { provider.suggestions(settings.latest.value.contentLocale(), q) }.getOrDefault(emptyList()))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recent: StateFlow<List<String>> = session.context
        .flatMapLatest { history.recentSearches(it?.scopeKey ?: FeedController.GUEST_SCOPE) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        if (initial.isNotBlank()) submit(initial)
    }

    fun onInput(text: String) {
        mutable.update { it.copy(input = text, submitted = null) }
        resultsJob?.cancel()
        controllerState.value = null
    }

    fun submit(query: String = mutable.value.input) {
        val q = query.trim()
        if (q.isEmpty()) return
        mutable.update { it.copy(input = q, submitted = q) }
        startSearch()
        if (settings.latest.value.recordsLocalHistory) {
            viewModelScope.launch { history.recordSearch(session.currentContext()?.scopeKey ?: FeedController.GUEST_SCOPE, q) }
        }
    }

    fun setKind(kind: SearchKind) {
        mutable.update { it.copy(kind = kind, sort = if (kind == SearchKind.Channel || kind == SearchKind.Playlist || kind == SearchKind.Shorts) SearchSort.Relevance else it.sort) }
        if (mutable.value.submitted != null) startSearch()
    }

    fun setSort(sort: SearchSort) {
        mutable.update { it.copy(sort = sort) }
        if (mutable.value.submitted != null) startSearch()
    }

    val featuredChannel = MutableStateFlow<vibe.tube.youtube.model.ChannelItem?>(null)
    val featuredVideos = MutableStateFlow<List<vibe.tube.youtube.model.VideoItem>>(emptyList())
    private var featuredJob: Job? = null

    val featuredBusy = MutableStateFlow(false)

    /** Subscription state of the featured channel for the active account; null until it is known. */
    val featuredSubscription: StateFlow<ChannelSubscription?> = combine(featuredChannel, subscriptions.states, session.context) { channel, known, ctx ->
        channel?.let { known[(ctx?.scopeKey ?: FeedController.GUEST_SCOPE) to it.id] }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun scopeKey(): String = session.context.value?.scopeKey ?: FeedController.GUEST_SCOPE

    fun toggleFeatured(onLogin: () -> Unit) {
        val channel = featuredChannel.value ?: return
        if (session.authState.value !is vibe.tube.account.AuthState.Verified) { onLogin(); return }
        if (featuredBusy.value) return
        val scope = scopeKey()
        val current = subscriptions.get(scope, channel.id) ?: return
        viewModelScope.launch {
            featuredBusy.value = true
            try {
                optimisticToggle(
                    previous = current.subscribed,
                    desired = !current.subscribed,
                    apply = { subscriptions.setSubscribed(scope, channel.id, it) },
                    request = { actions.subscribe(channel.id, it) },
                )
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
            finally { featuredBusy.value = false }
        }
    }

    fun setFeaturedNotify(level: vibe.tube.youtube.model.NotifyLevel) {
        val channel = featuredChannel.value ?: return
        val scope = scopeKey()
        val current = subscriptions.get(scope, channel.id) ?: return
        val params = current.notifyParams[level] ?: return
        val previous = current.notifyLevel
        subscriptions.update(scope, channel.id) { it.copy(notifyLevel = level) }
        viewModelScope.launch {
            runCatching { actions.setNotifyLevel(params) }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                subscriptions.update(scope, channel.id) { it.copy(notifyLevel = previous) }
            }
        }
    }

    fun refreshFeaturedSubscription() {
        val channel = featuredChannel.value ?: return
        val scope = scopeKey()
        viewModelScope.launch {
            try {
                rememberSubscription(scope, channel.id, provider.channel(actions.publicCall(), channel.id))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    fun offerFeatured(channel: vibe.tube.youtube.model.ChannelItem) {
        if (featuredChannel.value != null) return
        featuredJob?.cancel()
        featuredJob = viewModelScope.launch {
            featuredChannel.value = channel
            loadFeatured(channel)
        }
    }

    /** Fills the featured card's details and subscription state; the card shows what is already stored without waiting for this. */
    private suspend fun loadFeatured(channel: vibe.tube.youtube.model.ChannelItem) {
        val scope = scopeKey()
        if (session.authState.value !is vibe.tube.account.AuthState.Verified) subscriptions.put(scope, channel.id, ChannelSubscription(subscribed = false))
        val detail = try { provider.channel(actions.publicCall(), channel.id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { android.util.Log.w("Search", "featured channel ${channel.id}: ${e.javaClass.simpleName} ${e.message}"); null }
        detail?.let { rememberSubscription(scope, channel.id, it) }
        val avatar = detail?.avatar?.let { listOf(vibe.tube.youtube.model.Thumb(it, 512, 512)) } ?: channel.avatars
        featuredChannel.value = channel.copy(name = detail?.name?.ifBlank { channel.name } ?: channel.name, avatars = avatar, handle = detail?.handle ?: channel.handle,
            // The search result carries the full "524 Tr người đăng ký"; the guest channel page only gives "524".
            subscriberText = channel.subscriberText ?: detail?.subscriberText)
        try {
            val avatarUrl = avatar.maxByOrNull { it.width }?.url
            featuredVideos.value = provider.channelVideos(actions.publicCall(), channel.id, null).items.filterIsInstance<vibe.tube.youtube.model.VideoItem>().filterNot { it.isShort }.take(6).map { it.copy(channelName = it.channelName ?: channel.name, channelId = it.channelId ?: channel.id, channelAvatar = it.channelAvatar ?: avatarUrl) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { android.util.Log.w("Search", "featured videos ${channel.id}: ${e.javaClass.simpleName} ${e.message}") }
    }

    private fun rememberSubscription(scope: String, channelId: String, detail: vibe.tube.youtube.model.ChannelDetail) {
        val subscribed = detail.subscribed ?: return
        subscriptions.put(scope, channelId, ChannelSubscription(subscribed, detail.notify?.current, detail.notify?.params.orEmpty()))
    }

    private fun findFeatured(query: String, kind: SearchKind) {
        featuredJob?.cancel()
        featuredChannel.value = null
        featuredVideos.value = emptyList()
        if (kind != SearchKind.All) return
        featuredJob = viewModelScope.launch {
            try {
                val candidates = provider.search(actions.publicCall().copy(auth = null), query, SearchKind.Channel, SearchSort.Relevance, null).items.filterIsInstance<vibe.tube.youtube.model.ChannelItem>()
                val channel = pickFeaturedChannel(query, candidates) ?: return@launch
                featuredChannel.value = channel
                loadFeatured(channel)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    private fun startSearch() {
        val s = mutable.value
        val query = s.submitted ?: return
        findFeatured(query, s.kind)
        resultsJob?.cancel()
        val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
        resultsJob = scope.coroutineContext[Job]
        controllerState.value = feeds.search(query, s.kind, s.sort, scope)
    }

    fun refresh() = controllerState.value?.refresh()

    fun loadMore() = controllerState.value?.loadMore()

    fun removeRecent(query: String) {
        viewModelScope.launch { history.removeSearch(session.currentContext()?.scopeKey ?: FeedController.GUEST_SCOPE, query) }
    }

    fun clearRecent() {
        viewModelScope.launch { history.clearSearches(session.currentContext()?.scopeKey ?: FeedController.GUEST_SCOPE) }
    }

    override fun onCleared() {
        resultsJob?.cancel()
    }
}
