package vibe.tube.data.repo

import kotlinx.coroutines.flow.Flow
import vibe.tube.core.Clock
import vibe.tube.data.db.PlaybackHistoryDao
import vibe.tube.data.db.PlaybackHistoryEntity
import vibe.tube.data.db.SearchHistoryDao
import vibe.tube.data.db.SearchHistoryEntity

class LocalHistoryRepository(
    private val playback: PlaybackHistoryDao,
    private val search: SearchHistoryDao,
    private val clock: Clock = Clock.System,
) {
    fun recentPlayback(scopeKey: String, limit: Int = 200): Flow<List<PlaybackHistoryEntity>> = playback.recent(scopeKey, limit)

    suspend fun resumePosition(scopeKey: String, videoId: String): Long? =
        playback.get(scopeKey, videoId)?.takeIf { !it.completed && it.positionMs > MIN_RESUME_MS }?.positionMs

    suspend fun recordProgress(
        scopeKey: String,
        videoId: String,
        title: String,
        channelName: String?,
        thumbnailUrl: String?,
        durationSeconds: Int?,
        positionMs: Long,
        durationMs: Long,
    ) {
        val completed = durationMs > 0 && positionMs >= durationMs - COMPLETION_TAIL_MS
        playback.put(
            PlaybackHistoryEntity(
                scopeKey = scopeKey,
                videoId = videoId,
                title = title,
                channelName = channelName,
                thumbnailUrl = thumbnailUrl,
                durationSeconds = durationSeconds,
                positionMs = if (completed) 0 else positionMs,
                lastPlayedAt = clock.nowMillis(),
                completed = completed,
            ),
        )
    }

    suspend fun removePlayback(scopeKey: String, videoId: String) = playback.delete(scopeKey, videoId)

    suspend fun clearPlayback(scopeKey: String) = playback.clearScope(scopeKey)

    fun recentSearches(scopeKey: String, limit: Int = 12): Flow<List<String>> = search.recent(scopeKey, limit)

    suspend fun recordSearch(scopeKey: String, query: String) {
        val q = query.trim()
        if (q.isNotEmpty()) search.put(SearchHistoryEntity(scopeKey, q, clock.nowMillis()))
    }

    suspend fun removeSearch(scopeKey: String, query: String) = search.delete(scopeKey, query)

    suspend fun clearSearches(scopeKey: String) = search.clearScope(scopeKey)

    suspend fun clearAccount(accountId: String) {
        playback.clearAccount(accountId)
        search.clearAccount(accountId)
    }

    companion object {
        const val MIN_RESUME_MS = 5_000L
        const val COMPLETION_TAIL_MS = 10_000L
    }
}
