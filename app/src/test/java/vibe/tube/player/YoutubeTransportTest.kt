@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package vibe.tube.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class YoutubeTransportTest {
    private class Upstream : DataSource {
        lateinit var spec: DataSpec
        override fun open(dataSpec: DataSpec): Long { spec = dataSpec; return C.LENGTH_UNSET.toLong() }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = C.RESULT_END_OF_INPUT
        override fun getUri(): Uri = spec.uri
        override fun addTransferListener(listener: TransferListener) {}
        override fun close() {}
    }
    @Test fun nativeStreamsSupportLargeOffsets() {
        val upstream = Upstream()
        val data = YoutubeDataSource(upstream)
        val size = data.open(DataSpec.Builder().setUri("https://rr1.googlevideo.com/videoplayback?id=a&clen=4000000000").setPosition(3000000000L).setLength(1000000L).build())
        assertEquals(1000000L, size)
        assertEquals(DataSpec.HTTP_METHOD_POST, upstream.spec.httpMethod)
        assertArrayEquals(YoutubeRequests.body(), upstream.spec.httpBody)
        assertEquals("3000000000-3000999999", upstream.spec.uri.getQueryParameter("range"))
        data.close()
    }
    private class RefusingUpstream(private val refuse: (Uri) -> Int?) : DataSource {
        val opened = mutableListOf<Uri>()
        lateinit var spec: DataSpec
        override fun open(dataSpec: DataSpec): Long {
            spec = dataSpec
            opened += dataSpec.uri
            refuse(dataSpec.uri)?.let { throw HttpDataSource.InvalidResponseCodeException(it, null, null, emptyMap(), dataSpec, ByteArray(0)) }
            return C.LENGTH_UNSET.toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = C.RESULT_END_OF_INPUT
        override fun getUri(): Uri = spec.uri
        override fun addTransferListener(listener: TransferListener) {}
        override fun close() {}
    }
    @Test fun refusedChunkIsRetriedOnTheRenewedUrl() {
        val upstream = RefusingUpstream { if (it.getQueryParameter("c") == "IOS") 403 else null }
        val renewed = "https://rr1.googlevideo.com/videoplayback?id=a&clen=5000&c=VISIONOS"
        val data = YoutubeDataSource(upstream) { _, _ -> renewed }
        val size = data.open(DataSpec.Builder().setUri("https://rr1.googlevideo.com/videoplayback?id=a&clen=5000&c=IOS").build())
        assertEquals(5000L, size)
        assertEquals(listOf("IOS", "VISIONOS"), upstream.opened.map { it.getQueryParameter("c") })
        data.close()
    }
    @Test fun refusalWithoutAReplacementStillFails() {
        val upstream = RefusingUpstream { 403 }
        val data = YoutubeDataSource(upstream) { _, _ -> null }
        try {
            data.open(DataSpec.Builder().setUri("https://rr1.googlevideo.com/videoplayback?id=a&clen=5000&c=IOS").build())
            fail("expected the 403 to surface")
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            assertEquals(403, e.responseCode)
        }
    }
    @Test fun chunksCoverAboutThirtySecondsOfMedia() {
        val upstream = Upstream()
        val data = YoutubeDataSource(upstream)
        // 60 MB over 240 s is 250 KB/s, so a chunk is 7.5 MB rather than the 10 MB ceiling.
        data.open(DataSpec.Builder().setUri("https://rr1.googlevideo.com/videoplayback?id=a&clen=60000000&dur=240.0").build())
        assertEquals("0-7499999", upstream.spec.uri.getQueryParameter("range"))
        data.close()
    }
    @Test fun refusalAfterALongGapIsReportedAsIdle() {
        val url = "https://rr1.googlevideo.com/videoplayback?id=idle&clen=5000&c=VISIONOS"
        StreamActivity.touch(url)
        val field = StreamActivity::class.java.getDeclaredField("last").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(StreamActivity) as MutableMap<String, Long>)[url] = System.currentTimeMillis() - 200_000L
        val upstream = RefusingUpstream { if (it.getQueryParameter("id") == "idle") 403 else null }
        var sawIdle: Boolean? = null
        val data = YoutubeDataSource(upstream) { _, idle -> sawIdle = idle; "https://rr1.googlevideo.com/videoplayback?id=b&clen=5000&c=VISIONOS" }
        data.open(DataSpec.Builder().setUri(url).build())
        assertEquals(true, sawIdle)
        data.close()
    }
    @Test fun verifiedNativeTransportStillUsesItsOriginalPostRequest() {
        val upstream = Upstream()
        val data = YoutubeDataSource(upstream)
        data.open(DataSpec.Builder().setUri("https://rr1.googlevideo.com/videoplayback?id=a&clen=4000000000").build())
        assertEquals(DataSpec.HTTP_METHOD_POST, upstream.spec.httpMethod)
        assertArrayEquals(YoutubeRequests.body(), upstream.spec.httpBody)
        data.close()
    }
}
