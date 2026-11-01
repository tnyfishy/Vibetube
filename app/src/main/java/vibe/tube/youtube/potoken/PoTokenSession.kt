package vibe.tube.youtube.potoken

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import vibe.tube.core.Clock
import vibe.tube.network.await

/** Mints proof-of-origin tokens bound to an identifier (a video ID for the player, a visitor ID for streaming). */
interface PoTokenMinter {
    /** Null when no token can be had; callers then proceed without one rather than fail. */
    suspend fun mint(identifier: String): String?

    /** The host refused a token it was given: the next mint must start from a fresh attestation. */
    fun invalidate()

    /** Starts attesting in the background so the first mint does not wait for it. */
    fun warmUp()
}

/** Runs the BotGuard virtual machine, which only exists as JavaScript. */
interface BotGuardRunner {
    /** Runs [program] under [interpreter] and returns the attestation response to hand to `GenerateIT`. */
    suspend fun attest(program: String, globalName: String, interpreter: String): String

    suspend fun mint(integrityToken: String, identifier: String): String

    /** Discards the VM, so the next [attest] starts clean. */
    fun reset()
}

/**
 * One attested BotGuard session: fetches the challenge, has the VM answer it, trades the answer for an
 * integrity token, and mints identifier-bound tokens from that. The integrity token outlives many
 * mints, so attestation is the rare, slow step and minting the cheap one.
 */
class PoTokenSession(
    private val http: OkHttpClient,
    private val runner: BotGuardRunner,
    private val clock: Clock = Clock.System,
    private val scope: CoroutineScope? = null,
    private val attestTimeoutMs: Long = ATTEST_TIMEOUT_MS,
    private val mintTimeoutMs: Long = MINT_TIMEOUT_MS,
    private val retryAfterFailureMs: Long = RETRY_AFTER_FAILURE_MS,
    private val baseUrl: String = BASE,
) : PoTokenMinter {
    private val lock = Mutex()
    private var integrity: String? = null
    private var validUntil = 0L
    private var failedAt: Long? = null

    @Volatile private var stale = false

    override fun invalidate() {
        stale = true
    }

    override fun warmUp() {
        scope?.launch(Dispatchers.Default) { runCatching { withTimeoutOrNull(attestTimeoutMs) { ensureAttested() } } }
    }

    override suspend fun mint(identifier: String): String? {
        if (identifier.isBlank()) return null
        return try {
            withTimeoutOrNull(mintTimeoutMs + attestTimeoutMs) {
                val token = ensureAttested() ?: return@withTimeoutOrNull null
                withTimeoutOrNull(mintTimeoutMs) { runner.mint(token, identifier) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "mint failed: ${e.javaClass.simpleName} ${e.message?.take(120)}")
            null
        }
    }

    private suspend fun ensureAttested(): String? = lock.withLock {
        val now = clock.nowMillis()
        val current = integrity
        if (current != null && !stale && now < validUntil) return current
        if (failedAt?.let { now - it < retryAfterFailureMs } == true) return null
        try {
            attest(now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedAt = clock.nowMillis()
            integrity = null
            runner.reset()
            Log.w(TAG, "attestation failed: ${e.javaClass.simpleName} ${e.message?.take(160)}")
            null
        }
    }

    private suspend fun attest(now: Long): String {
        val challenge = BotGuardCodec.parseChallenge(post(CREATE, """["$REQUEST_KEY"]"""))
        val interpreter = challenge.script
            ?: challenge.scriptUrl?.let { fetchInterpreter(it) }
            ?: error("challenge has no interpreter")
        runner.reset()
        val response = runner.attest(challenge.program, challenge.globalName, interpreter)
        val (token, ttlSeconds) = BotGuardCodec.parseIntegrity(post(GENERATE, """["$REQUEST_KEY",${jsonString(response)}]"""))
        integrity = token
        stale = false
        failedAt = null
        // Refresh well before the stated expiry so a token never dies mid-playback.
        validUntil = now + ttlSeconds * 1000 * 9 / 10
        Log.i(TAG, "attested, valid for ${ttlSeconds}s")
        return token
    }

    private suspend fun post(path: String, body: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/$path")
            .post(body.toRequestBody(PROTOBUF_JSON))
            .header("x-goog-api-key", API_KEY)
            .header("x-user-agent", "grpc-web-javascript/0.1")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        http.newCall(request).await().use { response ->
            val text = response.body.string()
            check(response.isSuccessful) { "$path http ${response.code}" }
            text
        }
    }

    private suspend fun fetchInterpreter(url: String): String = withContext(Dispatchers.IO) {
        val absolute = if (url.startsWith("//")) "https:$url" else url
        val request = Request.Builder().url(absolute).header("User-Agent", USER_AGENT).build()
        http.newCall(request).await().use { response ->
            check(response.isSuccessful) { "interpreter http ${response.code}" }
            response.body.string()
        }
    }

    private fun jsonString(value: String): String = kotlinx.serialization.json.JsonPrimitive(value).toString()

    companion object {
        private const val TAG = "PoToken"
        const val BASE = "https://www.youtube.com/api/jnn/v1"
        private const val CREATE = "Create"
        private const val GENERATE = "GenerateIT"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        private val PROTOBUF_JSON = "application/json+protobuf".toMediaType()
        const val ATTEST_TIMEOUT_MS = 20_000L
        const val MINT_TIMEOUT_MS = 4_000L
        const val RETRY_AFTER_FAILURE_MS = 30_000L
    }
}
