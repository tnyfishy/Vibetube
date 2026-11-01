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
    val previewLoaded: Boolean = false,
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
    val featured: Comment? = null,
    val busyComments: Set<String> = emptySet(),
    val replies: Map<String, RepliesState> = emptyMap(),
)

data class WatchUiState(
    val videoId: String? = null,
    val loading: Boolean = false,
    val error: Throwable? = null,
    val info: WatchInfo? = null,
    val likeStatus: LikeStatus? = null,
                                                                                                                   
    val likeBaseline: LikeStatus? = null,
                                                                                                     
    val votes: vibe.tube.data.repo.Votes? = null,
    val subscribed: Boolean? = null,
                                                                         
    val notifyLevel: vibe.tube.youtube.model.NotifyLevel? = null,
    val likeBusy: Boolean = false,
    val subscribeBusy: Boolean = false,
    val related: List<FeedItem> = emptyList(),
    val relatedContinuation: String? = null,
    val relatedLoading: Boolean = false,
    val comments: CommentsUiState = CommentsUiState(),
    val descriptionOpen: Boolean = false,
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
                        if (vibe.tube.youtube.model.isVideoId(id)) {
                            load(id, playlistId, index.takeIf { it >= 0 })
                        } else {
                            
                            loadJob?.cancel()
                            commentsJob?.cancel()
                            generation++
                            mutable.value = WatchUiState(videoId = id, loading = true)
                        }
                    }
                }
        }
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(session.context, session.authState) { ctx, auth -> ctx?.scopeKey to (auth is AuthState.Verified) }.distinctUntilChanged().collect {
                val id = mutable.value.videoId
                if (id != null && vibe.tube.youtube.model.isVideoId(id)) load(id, player.state.value.playlistId, null)
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
                val info = fetchWatch(videoId, playlistId, index)
                if (gen != generation) return@launch
                mutable.update {
                    it.copy(
                        loading = false,
                        info = info,
                        comments = it.comments.copy(totalText = info.commentsCountText),
                        likeStatus = info.likeStatus,
                        likeBaseline = info.likeStatus,
                        subscribed = info.channel?.subscribed,
                        notifyLevel = info.channel?.notify?.current,
                        related = info.related.withoutShorts(),
                        relatedContinuation = info.relatedContinuation,
                    )
                }
                viewModelScope.launch {
                    val v = voteCounts.get(videoId)
                    if (v != null && gen == generation) mutable.update { it.copy(votes = v) }
                }
                val channel = info.channel
                val channelId = channel?.id ?: player.state.value.current?.channelId
                if (channelId != null && (channel?.name.isNullOrBlank() || channel?.subscriberText.isNullOrBlank())) viewModelScope.launch {
                    try {
                        val detail = provider.channel(actions.publicCall(), channelId)
                        if (gen == generation) mutable.update { current -> current.copy(info = current.info?.copy(channel = (current.info?.channel ?: vibe.tube.youtube.model.ChannelRef(channelId, detail.name, detail.avatar, null, null)).copy(name = detail.name, subscriberText = detail.subscriberText, avatar = detail.avatar, handle = detail.handle, badge = detail.badge))) }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
                }
                player.enrichCurrent(info)
                player.offerAutoplay(info.autoplayVideoId?.let { autoplayItem(info) }, info.videoId)
                info.commentsToken?.let { loadComments(it, gen, reset = true) }
                    ?: mutable.update { it.copy(comments = CommentsUiState(disabledMessage = "")) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) mutable.update { it.copy(loading = false, error = e) }
            }
        }
    }

    private suspend fun fetchWatch(videoId: String, playlistId: String?, index: Int?): WatchInfo {
        try {
            return provider.watch(actions.publicCall(), videoId, playlistId, index)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            kotlinx.coroutines.delay(400)
            return try {
                provider.watch(actions.publicCall(), videoId, playlistId, index)
            } catch (e2: kotlinx.coroutines.CancellationException) {
                throw e2
            } catch (e2: Exception) {
                if (playlistId == null && index == null) throw e2
                provider.watch(actions.publicCall(), videoId, null, null)
            }
        }
    }

    private fun autoplayItem(info: WatchInfo): VideoItem? {
        val id = info.autoplayVideoId ?: return null
        val longVideos = info.related.filterIsInstance<VideoItem>().filterNot { it.isShort }
        return longVideos.firstOrNull { it.id == id } ?: longVideos.firstOrNull()
    }

    suspend fun locateComment(commentId: String) {
        if (kotlinx.coroutines.withTimeoutOrNull(25_000) { locateCommentPages(commentId); true } == null) events.post(R.string.comment_target_missing)
    }

    private suspend fun locateCommentPages(commentId: String) {
        val gen = generation
        val videoId = mutable.value.videoId ?: return
        val visited = HashSet<String>()
        var pages = 0
        while (gen == generation && videoId == mutable.value.videoId && pages++ < 20) {
            val state = mutable.value.comments
            if (state.loading || state.loadingMore) { kotlinx.coroutines.delay(100); pages--; continue }
            val parentId = commentId.substringBefore('.')
            val parent = state.comments.firstOrNull { it.id == commentId || it.id == parentId }
            if (parent != null) {
                if (parent.id == commentId) return
                var token = parent.repliesToken ?: break
                var more = 0
                val seenTokens = HashSet<String>()
                while (gen == generation && more++ < 20 && seenTokens.add(token)) {
                    val page = try { provider.comments(actions.publicCall(), token) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { break }
                    if (gen != generation) return
                    updateReplies(parent.id) { old -> old.copy(expanded = true, items = (old.items + page.comments).distinctBy { it.id }, continuation = page.continuation, loading = false) }
                    if (page.comments.any { it.id == commentId }) return
                    token = page.continuation ?: break
                }
                break
            }
            val token = state.continuation ?: break
            if (!visited.add(token)) break
            val page = try { provider.comments(actions.publicCall(), token) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { break }
            if (gen != generation) return
            mutable.update { old -> old.copy(comments = old.comments.copy(comments = (old.comments.comments + page.comments).distinctBy { it.id }, continuation = page.continuation)) }
        }
        if (gen == generation) events.post(R.string.comment_target_missing)
    }

    fun commentNotice(resId: Int, icon: Int?) = events.post(vibe.tube.feature.common.UiMessage(resId, icon = icon))

    fun rateComment(comment: Comment, heart: Boolean = false, dislike: Boolean = false) {
        if (authState.value !is AuthState.Verified) { events.post(R.string.msg_login_required); return }
        if (comment.id in mutable.value.comments.busyComments) return
        val reaction = if (heart) CommentReaction.Heart else if (dislike) CommentReaction.Dislike else CommentReaction.Like
        val token = CommentRating.action(comment, reaction)
        if (token == null) { events.post(R.string.msg_comment_unavailable); return }
        if (authState.value !is AuthState.Verified) { events.post(R.string.comment_sign_in_to_comment); return }
        val gen = generation
        val previous = comment
        val next = CommentRating.optimistic(comment, reaction)
        replaceComment(next)
        mutable.update { it.copy(comments = it.comments.copy(busyComments = it.comments.busyComments + comment.id)) }
        viewModelScope.launch {
            try { actions.performCommentAction(token) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                if (gen == generation) { replaceComment(previous); events.postError(e) }
            } finally {
                if (gen == generation) mutable.update { it.copy(comments = it.comments.copy(busyComments = it.comments.busyComments - comment.id)) }
            }
        }
    }

    private fun replaceComment(c: Comment) = mutable.update { s ->
        s.copy(comments = s.comments.copy(
            comments = s.comments.comments.map { if (it.id == c.id) c else it },
            replies = s.comments.replies.mapValues { (_, replies) -> replies.copy(items = replies.items.map { if (it.id == c.id) c else it }) },
            featured = if (s.comments.featured?.id == c.id) c else s.comments.featured,
        ))
    }

    fun toggleDescription() = mutable.update { it.copy(descriptionOpen = !it.descriptionOpen) }

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
                        cur.copy(related = cur.related + page.items.withoutShorts().filter { seen.add(it.id) }, relatedContinuation = page.continuation, relatedLoading = false)
                    }
                }
                .onFailure { if (gen == generation) mutable.update { it.copy(relatedLoading = false) } }
        }
    }

    private fun loadComments(token: String, gen: Int, reset: Boolean) {
        commentsJob?.cancel()
        commentsJob = viewModelScope.launch {
            mutable.update { it.copy(comments = if (reset) CommentsUiState(loading = true, sorts = it.comments.sorts, featured = it.comments.featured) else it.comments.copy(loadingMore = true, loadMoreError = false)) }
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
                            featured = old.featured ?: page.comments.filter { it.text.isNotBlank() }.randomOrNull(),
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

    private val replySlots = kotlinx.coroutines.sync.Semaphore(2)

    fun previewReplies(comment: Comment) {
        val token = comment.repliesToken ?: return
        if (mutable.value.comments.replies.containsKey(comment.id)) return
        fetchReplies(comment.id, token, append = false, expand = false)
    }

    fun toggleReplies(comment: Comment) {
        val current = mutable.value.comments.replies[comment.id]
        val token = comment.repliesToken
        if (token == null) {
            if (comment.previewReplies.isNotEmpty()) updateReplies(comment.id) {
                it.copy(items = comment.previewReplies, expanded = !it.expanded, previewLoaded = true)
            }
            return
        }
        if (current?.loading == true) {
            updateReplies(comment.id) { it.copy(expanded = !it.expanded) }
            return
        }
        if (current != null && (current.previewLoaded || current.items.isNotEmpty()) && !current.failed) {
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

    private fun fetchReplies(commentId: String, token: String, append: Boolean, expand: Boolean = true) {
        val gen = generation
        updateReplies(commentId) { it.copy(loading = true, expanded = it.expanded || expand, failed = false) }
        viewModelScope.launch {
            runCatching {
                replySlots.acquire()
                try {
                    if (gen != generation) throw kotlinx.coroutines.CancellationException("Video changed")
                    provider.comments(actions.publicCall(), token)
                } finally { replySlots.release() }
            }
                .onSuccess { page ->
                    if (gen != generation) return@onSuccess
                    updateReplies(commentId) {
                        val seen = it.items.mapTo(HashSet()) { c -> c.id }
                        it.copy(
                            items = if (append) it.items + page.comments.filter { c -> seen.add(c.id) } else page.comments,
                            continuation = page.continuation,
                            loading = false,
                            previewLoaded = true,
                        )
                    }
                }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    if (gen == generation) updateReplies(commentId) { it.copy(loading = false, failed = true) }
                }
        }
    }

    fun postComment(text: String, onPosted: () -> Unit) {
        if (mutable.value.comments.posting) return
        val gen = generation
        val c = mutable.value.comments
        val params = c.createToken
        if (params == null) {
            events.post(R.string.msg_comment_unavailable)
            return
        }
        mutable.update { it.copy(comments = it.comments.copy(posting = true)) }
        viewModelScope.launch {
            runCatching { actions.postComment(params, text.trim()) }
                .onSuccess {
                    if (gen != generation) return@onSuccess
                    events.post(R.string.msg_comment_posted)
                    onPosted()
                    mutable.value.info?.commentsToken?.let { t -> loadComments(t, generation, reset = true) }
                }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; events.postError(it) }
            if (gen == generation) mutable.update { it.copy(comments = it.comments.copy(posting = false)) }
        }
    }

    fun postReply(comment: Comment, text: String, onPosted: () -> Unit) {
        if (comment.id in mutable.value.comments.busyComments) return
        val gen = generation
        if (text.isBlank()) return
        if (authState.value !is AuthState.Verified) { events.post(R.string.comment_sign_in_to_comment); return }
        mutable.update { it.copy(comments = it.comments.copy(busyComments = it.comments.busyComments + comment.id)) }
        viewModelScope.launch {
            runCatching {
                var params = comment.replyParams
                if (params == null) {
                    val first = mutable.value.info?.commentsToken
                    if (first != null) {
                        val page = provider.comments(actions.publicCall(), first)
                        if (gen != generation) return@launch
                        params = page.comments.firstOrNull { it.id == comment.id }?.replyParams
                    }
                }
                if (params == null) throw vibe.tube.core.AppError.Unsupported("reply_token_unavailable")
                actions.postReply(params!!, text.trim())
            }
                .onSuccess {
                    if (gen != generation) return@onSuccess
                    events.post(R.string.msg_reply_posted)
                    onPosted()
                    val parent = mutable.value.comments.comments.firstOrNull { top -> top.id == comment.id || mutable.value.comments.replies[top.id]?.items?.any { it.id == comment.id } == true }
                    parent?.repliesToken?.let { fetchReplies(parent.id, it, append = false) }
                }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; events.postError(it) }
            if (gen == generation) mutable.update { it.copy(comments = it.comments.copy(busyComments = it.comments.busyComments - comment.id)) }
        }
    }

    fun toggleLike(onLogin: () -> Unit) = rate(LikeStatus.Like, onLogin)

    fun toggleDislike(onLogin: () -> Unit) = rate(LikeStatus.Dislike, onLogin)

    private fun rate(tapped: LikeStatus, onLogin: () -> Unit) {
        val id = mutable.value.videoId ?: return
        if (session.authState.value !is AuthState.Verified) {
            events.post(R.string.msg_login_required)
            return
        }
        if (mutable.value.likeBusy) return
        val gen = generation
        val previous = mutable.value.likeStatus ?: LikeStatus.Indifferent
        val desired = if (previous == tapped) LikeStatus.Indifferent else tapped
        mutable.update { it.copy(likeStatus = desired, likeBusy = true) }
        viewModelScope.launch {
            val failure = runCatching { actions.rate(id, desired) }.exceptionOrNull()
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            if (gen != generation) return@launch
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
        if (mutable.value.subscribeBusy) return
        val gen = generation
        val previous = mutable.value.subscribed ?: false
        mutable.update { it.copy(subscribeBusy = true) }
        viewModelScope.launch {
            val result = optimisticToggle(
                previous = previous,
                desired = !previous,
                apply = { v -> if (gen == generation) mutable.update { it.copy(subscribed = v) } },
                request = { v -> actions.subscribe(channelId, v) },
            )
            if (gen != generation) return@launch
            mutable.update { it.copy(subscribeBusy = false) }
            when (result) {
                is ToggleResult.RolledBack -> events.postError(result.error)
                is ToggleResult.Confirmed -> {
                    events.post(if (result.value) R.string.msg_subscribed else R.string.msg_unsubscribed)
                    if (!result.value) {
                        mutable.update { it.copy(notifyLevel = null) }
                    } else {
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

    fun setNotify(level: vibe.tube.youtube.model.NotifyLevel) {
        val params = mutable.value.info?.channel?.notify?.params?.get(level) ?: return
        val gen = generation
        val previous = mutable.value.notifyLevel
        mutable.update { it.copy(notifyLevel = level) }
        viewModelScope.launch {
            runCatching { actions.setNotifyLevel(params) }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (gen != generation) return@onFailure
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

private fun List<FeedItem>.withoutShorts(): List<FeedItem> = filterNot { it is VideoItem && it.isShort }

fun Throwable?.isLoginProblem(): Boolean = this is AppError.LoginRequired || this is AppError.SessionExpired
