package vibe.tube.feature.watch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vibe.tube.R
import vibe.tube.account.AuthState
import vibe.tube.account.SessionManager
import vibe.tube.core.AppError
import vibe.tube.data.repo.AccountActions
import vibe.tube.data.repo.ToggleResult
import vibe.tube.data.repo.optimisticToggle
import vibe.tube.feature.common.UiEvents
import vibe.tube.player.PlayerController
import vibe.tube.youtube.YouTubeProvider
import vibe.tube.youtube.model.Comment
import vibe.tube.youtube.model.CommentSort
import vibe.tube.youtube.model.FeedItem
import vibe.tube.youtube.model.LikeStatus
import vibe.tube.youtube.model.VideoItem
import vibe.tube.youtube.model.WatchInfo
import javax.inject.Inject

data class RepliesState(
    val items: List<Comment> = emptyList(),
    val continuation: String? = null,
    val loading: Boolean = false,
    val expanded: Boolean = false,
    val failed: Boolean = false,
)

data class CommentsUiState(
    val comments: List<Comment> = emptyList(),
    val continuation: String? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: Boolean = false,
    val loadMoreError: Boolean = false,
    val disabledMessage: String? = null,
    val totalText: String? = null,
    val createToken: String? = null,
    val sorts: List<CommentSort> = emptyList(),
    val posting: Boolean = false,
    val replies: Map<String, RepliesState> = emptyMap(),
)

