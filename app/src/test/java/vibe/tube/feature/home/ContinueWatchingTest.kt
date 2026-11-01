package vibe.tube.feature.home

import org.junit.Assert.*
import org.junit.Test
import vibe.tube.data.db.PlaybackHistoryEntity

class ContinueWatchingTest {
    private fun entry(id: String, at: Long, position: Long = 15_000, done: Boolean = false) =
        PlaybackHistoryEntity("guest", id, id, null, null, 100, position, at, done)

    @Test fun onlyRecentUnfinishedVideosAppearOnceAndTheShelfStopsAtFive() {
        val result = recentUnfinished((0..14).map { entry("v$it", it.toLong()) } +
            entry("completed", 1000, done = true) + entry("just-opened", 2000, position = 0) + entry("v14", 100))
        assertEquals(5, result.size)
        assertEquals("v14", result.first().videoId)
        assertEquals(5, result.map { it.videoId }.distinct().size)
        assertTrue(result.none { it.completed || it.positionMs < 5000 })
    }
}
