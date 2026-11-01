package vibe.tube.player

import org.junit.Assert.*
import org.junit.Test
import vibe.tube.support.video

class NextPlaybackPolicyTest {
    private val first = video("first000001")
    private val second = video("second00002")
    private val suggested = video("third000003")
    private val state = PlayerUiState(current = first, queue = listOf(first, second), queueIndex = 0)
    @Test fun autoplayOffDoesNotAccidentallyPlayAPreloadedSingleVideoSuccessor() {
        assertNull(NextPlaybackPolicy.next(state, false, suggested))
        assertEquals(second, NextPlaybackPolicy.next(state, true, suggested))
        assertEquals(second, NextPlaybackPolicy.next(state.copy(playlistId = "RDmix"), false, suggested))
    }
    @Test fun sleepAndRepeatOneAndSectionLoopPreventStaging() {
        assertNull(NextPlaybackPolicy.next(state.copy(sleepAtEnd = true), true, suggested))
        assertNull(NextPlaybackPolicy.next(state.copy(repeatMode = RepeatMode.One), true, suggested))
        assertNull(NextPlaybackPolicy.next(state.copy(loopStartMs = 0, loopEndMs = 10_000), true, suggested))
    }
    @Test fun repeatAllWrapsAndSuggestionsCannotRepeatCurrent() {
        val last = state.copy(current = second, queueIndex = 1, repeatMode = RepeatMode.All)
        assertEquals(first, NextPlaybackPolicy.next(last, false, null))
        assertEquals(second, NextPlaybackPolicy.next(state.copy(repeatMode = RepeatMode.All), false, null))
        assertNull(NextPlaybackPolicy.next(state.copy(queue = listOf(first)), true, first))
    }
}
