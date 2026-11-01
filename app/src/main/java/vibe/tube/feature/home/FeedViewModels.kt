package vibe.tube.feature.home
import kotlinx.coroutines.flow.drop

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
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
                                                                                 
    val hideShorts: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false)
    val authState: StateFlow<AuthState> = session.authState
    fun refresh() = controller.refresh()
    fun refreshIfStale(maxAgeMillis: Long) = controller.refreshIfStale(maxAgeMillis)
    fun loadMore() = controller.loadMore()
}

/** A home topic; one with a [destination] is YouTube's own page for it, the rest are searches. */
enum class HomeTopic(val query: String?, val playlists: Boolean = false, val destination: String? = null) {
    All(null),
    Music("nhạc Việt Nam", destination = vibe.tube.youtube.InnerTubeProvider.DESTINATION_MUSIC),
    Mixes("liên khúc nhạc Việt Nam", true),
    News("tin tức Việt Nam hôm nay", destination = vibe.tube.youtube.InnerTubeProvider.DESTINATION_NEWS),
    Gaming("game Việt Nam", destination = vibe.tube.youtube.InnerTubeProvider.DESTINATION_GAMING),
    Podcasts("podcast tiếng Việt"),
    Live("trực tiếp Việt Nam", destination = vibe.tube.youtube.InnerTubeProvider.DESTINATION_LIVE),
    Smartphones("đánh giá điện thoại Việt Nam"),
    Playlist("danh sách nhạc Việt Nam", true),
    LoFi("nhạc lofi Việt Nam");
    val labelRes: Int get() = when (this) {
        All -> vibe.tube.R.string.topic_all
        Music -> vibe.tube.R.string.topic_music
        Mixes -> vibe.tube.R.string.topic_mixes
        News -> vibe.tube.R.string.topic_news
        Gaming -> vibe.tube.R.string.topic_gaming
        Podcasts -> vibe.tube.R.string.topic_podcasts
        Live -> vibe.tube.R.string.topic_live
        Smartphones -> vibe.tube.R.string.topic_phones
        Playlist -> vibe.tube.R.string.topic_playlists
        LoFi -> vibe.tube.R.string.topic_lofi
    }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val feeds: FeedRepository,
    settings: SettingsRepository,
    session: SessionManager,
    val events: vibe.tube.feature.common.UiEvents,
    history: vibe.tube.data.repo.LocalHistoryRepository,
    private val provider: vibe.tube.youtube.YouTubeProvider,
) : FeedViewModel(settings, session) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val historyMetadata = kotlinx.coroutines.flow.MutableStateFlow<Map<String, vibe.tube.youtube.model.ChannelRef>>(emptyMap())
    private val historyRequested = mutableSetOf<String>()
    private val historySlots = kotlinx.coroutines.sync.Semaphore(2)
    val unfinished = kotlinx.coroutines.flow.combine(
        session.context.flatMapLatest { history.recentPlayback(it?.scopeKey ?: FeedController.GUEST_SCOPE) }.map { recentUnfinished(it) },
        historyMetadata,
    ) { entries, metadata ->
        entries.map { entry ->
            if (entry.channelAvatar == null && historyRequested.add(entry.videoId)) viewModelScope.launch {
                historySlots.acquire()
                try {
                    val info = provider.watch(vibe.tube.youtube.ProviderCall(settings.latest.value.contentLocale(), null), entry.videoId, null, null)
                    info.channel?.let { channel -> historyMetadata.value = historyMetadata.value + (entry.videoId to channel) }
                } catch (e: kotlinx.coroutines.CancellationException) { historyRequested.remove(entry.videoId); throw e }
                catch (_: Exception) {                                                                  }
                finally { historySlots.release() }
            }
            metadata[entry.videoId]?.let { entry.copy(channelId = it.id, channelAvatar = it.avatar) } ?: entry
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val home = feeds.home(viewModelScope)
    val selectedFeed = kotlinx.coroutines.flow.MutableStateFlow(home)
    val topic = kotlinx.coroutines.flow.MutableStateFlow(HomeTopic.All)
    val homeChips: kotlinx.coroutines.flow.StateFlow<List<vibe.tube.youtube.model.HomeChip>> = home.state
        .map { emptyList<vibe.tube.youtube.model.HomeChip>() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val selectedChip = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    fun selectChip(chip: vibe.tube.youtube.model.HomeChip) {
        if (selectedChip.value == chip.key) return
        topicJob?.cancel()
        selectedChip.value = chip.key
        val job = kotlinx.coroutines.SupervisorJob(viewModelScope.coroutineContext[kotlinx.coroutines.Job])
        topicJob = job
        selectedFeed.value = feeds.homeChip(chip, kotlinx.coroutines.CoroutineScope(viewModelScope.coroutineContext + job))
    }
    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(settings.latest, session.context) { prefs, ctx -> listOf(prefs.contentLanguage, prefs.contentRegion, ctx?.scopeKey) }
                .distinctUntilChanged().drop(1).collect { topicJob?.cancel(); selectedChip.value = null; topic.value = HomeTopic.All; selectedFeed.value = home }
        }
    }
    override val controller: FeedController get() = selectedFeed.value
    private var topicJob: kotlinx.coroutines.Job? = null
    fun select(value: HomeTopic) {
        if (value == topic.value && selectedChip.value == null) return
        selectedChip.value = null
        topic.value = value
        topicJob?.cancel()
        if (value == HomeTopic.All) { selectedFeed.value = home; return }
        val job = kotlinx.coroutines.SupervisorJob(viewModelScope.coroutineContext[kotlinx.coroutines.Job])
        topicJob = job
        val scope = kotlinx.coroutines.CoroutineScope(viewModelScope.coroutineContext + job)
        scope.launch {
            kotlinx.coroutines.delay(180)
            selectedFeed.value = value.destination?.let { feeds.explore(it, scope) }
                ?: feeds.topic(value.query!!, value.playlists, value == HomeTopic.Live, scope)
        }
    }
}

