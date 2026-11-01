package vibe.tube.youtube

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vibe.tube.core.PlaybackFailure
import vibe.tube.network.ClientProfile
import vibe.tube.network.InnerTubeClient
import vibe.tube.network.InnerTubeConfig
import vibe.tube.support.Fixtures
import vibe.tube.youtube.model.ContentLocale
import vibe.tube.youtube.player.ClientGateRegistry
import vibe.tube.youtube.player.ClientUserAgents
import vibe.tube.youtube.player.ResolveResult
import vibe.tube.youtube.player.ResolvedMedia
import vibe.tube.youtube.player.StreamDenial
import vibe.tube.youtube.player.StreamDenialKind
import vibe.tube.youtube.player.StreamDenialClassifier
import vibe.tube.youtube.player.StreamFormat
import vibe.tube.youtube.player.StreamResolver
import java.net.InetAddress

class StreamDenialClassifierTest {
    @Test
    fun passedDeadlineIsAnExpiredUrl() {
        val url = "https://rr1.googlevideo.com/videoplayback?expire=1000&c=IOS&id=1"
        assertEquals(StreamDenial.Expired, StreamDenialClassifier.classify(url, nowSeconds = 1000))
        assertEquals(StreamDenial.Expired, StreamDenialClassifier.classify(url, nowSeconds = 5000))
    }

    @Test
    fun refusalBeforeTheDeadlineBlamesTheClient() {
        val url = "https://rr1.googlevideo.com/videoplayback?expire=9000&c=ANDROID_VR&id=1"
        assertEquals(StreamDenial.Refused, StreamDenialClassifier.classify(url, nowSeconds = 1000))
    }

    @Test
    fun urlWithoutADeadlineCannotBeJudged() {
        assertEquals(StreamDenial.Unknown, StreamDenialClassifier.classify("https://rr1.googlevideo.com/videoplayback?id=1", 1000))
        assertEquals(StreamDenial.Unknown, StreamDenialClassifier.classify(null, 1000))
        assertEquals(StreamDenial.Unknown, StreamDenialClassifier.classify("https://x/videoplayback?expire=abc", 1000))
    }

    @Test
    fun mintingClientIsReadFromTheUrl() {
        assertEquals("IOS", StreamDenialClassifier.clientOf("https://rr1.googlevideo.com/videoplayback?expire=1&c=IOS"))
        assertNull(StreamDenialClassifier.clientOf("https://rr1.googlevideo.com/videoplayback?expire=1"))
        assertNull(StreamDenialClassifier.clientOf("https://rr1.googlevideo.com/videoplayback?c="))
    }
}

class ClientGateRegistryTest {
    private var now = 0L
    private val gates = ClientGateRegistry(ttlMs = 1000, clockMs = { now })

    @Test
    fun gateHoldsUntilItsTtlLapses() {
        gates.reportGated("ios")
        assertTrue(gates.isGated("IOS"))
        now = 999
        assertTrue(gates.isGated("ios"))
        now = 1000
        assertFalse(gates.isGated("IOS"))
        assertTrue(gates.gatedClients().isEmpty())
    }

    @Test
    fun blankOrMissingClientIsIgnored() {
        gates.reportGated(null)
        gates.reportGated("  ")
        assertTrue(gates.gatedClients().isEmpty())
        assertFalse(gates.isGated(null))
    }

    @Test
    fun gatedClientsMoveToTheBackWithoutDisappearing() {
        gates.reportGated("B")
        assertEquals(listOf("A", "C", "B"), gates.demoted(listOf("A", "B", "C")) { it })
        gates.reportGated("A")
        gates.reportGated("C")
        assertEquals(listOf("A", "B", "C"), gates.demoted(listOf("A", "B", "C")) { it })
    }
}

class ClientUserAgentsTest {
    @Test
    fun eachClientPresentsItsOwnIdentity() {
        assertEquals(ClientUserAgents.IOS, ClientUserAgents.forClient("ios", "fallback"))
        assertEquals(ClientUserAgents.ANDROID, ClientUserAgents.forClient("ANDROID", "fallback"))
        assertEquals(ClientUserAgents.ANDROID_VR, ClientUserAgents.forClient("ANDROID_VR", "fallback"))
        assertTrue(ClientUserAgents.forClient("VISIONOS", "fallback", "VN").endsWith("VN)"))
    }

