package vibe.tube.youtube.potoken

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult

/**
 * Lends the app's PO token session to the fallback extractor, so its requests carry the same kind of
 * proof as ours. The extractor calls on its own I/O thread and blocks on the answer; `null` tells it
 * to carry on without a token.
 */
class NewPipePoTokenBridge(
    private val minter: PoTokenMinter,
    private val visitorData: suspend () -> String?,
    private val timeoutMs: Long = TIMEOUT_MS,
) : PoTokenProvider {
    override fun getWebClientPoToken(videoId: String): PoTokenResult? = tokensFor(videoId)

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = tokensFor(videoId)

    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = tokensFor(videoId)

    override fun getIosClientPoToken(videoId: String): PoTokenResult? = tokensFor(videoId)

    private fun tokensFor(videoId: String): PoTokenResult? = runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMs) {
            val visitor = visitorData() ?: return@withTimeoutOrNull null
            val visitorId = BotGuardCodec.visitorId(visitor) ?: return@withTimeoutOrNull null
            val player = minter.mint(videoId) ?: return@withTimeoutOrNull null
            val streaming = minter.mint(visitorId) ?: return@withTimeoutOrNull null
            PoTokenResult(visitor, player, streaming)
        }.also { android.util.Log.d("PoToken", "extractor asked for $videoId: tokens=${it != null}") }
    }

    private companion object {
        const val TIMEOUT_MS = 8_000L
    }
}
