package vibe.tube.support

import kotlinx.coroutines.CoroutineScope
import vibe.tube.account.AccountIdentity
import vibe.tube.account.AccountSession
import vibe.tube.account.InMemorySessionVault
import vibe.tube.account.SessionHooks
import vibe.tube.account.SessionManager
import vibe.tube.account.StoredCookie
import vibe.tube.data.repo.FeedCache
import vibe.tube.data.repo.InMemoryFeedCache
import vibe.tube.youtube.ProviderCall
import vibe.tube.youtube.VerifyResult
import vibe.tube.youtube.YouTubeProvider
import vibe.tube.youtube.model.AccountProfile
import vibe.tube.youtube.model.AddToPlaylistOption
import vibe.tube.youtube.model.ChannelDetail
import vibe.tube.youtube.model.CommentsPage
import vibe.tube.youtube.model.ContentLocale
import vibe.tube.youtube.model.FeedItem
import vibe.tube.youtube.model.Page
import vibe.tube.youtube.model.PlaylistDetail
import vibe.tube.youtube.model.PlaylistPrivacy
import vibe.tube.youtube.model.SearchKind
import vibe.tube.youtube.model.SearchSort
import vibe.tube.youtube.model.VideoItem
import vibe.tube.youtube.model.WatchInfo

fun video(id: String, title: String = "title $id") = VideoItem(
    id = id, title = title, channelName = "ch", channelId = "UC1", channelAvatar = null,
    thumbnails = emptyList(), durationText = "1:00", durationSeconds = 60, viewCountText = null, publishedText = null,
)

open class FakeProvider : YouTubeProvider {
    override val name = "fake"
    val verifyResults = HashMap<String, VerifyResult>()
    var defaultVerify: (AccountSession) -> VerifyResult = { s ->
        VerifyResult.Verified(AccountProfile(s.cookies.first { it.name == "SAPISID" }.value, null, null, true), emptyList(), null, s.cookies)
    }

    override suspend fun verify(session: AccountSession, locale: ContentLocale): VerifyResult =
        verifyResults[session.id] ?: defaultVerify(session)

    override suspend fun home(call: ProviderCall, continuation: String?): Page<FeedItem> = notImplemented()
    override suspend fun subscriptionsFeed(call: ProviderCall, continuation: String?): Page<FeedItem> = notImplemented()
    override suspend fun subscribedChannels(call: ProviderCall, continuation: String?): Page<FeedItem> = notImplemented()
    override suspend fun userPlaylists(call: ProviderCall, continuation: String?): Page<FeedItem> = notImplemented()
    override suspend fun history(call: ProviderCall, continuation: String?): Page<FeedItem> = notImplemented()
    override suspend fun playlist(call: ProviderCall, playlistId: String, continuation: String?): PlaylistDetail = notImplemented()
    override suspend fun search(call: ProviderCall, query: String, kind: SearchKind, sort: SearchSort, continuation: String?): Page<FeedItem> = notImplemented()
    override suspend fun suggestions(locale: ContentLocale, query: String): List<String> = notImplemented()
    override suspend fun channel(call: ProviderCall, channelId: String): ChannelDetail = notImplemented()
    override suspend fun channelVideos(call: ProviderCall, channelId: String, continuation: String?): Page<FeedItem> = notImplemented()
    override suspend fun watch(call: ProviderCall, videoId: String, playlistId: String?, playlistIndex: Int?): WatchInfo = notImplemented()
    override suspend fun related(call: ProviderCall, continuation: String): Page<FeedItem> = notImplemented()
    override suspend fun comments(call: ProviderCall, continuation: String): CommentsPage = notImplemented()
    override suspend fun like(call: ProviderCall, videoId: String, liked: Boolean): Unit = notImplemented()
    override suspend fun subscribe(call: ProviderCall, channelId: String, subscribed: Boolean): Unit = notImplemented()
    override suspend fun playlistsForVideo(call: ProviderCall, videoId: String): List<AddToPlaylistOption> = notImplemented()
    override suspend fun addToPlaylist(call: ProviderCall, playlistId: String, videoId: String): Unit = notImplemented()
    override suspend fun removeFromPlaylist(call: ProviderCall, playlistId: String, videoId: String, setVideoId: String?): Unit = notImplemented()
    override suspend fun createPlaylist(call: ProviderCall, title: String, privacy: PlaylistPrivacy, videoId: String?): String = notImplemented()
    override suspend fun renamePlaylist(call: ProviderCall, playlistId: String, title: String?, description: String?): Unit = notImplemented()
    override suspend fun deletePlaylist(call: ProviderCall, playlistId: String): Unit = notImplemented()
    override suspend fun postComment(call: ProviderCall, params: String, text: String): Unit = notImplemented()
    override suspend fun postReply(call: ProviderCall, params: String, text: String): Unit = notImplemented()

    private fun notImplemented(): Nothing = throw NotImplementedError()
}

class RecordingHooks : SessionHooks {
    val cancelled = mutableListOf<String>()
    val wiped = mutableListOf<String>()
    var cache: FeedCache? = null

    override fun cancelRequests(accountId: String) {
        cancelled += accountId
    }

    override suspend fun wipeAccountData(accountId: String) {
        wiped += accountId
        cache?.clearAccount(accountId)
    }
}

fun cookiesFor(sapisid: String): List<StoredCookie> = listOf(
    StoredCookie("SAPISID", sapisid, "youtube.com"),
    StoredCookie("__Secure-1PAPISID", "1p-$sapisid", "youtube.com"),
    StoredCookie("__Secure-3PAPISID", "3p-$sapisid", "youtube.com"),
    StoredCookie("SID", "sid-$sapisid", "youtube.com"),
)

class TestSessionEnv(
    val provider: FakeProvider = FakeProvider(),
    val hooks: RecordingHooks = RecordingHooks(),
    val vault: InMemorySessionVault = InMemorySessionVault(),
    val cache: InMemoryFeedCache = InMemoryFeedCache(),
    var now: Long = 1_000_000L,
    val locale: ContentLocale = ContentLocale("en", "US"),
) {
    init {
        hooks.cache = cache
    }

    val manager = SessionManager(vault, provider, hooks, { locale }, { now })
}

val identity = AccountIdentity("primary", null, "n", null, null, null)

fun kotlinx.coroutines.test.TestScope.appScope(): CoroutineScope =
    CoroutineScope(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler) + kotlinx.coroutines.SupervisorJob())
