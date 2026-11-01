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

    suspend fun homeChip(call: ProviderCall, chip: vibe.tube.youtube.model.HomeChip, continuation: String?): Page<FeedItem> = home(call, continuation ?: chip.continuation)
    suspend fun home(call: ProviderCall, continuation: String?): Page<FeedItem>

    suspend fun trending(call: ProviderCall, continuation: String?): Page<FeedItem>
    suspend fun subscriptionsFeed(call: ProviderCall, continuation: String?): Page<FeedItem>
    suspend fun subscribedChannels(call: ProviderCall, continuation: String?): Page<FeedItem>
    suspend fun userPlaylists(call: ProviderCall, continuation: String?): Page<FeedItem>
    suspend fun history(call: ProviderCall, continuation: String?): Page<FeedItem>

    suspend fun shorts(call: ProviderCall, continuation: String?): Page<FeedItem> = Page(emptyList(), null)

    /**
     * A feed built from YouTube's own destination pages ([destinations] are their browse ids), widened page by page
     * with what YouTube recommends next to the videos already shown.
     */
    suspend fun explore(call: ProviderCall, destinations: List<String>, continuation: String?): Page<FeedItem> = Page(emptyList(), null)

    /** Records a view of [videoId] in the signed-in account's YouTube watch history, among its Shorts when [short]. */
    suspend fun markWatched(call: ProviderCall, videoId: String, short: Boolean = false) {}

    suspend fun playlist(call: ProviderCall, playlistId: String, continuation: String?): PlaylistDetail
    suspend fun search(call: ProviderCall, query: String, kind: SearchKind, sort: SearchSort, continuation: String?): Page<FeedItem>

    suspend fun topic(call: ProviderCall, query: String, playlists: Boolean, live: Boolean, continuation: String?): Page<FeedItem> =
        search(call, query, if (playlists) SearchKind.Playlist else SearchKind.Video, SearchSort.Relevance, continuation)
    suspend fun suggestions(locale: ContentLocale, query: String): List<String>

    suspend fun channelTab(call: ProviderCall, channelId: String, tab: String, continuation: String?): Page<FeedItem> = throw UnsupportedOperationException("Channel tab unavailable")
    suspend fun channel(call: ProviderCall, channelId: String): ChannelDetail
    suspend fun channelAbout(call: ProviderCall, channelId: String): ChannelDetail = channel(call, channelId)
    suspend fun channelVideos(call: ProviderCall, channelId: String, continuation: String?, sort: vibe.tube.youtube.model.ChannelSort = vibe.tube.youtube.model.ChannelSort.Latest): Page<FeedItem>

    suspend fun watch(call: ProviderCall, videoId: String, playlistId: String?, playlistIndex: Int?): WatchInfo
    suspend fun related(call: ProviderCall, continuation: String): Page<FeedItem>
    suspend fun comments(call: ProviderCall, continuation: String): CommentsPage

    suspend fun like(call: ProviderCall, videoId: String, liked: Boolean)

    suspend fun setNotifyLevel(call: ProviderCall, params: String)

    suspend fun notifications(call: ProviderCall, continuation: String?): vibe.tube.youtube.model.NotificationsPage

    suspend fun unseenNotificationCount(call: ProviderCall): Int

    suspend fun rate(call: ProviderCall, videoId: String, status: vibe.tube.youtube.model.LikeStatus)
    suspend fun subscribe(call: ProviderCall, channelId: String, subscribed: Boolean)
    suspend fun playlistsForVideo(call: ProviderCall, videoId: String): List<AddToPlaylistOption>
    suspend fun addToPlaylist(call: ProviderCall, playlistId: String, videoId: String)
    suspend fun removeFromPlaylist(call: ProviderCall, playlistId: String, videoId: String, setVideoId: String?)
    suspend fun createPlaylist(call: ProviderCall, title: String, privacy: PlaylistPrivacy, videoId: String?): String
    suspend fun renamePlaylist(call: ProviderCall, playlistId: String, title: String?, description: String?)
    suspend fun deletePlaylist(call: ProviderCall, playlistId: String)
    suspend fun postComment(call: ProviderCall, params: String, text: String)
    suspend fun performCommentAction(call: ProviderCall, action: vibe.tube.youtube.model.CommentAction) {
        throw AppError.Rejected("comment", "unsupported")
    }
    suspend fun postReply(call: ProviderCall, params: String, text: String)
}
