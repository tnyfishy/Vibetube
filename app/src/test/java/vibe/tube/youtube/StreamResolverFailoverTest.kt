package vibe.tube.youtube

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vibe.tube.core.AppError
import vibe.tube.network.ClientProfile
import vibe.tube.network.InnerTubeClient
import vibe.tube.network.InnerTubeConfig
import vibe.tube.support.Fixtures
import vibe.tube.youtube.model.ContentLocale
import vibe.tube.youtube.player.ResolveResult
import vibe.tube.youtube.player.StreamResolver
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** One client misbehaving (slow, garbled) must not sink the whole request: the next client gets its turn. */
class StreamResolverFailoverTest {

    private lateinit var server: MockWebServer
    private lateinit var resolver: StreamResolver
    private lateinit var client: InnerTubeClient
    private lateinit var profiles: List<ClientProfile>
    private val locale = ContentLocale("en", "US")

    private val playable = """{"playabilityStatus":{"status":"OK"},"streamingData":{"formats":[
        {"itag":18,"url":"https://rr1.googlevideo.com/videoplayback?id=1","mimeType":"video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"","width":640,"height":360,"bitrate":500000}]},
        "videoDetails":{"title":"t","author":"a","lengthSeconds":"60"}}"""

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).dns { listOf(InetAddress.getByName("127.0.0.1")) }.build()
        client = InnerTubeClient(http, Fixtures.json, { 1_700_000_000_000L }, InnerTubeConfig(maxAttempts = 1, backoffMillis = 1))
        val base = "http://www.youtube.com:${server.port}/"
        profiles = listOf("FIRST", "SECOND").map { ClientProfile(name = it, version = "1", nameId = "1", userAgent = "ua-$it", baseUrl = base) }
        resolver = StreamResolver(client, { 1_700_000_000_000L }, profiles, profileTimeoutMs = 500)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun json(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body)

    @Test
    fun unreadableAnswerFromOneClientFallsThroughToTheNext() = runBlocking {
        server.enqueue(json("<html>not json</html>").build())
        server.enqueue(json(playable).build())
        val result = resolver.resolve("vid", locale, null)
        assertTrue(result is ResolveResult.Playable)
        assertEquals("SECOND", (result as ResolveResult.Playable).media.clientName)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun slowClientIsAbandonedWithinItsBudget() = runBlocking {
        server.enqueue(json(playable).headersDelay(3, TimeUnit.SECONDS).build())
        server.enqueue(json(playable).build())
        val started = System.currentTimeMillis()
        val result = resolver.resolve("vid", locale, null)
        assertEquals("SECOND", (result as ResolveResult.Playable).media.clientName)
        assertTrue("took ${System.currentTimeMillis() - started}ms", System.currentTimeMillis() - started < 2_500)
    }

    @Test
    fun allClientsDownIsReportedAsANetworkError() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(MockResponse.Builder().code(503).build())
        val error = runCatching { resolver.resolve("vid", locale, null) }.exceptionOrNull()
        assertTrue("got $error", error is AppError.Network)
    }
}
