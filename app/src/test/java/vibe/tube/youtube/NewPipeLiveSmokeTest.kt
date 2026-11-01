package vibe.tube.youtube

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.*
import vibe.tube.youtube.extractor.NewPipeAdapter
import vibe.tube.youtube.model.*

/** Opt-in, read-only test. Network/bot challenges must never be counted as an offline pass. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class NewPipeLiveSmokeTest {
    @Test fun vietnameseSearchAndChannelTabsAreDistinct() = runBlocking {
        assumeTrue(System.getenv("VIBETUBE_EXTRACTOR_LIVE") == "1")
        val extractor = NewPipeAdapter(OkHttpClient())
        val locale = ContentLocale("vi", "VN")
        val provider = vibe.tube.youtube.extractor.ExtractorYouTubeProvider(extractor,
            InnerTubeProvider(vibe.tube.network.InnerTubeClient(OkHttpClient(), vibe.tube.support.Fixtures.json)))
        val search = provider.search(ProviderCall(locale, null), "nhạc lofi", SearchKind.All, SearchSort.Relevance, null)
        assertTrue("Vietnamese search is empty", search.items.isNotEmpty())
        val id = "UCuAXFkgsw1L7xaCfnd5JJOw"
        val videos = provider.channelVideos(ProviderCall(locale, null), id, null)
        val shorts = provider.channelTab(ProviderCall(locale, null), id, "shorts", null)
        val playlists = provider.channelTab(ProviderCall(locale, null), id, "playlists", null)
        println("Channel route videos=${videos.items.size}, shorts=${shorts.items.size}, playlists=${playlists.items.size}")
        assertTrue(videos.items.isNotEmpty())
        assertTrue("Shorts tab missing short-form results", shorts.items.any { it is VideoItem && it.isShort })
        assertTrue("Playlists tab returned videos", playlists.items.any { it is PlaylistItem })
        println("LIVE VN search=${search.items.size}; channel videos=${videos.items.size}, shorts=${shorts.items.size}, playlists=${playlists.items.size}")
    }
    @Test fun publicSearchAndStreamsUseTheRealExtractor() = runBlocking {
        assumeTrue(System.getenv("VIBETUBE_EXTRACTOR_LIVE") == "1")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request()).also { response ->
                println("NewPipe HTTP ${chain.request().method} ${chain.request().url.host}${chain.request().url.encodedPath} => ${response.code} (${response.request.url.host})")
            }
        }.build()
        val extractor = NewPipeAdapter(client)
        val page = extractor.search("NewPipe test video", SearchKind.Video, ContentLocale("en", "US"), null)
        val video = page.items.filterIsInstance<VideoItem>().first()
        assertTrue(isVideoId(video.id))
        val media = extractor.media(video.id, ContentLocale("en", "US"))
        assertTrue(media.muxed.isNotEmpty() || media.audioOnly.isNotEmpty() || media.hlsUrl != null)
        assertEquals("NewPipeExtractor", media.clientName)
        println("Real NewPipe read: ${page.items.size} search results; ${media.audioOnly.size} audio streams; ${media.videoOnly.size} video-only streams")
    }
}
