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

/**
 * Reproduces how NewPipe (and the official apps) talk to `googlevideo.com/videoplayback`:
 *  - the byte range travels as a `&range=start-end` query parameter instead of a `Range` header,
 *  - every request carries an increasing `&rn=` request number,
 *  - requests are `POST`s with the two-byte body `x\0`.
 * Plain `GET` + `Range` requests are rejected with HTTP 403 by current YouTube streaming servers.
 */
object YoutubeRequests {
    private val POST_BODY = byteArrayOf(0x78, 0)

    fun isVideoPlayback(uri: Uri): Boolean =
        uri.path == "/videoplayback" && uri.host?.endsWith("googlevideo.com") == true

    /** The URL to request for [start]..[end] (inclusive; [end] < 0 means open ended), numbered with [requestNumber]. */
    fun rangedUrl(url: String, start: Long, end: Long, requestNumber: Int): String {
        var result = url
        if (!result.contains("&rn=") && !result.contains("?rn=")) result += "&rn=$requestNumber"
        if (start > 0 || end >= 0) result += "&range=$start-${if (end >= 0) end.toString() else ""}"
        return result
    }

    fun body(): ByteArray = POST_BODY.copyOf()
}

/**
 * Wraps the real data source for googlevideo URLs. Unbounded reads are throttled by the server after the first
 * few MB, so the stream is fetched in [CHUNK]-sized ranges and the chunks are stitched together here, invisibly
 * to ExoPlayer. The total size comes from the URL's `clen` parameter when present.
 */
class YoutubeDataSource(private val upstream: DataSource) : DataSource {
    private val requestNumber = AtomicInteger(0)
    private var passthrough = true
    private var baseUri: Uri? = null
    private var template: DataSpec? = null
    private var next = 0L
    private var endExclusive = -1L
    private var chunkEnd = 0L
    private var upstreamOpen = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        passthrough = !YoutubeRequests.isVideoPlayback(dataSpec.uri)
        if (passthrough) return upstream.open(dataSpec)
        baseUri = dataSpec.uri
        template = dataSpec
        val clen = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull() ?: -1L
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
        chunkEnd = if (endExclusive > 0) minOf(next + CHUNK, endExclusive) else next + CHUNK
        val url = YoutubeRequests.rangedUrl(baseUri.toString(), next, chunkEnd - 1, requestNumber.getAndIncrement())
        val rewritten = spec.buildUpon()
            .setUri(Uri.parse(url))
            .setPosition(0)
            .setLength(C.LENGTH_UNSET.toLong())
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(YoutubeRequests.body())
            .build()
        upstream.open(rewritten)
        upstreamOpen = true
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (passthrough) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        while (true) {
            if (endExclusive in 1..next) return C.RESULT_END_OF_INPUT
            val read = upstream.read(buffer, offset, length)
            if (read != C.RESULT_END_OF_INPUT) {
                next += read
                return read
            }
            // This chunk is finished. A short chunk means the stream itself ended.
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

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = YoutubeDataSource(upstream.createDataSource())
    }

    private companion object {
        const val CHUNK = 10L * 1024 * 1024
    }
}
