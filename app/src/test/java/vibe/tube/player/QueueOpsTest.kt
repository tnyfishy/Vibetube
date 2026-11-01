package vibe.tube.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vibe.tube.feature.watch.queueKeys
import vibe.tube.feature.watch.upNextRelated
import vibe.tube.youtube.model.PlaylistItem
import vibe.tube.youtube.model.VideoItem
import kotlin.random.Random

class QueueOpsTest {
    private fun v(id: String, channel: String? = null) = VideoItem(id, "t$id", "c", channel, null, emptyList(), null, null, null, null)

    private val queue = (1..10).map { v("v$it") }
    private val state = PlayerUiState(queue = queue, queueIndex = 3, current = queue[3], playlistId = "RDx", queueTitle = "Mix - x")

    @Test
    fun shuffleKeepsWhatPlayedAndThePlayingVideo() {
        val s = QueueOps.shuffle(state, Random(7))
        assertTrue(s.shuffled)
        assertEquals(queue.take(4), s.queue.take(4))
        assertEquals(3, s.queueIndex)
        assertEquals(queue.drop(4).toSet(), s.queue.drop(4).toSet())
        assertEquals(queue, s.unshuffledQueue)
    }

    @Test
    fun unshuffleReturnsToTheOwnOrderOnThePlayingVideo() {
        val shuffled = QueueOps.shuffle(state, Random(7))
        // Play on to another (shuffled) entry, then turn shuffle off.
        val moved = shuffled.copy(queueIndex = 6, current = shuffled.queue[6])
        val s = QueueOps.unshuffle(moved)
        assertFalse(s.shuffled)
        assertEquals(queue, s.queue)
        assertEquals(queue.indexOf(moved.current), s.queueIndex)
        assertNull(s.unshuffledQueue)
    }

    @Test
    fun appendedPagesSkipEntriesAlreadyQueued() {
        val s = QueueOps.append(state, listOf(v("v2"), v("v11"), v("v11"), v("v12")))
        assertEquals(queue + listOf(v("v11"), v("v12")), s.queue)
        assertEquals(3, s.queueIndex)
        // While shuffled, both orders grow.
        val sh = QueueOps.append(QueueOps.shuffle(state, Random(1)), listOf(v("v11"), v("v12")), Random(2))
        assertEquals(12, sh.queue.size)
        assertEquals(queue + listOf(v("v11"), v("v12")), sh.unshuffledQueue)
        assertEquals(12, sh.queue.map { it.id }.toSet().size)
    }

    @Test
    fun keysStayUniqueWhenAPlaylistRepeatsAVideo() {
        val keys = queueKeys(listOf(v("a"), v("b"), v("a")))
        assertEquals(3, keys.toSet().size)
        assertEquals("q:a", keys[0])
    }

    @Test
    fun upNextLeavesTheMixOutWhenThereIsEnoughElse() {
        val related = listOf(v("v1"), v("v5"), v("r1"), v("r2"), v("r3"), v("r4"), v("r5"), v("r6"), v("hidden", channel = "bad"))
        val mixCard = PlaylistItem("RDx", "Mix", null, null, null, emptyList())
        val out = upNextRelated(related + mixCard, queue, "v4", "RDx", setOf("bad"))
        assertEquals(listOf("r1", "r2", "r3", "r4", "r5", "r6"), out.map { it.id })
    }

    @Test
    fun upNextKeepsYouTubesListWhenTooFewOthersRemain() {
        val related = listOf(v("v1"), v("v5"), v("r1"), v("r2"))
        val out = upNextRelated(related, queue, "v4", "RDx", emptySet())
        assertEquals(listOf("v1", "v5", "r1", "r2"), out.map { it.id })
        // The playing video itself never shows up as "next".
        assertFalse(upNextRelated(listOf(v("v4"), v("r1")), queue, "v4", "RDx", emptySet()).any { it.id == "v4" })
    }
}
