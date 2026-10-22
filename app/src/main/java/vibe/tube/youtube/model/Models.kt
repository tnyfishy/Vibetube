package vibe.tube.youtube.model

import kotlinx.serialization.Serializable

@Serializable
data class Thumb(val url: String, val width: Int, val height: Int)

/** Largest available thumbnail (highest resolution). */
fun List<Thumb>.best(): String? {
    if (isEmpty()) return null
    val maxWidth = maxOf { it.width }
    return last { it.width == maxWidth }.url
}

fun List<Thumb>.bestFor(minWidth: Int): String? {
    if (isEmpty()) return null
    val sorted = sortedBy { it.width }
    return (sorted.firstOrNull { it.width >= minWidth } ?: sorted.last()).url
}

@Serializable
enum class VideoAvailability { Available, Private, Deleted, Unplayable }

@Serializable
sealed interface FeedItem {
    val id: String
}

@Serializable
data class VideoItem(
    override val id: String,
    val title: String,
    val channelName: String?,
    val channelId: String?,
    val channelAvatar: String?,
    val thumbnails: List<Thumb>,
    val durationText: String?,
    val durationSeconds: Int?,
    val viewCountText: String?,
    val publishedText: String?,
    val isLive: Boolean = false,
    val isShort: Boolean = false,
    val watchedPercent: Int? = null,
    val setVideoId: String? = null,
    val availability: VideoAvailability = VideoAvailability.Available,
    val playlistIndex: Int? = null,
) : FeedItem

@Serializable
data class PlaylistItem(
    override val id: String,
    val title: String,
    val ownerName: String?,
    val ownerId: String?,
    val videoCountText: String?,
    val thumbnails: List<Thumb>,
    val firstVideoId: String? = null,
    val updatedText: String? = null,
) : FeedItem

@Serializable
data class ChannelItem(
    override val id: String,
    val name: String,
    val handle: String?,
    val avatars: List<Thumb>,
    val subscriberText: String?,
    val description: String?,
    val subscribed: Boolean? = null,
) : FeedItem

data class Page<T>(
    val items: List<T>,
    val continuation: String?,
    val adsSkipped: Int = 0,
    val title: String? = null,
    val subtitle: String? = null,
    val totalText: String? = null,
)

data class ContentLocale(val language: String, val region: String) {
    val tag: String get() = "$language-$region"

    companion object {
        val Default = ContentLocale("en", "US")
    }
}

data class CommentSort(val title: String, val token: String, val selected: Boolean)

data class Comment(
    val id: String,
    val authorName: String,
    val authorChannelId: String?,
    val authorAvatar: String?,
    val text: String,
    val publishedText: String?,
    val likeCountText: String?,
    val replyCountText: String?,
    val isReply: Boolean,
    val isCreator: Boolean,
    val isOwn: Boolean,
    val isLiked: Boolean,
    val repliesToken: String?,
    val replyParams: String? = null,
)

data class CommentsPage(
    val comments: List<Comment>,
    val continuation: String?,
    val sorts: List<CommentSort>,
    val disabledMessage: String?,
    val totalText: String?,
    val createToken: String?,
)

data class ChannelRef(
    val id: String?,
    val name: String,
    val avatar: String?,
    val subscriberText: String?,
    val subscribed: Boolean?,
)

enum class LikeStatus { Like, Indifferent, Dislike }

data class WatchInfo(
    val videoId: String,
    val title: String,
    val viewCountText: String?,
    val dateText: String?,
    val description: String?,
    val channel: ChannelRef?,
    val likeCountText: String?,
    val likeStatus: LikeStatus?,
    val related: List<FeedItem>,
    val relatedContinuation: String?,
    val commentsToken: String?,
    val commentSorts: List<CommentSort>,
    val commentsCountText: String?,
    val queue: List<VideoItem>,
    val queueTitle: String?,
    val queueIndex: Int?,
    val autoplayVideoId: String?,
)

data class SearchSuggestion(val text: String)

enum class SearchKind(val params: String?) {
    All(null),
    Video("EgIQAQ%3D%3D"),
    Channel("EgIQAg%3D%3D"),
    Playlist("EgIQAw%3D%3D"),
}

enum class SearchSort(val paramsAll: String?, val paramsVideo: String?) {
    Relevance(null, null),
    UploadDate("CAI%3D", "CAISAhAB"),
    ViewCount("CAM%3D", "CAMSAhAB"),
    Rating("CAE%3D", "CAESAhAB"),
}

data class AccountProfile(
    val name: String,
    val handle: String?,
    val avatarUrl: String?,
    val loggedIn: Boolean,
)

data class PlaylistDetail(
    val id: String,
    val title: String,
    val ownerName: String?,
    val ownerId: String?,
    val countText: String?,
    val description: String?,
    val items: List<VideoItem>,
    val continuation: String?,
)

data class AddToPlaylistOption(
    val playlistId: String,
    val title: String,
    val containsVideo: Boolean,
    val privacy: String?,
)

enum class PlaylistPrivacy(val wire: String) {
    Private("PRIVATE"),
    Unlisted("UNLISTED"),
    Public("PUBLIC"),
}

data class ChannelDetail(
    val id: String,
    val name: String,
    val handle: String?,
    val avatar: String?,
    val subscriberText: String?,
    val description: String?,
    val subscribed: Boolean?,
)
