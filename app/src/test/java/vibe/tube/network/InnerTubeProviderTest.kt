package vibe.tube.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vibe.tube.account.AccountSession
import vibe.tube.account.SessionCookieJar
import vibe.tube.account.StoredCookie
import vibe.tube.core.AppError
import vibe.tube.support.Fixtures
import vibe.tube.youtube.InnerTubeProvider
import vibe.tube.youtube.ProviderCall
import vibe.tube.youtube.VerifyResult
import vibe.tube.youtube.model.ContentLocale
import vibe.tube.youtube.model.SearchKind
import vibe.tube.youtube.model.SearchSort
import vibe.tube.youtube.model.VideoItem
import java.net.InetAddress

class InnerTubeProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var client: InnerTubeClient
    private lateinit var provider: InnerTubeProvider
    private val locale = ContentLocale("vi", "VN")

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val http = OkHttpClient.Builder().dns { listOf(InetAddress.getByName("127.0.0.1")) }.build()
        val base = "http://www.youtube.com:${server.port}/".toHttpUrl()
        client = InnerTubeClient(http, Fixtures.json, { 1_700_000_000_000L }, InnerTubeConfig(baseUrl = base, backoffMillis = 1))
        provider = InnerTubeProvider(client, { 1_700_000_000_000L }, "http://www.youtube.com:${server.port}/")
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun jar(sapisid: String = "alice-secret") = SessionCookieJar(
        listOf(
            StoredCookie("SAPISID", sapisid, "youtube.com", secure = false),
            StoredCookie("__Secure-1PAPISID", "1p-$sapisid", "youtube.com", secure = false),
            StoredCookie("__Secure-3PAPISID", "3p-$sapisid", "youtube.com", secure = false),
            StoredCookie("SID", "sid-value", "youtube.com", secure = false),
        ),
    )

    private fun auth(accountId: String = "acc-1", pageId: String? = null, sapisid: String = "alice-secret") =
        RequestAuth(accountId, jar(sapisid), pageId, "visitor-1")

    private fun enqueueJson(body: String, code: Int = 200) {
        server.enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build())
    }

    @Test
    fun homeRequestCarriesSessionAndTargetsPersonalizedFeed() = runBlocking {
        enqueueJson(Fixtures.text("home_logged_in_structure.json"))
        val page = provider.home(ProviderCall(locale, auth()), null)
        val request = server.takeRequest()
        assertEquals("/youtubei/v1/browse?prettyPrint=false", request.url.encodedPath + "?" + request.url.encodedQuery)
        val authorization = request.headers["Authorization"]!!
        assertTrue(authorization.startsWith("SAPISIDHASH 1700000000_"))
        assertTrue(authorization.contains("SAPISID1PHASH"))
        assertTrue(authorization.contains("SAPISID3PHASH"))
        assertTrue(request.headers["Cookie"]!!.contains("SAPISID=alice-secret"))
        assertEquals("0", request.headers["X-Goog-AuthUser"])
        assertEquals("https://www.youtube.com", request.headers["Origin"])
        assertEquals("visitor-1", request.headers["X-Goog-Visitor-Id"])
        val body = Fixtures.json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("FEwhat_to_watch", body["browseId"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("continuation"))
        val clientCtx = body["context"]!!.jsonObject["client"]!!.jsonObject
        assertEquals("vi", clientCtx["hl"]!!.jsonPrimitive.content)
        assertEquals("VN", clientCtx["gl"]!!.jsonPrimitive.content)
        assertEquals("WEB", clientCtx["clientName"]!!.jsonPrimitive.content)
        assertEquals(4, page.items.size)
        assertEquals("HOME_PAGE_2_TOKEN", page.continuation)
    }

    @Test
    fun continuationRequestUsesTokenFromPreviousResponseOnly() = runBlocking {
        enqueueJson(Fixtures.text("home_logged_in_structure.json"))
        enqueueJson(Fixtures.text("home_logged_in_page2_structure.json"))
        val a = auth()
        val first = provider.home(ProviderCall(locale, a), null)
        val second = provider.home(ProviderCall(locale, a), first.continuation)
        server.takeRequest()
        val body = Fixtures.json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("HOME_PAGE_2_TOKEN", body["continuation"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("browseId"))
        assertEquals("HOME_PAGE_3_TOKEN", second.continuation)
    }

    @Test
    fun brandIdentityIsSentAsPageIdHeaderAndOnBehalfOfUser() = runBlocking {
        enqueueJson(Fixtures.text("home_logged_in_structure.json"))
        provider.home(ProviderCall(locale, auth(pageId = "1234567890")), null)
        val request = server.takeRequest()
        assertEquals("1234567890", request.headers["X-Goog-PageId"])
        val user = Fixtures.json.parseToJsonElement(request.body!!.utf8()).jsonObject["context"]!!.jsonObject["user"]!!.jsonObject
        assertEquals("1234567890", user["onBehalfOfUser"]!!.jsonPrimitive.content)
    }

    @Test
    fun homeWithoutAuthIsRefusedWithoutAnyNetworkCall() = runBlocking {
        val failure = runCatching { provider.home(ProviderCall(locale, null), null) }.exceptionOrNull()
        assertTrue(failure is AppError.LoginRequired)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun guestTrendingUsesWeeklyMostViewedVideoSearchWithoutAuth() = runBlocking {
        enqueueJson(Fixtures.text("search_videos.json"))
        val page = provider.trending(ProviderCall(locale, null), null)
        val request = server.takeRequest()
        assertTrue(request.path!!.contains("/search"))
        val body = Fixtures.json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(InnerTubeProvider.TRENDING_SEARCH_PARAMS, body["params"]!!.jsonPrimitive.content)
        assertTrue(body["query"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(page.items.isNotEmpty())
    }

    @Test
    fun loggedOutResponseToAuthenticatedRequestRaisesSessionExpired() = runBlocking {
        enqueueJson(Fixtures.text("home_guest.json"))
        val failure = runCatching { provider.home(ProviderCall(locale, auth("acc-9")), null) }.exceptionOrNull()
        assertTrue(failure is AppError.SessionExpired)
        assertEquals("acc-9", (failure as AppError.SessionExpired).accountId)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun unauthenticatedStatusRaisesSessionExpiredWithoutRetry() = runBlocking {
        enqueueJson("""{"error":{"code":401,"status":"UNAUTHENTICATED"}}""", 401)
        val failure = runCatching { provider.home(ProviderCall(locale, auth()), null) }.exceptionOrNull()
        assertTrue(failure is AppError.SessionExpired)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun serverErrorsAreRetriedWithLimitThenSurface() = runBlocking {
        repeat(3) { enqueueJson("{}", 503) }
        val failure = runCatching { provider.search(ProviderCall(locale, null), "q", SearchKind.All, SearchSort.Relevance, null) }.exceptionOrNull()
        assertTrue(failure is AppError.Network)
        assertEquals(503, (failure as AppError.Network).httpCode)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun transientErrorThenSuccessRecovers() = runBlocking {
        enqueueJson("{}", 500)
        enqueueJson(Fixtures.text("search_videos.json"))
        val page = provider.search(ProviderCall(locale, null), "lofi", SearchKind.All, SearchSort.Relevance, null)
        assertTrue(page.items.isNotEmpty())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun clientErrorsAreNotRetried() = runBlocking {
        enqueueJson("{}", 403)
        val failure = runCatching { provider.search(ProviderCall(locale, null), "x", SearchKind.All, SearchSort.Relevance, null) }.exceptionOrNull()
        assertEquals(403, (failure as AppError.Network).httpCode)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun guestSearchSendsNoCredentials() = runBlocking {
        enqueueJson(Fixtures.text("search_videos.json"))
        val page = provider.search(ProviderCall(locale, null), "nhạc lofi", SearchKind.Video, SearchSort.Relevance, null)
        val request = server.takeRequest()
        assertNull(request.headers["Authorization"])
        assertNull(request.headers["Cookie"])
        assertNull(request.headers["X-Goog-AuthUser"])
        val body = Fixtures.json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("nhạc lofi", body["query"]!!.jsonPrimitive.content)
        assertEquals(SearchKind.Video.params, body["params"]!!.jsonPrimitive.content)
        assertTrue(page.items.filterIsInstance<VideoItem>().size >= 8)
    }

    @Test
    fun twoAccountsNeverShareCookiesOnTheWire() = runBlocking {
        enqueueJson(Fixtures.text("home_logged_in_structure.json"))
        enqueueJson(Fixtures.text("home_logged_in_structure.json"))
        provider.home(ProviderCall(locale, auth("a", sapisid = "alice-secret")), null)
        provider.home(ProviderCall(locale, auth("b", sapisid = "bob-secret")), null)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertTrue(first.headers["Cookie"]!!.contains("alice-secret"))
        assertFalse(first.headers["Cookie"]!!.contains("bob-secret"))
        assertTrue(second.headers["Cookie"]!!.contains("bob-secret"))
        assertFalse(second.headers["Cookie"]!!.contains("alice-secret"))
    }

    @Test
    fun verifyAcceptsOnlyWhenYouTubeReturnsActiveAccountHeader() = runBlocking {
        enqueueJson(Fixtures.text("account_menu_logged_in_structure.json"))
        enqueueJson(Fixtures.text("accounts_list_structure.json"))
        val session = AccountSession("s1", "", null, null, cookies = jar().snapshot(), createdAt = 0)
        val result = provider.verify(session, locale)
        assertTrue(result is VerifyResult.Verified)
        result as VerifyResult.Verified
        assertEquals("Test User", result.profile.name)
        assertEquals(2, result.identities.size)
        assertEquals("/youtubei/v1/account/account_menu", server.takeRequest().url.encodedPath)
        assertEquals("/youtubei/v1/account/accounts_list", server.takeRequest().url.encodedPath)
    }

    @Test
    fun verifyRejectsGuestMenuEvenWithCookiesPresent() = runBlocking {
        enqueueJson(Fixtures.text("account_menu_guest.json"))
        val session = AccountSession("s1", "Looks Legit", "@legit", "https://x/y.jpg", cookies = jar().snapshot(), createdAt = 0)
        assertEquals(VerifyResult.LoggedOut, provider.verify(session, locale))
    }

    @Test
    fun verifyWithoutSapisidDoesNotTouchTheNetwork() = runBlocking {
        val session = AccountSession("s1", "n", null, null, cookies = listOf(StoredCookie("VISITOR_INFO1_LIVE", "v", "youtube.com")), createdAt = 0)
        assertEquals(VerifyResult.LoggedOut, provider.verify(session, locale))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun verifyReportsNetworkFailureAsFailedNotAsLoggedOut() = runBlocking {
        repeat(3) { enqueueJson("{}", 502) }
        val session = AccountSession("s1", "n", null, null, cookies = jar().snapshot(), createdAt = 0)
        assertTrue(provider.verify(session, locale) is VerifyResult.Failed)
    }

    @Test
    fun watchAndCommentsEndpointsParseRealResponses() = runBlocking {
        enqueueJson(Fixtures.text("watch_next.json"))
        enqueueJson(Fixtures.text("comments_page.json"))
        val info = provider.watch(ProviderCall(locale, null), "dQw4w9WgXcQ", null, null)
        assertNotNull(info.commentsToken)
        val comments = provider.comments(ProviderCall(locale, null), info.commentsToken!!)
        assertEquals(4, comments.comments.size)
        server.takeRequest()
        val body = Fixtures.json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals(info.commentsToken, body["continuation"]!!.jsonPrimitive.content)
    }

    @Test
    fun likeIsConfirmedOnlyByASuccessfulResponse() = runBlocking {
        enqueueJson("""{"responseContext":{},"actions":[{"addToToastAction":{}}]}""")
        provider.like(ProviderCall(locale, auth()), "abc", true)
        assertEquals("/youtubei/v1/like/like", server.takeRequest().url.encodedPath)
        enqueueJson("""{"error":{"code":400,"message":"nope","status":"INVALID_ARGUMENT"}}""")
        val failure = runCatching { provider.like(ProviderCall(locale, auth()), "abc", false) }.exceptionOrNull()
        assertTrue(failure is AppError.Rejected || failure is AppError.Network)
    }

    @Test
    fun playlistEditRequiresExplicitSuccessStatus() = runBlocking {
        enqueueJson("""{"status":"STATUS_SUCCEEDED"}""")
        provider.addToPlaylist(ProviderCall(locale, auth()), "PL1", "vid")
        val body = Fixtures.json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("PL1", body["playlistId"]!!.jsonPrimitive.content)
        enqueueJson("""{"responseContext":{}}""")
        val failure = runCatching { provider.addToPlaylist(ProviderCall(locale, auth()), "PL1", "vid") }.exceptionOrNull()
        assertTrue(failure is AppError.Rejected)
    }

    @Test
    fun commentIsAcceptedOnlyWhenServerReturnsCreateAction() = runBlocking {
        enqueueJson("""{"actions":[{"createCommentAction":{"contents":{}}}]}""")
        provider.postComment(ProviderCall(locale, auth()), "PARAMS", "hi")
        enqueueJson("""{"actions":[]}""")
        val failure = runCatching { provider.postComment(ProviderCall(locale, auth()), "PARAMS", "hi") }.exceptionOrNull()
        assertTrue(failure is AppError.Rejected)
    }
}
