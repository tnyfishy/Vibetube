package vibe.tube.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackInputTest {
    @Test fun rejectsNonfiniteAndOutOfRangeInput() {
        listOf("NaN", "Infinity", "-1", "0", "3.01", "1..2", "").forEach {
            assertNull(it, parsePlaybackNumber(it, 0.25f..3f))
        }
        assertEquals(1.25f, parsePlaybackNumber("1,25", 0.25f..3f)!!, 0f)
        assertEquals(0.25f, parsePlaybackNumber("0.25", 0.25f..3f)!!, 0f)
        assertEquals(3f, parsePlaybackNumber(" 3 ", 0.25f..3f)!!, 0f)
    }
    @Test fun remainingTimeTracksSeekSpeedAndFiniteDuration() {
        val s = PlayerUiState(durationMs = 600_000, positionMs = 120_000)
        assertEquals(8, remainingSleepMinutes(s))
        assertEquals(4, remainingSleepMinutes(s.copy(speed = 2f)))
        assertEquals(0, remainingSleepMinutes(s.copy(positionMs = 700_000)))
        assertNull(remainingSleepMinutes(s.copy(isLive = true)))
        assertNull(remainingSleepMinutes(s.copy(durationMs = 0)))
    }
}
