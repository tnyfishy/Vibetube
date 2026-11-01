package vibe.tube.youtube

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vibe.tube.youtube.potoken.BotGuardRunner
import vibe.tube.youtube.potoken.PoTokenSession
import java.net.InetAddress

private class FakeRunner(private val failAttest: Boolean = false) : BotGuardRunner {
    var attests = 0
    var resets = 0
    val minted = mutableListOf<String>()

    override suspend fun attest(program: String, globalName: String, interpreter: String): String {
        attests++
        check(!failAttest) { "vm failed" }
        return "response-for-$program-$globalName-${interpreter.length}"
    }

    override suspend fun mint(integrityToken: String, identifier: String): String {
        minted += identifier
        return "pot($integrityToken,$identifier)"
    }

    override fun reset() {
        resets++
    }
}

class PoTokenSessionTest {
    private lateinit var server: MockWebServer
    private lateinit var base: String
    private var now = 1_000_000L
    private val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).dns { listOf(InetAddress.getByName("127.0.0.1")) }.build()

    private val challenge = """[["m",[null,null,"function(){}"],null,"h","PROG","glob"]]"""
    private val integrity = """["INTEGRITY",100,10]"""

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        base = "http://127.0.0.1:${server.port}"
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun json(body: String) = MockResponse.Builder().code(200).body(body).build()

    private fun session(runner: BotGuardRunner) = PoTokenSession(http, runner, { now }, scope = null, retryAfterFailureMs = 30_000, baseUrl = base)

    @Test
    fun attestsOnceThenMintsFromTheSameIntegrityToken() = runBlocking {
        server.enqueue(json(challenge))
        server.enqueue(json(integrity))
        val runner = FakeRunner()
        val session = session(runner)
        assertEquals("pot(INTEGRITY,video1)", session.mint("video1"))
        assertEquals("pot(INTEGRITY,visitor1)", session.mint("visitor1"))
        assertEquals(1, runner.attests)
        assertEquals(2, server.requestCount)
        assertEquals("/Create", server.takeRequest().url.encodedPath)
    }

    @Test
    fun attestationRequestsCarryTheProtocolHeaders() = runBlocking {
        server.enqueue(json(challenge))
        server.enqueue(json(integrity))
        session(FakeRunner()).mint("v")
        val create = server.takeRequest()
        assertEquals("""["O43z0dpjhgX20SCx4KAo"]""", create.body?.utf8())
        assertEquals("grpc-web-javascript/0.1", create.headers["x-user-agent"])
        assertTrue(create.headers["x-goog-api-key"]!!.isNotBlank())
        val generate = server.takeRequest()
        assertTrue(generate.body!!.utf8().startsWith("""["O43z0dpjhgX20SCx4KAo","response-for-PROG-glob-"""))
    }

    @Test
    fun expiredIntegrityTokenIsRenewed() = runBlocking {
        repeat(2) { server.enqueue(json(challenge)); server.enqueue(json(integrity)) }
        val runner = FakeRunner()
        val session = session(runner)
        session.mint("a")
        now += 91_000 // past 90% of the 100 s lifetime
        session.mint("b")
        assertEquals(2, runner.attests)
    }

    @Test
    fun invalidatedSessionAttestsAgain() = runBlocking {
        repeat(2) { server.enqueue(json(challenge)); server.enqueue(json(integrity)) }
        val runner = FakeRunner()
        val session = session(runner)
        session.mint("a")
        session.invalidate()
        session.mint("b")
        assertEquals(2, runner.attests)
    }

    @Test
    fun failedAttestationGivesNoTokenAndIsNotRetriedImmediately() = runBlocking {
        server.enqueue(json(challenge))
        val runner = FakeRunner(failAttest = true)
        val session = session(runner)
        assertNull(session.mint("a"))
        assertNull(session.mint("b"))
        assertEquals(1, runner.attests)
        assertTrue("runner is reset after a failure", runner.resets >= 1)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun serverErrorMeansNoToken() = runBlocking {
        server.enqueue(MockResponse.Builder().code(500).build())
        assertNull(session(FakeRunner()).mint("a"))
    }

    @Test
    fun blankIdentifierIsNeverMinted() = runBlocking {
        val runner = FakeRunner()
        assertNull(session(runner).mint(" "))
        assertEquals(0, runner.attests)
    }
}
