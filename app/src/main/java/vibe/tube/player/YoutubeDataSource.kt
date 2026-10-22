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

class YoutubeDataSource(private val upstream: DataSource) : DataSource {
    private val requestNumber = AtomicInteger(0)

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        if (!YoutubeRequests.isVideoPlayback(dataSpec.uri)) return upstream.open(dataSpec)
        val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) -1L else dataSpec.position + dataSpec.length - 1
        val url = YoutubeRequests.rangedUrl(dataSpec.uri.toString(), dataSpec.position, end, requestNumber.getAndIncrement())
        val rewritten = dataSpec.buildUpon()
            .setUri(Uri.parse(url))
            .setPosition(0)
            .setLength(C.LENGTH_UNSET.toLong())
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpRequestBody(YoutubeRequests.body())
            .build()
        return upstream.open(rewritten)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = YoutubeDataSource(upstream.createDataSource())
    }
}
