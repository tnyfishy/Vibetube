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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vibe.tube.account.SessionManager
import vibe.tube.data.repo.AccountActions
import vibe.tube.data.repo.FeedController
import vibe.tube.data.repo.FeedRepository
import vibe.tube.data.repo.FeedState
import vibe.tube.data.repo.LocalHistoryRepository
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
) : ViewModel() {

    private val initial = savedState.toRoute<SearchRoute>().query
    private val mutable = MutableStateFlow(SearchUiState(input = initial))
    val ui: StateFlow<SearchUiState> = mutable.asStateFlow()

    private val controllerState = MutableStateFlow<FeedController?>(null)
    private var resultsJob: Job? = null

    val results: StateFlow<FeedState?> = controllerState
        .flatMapLatest { it?.state ?: kotlinx.coroutines.flow.flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val hideShorts: StateFlow<Boolean> = settings.settings.map { it.hideShorts }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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
        mutable.update { it.copy(kind = kind, sort = if (kind == SearchKind.Channel || kind == SearchKind.Playlist) SearchSort.Relevance else it.sort) }
        if (mutable.value.submitted != null) startSearch()
    }

    fun setSort(sort: SearchSort) {
        mutable.update { it.copy(sort = sort) }
        if (mutable.value.submitted != null) startSearch()
    }

    private fun startSearch() {
        val s = mutable.value
        val query = s.submitted ?: return
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
