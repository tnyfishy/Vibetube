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
import vibe.tube.feature.watch.CommentsUiState
import vibe.tube.feature.watch.RepliesState
import vibe.tube.youtube.YouTubeProvider
import vibe.tube.youtube.model.Comment
import vibe.tube.youtube.model.CommentSort

/**
 * Comments of the short whose comment sheet is open: first page, more pages, sorting, replies, posting. The same shape
 * (and the same screen components) as the player's comments, but not tied to the main player.
 */
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

    /** Opens the comments of [videoId]; [token] is the comments token from its watch info (null = comments are off). */
    fun open(videoId: String, token: String?, sorts: List<CommentSort>, totalText: String?) {
        if (this.videoId == videoId && firstToken == token && !mutable.value.error) return
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
        // Keeps the loaded page so reopening the same short is instant.
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

    fun toggleReplies(comment: Comment) {
        val token = comment.repliesToken ?: return
        val current = mutable.value.replies[comment.id]
        if (current != null && current.items.isNotEmpty()) {
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

    private fun fetchReplies(commentId: String, token: String, append: Boolean) {
        val gen = generation
        updateReplies(commentId) { it.copy(loading = true, expanded = true, failed = false) }
        scope.launch {
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
        val params = comment.replyParams
        if (params == null) {
            events.post(R.string.msg_comment_unavailable)
            return
        }
        scope.launch {
            runCatching { actions.postReply(params, text.trim()) }
                .onSuccess {
                    events.post(R.string.msg_reply_posted)
                    onPosted()
                    comment.repliesToken?.let { fetchReplies(comment.id, it, append = false) }
                }
                .onFailure(events::postError)
        }
    }
}
