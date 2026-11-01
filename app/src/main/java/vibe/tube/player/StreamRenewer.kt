package vibe.tube.player

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import vibe.tube.network.RequestAuth
import vibe.tube.youtube.model.ContentLocale
import vibe.tube.youtube.player.ResolveResult
import vibe.tube.youtube.player.ResolvedMedia
import vibe.tube.youtube.player.StreamDenialClassifier
import vibe.tube.youtube.player.StreamResolver
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Replaces a stream URL googlevideo has just refused, without the player seeing an error: the refused
 * client is demoted, the video is resolved again without it, and the same file (itag and length) from the
 * new answer takes over. Both tracks of a video share one resolve, and nothing else waits on it.
 *
 * An idle refusal (the stream sat untouched past googlevideo's cutoff, e.g. while paused) says nothing
 * about the client, so it is resolved again on the normal ladder without demoting anyone.
 *
 * Called on a loader thread, so it blocks; the wait is bounded.
 */
class StreamRenewer(
    private val resolve: suspend (videoId: String, auth: RequestAuth?, skip: Set<String>) -> ResolveResult,
    private val reportRefused: (client: String) -> Unit,
    private val auth: suspend () -> RequestAuth? = { null },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = RESOLVE_TIMEOUT_MS,
) {
    constructor(resolver: StreamResolver, locale: () -> ContentLocale, auth: suspend () -> RequestAuth? = { null }) : this(
        resolve = { id, a, skip -> resolver.resolve(id, locale(), a, skip) },
        reportRefused = resolver.gates::reportGated,
        auth = auth,
    )

    private class Fresh(val media: CompletableFuture<ResolvedMedia?>, val atMs: Long, val without: String?)

    private val lock = Any()
    private val fresh = HashMap<String, Fresh>()

    fun renew(videoId: String, refusedUrl: String, idle: Boolean = false): String? {
        val itag = param(refusedUrl, "itag")?.toIntOrNull() ?: return null
        val refusedClient = if (idle) null else StreamDenialClassifier.clientOf(refusedUrl)?.uppercase()
        val answer = answerFor(videoId, refusedClient)
        val media = try {
            answer.get(timeoutMs + GRACE_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            null
        } ?: return null
        val length = param(refusedUrl, "clen")
        val replacement = (media.muxed + media.videoOnly + media.audioOnly)
            .firstOrNull { it.itag == itag && (length == null || param(it.url, "clen") == length) }
        return replacement?.url?.takeIf { it != refusedUrl }
    }

    /** One resolve per video: the second track to be refused joins the first track's answer instead of starting another. */
    private fun answerFor(videoId: String, refusedClient: String?): CompletableFuture<ResolvedMedia?> {
        val starter: Boolean
        val entry = synchronized(lock) {
            val existing = fresh[videoId]
            val reusable = existing != null && nowMs() - existing.atMs < REUSE_MS && existing.without == refusedClient
            if (reusable) { starter = false; existing!! } else {
                starter = true
                Fresh(CompletableFuture<ResolvedMedia?>(), nowMs(), refusedClient).also { fresh[videoId] = it }
            }
        }
        if (starter) {
            val future = entry.media
            try {
                future.complete(resolveWithout(videoId, refusedClient))
            } catch (e: Throwable) {
                future.complete(null)
            }
        }
        return entry.media
    }

    private fun resolveWithout(videoId: String, refusedClient: String?): ResolvedMedia? {
        refusedClient?.let(reportRefused)
        val skip = refusedClient?.let { setOf(it) }.orEmpty()
        val result = try {
            runBlocking { withTimeoutOrNull(timeoutMs) { resolve(videoId, auth(), skip) } }
        } catch (e: Exception) {
            null
        }
        val media = (result as? ResolveResult.Playable)?.media ?: return null
        android.util.Log.i("StreamRenewer", "$videoId: ${refusedClient ?: "idle stream"} refused, renewed via ${media.clientName}")
        return media
    }

    private fun param(url: String, name: String): String? =
        url.substringAfter('?', "").substringBefore('#').split('&')
            .firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")?.takeIf { it.isNotEmpty() }

    private companion object {
        const val RESOLVE_TIMEOUT_MS = 12_000L
        const val GRACE_MS = 1_000L
        const val REUSE_MS = 30_000L
    }
}