internal fun recentUnfinished(entries: List<vibe.tube.data.db.PlaybackHistoryEntity>) = entries
    .asSequence().filter { !it.completed && it.positionMs >= vibe.tube.data.repo.LocalHistoryRepository.MIN_RESUME_MS }
    .sortedByDescending { it.lastPlayedAt }.distinctBy { it.videoId }.take(5).toList()

@HiltViewModel
class SubscriptionsFeedViewModel @Inject constructor(
    private val feeds: FeedRepository,
    settings: SettingsRepository,
    session: SessionManager,
) : FeedViewModel(settings, session) {
    private val all = feeds.subscriptions(viewModelScope)
    val selectedFeed = kotlinx.coroutines.flow.MutableStateFlow(all)
    val channel = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    override val controller: FeedController get() = selectedFeed.value
    private var channelJob: kotlinx.coroutines.Job? = null
    fun select(id: String?) {
        if (id == channel.value) return
        channel.value = id
        channelJob?.cancel()
        if (id == null) { selectedFeed.value = all; return }
        val job = kotlinx.coroutines.SupervisorJob(viewModelScope.coroutineContext[kotlinx.coroutines.Job])
        channelJob = job
        selectedFeed.value = feeds.channelVideos(id, { vibe.tube.youtube.model.ChannelSort.Latest },
            kotlinx.coroutines.CoroutineScope(viewModelScope.coroutineContext + job))
    }
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

    private val mutableSort = kotlinx.coroutines.flow.MutableStateFlow(vibe.tube.youtube.model.ChannelSort.Latest)
    val sort: kotlinx.coroutines.flow.StateFlow<vibe.tube.youtube.model.ChannelSort> = mutableSort

    private val mutableTab = kotlinx.coroutines.flow.MutableStateFlow("videos")
    val tab: kotlinx.coroutines.flow.StateFlow<String> = mutableTab
    override val controller = feeds.channelVideos(channelId, { mutableSort.value }, { mutableTab.value }, viewModelScope)
    fun setTab(value: String) { if (mutableTab.value != value) { mutableTab.value = value; if (value != "about") controller.reload(clearItems = true) } }

    fun setSort(value: vibe.tube.youtube.model.ChannelSort) {
        if (mutableSort.value == value) return
        mutableSort.value = value
        controller.reload()
    }
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
