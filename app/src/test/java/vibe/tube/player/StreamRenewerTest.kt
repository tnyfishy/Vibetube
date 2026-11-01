package vibe.tube.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import vibe.tube.youtube.player.ResolveResult
import vibe.tube.youtube.player.ResolvedMedia
import vibe.tube.youtube.player.StreamFormat
import java.util.concurrent.atomic.AtomicInteger

class StreamRenewerTest {
    private fun fmt(itag: Int, mime: String, client: String, clen: Long) = StreamFormat(
        itag, "https://rr1.googlevideo.com/videoplayback?itag=$itag&clen=$clen&c=$client", mime, null, 1, null, null, null, null, null, null, false, null,
    )

    private fun media(client: String, clen: Long = 1000) = ResolvedMedia(
        "v", null, null, null, false, null, emptyList(),
        listOf(fmt(137, "video/mp4", client, clen)), listOf(fmt(140, "audio/mp4", client, clen)),
        emptyList(), expiresAtMillis = null, clientName = client, userAgent = "ua", skippedCipherFormats = 0,
    )

    private val refusedVideo = "https://rr1.googlevideo.com/videoplayback?itag=137&clen=1000&c=IOS"
    private val refusedAudio = "https://rr1.googlevideo.com/videoplayback?itag=140&clen=1000&c=IOS"

    @Test fun renewsTheSameFileFromAnotherClientAndSharesOneResolve() {
        val calls = AtomicInteger()
        val skipped = mutableListOf<Set<String>>()
        val reported = mutableListOf<String>()
        val renewer = StreamRenewer(
            resolve = { _, _, skip -> calls.incrementAndGet(); skipped += skip; ResolveResult.Playable(media("VISIONOS")) },
            reportRefused = { reported += it },
        )
        assertEquals("https://rr1.googlevideo.com/videoplayback?itag=137&clen=1000&c=VISIONOS", renewer.renew("v", refusedVideo))
        assertEquals("https://rr1.googlevideo.com/videoplayback?itag=140&clen=1000&c=VISIONOS", renewer.renew("v", refusedAudio))
        assertEquals(1, calls.get())
        assertEquals(listOf(setOf("IOS")), skipped)
        assertEquals(listOf("IOS"), reported)
    }

    @Test fun differentFileLengthIsNotAReplacement() {
        val renewer = StreamRenewer(resolve = { _, _, _ -> ResolveResult.Playable(media("VISIONOS", clen = 2222)) }, reportRefused = {})
        assertNull(renewer.renew("v", refusedVideo))
    }

    @Test fun failedResolveGivesNoReplacement() {
        val renewer = StreamRenewer(resolve = { _, _, _ -> throw IllegalStateException("boom") }, reportRefused = {})
        assertNull(renewer.renew("v", refusedVideo))
    }

    @Test fun sameClientAnswerIsNotReusedAsAReplacement() {
        val renewer = StreamRenewer(resolve = { _, _, _ -> ResolveResult.Playable(media("IOS")) }, reportRefused = {})
        // The "new" URL equals the refused one, so there is nothing to swap to.
        assertNull(renewer.renew("v", refusedVideo))
    }
}