    @Test
    fun unknownClientKeepsTheFallback() {
        assertEquals("fallback", ClientUserAgents.forClient(null, "fallback"))
        assertEquals("fallback", ClientUserAgents.forClient("UNHEARD_OF", "fallback"))
    }

    @Test
    fun theTvClientPresentsTheTizenIdentity() {
        assertEquals(ClientUserAgents.TV_TIZEN, ClientUserAgents.forClient("TVHTML5", "fallback"))
    }
}

/** The ladder's last chances: a gate remembered across videos, a fresh visitor after a bot wall, and the independent extractor. */
class StreamResolverResilienceTest {

    private lateinit var server: MockWebServer
    private lateinit var client: InnerTubeClient
    private lateinit var profiles: List<ClientProfile>
    private val locale = ContentLocale("en", "US")
    private var fallbackCalls = 0

    private val playable = """{"playabilityStatus":{"status":"OK"},"streamingData":{"formats":[
        {"itag":18,"url":"https://rr1.googlevideo.com/videoplayback?id=1","mimeType":"video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"","width":1280,"height":720,"bitrate":500000}]},
        "videoDetails":{"title":"t","author":"a","lengthSeconds":"60"}}"""
    private val botWall = """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you're not a bot"}}"""
    private val privateVideo = """{"playabilityStatus":{"status":"UNPLAYABLE","reason":"This is a private video"}}"""

