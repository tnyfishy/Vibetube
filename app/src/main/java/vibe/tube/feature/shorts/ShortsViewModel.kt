package vibe.tube.feature.shorts

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vibe.tube.R
import vibe.tube.account.AuthState
import vibe.tube.account.SessionManager
import vibe.tube.data.repo.AccountActions
import vibe.tube.data.repo.FeedRepository
import vibe.tube.data.settings.SettingsRepository
import vibe.tube.feature.common.UiEvents
import vibe.tube.feature.home.FeedViewModel
import vibe.tube.player.ShortsPlayer
import vibe.tube.youtube.YouTubeProvider
import vibe.tube.youtube.model.ChannelRef
import vibe.tube.youtube.model.CommentSort
import vibe.tube.youtube.model.LikeStatus
import vibe.tube.youtube.model.VideoItem
import javax.inject.Inject

data class ShortMeta(
    val channel: ChannelRef? = null,
    val liked: Boolean = false,
    val disliked: Boolean = false,
                                                                                                             
    val likeCount: String? = null,
    val dislikeCount: String? = null,
    val subscribed: Boolean = false,
    val subscribeBusy: Boolean = false,
    val notifyLevel: vibe.tube.youtube.model.NotifyLevel? = null,
    val commentsToken: String? = null,
    val commentsCount: String? = null,
    val sorts: List<CommentSort> = emptyList(),
    val loaded: Boolean = false,
    /** The reel feed names a reel without its title; the watch answer fills it in. */
    val title: String? = null,
    /** For a collaboration, whether the account follows each of its channels, by channel id. */
    val collabSubscribed: Map<String, Boolean> = emptyMap(),
)