data class WatchUiState(
    val videoId: String? = null,
    val loading: Boolean = false,
    val error: Throwable? = null,
    val info: WatchInfo? = null,
    val likeStatus: LikeStatus? = null,
    /** Like / dislike totals from Return YouTube Dislike (null until loaded or when unavailable). */
    val votes: vibe.tube.data.repo.Votes? = null,
    val subscribed: Boolean? = null,
    /** The channel's notification bell (only known while subscribed). */
    val notifyLevel: vibe.tube.youtube.model.NotifyLevel? = null,
    val likeBusy: Boolean = false,
    val subscribeBusy: Boolean = false,
    val related: List<FeedItem> = emptyList(),
    val relatedContinuation: String? = null,
    val relatedLoading: Boolean = false,
    val comments: CommentsUiState = CommentsUiState(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WatchViewModel @Inject constructor(
    private val player: PlayerController,
    private val provider: YouTubeProvider,
    private val actions: AccountActions,
    private val session: SessionManager,
    private val events: UiEvents,
    private val voteCounts: vibe.tube.data.repo.VoteCounts,
) : ViewModel() {

    private val mutable = MutableStateFlow(WatchUiState())
    val state: StateFlow<WatchUiState> = mutable.asStateFlow()
    val authState: StateFlow<AuthState> = session.authState
    private var loadJob: Job? = null
    private var commentsJob: Job? = null
    private var generation = 0

    init {
        viewModelScope.launch {
            player.state.map { it.current?.id to it.playlistId to it.queueIndex }
                .distinctUntilChanged()
                .collect { (idAndPlaylist, index) ->
                    val (id, playlistId) = idAndPlaylist
                    if (id == null) {
                        generation++
                        mutable.value = WatchUiState()
                    } else if (id != mutable.value.videoId) {
                        load(id, playlistId, index.takeIf { it >= 0 })
                    }
                }
        }
        viewModelScope.launch {
            session.context.map { it?.scopeKey }.distinctUntilChanged().collect {
                val id = mutable.value.videoId
                if (id != null) load(id, player.state.value.playlistId, null)
            }
        }
    }

    fun load(videoId: String, playlistId: String? = null, index: Int? = null) {
        loadJob?.cancel()
        commentsJob?.cancel()
        val gen = ++generation
        mutable.value = WatchUiState(videoId = videoId, loading = true)
        loadJob = viewModelScope.launch {
            try {
                val info = provider.watch(actions.publicCall(), videoId, playlistId, index)
                if (gen != generation) return@launch
                mutable.update {
                    it.copy(
                        loading = false,
                        info = info,
                        likeStatus = info.likeStatus,
                        subscribed = info.channel?.subscribed,
                        notifyLevel = info.channel?.notify?.current,
                        related = info.related,
                        relatedContinuation = info.relatedContinuation,
                    )
                }
                viewModelScope.launch {
                    val v = voteCounts.get(videoId)
                    if (v != null && gen == generation) mutable.update { it.copy(votes = v) }
                }
                player.offerAutoplay(info.autoplayVideoId?.let { autoplayItem(info) })
                info.commentsToken?.let { loadComments(it, gen, reset = true) }
                    ?: mutable.update { it.copy(comments = CommentsUiState(disabledMessage = "")) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) mutable.update { it.copy(loading = false, error = e) }
            }
        }
    }

    private fun autoplayItem(info: WatchInfo): VideoItem? {
        val id = info.autoplayVideoId ?: return null
        return info.related.filterIsInstance<VideoItem>().firstOrNull { it.id == id }
            ?: info.related.filterIsInstance<VideoItem>().firstOrNull()
    }

    fun retry() {
        mutable.value.videoId?.let { load(it, player.state.value.playlistId, null) }
    }

    fun loadMoreRelated() {
        val s = mutable.value
        val token = s.relatedContinuation ?: return
        if (s.relatedLoading) return
        val gen = generation
        mutable.update { it.copy(relatedLoading = true) }
        viewModelScope.launch {
            runCatching { provider.related(actions.publicCall(), token) }
                .onSuccess { page ->
                    if (gen != generation) return@onSuccess
                    mutable.update { cur ->
                        val seen = cur.related.mapTo(HashSet()) { it.id }
                        cur.copy(related = cur.related + page.items.filter { seen.add(it.id) }, relatedContinuation = page.continuation, relatedLoading = false)
                    }
                }
                .onFailure { if (gen == generation) mutable.update { it.copy(relatedLoading = false) } }
        }
    }

    private fun loadComments(token: String, gen: Int, reset: Boolean) {
        commentsJob?.cancel()
        commentsJob = viewModelScope.launch {
            mutable.update { it.copy(comments = if (reset) CommentsUiState(loading = true, sorts = it.comments.sorts) else it.comments.copy(loadingMore = true, loadMoreError = false)) }
            try {
                val page = provider.comments(actions.publicCall(), token)
                if (gen != generation) return@launch
                mutable.update { cur ->
                    val old = cur.comments
                    val merged = if (reset) page.comments else {
                        val seen = old.comments.mapTo(HashSet()) { it.id }
                        old.comments + page.comments.filter { seen.add(it.id) }
                    }
                    cur.copy(
                        comments = old.copy(
                            comments = merged,
                            continuation = page.continuation,
                            loading = false,
                            loadingMore = false,
                            error = false,
                            disabledMessage = page.disabledMessage,
                            totalText = page.totalText ?: old.totalText,
                            createToken = page.createToken ?: old.createToken,
                            sorts = page.sorts.ifEmpty { old.sorts }.ifEmpty { cur.info?.commentSorts.orEmpty() },
                        ),
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) {
                    mutable.update {
                        it.copy(comments = it.comments.copy(loading = false, loadingMore = false, error = reset, loadMoreError = !reset))
                    }
                }
            }
        }
    }

    fun loadMoreComments() {
        val c = mutable.value.comments
        val token = c.continuation ?: return
        if (c.loading || c.loadingMore) return
        loadComments(token, generation, reset = false)
    }

    fun retryComments() {
        val token = mutable.value.info?.commentsToken ?: return
        loadComments(token, generation, reset = true)
    }

    fun changeSort(sort: CommentSort) {
        mutable.update { it.copy(comments = it.comments.copy(sorts = it.comments.sorts.map { s -> s.copy(selected = s.token == sort.token) })) }
        loadComments(sort.token, generation, reset = true)
    }

    fun toggleReplies(comment: Comment) {
        val token = comment.repliesToken ?: return
        val current = mutable.value.comments.replies[comment.id]
        if (current != null && current.items.isNotEmpty()) {
            updateReplies(comment.id) { it.copy(expanded = !it.expanded) }
            return
        }
        fetchReplies(comment.id, token, append = false)
    }

    fun loadMoreReplies(commentId: String) {
        val state = mutable.value.comments.replies[commentId] ?: return
        val token = state.continuation ?: return
        if (state.loading) return
        fetchReplies(commentId, token, append = true)
    }

    private fun updateReplies(commentId: String, transform: (RepliesState) -> RepliesState) {
        mutable.update {
            val map = it.comments.replies
            it.copy(comments = it.comments.copy(replies = map + (commentId to transform(map[commentId] ?: RepliesState()))))
        }
    }

    private fun fetchReplies(commentId: String, token: String, append: Boolean) {
        val gen = generation
        updateReplies(commentId) { it.copy(loading = true, expanded = true, failed = false) }
        viewModelScope.launch {
            runCatching { provider.comments(actions.publicCall(), token) }
                .onSuccess { page ->
                    if (gen != generation) return@onSuccess
                    updateReplies(commentId) {
                        val seen = it.items.mapTo(HashSet()) { c -> c.id }
                        it.copy(
                            items = if (append) it.items + page.comments.filter { c -> seen.add(c.id) } else page.comments,
                            continuation = page.continuation,
                            loading = false,
                        )
                    }
                }
                .onFailure { if (gen == generation) updateReplies(commentId) { it.copy(loading = false, failed = true) } }
        }
    }

    fun postComment(text: String, onPosted: () -> Unit) {
        val c = mutable.value.comments
        val params = c.createToken
        if (params == null) {
            events.post(R.string.msg_comment_unavailable)
            return
        }
        viewModelScope.launch {
            mutable.update { it.copy(comments = it.comments.copy(posting = true)) }
            runCatching { actions.postComment(params, text.trim()) }
                .onSuccess {
                    events.post(R.string.msg_comment_posted)
                    onPosted()
                    mutable.value.info?.commentsToken?.let { t -> loadComments(t, generation, reset = true) }
                }
                .onFailure(events::postError)
            mutable.update { it.copy(comments = it.comments.copy(posting = false)) }
        }
    }

    fun postReply(comment: Comment, text: String, onPosted: () -> Unit) {
        val params = comment.replyParams
        if (params == null) {
            events.post(R.string.msg_comment_unavailable)
            return
        }
        viewModelScope.launch {
            runCatching { actions.postReply(params, text.trim()) }
                .onSuccess {
                    events.post(R.string.msg_reply_posted)
                    onPosted()
                    comment.repliesToken?.let { fetchReplies(comment.id, it, append = false) }
                }
                .onFailure(events::postError)
        }
    }

    fun toggleLike(onLogin: () -> Unit) = rate(LikeStatus.Like, onLogin)

    fun toggleDislike(onLogin: () -> Unit) = rate(LikeStatus.Dislike, onLogin)

    /** Tapping the rating the video already has takes it back; tapping the other one switches to it. */
    private fun rate(tapped: LikeStatus, onLogin: () -> Unit) {
        val id = mutable.value.videoId ?: return
        if (session.authState.value !is AuthState.Verified) {
            events.post(R.string.msg_login_required)
            onLogin()
            return
        }
        if (mutable.value.likeBusy) return
        val previous = mutable.value.likeStatus ?: LikeStatus.Indifferent
        val desired = if (previous == tapped) LikeStatus.Indifferent else tapped
        mutable.update { it.copy(likeStatus = desired, likeBusy = true) }
        viewModelScope.launch {
            val failure = runCatching { actions.rate(id, desired) }.exceptionOrNull()
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            mutable.update { it.copy(likeBusy = false, likeStatus = if (failure != null) previous else it.likeStatus) }
            if (failure != null) {
                events.postError(failure)
                return@launch
            }
            events.post(
                when (desired) {
                    LikeStatus.Like -> R.string.msg_liked
                    LikeStatus.Dislike -> R.string.msg_disliked
                    LikeStatus.Indifferent -> if (previous == LikeStatus.Dislike) R.string.msg_undisliked else R.string.msg_unliked
                },
            )
            reconcile(id) { info ->
                val server = info.likeStatus
                if (server != null && server != desired) {
                    mutable.update { it.copy(likeStatus = server) }
                    events.post(R.string.msg_action_not_reflected)
                }
            }
        }
    }

    fun toggleSubscribe(onLogin: () -> Unit) {
        val channelId = mutable.value.info?.channel?.id ?: return
        if (session.authState.value !is AuthState.Verified) {
            events.post(R.string.msg_login_required)
            onLogin()
            return
        }
        val previous = mutable.value.subscribed ?: false
        viewModelScope.launch {
            mutable.update { it.copy(subscribeBusy = true) }
            val result = optimisticToggle(
                previous = previous,
                desired = !previous,
                apply = { v -> mutable.update { it.copy(subscribed = v) } },
                request = { v -> actions.subscribe(channelId, v) },
            )
            mutable.update { it.copy(subscribeBusy = false) }
            when (result) {
                is ToggleResult.RolledBack -> events.postError(result.error)
                is ToggleResult.Confirmed -> {
                    events.post(if (result.value) R.string.msg_subscribed else R.string.msg_unsubscribed)
                    if (!result.value) {
                        mutable.update { it.copy(notifyLevel = null) }
                    } else {
                        // The bell's requests only come with the page of a channel that is already subscribed: fetch it again.
                        val videoId = mutable.value.videoId ?: return@launch
                        reconcile(videoId) { info ->
                            val notify = info.channel?.notify
                            mutable.update { s ->
                                s.copy(
                                    info = s.info?.let { i -> i.copy(channel = i.channel?.copy(notify = notify)) },
                                    notifyLevel = notify?.current,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** Switches the channel's bell; the icon changes at once and goes back if YouTube refuses. */
    fun setNotify(level: vibe.tube.youtube.model.NotifyLevel) {
        val params = mutable.value.info?.channel?.notify?.params?.get(level) ?: return
        val previous = mutable.value.notifyLevel
        mutable.update { it.copy(notifyLevel = level) }
        viewModelScope.launch {
            runCatching { actions.setNotifyLevel(params) }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                mutable.update { it.copy(notifyLevel = previous) }
                events.postError(e)
            }
        }
    }

    private suspend fun reconcile(videoId: String, apply: (WatchInfo) -> Unit) {
        runCatching { provider.watch(actions.publicCall(), videoId, null, null) }
            .onSuccess { info -> if (mutable.value.videoId == videoId) apply(info) }
    }

    fun requestSave() {
        mutable.value.info?.let { info ->
            val current = player.state.value.current
            if (current != null) events.requestSave(current)
        }
    }
}

fun Throwable?.isLoginProblem(): Boolean = this is AppError.LoginRequired || this is AppError.SessionExpired
