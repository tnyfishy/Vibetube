@file:OptIn(UnstableApi::class)

package vibe.tube.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.atomic.AtomicInteger

object YoutubeRequests {
    private val POST_BODY = byteArrayOf(0x78, 0)

    fun isVideoPlayback(uri: Uri): Boolean =
        uri.path == "/videoplayback" && uri.host?.endsWith("googlevideo.com") == true

    fun rangedUrl(url: String, start: Long, end: Long, requestNumber: Int): String {
        var result = url
        if (!result.contains("&rn=") && !result.contains("?rn=")) result += "&rn=$requestNumber"
        if (start > 0 || end >= 0) result += "&range=$start-${if (end >= 0) end.toString() else ""}"
        return result
    }

    fun body(): ByteArray = POST_BODY.copyOf()
}

/**
 * [renew] maps a URL googlevideo refused to a working one for the same file, or null; a refused chunk is
 * then retried on the new URL, and every later chunk of this stream uses it too. `idle` is true when the
 * refusal follows a long gap since the stream was last fetched, which is not the client's fault.
 *
 * googlevideo drops an unattested stream left untouched for roughly two minutes (measured: a 100s gap is
 * served, 150s is refused with 403, even with hours left on `expire`). Chunks are therefore sized to about
 * [CHUNK_SECONDS] of media, so steady playback keeps touching the stream well inside that window.
 */
class YoutubeDataSource(private val upstream: DataSource, private val renew: ((refusedUrl: String, idle: Boolean) -> String?)? = null) : DataSource {
    private val requestNumber = AtomicInteger(0)
    private var renewals = 0
    private var passthrough = true
    private var baseUri: Uri? = null
    private var template: DataSpec? = null
    private var next = 0L
    private var endExclusive = -1L
    private var chunkEnd = 0L
    private var chunkSize = CHUNK
    private var upstreamOpen = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        passthrough = !YoutubeRequests.isVideoPlayback(dataSpec.uri)
        if (passthrough) return upstream.open(dataSpec)
        baseUri = dataSpec.uri
        template = dataSpec
        val clen = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull() ?: -1L
        chunkSize = chunkFor(clen, dataSpec.uri.getQueryParameter("dur")?.toDoubleOrNull())
        next = dataSpec.position
        endExclusive = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.position + dataSpec.length
            clen > 0 -> clen
            else -> -1L
        }
        openChunk()
        return if (endExclusive > 0) endExclusive - dataSpec.position else C.LENGTH_UNSET.toLong()
    }

    private fun openChunk() {
        val spec = template!!
        chunkEnd = if (endExclusive > 0) minOf(next + chunkSize, endExclusive) else next + chunkSize
        val url = YoutubeRequests.rangedUrl(baseUri.toString(), next, chunkEnd - 1, requestNumber.getAndIncrement())
        val rewritten = spec.buildUpon()
            .setUri(Uri.parse(url))
            .setPosition(0)
            .setLength(C.LENGTH_UNSET.toLong())
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(YoutubeRequests.body())
            .build()
        try {
            upstream.open(rewritten)
        } catch (e: androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
            val refused = e.responseCode == 403 || e.responseCode == 410
            val idle = StreamActivity.idleMs(baseUri.toString()) > IDLE_REFUSAL_MS
            android.util.Log.w("YoutubeData", "http=${e.responseCode} c=${baseUri?.getQueryParameter("c")} itag=${baseUri?.getQueryParameter("itag")} at=$next pot=${baseUri?.getQueryParameter("pot") != null} idle=$idle")
            val fresh = if (refused && renewals < MAX_RENEWALS) renew?.invoke(baseUri.toString(), idle) else null
            if (fresh == null) throw e
            renewals++
            upstream.close()
            baseUri = Uri.parse(fresh)
            openChunk()
            return
        }
        upstreamOpen = true
        StreamActivity.touch(baseUri.toString())
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (passthrough) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        while (true) {
            if (endExclusive in 1..next) return C.RESULT_END_OF_INPUT
            val read = upstream.read(buffer, offset, length)
            if (read != C.RESULT_END_OF_INPUT) {
                StreamActivity.touch(baseUri.toString())
                next += read
                return read
            }
            if (next < chunkEnd) return C.RESULT_END_OF_INPUT
            upstream.close()
            upstreamOpen = false
            try {
                openChunk()
            } catch (e: androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                if (e.responseCode == 416) return C.RESULT_END_OF_INPUT
                throw e
            }
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        upstreamOpen = false
        upstream.close()
    }

    class Factory(private val upstream: DataSource.Factory, private val renew: ((String, Boolean) -> String?)? = null) : DataSource.Factory {
        override fun createDataSource(): DataSource = YoutubeDataSource(upstream.createDataSource(), renew)
    }

    private companion object {
        const val CHUNK = 10L * 1024 * 1024
        const val MIN_CHUNK = 256L * 1024
        const val CHUNK_SECONDS = 30.0
        const val MAX_RENEWALS = 2
        /** A refusal after this long without touching the stream is the idle cutoff, not a gated client. */
        const val IDLE_REFUSAL_MS = 90_000L

        fun chunkFor(clen: Long, durSeconds: Double?): Long {
            if (clen <= 0 || durSeconds == null || durSeconds <= 0.0) return CHUNK
            return (clen / durSeconds * CHUNK_SECONDS).toLong().coerceIn(MIN_CHUNK, CHUNK)
        }
    }
}

/**
 * When each stream file was last fetched, keyed by its URL without the per-request range. Shared across
 * data source instances, since the player opens a new one for every load of the same file.
 */
internal object StreamActivity {
    private val last = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun touch(url: String) {
        last[url] = System.currentTimeMillis()
        if (last.size > 64) last.entries.minByOrNull { it.value }?.let { last.remove(it.key) }
    }

    /** Milliseconds since [url] was last fetched; 0 when it never was (a first request is never idle). */
    fun idleMs(url: String): Long = last[url]?.let { System.currentTimeMillis() - it } ?: 0L
}
