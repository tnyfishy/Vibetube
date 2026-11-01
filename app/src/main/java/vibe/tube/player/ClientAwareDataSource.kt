@file:OptIn(UnstableApi::class)

package vibe.tube.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import vibe.tube.youtube.player.StreamDenialClassifier

/**
 * Opens each request through an upstream chosen by the client that minted its URL (the `c` query
 * parameter). googlevideo refuses URLs fetched with another client's user agent, often only partway
 * through a video, so the upstream for a request must present its minting client's identity.
 */
class ClientAwareDataSource(private val upstreamFor: (client: String?) -> DataSource) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private val upstreams = HashMap<String, DataSource>()
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        upstreams.values.forEach { it.addTransferListener(transferListener) }
    }

    override fun open(dataSpec: DataSpec): Long {
        val client = StreamDenialClassifier.clientOf(dataSpec.uri.toString())?.uppercase().orEmpty()
        val upstream = upstreams.getOrPut(client) {
            upstreamFor(client.ifEmpty { null }).also { created -> listeners.forEach(created::addTransferListener) }
        }
        active = upstream
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active!!.read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }

    class Factory(private val upstreamFor: (client: String?) -> DataSource) : DataSource.Factory {
        override fun createDataSource(): DataSource = ClientAwareDataSource(upstreamFor)
    }
}
