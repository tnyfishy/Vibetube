package vibe.tube.feature.shorts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vibe.tube.R
import vibe.tube.data.repo.AccountActions
import vibe.tube.feature.common.UiEvents
import vibe.tube.feature.watch.CommentRating
import vibe.tube.feature.watch.CommentReaction
import vibe.tube.feature.watch.CommentsUiState
import vibe.tube.feature.watch.RepliesState
import vibe.tube.youtube.YouTubeProvider
import vibe.tube.youtube.model.Comment
import vibe.tube.youtube.model.CommentSort

class ShortComments(
    private val scope: CoroutineScope,
    private val provider: YouTubeProvider,
    private val actions: AccountActions,
    private val events: UiEvents,
) {
    private val mutable = MutableStateFlow(CommentsUiState(loading = true))
    val state: StateFlow<CommentsUiState> = mutable.asStateFlow()

    private var videoId: String? = null
    private var firstToken: String? = null
    private var job: Job? = null
    private var generation = 0
    private var accountKey: String? = null

    fun open(videoId: String, token: String?, sorts: List<CommentSort>, totalText: String?, authKey: String? = null) {
        if (this.videoId == videoId && firstToken == token && accountKey == authKey && !mutable.value.error) return
        accountKey = authKey
        this.videoId = videoId
        firstToken = token
        generation++
        job?.cancel()
        if (token == null) {
            mutable.value = CommentsUiState(disabledMessage = "", totalText = totalText)
            return
        }
        mutable.value = CommentsUiState(loading = true, sorts = sorts, totalText = totalText)
        load(token, reset = true)
    }

    fun close() {
        
    }

    private fun load(token: String, reset: Boolean) {
        job?.cancel()
        val gen = generation
        job = scope.launch {
            mutable.update { it.copy(loading = reset, loadingMore = !reset, error = false, loadMoreError = false) }
            try {
                val page = provider.comments(actions.publicCall(), token)
                if (gen != generation) return@launch
                mutable.update { old ->
                    val merged = if (reset) page.comments else {
                        val seen = old.comments.mapTo(HashSet()) { it.id }
                        old.comments + page.comments.filter { seen.add(it.id) }
                    }
                    old.copy(
                        comments = merged,
                        continuation = page.continuation,
                        loading = false,
                        loadingMore = false,
                        error = false,
                        disabledMessage = page.disabledMessage,
                        totalText = page.totalText ?: old.totalText,
                        createToken = page.createToken ?: old.createToken,
                        sorts = page.sorts.ifEmpty { old.sorts },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) mutable.update { it.copy(loading = false, loadingMore = false, error = reset, loadMoreError = !reset) }
            }
        }
    }

    fun loadMore() {
        val c = mutable.value
        val token = c.continuation ?: return
        if (c.loading || c.loadingMore) return
        load(token, reset = false)
    }

    fun retry() {
        firstToken?.let { load(it, reset = true) }
    }

    fun changeSort(sort: CommentSort) {
        mutable.update { it.copy(sorts = it.sorts.map { s -> s.copy(selected = s.token == sort.token) }) }
        load(sort.token, reset = true)
    }

    private fun updateReplies(commentId: String, transform: (RepliesState) -> RepliesState) {
        mutable.update { it.copy(replies = it.replies + (commentId to transform(it.replies[commentId] ?: RepliesState()))) }
    }

    private val replySlots = kotlinx.coroutines.sync.Semaphore(2)

    fun previewReplies(comment: Comment) {
        val token = comment.repliesToken ?: return
        if (mutable.value.replies.containsKey(comment.id)) return
        fetchReplies(comment.id, token, append = false, expand = false)
    }

    fun toggleReplies(comment: Comment) {
        val current = mutable.value.replies[comment.id]
        val token = comment.repliesToken
        if (token == null) {
            if (comment.previewReplies.isNotEmpty()) updateReplies(comment.id) {
                it.copy(items = comment.previewReplies, expanded = !it.expanded, previewLoaded = true)
            }
            return
        }
        if (current?.loading == true || (current != null && (current.previewLoaded || current.items.isNotEmpty()) && !current.failed)) {
            updateReplies(comment.id) { it.copy(expanded = !it.expanded) }
            return
        }
        fetchReplies(comment.id, token, append = false)
    }

    fun loadMoreReplies(commentId: String) {
        val state = mutable.value.replies[commentId] ?: return
        val token = state.continuation ?: return
        if (state.loading) return
        fetchReplies(commentId, token, append = true)
    }

    private fun fetchReplies(commentId: String, token: String, append: Boolean, expand: Boolean = true) {
        val gen = generation
        updateReplies(commentId) { it.copy(loading = true, expanded = it.expanded || expand, failed = false) }
        scope.launch {
            runCatching {
                replySlots.acquire()
                try {
                    if (gen != generation) throw CancellationException("Video changed")
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
                    if (it is CancellationException) throw it
                    if (gen == generation) updateReplies(commentId) { it.copy(loading = false, failed = true) }
                }
        }
    }

    fun commentNotice(resId: Int, icon: Int?) = events.post(vibe.tube.feature.common.UiMessage(resId, icon = icon))

    fun rateComment(comment: Comment, heart: Boolean = false, dislike: Boolean = false) {
        if (comment.id in mutable.value.busyComments) return
        val reaction = if (heart) CommentReaction.Heart else if (dislike) CommentReaction.Dislike else CommentReaction.Like
        val action = CommentRating.action(comment, reaction) ?: return
        val gen = generation
        fun replace(c: Comment) = mutable.update { old -> old.copy(
            comments = old.comments.map { if (it.id == c.id) c else it },
            replies = old.replies.mapValues { (_, replies) -> replies.copy(items = replies.items.map { if (it.id == c.id) c else it }) }) }
        replace(CommentRating.optimistic(comment, reaction))
        mutable.update { it.copy(busyComments = it.busyComments + comment.id) }
        scope.launch {
            try { actions.performCommentAction(action) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (gen == generation) { replace(comment); events.postError(e) } }
            finally { if (gen == generation) mutable.update { it.copy(busyComments = it.busyComments - comment.id) } }
        }
    }

    fun postComment(text: String, onPosted: () -> Unit) {
        val params = mutable.value.createToken
        if (params == null) {
            events.post(R.string.msg_comment_unavailable)
            return
        }
        scope.launch {
            mutable.update { it.copy(posting = true) }
            runCatching { actions.postComment(params, text.trim()) }
                .onSuccess {
                    events.post(R.string.msg_comment_posted)
                    onPosted()
                    firstToken?.let { t -> load(t, reset = true) }
                }
                .onFailure(events::postError)
            mutable.update { it.copy(posting = false) }
        }
    }

    fun postReply(comment: Comment, text: String, onPosted: () -> Unit) {
        if (comment.id in mutable.value.busyComments || text.isBlank()) return
        val gen = generation
        val params = comment.replyParams
        if (params == null) {
            events.post(R.string.msg_comment_unavailable)
            return
        }
        mutable.update { it.copy(busyComments = it.busyComments + comment.id) }
        scope.launch {
            runCatching { actions.postReply(params, text.trim()) }
                .onSuccess {
                    if (gen != generation) return@onSuccess
                    events.post(R.string.msg_reply_posted)
                    onPosted()
                    val parent = mutable.value.comments.firstOrNull { top -> top.id == comment.id || mutable.value.replies[top.id]?.items?.any { it.id == comment.id } == true }
                    parent?.repliesToken?.let { fetchReplies(parent.id, it, append = false) }
                }
                .onFailure { if (it is CancellationException) throw it; if (gen == generation) events.postError(it) }
            if (gen == generation) mutable.update { it.copy(busyComments = it.busyComments - comment.id) }
        }
    }
}
