package vibe.tube.youtube.model

import kotlinx.serialization.Serializable

@Serializable
data class Thumb(val url: String, val width: Int, val height: Int)

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
enum class ChannelBadge { None, Verified, Artist }

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
    val channelBadge: ChannelBadge = ChannelBadge.None,
    val membersOnly: Boolean = false,
    val historyGroup: String? = null,
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

val PlaylistItem.mixFirstVideoId: String?
    get() = firstVideoId ?: id.removePrefix("RD").takeIf { id.startsWith("RD") && isVideoId(it) }

private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

fun isVideoId(id: String): Boolean = VIDEO_ID.matches(id)

@Serializable
data class ChannelItem(
    override val id: String,
    val name: String,
    val handle: String?,
    val avatars: List<Thumb>,
    val subscriberText: String?,
    val description: String?,
    val subscribed: Boolean? = null,
    val badge: ChannelBadge = ChannelBadge.None,
) : FeedItem

data class HomeChip(val title: String, val browseId: String?, val params: String?, val continuation: String?, val selected: Boolean = false) {
    val key: String get() = listOf(browseId, params, continuation).joinToString("|")
}

data class Page<T>(
    val items: List<T>,
    val continuation: String?,
    val adsSkipped: Int = 0,
    val chips: List<HomeChip> = emptyList(),
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

data class TextLink(val start: Int, val end: Int, val url: String)

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
    val creatorHeart: Boolean = false,
    val likeAction: CommentAction? = null,
    val unlikeAction: CommentAction? = null,
    val heartAction: CommentAction? = null,
    val unheartAction: CommentAction? = null,
    val isDisliked: Boolean = false,
    val dislikeAction: CommentAction? = null,
    val undislikeAction: CommentAction? = null,
    val creatorHeartAvatar: String? = null,
    val authorBadge: ChannelBadge = ChannelBadge.None,
    val previewReplies: List<Comment> = emptyList(),
    val links: List<TextLink> = emptyList(),
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
    val badge: ChannelBadge = ChannelBadge.None,
                                            
    val handle: String? = null,
                                                                                               
    val notify: ChannelNotify? = null,
    val banner: String? = null,
    val contentTabs: List<String> = emptyList(),
    /** Every channel of a collaboration, this one first; empty for a video with a single owner. */
    val collaborators: List<ChannelRef> = emptyList(),
)

enum class NotifyLevel { All, Personalized, None }

data class ChannelNotify(val current: NotifyLevel, val params: Map<NotifyLevel, String>)

data class NotificationItem(
    val id: String,
    val title: String,
    val timeText: String?,
    val avatar: String?,
    val thumbnail: String?,
    val videoId: String?,
    val channelPath: String?,
    val read: Boolean,
                                                                                                                          
    val target: NotificationTarget? = null,
)

sealed interface NotificationTarget {
    data class Video(val videoId: String, val startSeconds: Int? = null, val playlistId: String? = null, val commentId: String? = null, val openComments: Boolean = commentId != null) : NotificationTarget
    data class Channel(val channelId: String) : NotificationTarget
    data class Playlist(val playlistId: String) : NotificationTarget

    fun toUrl(): String = when (this) {
        is Video -> buildString {
            append("https://www.youtube.com/watch?v=").append(videoId)
            startSeconds?.let { append("&t=").append(it).append('s') }
            playlistId?.let { append("&list=").append(it) }
            commentId?.let { append("&lc=").append(it) }
            if (openComments && commentId == null) append("&comments=1")
        }
        is Channel -> "https://www.youtube.com/channel/$channelId"
        is Playlist -> "https://www.youtube.com/playlist?list=$playlistId"
    }

    companion object {
        private val ID = Regex("[A-Za-z0-9_-]+")
        private val DURATION = Regex("""^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$""")

        fun fromUrl(url: String?): NotificationTarget? {
            if (url.isNullOrBlank()) return null
            val hasScheme = "://" in url
            val noScheme = url.substringAfter("://")
            val host = if (hasScheme) noScheme.substringBefore('/') else ""
            val pathAndQuery = if (hasScheme) noScheme.removePrefix(host) else url
            val path = pathAndQuery.substringBefore('?').substringBefore('#')
            val query = pathAndQuery.substringAfter('?', "").substringBefore('#').split('&')
                .mapNotNull { part ->
                    part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to runCatching { java.net.URLDecoder.decode(it[1], "UTF-8") }.getOrDefault(it[1]) }
                }
                .toMap()
            fun id(value: String?) = value?.takeIf { ID.matches(it) }
            val video = when {
                host.endsWith("youtu.be") -> id(path.trim('/').substringBefore('/'))
                path == "/watch" -> id(query["v"])
                path.startsWith("/shorts/") || path.startsWith("/live/") -> id(path.split('/').getOrNull(2))
                else -> null
            }
            if (video != null && isVideoId(video)) {
                val comment = (query["lc"] ?: query["comment_id"])?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,512}")) }
                return Video(video, parseSeconds(query["t"] ?: query["start"]), id(query["list"]), comment, comment != null || query["comments"] == "1")
            }
            if (path.startsWith("/channel/")) id(path.split('/').getOrNull(2))?.takeIf { it.startsWith("UC") }?.let { return Channel(it) }
            if (path == "/playlist") id(query["list"])?.let { return Playlist(it) }
            return null
        }

        fun parseSeconds(t: String?): Int? {
            if (t.isNullOrBlank()) return null
            t.toIntOrNull()?.let { return it.takeIf { s -> s > 0 } }
            val m = DURATION.matchEntire(t) ?: return null
            val (h, min, sec) = m.destructured
            val total = (h.toIntOrNull() ?: 0) * 3600 + (min.toIntOrNull() ?: 0) * 60 + (sec.toIntOrNull() ?: 0)
            return total.takeIf { it > 0 }
        }

        fun fromBrowseId(browseId: String?): NotificationTarget? = when {
            browseId == null -> null
            browseId.startsWith("UC") -> Channel(browseId)
            browseId.startsWith("VL") -> Playlist(browseId.removePrefix("VL"))
            else -> null
        }
    }
}

data class NotificationsPage(val items: List<NotificationItem>, val continuation: String?)

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
                                                                                                                    
    val chapters: List<VideoChapter> = emptyList(),
    val descriptionLinks: List<TextLink> = emptyList(),
)

data class VideoChapter(val startMs: Long, val title: String)


enum class SearchKind(val params: String?) {
    All(null),
    Video("EgIQAQ%3D%3D"),
    Shorts("EgIQCQ%3D%3D"),
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

enum class ChannelSort(val chipIndex: Int) { Latest(0), Popular(1), Oldest(2) }

data class ChannelDetail(
    val id: String,
    val name: String,
    val handle: String?,
    val avatar: String?,
    val subscriberText: String?,
    val description: String?,
    val subscribed: Boolean?,
    val badge: ChannelBadge = ChannelBadge.None,
    val notify: ChannelNotify? = null,
    val banner: String? = null,
    val contentTabs: List<String> = emptyList(),
    val aboutDetails: List<Pair<String, String>> = emptyList(),
    val links: List<Pair<String, String>> = emptyList(),
)

data class CommentAction(val params: String)