@HiltViewModel
class ShortsViewModel @Inject constructor(
    feeds: FeedRepository,
    settings: SettingsRepository,
    private val session: SessionManager,
    private val actions: AccountActions,
    private val events: UiEvents,
    private val provider: YouTubeProvider,
    private val voteCounts: vibe.tube.data.repo.VoteCounts,
    val shorts: ShortsPlayer,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : FeedViewModel(settings, session) {
    override val controller = feeds.shorts(viewModelScope)

    private val metaState = MutableStateFlow<Map<String, ShortMeta>>(emptyMap())
    val metas: StateFlow<Map<String, ShortMeta>> = metaState.asStateFlow()
    private val inFlight = HashSet<String>()

    val comments = ShortComments(viewModelScope, provider, actions, events)

    fun ensureMeta(video: VideoItem) {
        if (metaState.value[video.id]?.loaded == true || !inFlight.add(video.id)) return
        viewModelScope.launch {
            runCatching { provider.watch(actions.publicCall(), video.id, null, null) }
                .onFailure { android.util.Log.w("ShortsMeta", "${video.id} watch failed: ${it.javaClass.simpleName} ${it.message?.take(80)}") }
                .onSuccess { info ->                    setMeta(video.id) {
                        it.copy(
                            channel = info.channel,
                            liked = info.likeStatus == LikeStatus.Like,
                            disliked = info.likeStatus == LikeStatus.Dislike,
                            likeCount = info.likeCountText?.takeIf { t -> t.any(Char::isDigit) },
                            subscribed = info.channel?.subscribed == true,
                            notifyLevel = info.channel?.notify?.current,
                            commentsToken = info.commentsToken,
                            commentsCount = info.commentsCountText,
                            sorts = info.commentSorts,
                            loaded = true,
                            title = info.title.takeIf { t -> t.isNotBlank() },
                            collabSubscribed = info.channel?.collaborators.orEmpty()
                                .mapNotNull { c -> c.id?.let { id -> id to (c.subscribed == true) } }.toMap(),
                        )
                    }
                    info.channel?.avatar?.let(::prefetchAvatar)
                }
            inFlight.remove(video.id)
        }
        viewModelScope.launch {
            val votes = voteCounts.get(video.id) ?: return@launch
            setMeta(video.id) {
                it.copy(
                    likeCount = it.likeCount ?: votes.likes?.toString(),
                    dislikeCount = votes.dislikes?.toString(),
                )
            }
        }
    }

    fun prefetchMetas(videos: List<VideoItem>) {
        videos.forEach { video ->
            video.channelAvatar?.let(::prefetchAvatar)
            ensureMeta(video)
        }
    }

    private fun prefetchAvatar(url: String) {
        val sized = vibe.tube.ui.components.upscaleAvatarUrl(url, 144)
        coil3.SingletonImageLoader.get(appContext).enqueue(coil3.request.ImageRequest.Builder(appContext).data(sized).build())
    }

    fun download(video: VideoItem) = events.requestDownload(video, videoOnly = true)

    private fun setMeta(id: String, transform: (ShortMeta) -> ShortMeta) {
        metaState.update { it + (id to transform(it[id] ?: ShortMeta())) }
    }

    private fun requireLogin(onLogin: () -> Unit): Boolean {
        if (session.authState.value is AuthState.Verified) return true
        events.post(R.string.msg_login_required)
        onLogin()
        return false
    }

    fun toggleLike(video: VideoItem, onLogin: () -> Unit) = rate(video, LikeStatus.Like, onLogin)

    fun toggleDislike(video: VideoItem, onLogin: () -> Unit) = rate(video, LikeStatus.Dislike, onLogin)

    private fun rate(video: VideoItem, tapped: LikeStatus, onLogin: () -> Unit) {
        if (session.authState.value !is AuthState.Verified) { events.post(R.string.msg_login_required); return }
        val before = metaState.value[video.id] ?: ShortMeta()
        val previous = when {
            before.liked -> LikeStatus.Like
            before.disliked -> LikeStatus.Dislike
            else -> LikeStatus.Indifferent
        }
        val desired = if (previous == tapped) LikeStatus.Indifferent else tapped
        setMeta(video.id) { it.copy(liked = desired == LikeStatus.Like, disliked = desired == LikeStatus.Dislike) }
        viewModelScope.launch {
            runCatching { actions.rate(video.id, desired) }.onFailure {
                setMeta(video.id) { m -> m.copy(liked = previous == LikeStatus.Like, disliked = previous == LikeStatus.Dislike) }
                events.postError(it)
            }
        }
    }

    fun toggleSubscribe(video: VideoItem, onLogin: () -> Unit) {
        val meta = metaState.value[video.id]
        val channelId = meta?.channel?.id ?: video.channelId ?: return
        if (!requireLogin(onLogin)) return
        if (meta?.subscribeBusy == true) return
        val previous = meta?.subscribed == true
        setMeta(video.id) { it.copy(subscribed = !previous, subscribeBusy = true) }
        viewModelScope.launch {
            runCatching { actions.subscribe(channelId, !previous) }
                .onSuccess {
                    events.post(if (!previous) R.string.msg_subscribed else R.string.msg_unsubscribed)
                    if (previous) {
                        setMeta(video.id) { m -> m.copy(notifyLevel = null) }
                    } else {
                        runCatching { provider.watch(actions.publicCall(), video.id, null, null) }.onSuccess { info ->
                            setMeta(video.id) { m -> m.copy(channel = info.channel ?: m.channel, notifyLevel = info.channel?.notify?.current) }
                        }
                    }
                }
                .onFailure {
                    setMeta(video.id) { m -> m.copy(subscribed = previous) }
                    events.postError(it)
                }
            setMeta(video.id) { it.copy(subscribeBusy = false) }
        }
    }

    /**
     * Follows or unfollows some of a collaboration's channels at once. Each channel is its own request, so one that
     * fails is put back on its own and the rest keep their new state.
     */
    fun setCollaboratorsSubscribed(video: VideoItem, channelIds: List<String>, subscribe: Boolean, onLogin: () -> Unit) {
        val meta = metaState.value[video.id] ?: return
        if (channelIds.isEmpty() || !requireLogin(onLogin) || meta.subscribeBusy) return
        val leadId = meta.channel?.id
        fun apply(ids: Collection<String>, value: Boolean) = setMeta(video.id) { m ->
            m.copy(
                collabSubscribed = m.collabSubscribed + ids.associateWith { value },
                subscribed = if (leadId != null && leadId in ids) value else m.subscribed,
            )
        }
        apply(channelIds, subscribe)
        setMeta(video.id) { it.copy(subscribeBusy = true) }
        viewModelScope.launch {
            val failures = channelIds.mapNotNull { id -> runCatching { actions.subscribe(id, subscribe) }.exceptionOrNull()?.let { id to it } }
            if (failures.isNotEmpty()) apply(failures.map { it.first }, !subscribe)
            if (failures.size < channelIds.size) events.post(if (subscribe) R.string.msg_subscribed else R.string.msg_unsubscribed)
            failures.firstOrNull()?.let { events.postError(it.second) }
            setMeta(video.id) { it.copy(subscribeBusy = false) }
        }
    }

    fun setNotify(video: VideoItem, level: vibe.tube.youtube.model.NotifyLevel) {
        val meta = metaState.value[video.id] ?: return
        val params = meta.channel?.notify?.params?.get(level) ?: return
        val previous = meta.notifyLevel
        setMeta(video.id) { it.copy(notifyLevel = level) }
        viewModelScope.launch {
            runCatching { actions.setNotifyLevel(params) }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                setMeta(video.id) { m -> m.copy(notifyLevel = previous) }
                events.postError(e)
            }
        }
    }
}
