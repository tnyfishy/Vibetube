package vibe.tube.youtube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vibe.tube.player.sabr.integration.SabrFormat
import vibe.tube.player.sabr.integration.SabrOffer
import vibe.tube.youtube.player.MediaPlanner
import vibe.tube.youtube.player.PlanKind
import vibe.tube.youtube.player.PrerollWait
import vibe.tube.youtube.player.ResolvedMedia
import vibe.tube.youtube.player.SignatureCipher
import vibe.tube.youtube.player.StreamFormat

class SignatureCipherTest {
    @Test
    fun parsesTheUrlSignatureAndParameter() {
        val cipher = SignatureCipher.parse("s=abc%3D&sp=sig&url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback%3Fid%3D1")!!
        assertEquals("abc=", cipher.signature)
        assertEquals("sig", cipher.parameter)
        assertEquals("https://rr1.googlevideo.com/videoplayback?id=1&sig=solved", cipher.signedUrl("solved"))
    }

    @Test
    fun theParameterDefaultsToSignature() {
        assertEquals("signature", SignatureCipher.parse("s=x&url=https%3A%2F%2Fa%2Fb")!!.parameter)
    }

    @Test
    fun aCipherMissingItsSignatureOrUrlIsRejected() {
        assertNull(SignatureCipher.parse("url=https%3A%2F%2Fa%2Fb"))
        assertNull(SignatureCipher.parse("s=x"))
        assertNull(SignatureCipher.parse(null))
    }
}

class PrerollWaitTest {
    private fun placement(kind: String, skipMs: String?): JsonObject = buildJsonObject {
        put("adPlacementRenderer", buildJsonObject {
            put("config", buildJsonObject { put("adPlacementConfig", buildJsonObject { put("kind", kind) }) })
            put("renderer", buildJsonObject {
                put("instreamVideoAdRenderer", buildJsonObject { if (skipMs != null) put("skipOffsetMilliseconds", skipMs) })
            })
        })
    }

    @Test
    fun noAdsMeansNoWait() {
        assertEquals(0L, PrerollWait.of(null))
        assertEquals(0L, PrerollWait.of(JsonArray(emptyList())))
    }

    @Test
    fun aStartAdWaitsMostOfItsSkipOffset() {
        val ads = buildJsonArray { add(placement("AD_PLACEMENT_KIND_START", "5000")) }
        assertEquals(4000L, PrerollWait.of(ads))
    }

    @Test
    fun theWaitIsBounded() {
        val ads = buildJsonArray { add(placement("AD_PLACEMENT_KIND_START", "60000")) }
        assertEquals(PrerollWait.MAX_WAIT_MS, PrerollWait.of(ads))
    }

    @Test
    fun midRollsAreIgnored() {
        val ads = buildJsonArray { add(placement("AD_PLACEMENT_KIND_MILLISECONDS", "5000")) }
        assertEquals(0L, PrerollWait.of(ads))
    }
}

class SabrPlanningTest {
    private fun sabrFormat(itag: Int, height: Int, mime: String, bitrate: Int = 1_000_000, audio: Boolean = false) = SabrFormat(
        itag = itag, lastModified = 1L, xtags = "", mimeType = mime, bitrate = bitrate, height = height,
        audioTrackId = null, isAutoDubbed = false, isAudio = audio,
    )

    private val offer = SabrOffer(
        streamingUrl = "https://rr1.googlevideo.com/videoplayback?sabr=1", ustreamerConfig = byteArrayOf(1), poToken = "pot",
        visitorId = "visitor", cpn = "cpn", clientNameId = 2, clientVersion = "1", clientUserAgent = "ua", durationMs = 60_000,
        formats = listOf(
            sabrFormat(137, 1080, "video/mp4; codecs=\"avc1.640028\""),
            sabrFormat(136, 720, "video/mp4; codecs=\"avc1.4d401f\""),
            sabrFormat(135, 480, "video/mp4; codecs=\"avc1.4d401e\""),
            sabrFormat(140, 0, "audio/mp4; codecs=\"mp4a.40.2\"", audio = true),
            sabrFormat(251, 0, "audio/webm; codecs=\"opus\"", audio = true),
        ),
    )

    private fun directMedia(height: Int?, sabr: SabrOffer?): ResolvedMedia {
        val muxed = height?.let {
            listOf(StreamFormat(18, "https://rr1.googlevideo.com/videoplayback?id=1", "video/mp4", "avc1", 500_000, 640, it, 30, "${it}p", null, null, false, null))
        }.orEmpty()
        return ResolvedMedia(
            videoId = "v", title = "t", author = "a", durationSeconds = 60, isLive = false, hlsUrl = null, muxed = muxed,
            videoOnly = emptyList(), audioOnly = emptyList(), captions = emptyList(), expiresAtMillis = null,
            clientName = "VISIONOS", userAgent = "ua", skippedCipherFormats = 0, sabr = sabr,
        )
    }

    @Test
    fun theOfferPicksTheHeightUnderTheCap() {
        assertEquals(720, offer.select(720)!!.videoHeight)
        assertEquals(1080, offer.select(null)!!.videoHeight)
        assertEquals(480, offer.select(500)!!.videoHeight)
    }

    @Test
    fun anH264VideoGetsMp4Audio() {
        assertTrue(offer.select(1080)!!.audioMimeType.contains("mp4"))
    }

    @Test
    fun sabrWinsOnlyWhenItReachesHigherThanTheDirectLadder() {
        val plan = MediaPlanner.plan(directMedia(360, offer), maxHeight = null, allowSabr = true)
        assertEquals(PlanKind.Sabr, plan!!.kind)
        assertEquals(1080, plan.height)
        assertNotNull(plan.sabr)
    }

    @Test
    fun aDirectStreamAtTheCapIsNeverReplacedBySabr() {
        val plan = MediaPlanner.plan(directMedia(720, offer), maxHeight = 720, allowSabr = true)
        assertEquals(PlanKind.Muxed, plan!!.kind)
    }

    @Test
    fun sabrIsTheOnlyPlanWhenNothingDirectExists() {
        val plan = MediaPlanner.plan(directMedia(null, offer), maxHeight = 720, allowSabr = true)
        assertEquals(PlanKind.Sabr, plan!!.kind)
        assertEquals(720, plan.height)
    }

    @Test
    fun sabrIsOffUnlessAsked() {
        val plan = MediaPlanner.plan(directMedia(360, offer), maxHeight = null)
        assertEquals(PlanKind.Muxed, plan!!.kind)
    }

    @Test
    fun theQualityMenuListsSabrHeights() {
        assertEquals(listOf(1080, 720, 480, 360), MediaPlanner.availableHeights(directMedia(360, offer)))
    }
}
