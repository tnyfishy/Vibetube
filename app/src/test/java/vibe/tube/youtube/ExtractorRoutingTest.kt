package vibe.tube.youtube

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import vibe.tube.account.SessionCookieJar
import vibe.tube.network.RequestAuth
import vibe.tube.support.FakeProvider
import vibe.tube.support.video
import vibe.tube.youtube.extractor.*
import vibe.tube.youtube.model.*

@org.robolectric.annotation.Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class ExtractorRoutingTest {
    private val call = ProviderCall(ContentLocale("en", "US"), null)
    private class Public : PublicExtractor {
        var searches = 0
        var fail = false
        var cancel = false
        var empty = false
        override suspend fun search(query: String, kind: SearchKind, locale: ContentLocale, next: String?, shorts: Boolean): Page<FeedItem> {
            searches++; if (cancel) throw kotlinx.coroutines.CancellationException("cancelled"); if (fail) throw java.io.IOException("offline")
            return Page(if (empty) emptyList() else listOf(video("public")), "np:page")
        }
        override suspend fun watch(id: String, locale: ContentLocale): WatchInfo = error("Authenticated watch must not use public extraction")
        override suspend fun playlist(id: String, locale: ContentLocale, next: String?): PlaylistDetail = error("unused")
        override suspend fun channel(id: String, locale: ContentLocale): ChannelDetail = ChannelDetail(id, "public", null, null, null, null, null, banner = "https://example.org/banner", contentTabs = listOf("videos", "shorts"))
        override suspend fun channelVideos(id: String, locale: ContentLocale, next: String?): Page<FeedItem> = error("unused")
        override suspend fun comments(value: String): CommentsPage = error("unused")
    }
    private class Account : FakeProvider() {
        var searches = 0
        var homeAuth: RequestAuth? = null
        var watchCalls = 0
        var rateCalls = 0
        override suspend fun search(call: ProviderCall, query: String, kind: SearchKind, sort: SearchSort, continuation: String?): Page<FeedItem> {
            searches++; return Page(listOf(video("account")), "account:page")
        }
        override suspend fun home(call: ProviderCall, continuation: String?): Page<FeedItem> { homeAuth = call.auth; return Page(listOf(video("personalized")), null) }
        override suspend fun watch(call: ProviderCall, videoId: String, playlistId: String?, playlistIndex: Int?): WatchInfo { watchCalls++; throw IllegalStateException("account watch reached") }
        override suspend fun rate(call: ProviderCall, videoId: String, status: LikeStatus) { rateCalls++ }
        override suspend fun channel(call: ProviderCall, channelId: String): ChannelDetail = ChannelDetail(channelId, "account", null, null, null, null, true)
    }
    @Test fun vietnameseSearchUsesTheWorkingDirectAdapter() = runBlocking {
        val p = Public(); val a = Account(); val repo = ExtractorYouTubeProvider(p, a)
        repo.search(call.copy(locale = ContentLocale("vi", "VN")), "nhạc lofi", SearchKind.All, SearchSort.Relevance, null)
        assertEquals(0, p.searches); assertEquals(1, a.searches)
    }
    @Test fun emptyInitialSearchFallsBackButShortsUseTheSupportedFilter() = runBlocking {
        val p = Public().apply { empty = true }; val a = Account(); val repo = ExtractorYouTubeProvider(p, a)
        assertEquals("account", repo.search(call, "q", SearchKind.All, SearchSort.Relevance, null).items.first().id)
        assertEquals(1, p.searches)
        repo.search(call, "q", SearchKind.Shorts, SearchSort.Relevance, null)
        assertEquals(1, p.searches)
        assertEquals(2, a.searches)
    }
    @Test fun publicSearchAndOwnedContinuationsStayWithTheExtractor() = runBlocking {
        val p = Public(); val a = Account(); val repo = ExtractorYouTubeProvider(p, a)
        assertEquals("public", repo.search(call, "q", SearchKind.Video, SearchSort.Relevance, null).items.first().id)
        p.fail = true
        try { repo.search(call, "q", SearchKind.Video, SearchSort.Relevance, "np:page"); fail("must preserve continuation ownership") } catch (_: java.io.IOException) { }
        assertEquals(0, a.searches)
        assertEquals("account", repo.search(call, "q", SearchKind.Video, SearchSort.Relevance, "legacy").items.first().id)
        assertEquals(2, p.searches)
    }
    @Test fun onlyInitialFailureMayFallbackAndCancellationNeverDoes() = runBlocking {
        val p = Public().apply { fail = true }; val a = Account(); val repo = ExtractorYouTubeProvider(p, a)
        assertEquals("account", repo.search(call, "q", SearchKind.All, SearchSort.Relevance, null).items.first().id)
        assertEquals(1, a.searches)
        p.cancel = true
        try { repo.search(call, "q", SearchKind.All, SearchSort.Relevance, null); fail("cancel must propagate") } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(1, a.searches)
    }
    @Test fun accountHomeWatchAndWritesKeepTheAuthenticatedAdapter() = runBlocking {
        val p = Public(); val a = Account(); val repo = ExtractorYouTubeProvider(p, a)
        val auth = RequestAuth("account", SessionCookieJar(emptyList()), null, null)
        val signedIn = call.copy(auth = auth)
        assertEquals("personalized", repo.home(signedIn, null).items.first().id)
        assertSame(auth, a.homeAuth)
        try { repo.watch(signedIn, "video", null, null) } catch (_: IllegalStateException) { }
        repo.rate(signedIn, "video", LikeStatus.Like)
        assertEquals(1, a.watchCalls); assertEquals(1, a.rateCalls); assertEquals(0, p.searches)
    }
    @Test fun channelPublicMetadataPreservesAuthenticatedSubscription() = runBlocking {
        val repo = ExtractorYouTubeProvider(Public(), Account())
        val signedIn = call.copy(auth = RequestAuth("account", SessionCookieJar(emptyList()), null, null))
        val detail = repo.channel(signedIn, "UC1")
        assertEquals("account", detail.name)
        assertEquals(true, detail.subscribed)
        assertEquals("https://example.org/banner", detail.banner)
        assertEquals(listOf("videos", "shorts"), detail.contentTabs)
    }

}
