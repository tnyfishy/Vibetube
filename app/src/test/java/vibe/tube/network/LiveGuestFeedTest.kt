package vibe.tube.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import vibe.tube.youtube.*
import vibe.tube.youtube.extractor.*
import vibe.tube.youtube.model.*
import vibe.tube.support.Fixtures

class LiveGuestFeedTest {
    @Test fun guestHomeFetchReturnsVideosWithoutAnAccount() = runBlocking {
        assumeTrue(System.getenv("VIBETUBE_LIVE") == "1")
        val http = OkHttpClient()
        val provider = ExtractorYouTubeProvider(NewPipeAdapter(http), InnerTubeProvider(InnerTubeClient(http, Fixtures.json)))
        val page = provider.trending(ProviderCall(ContentLocale("vi", "VN"), null), null)
        assertTrue("Guest feed was empty", page.items.filterIsInstance<VideoItem>().size >= 5)
        println("Guest feed: ${page.items.size} items; continuation=${page.continuation != null}")
    }
}