    private val fallbackMedia = ResolvedMedia(
        videoId = "vid", title = "t", author = "a", durationSeconds = 60, isLive = false, hlsUrl = null,
        muxed = listOf(StreamFormat(18, "https://rr1.googlevideo.com/videoplayback?id=2", "video/mp4", "avc1", 500000, 640, 360, 30, "360p", null, null, false, null)),
        videoOnly = emptyList(), audioOnly = emptyList(), captions = emptyList(), expiresAtMillis = null,
        clientName = StreamResolver.FALLBACK_CLIENT, userAgent = "ua", skippedCipherFormats = 0,
    )

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).dns { listOf(InetAddress.getByName("127.0.0.1")) }.build()
        client = InnerTubeClient(http, Fixtures.json, { 1_700_000_000_000L }, InnerTubeConfig(maxAttempts = 1, backoffMillis = 1))
        val base = "http://www.youtube.com:${server.port}/"
        profiles = listOf("FIRST", "SECOND").map { ClientProfile(name = it, version = "1", nameId = "1", userAgent = "ua-$it", baseUrl = base) }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun resolver(withFallback: Boolean = true, gates: ClientGateRegistry = ClientGateRegistry(clockMs = { 1_700_000_000_000L })) =
        StreamResolver(
            client, { 1_700_000_000_000L }, profiles, profileTimeoutMs = 500, gates = gates,
            fallback = if (withFallback) { _, _ -> fallbackCalls++; fallbackMedia } else null,
        )

    private fun json(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun gatedClientIsTriedLast() = runBlocking {
        val gates = ClientGateRegistry(clockMs = { 1_700_000_000_000L }).apply { reportGated("FIRST") }
        server.enqueue(json(playable))
        val result = resolver(gates = gates).resolve("vid", locale, null)
        assertEquals("SECOND", (result as ResolveResult.Playable).media.clientName)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun gatedClientIsStillTriedWhenNothingElseWorks() = runBlocking {
        val gates = ClientGateRegistry(clockMs = { 1_700_000_000_000L }).apply { reportGated("FIRST"); reportGated("SECOND") }
        server.enqueue(json(playable))
        val result = resolver(gates = gates).resolve("vid", locale, null)
        assertTrue(result is ResolveResult.Playable)
    }

    @Test
    fun botWallOnEveryClientIsRetriedOnceWithAFreshVisitor() = runBlocking {
        server.enqueue(json(botWall))
        server.enqueue(json(botWall))
        server.enqueue(json(playable))
        val result = resolver().resolve("vid", locale, null)
        assertTrue("got $result", result is ResolveResult.Playable)
        assertEquals(3, server.requestCount)
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun persistentBotWallFallsBackToTheExtractor() = runBlocking {
        repeat(4) { server.enqueue(json(botWall)) }
        val result = resolver().resolve("vid", locale, null)
        assertEquals(StreamResolver.FALLBACK_CLIENT, (result as ResolveResult.Playable).media.clientName)
        assertEquals(4, server.requestCount)
        assertEquals(1, fallbackCalls)
    }

    @Test
    fun botWallIsReportedWhenThereIsNoFallback() = runBlocking {
        repeat(4) { server.enqueue(json(botWall)) }
        val result = resolver(withFallback = false).resolve("vid", locale, null)
        assertEquals(PlaybackFailure.BotCheck, (result as ResolveResult.Blocked).failure)
    }

    @Test
    fun definiteFailureNeverReachesTheExtractor() = runBlocking {
        server.enqueue(json(privateVideo))
        val result = resolver().resolve("vid", locale, null)
        assertEquals(PlaybackFailure.Private, (result as ResolveResult.Blocked).failure)
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun extractorThatAlreadyFailedIsNotAskedAgain() = runBlocking {
        repeat(4) { server.enqueue(json(botWall)) }
        val result = resolver().resolve("vid", locale, null, skipClients = setOf(StreamResolver.FALLBACK_CLIENT))
        assertTrue(result is ResolveResult.Blocked)
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun extractorThatFindsNothingLeavesTheOriginalVerdict() = runBlocking {
        repeat(4) { server.enqueue(json(botWall)) }
        val empty = StreamResolver(
            client, { 1_700_000_000_000L }, profiles, profileTimeoutMs = 500,
            fallback = { _, _ -> fallbackMedia.copy(muxed = emptyList()) },
        )
        val result = empty.resolve("vid", locale, null)
        assertEquals(PlaybackFailure.BotCheck, (result as ResolveResult.Blocked).failure)
    }
}

class StreamDenialKindTest {
    private val now = 1_000L

    @Test
    fun anExpiredUrlIsExpiredWhateverItCarried() {
        assertEquals(StreamDenialKind.UrlExpired, StreamDenialClassifier.kind("https://x/videoplayback?expire=900&pot=abc", now))
        assertEquals(StreamDenialKind.UrlExpired, StreamDenialClassifier.kind("https://x/videoplayback?expire=1020", now))
    }

    @Test
    fun aLiveUrlWithoutATokenIsAttestationGated() {
        assertEquals(StreamDenialKind.AttestationGated, StreamDenialClassifier.kind("https://x/videoplayback?expire=5000&c=VISIONOS", now))
    }

    @Test
    fun aLiveUrlThatCarriedATokenHadItRejected() {
        assertEquals(StreamDenialKind.TokenRejected, StreamDenialClassifier.kind("https://x/videoplayback?expire=5000&pot=abc&c=IOS", now))
    }

    @Test
    fun aTokenRefusalDemotesOnlyOnTheSecondStrike() {
        val gates = ClientGateRegistry(clockMs = { 1_000_000L })
        val url = "https://x/videoplayback?expire=5000&pot=abc&c=IOS"
        assertEquals(StreamDenialKind.TokenRejected, gates.reportDenied(url))
        assertFalse(gates.isGated("IOS"))
        gates.reportDenied(url)
        assertTrue(gates.isGated("IOS"))
    }

    @Test
    fun anAttestationGateDemotesAtOnce() {
        val gates = ClientGateRegistry(clockMs = { 1_000_000L })
        gates.reportDenied("https://x/videoplayback?expire=5000&c=ANDROID_VR")
        assertTrue(gates.isGated("ANDROID_VR"))
    }

    @Test
    fun aVisitorWalledClientReportsToTheCallback() {
        val gates = ClientGateRegistry(clockMs = { 1_000_000L })
        var seen: String? = null
        gates.onGated = { seen = it }
        gates.reportGated("visionos")
        assertEquals("VISIONOS", seen)
    }
}
