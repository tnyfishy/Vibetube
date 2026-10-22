package vibe.tube.youtube

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vibe.tube.core.PlaybackFailure
import vibe.tube.youtube.player.MediaPlanner
import vibe.tube.youtube.player.PlanKind
import vibe.tube.youtube.player.ResolveResult
import vibe.tube.youtube.player.ResolvedMedia
import vibe.tube.youtube.player.StreamFormat
import vibe.tube.youtube.player.StreamResolver

class StreamResolverTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val resolver = StreamResolver(vibe.tube.network.InnerTubeClient(okhttp3.OkHttpClient(), json))

    private fun parse(body: String) = resolver.parse(json.parseToJsonElement(body), "vid", "TEST", "ua")

    private fun blocked(body: String) = (parse(body) as ResolveResult.Blocked).failure

    @Test
    fun botCheckIsClassifiedAsBotCheckNotAsGenericFailure() {
        assertEquals(
            PlaybackFailure.BotCheck,
            blocked("""{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you’re not a bot"}}"""),
        )
    }

    @Test
    fun otherPlayabilityReasonsMapToSpecificFailures() {
        fun status(s: String, r: String) = blocked("""{"playabilityStatus":{"status":"$s","reason":"$r"}}""")
        assertEquals(PlaybackFailure.AgeRestricted, status("LOGIN_REQUIRED", "This video may be inappropriate for some users. Sign in to confirm your age."))
        assertEquals(PlaybackFailure.Private, status("LOGIN_REQUIRED", "This is a private video."))
        assertEquals(PlaybackFailure.RegionBlocked, status("UNPLAYABLE", "The uploader has not made this video available in your country."))
        assertEquals(PlaybackFailure.Removed, status("ERROR", "This video has been removed by the uploader"))
        assertEquals(PlaybackFailure.LoginRequired, status("LOGIN_REQUIRED", "Sign in to continue"))
        assertEquals(PlaybackFailure.Unavailable, status("UNPLAYABLE", "Video unavailable"))
    }

    @Test
    fun cipherOnlyFormatsAreReportedAsUnsupportedNotAsPlayable() {
        val result = parse(
            """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[
            {"itag":137,"mimeType":"video/mp4; codecs=\"avc1.640028\"","bitrate":1,"signatureCipher":"s=abc&url=x"}]}}""",
        )
        assertEquals(PlaybackFailure.CipherUnsupported, (result as ResolveResult.Blocked).failure)
    }

    @Test
    fun drmContentIsRejected() {
        val result = parse("""{"playabilityStatus":{"status":"OK"},"streamingData":{"formats":[{"itag":18,"url":"https://x","mimeType":"video/mp4"}]},"drmFamilies":["WIDEVINE"]}""")
        assertEquals(PlaybackFailure.DrmUnsupported, (result as ResolveResult.Blocked).failure)
    }

    @Test
    fun playableResponseExposesFormatsCaptionsAndExpiry() {
        val result = parse(
            """{"playabilityStatus":{"status":"OK"},"videoDetails":{"title":"T","author":"A","lengthSeconds":"60"},
            "captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[{"baseUrl":"https://c/x?lang=vi","languageCode":"vi","name":{"simpleText":"Vietnamese"}}]}},
            "streamingData":{"expiresInSeconds":"21540","formats":[{"itag":18,"url":"https://v/18","mimeType":"video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"","bitrate":500,"height":360}],
            "adaptiveFormats":[
              {"itag":136,"url":"https://v/136","mimeType":"video/mp4; codecs=\"avc1.4d401f\"","bitrate":900,"height":720,"fps":30},
              {"itag":247,"url":"https://v/247","mimeType":"video/webm; codecs=\"vp9\"","bitrate":800,"height":720,"fps":30},
              {"itag":140,"url":"https://v/140","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":128},
              {"itag":251,"url":"https://v/251","mimeType":"audio/webm; codecs=\"opus\"","bitrate":130}]}}""",
        ) as ResolveResult.Playable
        val media = result.media
        assertEquals(1, media.muxed.size)
        assertEquals(2, media.videoOnly.size)
        assertEquals(2, media.audioOnly.size)
        assertEquals("vi", media.captions.single().languageCode)
        assertTrue(media.expiresAtMillis!! > 0)
        val plan = MediaPlanner.plan(media, null)!!
        assertEquals(PlanKind.Adaptive, plan.kind)
        assertEquals(136, plan.video!!.itag)
        assertEquals(140, plan.audio!!.itag)
    }

    private fun fmt(itag: Int, mime: String, height: Int?, bitrate: Int = 100, codecs: String? = null, track: String? = null, default: Boolean = false) =
        StreamFormat(itag, "https://v/$itag", mime, codecs, bitrate, null, height, 30, null, track, track, default, null)

    private fun media(vararg parts: Any?, live: Boolean = false, hls: String? = null): ResolvedMedia {
        val all = parts.filterIsInstance<StreamFormat>()
        return ResolvedMedia(
            "v", null, null, 10, live, hls,
            all.filter { it.mimeType.startsWith("video/") && it.itag < 100 },
            all.filter { it.mimeType.startsWith("video/") && it.itag >= 100 },
            all.filter { it.mimeType.startsWith("audio/") },
            emptyList(), null, "t", "ua", 0,
        )
    }

    @Test
    fun plannerHonoursHeightCapAndFallsBackToMuxed() {
        val m = media(
            fmt(18, "video/mp4", 360), fmt(136, "video/mp4", 720, codecs = "avc1"), fmt(137, "video/mp4", 1080, codecs = "avc1"),
            fmt(140, "audio/mp4", null, codecs = "mp4a.40.2"),
        )
        assertEquals(137, MediaPlanner.plan(m, null)!!.video!!.itag)
        assertEquals(136, MediaPlanner.plan(m, 720)!!.video!!.itag)
        assertEquals(listOf(1080, 720, 360), MediaPlanner.availableHeights(m))
        val muxedOnly = media(fmt(18, "video/mp4", 360))
        assertEquals(PlanKind.Muxed, MediaPlanner.plan(muxedOnly, 720)!!.kind)
        assertNull(MediaPlanner.plan(media(), 720))
    }

    @Test
    fun plannerHonoursFrameRateCapButFallsBackWhenOnlyHigherFpsExists() {
        fun f(itag: Int, h: Int, fps: Int) =
            StreamFormat(itag, "https://v/$itag", "video/mp4", "avc1", 100, null, h, fps, null, null, null, false, null)
        val both = media(f(298, 720, 60), f(136, 720, 30), f(299, 1080, 60), f(137, 1080, 30), fmt(140, "audio/mp4", null, codecs = "mp4a.40.2"))
        assertEquals(299, MediaPlanner.plan(both, 1080, maxFps = 60)!!.video!!.itag)
        assertEquals(137, MediaPlanner.plan(both, 1080, maxFps = 30)!!.video!!.itag)
        assertEquals(136, MediaPlanner.plan(both, 720, maxFps = 30)!!.video!!.itag)
        val only60 = media(f(299, 1080, 60), fmt(140, "audio/mp4", null, codecs = "mp4a.40.2"))
        assertEquals(299, MediaPlanner.plan(only60, 1080, maxFps = 30)!!.video!!.itag)
    }

    @Test
    fun qualityCapLabelsCoverEveryHeightAndFrameRate() {
        assertNull(vibe.tube.data.settings.QualityCap.Auto.label)
        assertEquals("1080p60", vibe.tube.data.settings.QualityCap.P1080F60.label)
        assertEquals("1080p", vibe.tube.data.settings.QualityCap.P1080.label)
        assertEquals("4320p60", vibe.tube.data.settings.QualityCap.P4320F60.label)
        assertEquals("144p", vibe.tube.data.settings.QualityCap.P144.label)
        val labels = vibe.tube.data.settings.QualityCap.entries.mapNotNull { it.label }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun plannerPrefersHlsForLiveAndSelectsRequestedAudioTrack() {
        assertEquals(PlanKind.Hls, MediaPlanner.plan(media(live = true, hls = "https://h/live.m3u8"), null)!!.kind)
        val m = media(
            fmt(137, "video/mp4", 1080, codecs = "avc1"),
            fmt(140, "audio/mp4", null, codecs = "mp4a.40.2", track = "en.4", default = true),
            fmt(141, "audio/mp4", null, codecs = "mp4a.40.2", track = "vi.3"),
        )
        assertEquals(140, MediaPlanner.plan(m, null)!!.audio!!.itag)
        assertEquals(141, MediaPlanner.plan(m, null, "vi.3")!!.audio!!.itag)
        assertEquals(2, MediaPlanner.audioTracks(m).size)
    }

    @Test
    fun plannerCanAvoidWebmAfterDecoderFailure() {
        val m = media(
            fmt(247, "video/webm", 1080, codecs = "vp9"), fmt(136, "video/mp4", 720, codecs = "avc1"),
            fmt(251, "audio/webm", null, codecs = "opus"), fmt(140, "audio/mp4", null, codecs = "mp4a.40.2"),
        )
        val plan = MediaPlanner.plan(m, null, allowWebm = false)!!
        assertEquals(136, plan.video!!.itag)
        assertEquals(140, plan.audio!!.itag)
    }
}
