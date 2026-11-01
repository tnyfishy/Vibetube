package vibe.tube.youtube

import org.junit.Assert.*
import org.junit.Test
import vibe.tube.youtube.player.MediaPlanner
import vibe.tube.youtube.player.StreamFormat
import vibe.tube.data.settings.AudioQuality

class AudioQualityTest {
    private fun audio(id: Int, bitrate: Int, tier: String, track: String = "vi") = StreamFormat(id, "https://a/$id", "audio/mp4", "mp4a", bitrate, null, null, null, null, track, track, track == "vi", null, tier)
    @Test fun qualitySelectionStaysInsideTheSelectedLanguageAndAvoidsDrc() {
        val low = audio(1, 48_000, "AUDIO_QUALITY_LOW")
        val medium = audio(2, 128_000, "AUDIO_QUALITY_MEDIUM")
        val high = audio(3, 256_000, "AUDIO_QUALITY_HIGH")
        val pool = listOf(high.copy(itag = 4, isDrc = true), low, high, medium, audio(5, 320_000, "AUDIO_QUALITY_HIGH", "en"))
        assertEquals(3, MediaPlanner.pickAudio(pool, "vi", AudioQuality.Highest)!!.itag)
        assertEquals(2, MediaPlanner.pickAudio(pool, "vi", AudioQuality.Medium)!!.itag)
        assertEquals(1, MediaPlanner.pickAudio(pool, "vi", AudioQuality.Lowest)!!.itag)
        assertEquals(5, MediaPlanner.pickAudio(pool, "en", AudioQuality.Lowest)!!.itag)
    }
    @Test fun oneAvailableSourceIsValidAtEveryQuality() {
        val only = audio(1, 128_000, "AUDIO_QUALITY_MEDIUM")
        AudioQuality.entries.forEach { assertEquals(only, MediaPlanner.pickAudio(listOf(only), null, it)) }
    }
}
