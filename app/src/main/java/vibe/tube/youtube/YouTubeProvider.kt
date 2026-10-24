package vibe.tube.youtube

import vibe.tube.account.AccountIdentity
import vibe.tube.account.AccountSession
import vibe.tube.account.StoredCookie
import vibe.tube.core.AppError
import vibe.tube.network.RequestAuth
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
import vibe.tube.youtube.model.WatchInfo

data class ProviderCall(val locale: ContentLocale, val auth: RequestAuth?)

sealed interface VerifyResult {
    data class Verified(
        val profile: AccountProfile,
        val identities: List<AccountIdentity>,
        val visitorData: String?,
        val cookies: List<StoredCookie>,
    ) : VerifyResult

    data object LoggedOut : VerifyResult
    data class Failed(val error: AppError) : VerifyResult
}

interface YouTubeProvider {
    val name: String

    suspend fun verify(session: AccountSession, locale: ContentLocale): VerifyResult

    suspend fun home(call: ProviderCall, continuation: String?): Page<FeedItem>

    /** Public "popular right now" feed used by guest mode (YouTube's guest home is empty and the Trending tab is gone). */
    suspend fun trending(call: ProviderCall, continuation: String?): Page<FeedItem>
    suspend fun subscriptionsFeed(call: ProviderCall, continuation: String?): Page<FeedItem>
    suspend fun subscribedChannels(call: ProviderCall, continuation: String?): Page<FeedItem>
    suspend fun userPlaylists(call: ProviderCall, continuation: String?): Page<FeedItem>
    suspend fun history(call: ProviderCall, continuation: String?): Page<FeedItem>

    /** Short vertical videos for the Shorts tab (YouTube search results narrowed to Shorts). */
    suspend fun shorts(call: ProviderCall, continuation: String?): Page<FeedItem> = Page(emptyList(), null)

    suspend fun playlist(call: ProviderCall, playlistId: String, continuation: String?): PlaylistDetail
    suspend fun search(call: ProviderCall, query: String, kind: SearchKind, sort: SearchSort, continuation: String?): Page<FeedItem>
    suspend fun suggestions(locale: ContentLocale, query: String): List<String>

    suspend fun channel(call: ProviderCall, channelId: String): ChannelDetail
    suspend fun channelVideos(call: ProviderCall, channelId: String, continuation: String?): Page<FeedItem>

    suspend fun watch(call: ProviderCall, videoId: String, playlistId: String?, playlistIndex: Int?): WatchInfo
    suspend fun related(call: ProviderCall, continuation: String): Page<FeedItem>
    suspend fun comments(call: ProviderCall, continuation: String): CommentsPage

    suspend fun like(call: ProviderCall, videoId: String, liked: Boolean)
    suspend fun subscribe(call: ProviderCall, channelId: String, subscribed: Boolean)
    suspend fun playlistsForVideo(call: ProviderCall, videoId: String): List<AddToPlaylistOption>
    suspend fun addToPlaylist(call: ProviderCall, playlistId: String, videoId: String)
    suspend fun removeFromPlaylist(call: ProviderCall, playlistId: String, videoId: String, setVideoId: String?)
    suspend fun createPlaylist(call: ProviderCall, title: String, privacy: PlaylistPrivacy, videoId: String?): String
    suspend fun renamePlaylist(call: ProviderCall, playlistId: String, title: String?, description: String?)
    suspend fun deletePlaylist(call: ProviderCall, playlistId: String)
    suspend fun postComment(call: ProviderCall, params: String, text: String)
    suspend fun postReply(call: ProviderCall, params: String, text: String)
}
