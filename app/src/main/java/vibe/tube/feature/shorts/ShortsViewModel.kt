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

/** What the Shorts overlay needs beyond the feed item: the channel (avatar, @handle), subscription / like state, comments. */
data class ShortMeta(
    val channel: ChannelRef? = null,
    val liked: Boolean = false,
    val subscribed: Boolean = false,
    val subscribeBusy: Boolean = false,
    val commentsToken: String? = null,
    val commentsCount: String? = null,
    val sorts: List<CommentSort> = emptyList(),
    val loaded: Boolean = false,
)

@HiltViewModel
class ShortsViewModel @Inject constructor(
    feeds: FeedRepository,
    settings: SettingsRepository,
    private val session: SessionManager,
    private val actions: AccountActions,
    private val events: UiEvents,
    private val provider: YouTubeProvider,
    val shorts: ShortsPlayer,
) : FeedViewModel(settings, session) {
    override val controller = feeds.shorts(viewModelScope)

    private val metaState = MutableStateFlow<Map<String, ShortMeta>>(emptyMap())
    val metas: StateFlow<Map<String, ShortMeta>> = metaState.asStateFlow()
    private val inFlight = HashSet<String>()

    val comments = ShortComments(viewModelScope, provider, actions, events)

    /** Loads channel / like / comment info for [video] once (a watch-page request, only for shorts the user actually reaches). */
    fun ensureMeta(video: VideoItem) {
        if (metaState.value[video.id]?.loaded == true || !inFlight.add(video.id)) return
        viewModelScope.launch {
            runCatching { provider.watch(actions.publicCall(), video.id, null, null) }
                .onSuccess { info ->
                    setMeta(video.id) {
                        it.copy(
                            channel = info.channel,
                            liked = info.likeStatus == LikeStatus.Like,
                            subscribed = info.channel?.subscribed == true,
                            commentsToken = info.commentsToken,
                            commentsCount = info.commentsCountText,
                            sorts = info.commentSorts,
                            loaded = true,
                        )
                    }
                }
            inFlight.remove(video.id)
        }
    }

    private fun setMeta(id: String, transform: (ShortMeta) -> ShortMeta) {
        metaState.update { it + (id to transform(it[id] ?: ShortMeta())) }
    }

    private fun requireLogin(onLogin: () -> Unit): Boolean {
        if (session.authState.value is AuthState.Verified) return true
        events.post(R.string.msg_login_required)
        onLogin()
        return false
    }

    fun toggleLike(video: VideoItem, onLogin: () -> Unit) {
        if (!requireLogin(onLogin)) return
        val previous = metaState.value[video.id]?.liked == true
        setMeta(video.id) { it.copy(liked = !previous) }
        viewModelScope.launch {
            runCatching { actions.like(video.id, !previous) }.onFailure {
                setMeta(video.id) { m -> m.copy(liked = previous) }
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
                .onSuccess { events.post(if (!previous) R.string.msg_subscribed else R.string.msg_unsubscribed) }
                .onFailure {
                    setMeta(video.id) { m -> m.copy(subscribed = previous) }
                    events.postError(it)
                }
            setMeta(video.id) { it.copy(subscribeBusy = false) }
        }
    }
}
