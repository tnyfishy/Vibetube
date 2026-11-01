package vibe.tube.youtube.parser

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import vibe.tube.youtube.model.ChannelRef
import vibe.tube.youtube.model.CommentSort
import vibe.tube.youtube.model.FeedItem
import vibe.tube.youtube.model.LikeStatus
import vibe.tube.youtube.model.VideoItem
import vibe.tube.youtube.model.WatchInfo

object WatchParser {

    fun parse(root: JsonElement, videoId: String): WatchInfo {
        val results = root.path("contents", "twoColumnWatchNextResults")
        val primary = results.path("results", "results", "contents").list()
        val primaryInfo = primary.firstNotNullOfOrNull { it.at("videoPrimaryInfoRenderer") }
        val secondaryInfo = primary.firstNotNullOfOrNull { it.at("videoSecondaryInfoRenderer") }

        val title = primaryInfo.at("title").textOf().orEmpty()
        val views = primaryInfo.path("viewCount", "videoViewCountRenderer", "viewCount").textOf()
        val date = primaryInfo.at("dateText").textOf()
        val description = secondaryInfo.at("attributedDescription").textOf()
            ?: secondaryInfo.at("description").textOf()

        val owner = secondaryInfo.path("owner", "videoOwnerRenderer")
        val subscribeButton = secondaryInfo.path("subscribeButton", "subscribeButtonRenderer")
        val entities = entityPayloads(root)
        val subscribedEntity = entities.firstNotNullOfOrNull { it.at("subscriptionStateEntity") }?.at("subscribed").bool
        val channel = owner?.let {
            // A collaboration names no single owner: its title joins every channel and the channels themselves are listed
            // in the owner's dialog. The first one there is the uploader, and stands in for the owner.
            val collaborators = collaborators(it)
            val lead = collaborators.firstOrNull()
            ChannelRef(
                id = it.firstString("browseId") ?: subscribeButton.at("channelId").str,
                name = it.at("title").textOf() ?: lead?.name ?: it.path("attributedTitle", "content").str.orEmpty(),
                avatar = ItemParsers.thumbs(it.at("thumbnail")).lastOrNull()?.url ?: lead?.avatar
                    ?: it.path("avatarStack", "avatarStackViewModel", "avatars").at(0).path("avatarViewModel", "image", "sources").list().lastOrNull().at("url").str,
                subscriberText = it.at("subscriberCountText").textOf() ?: lead?.subscriberText,
                subscribed = subscribeButton.at("subscribed").bool ?: subscribedEntity ?: lead?.subscribed,
                badge = ItemParsers.badgeOf(it.at("badges")),
                handle = if (it.at("title") != null) {
                    ItemParsers.decodeHandle(it.firstString("canonicalBaseUrl")?.substringAfterLast('/')?.takeIf { h -> h.startsWith("@") })
                } else lead?.handle,
                notify = NotifyParser.parse(secondaryInfo),
                collaborators = collaborators.takeIf { list -> list.size > 1 }.orEmpty(),
            )
        }

        val likeStatus = entities.firstNotNullOfOrNull { it.at("likeStatusEntity") }?.at("likeStatus").str?.let {
            when (it) {
                "LIKE" -> LikeStatus.Like
                "DISLIKE" -> LikeStatus.Dislike
                else -> LikeStatus.Indifferent
            }
        }
        val likeCount = primaryInfo.at("videoActions").firstObject("likeButtonViewModel")
            ?.firstString("title")

        val relatedPage = FeedParser.parse(results.at("secondaryResults") ?: results ?: JsonObject(emptyMap()))
        val related: List<FeedItem> = relatedPage.items.filter { it.id != videoId }

        val panel = root.at("engagementPanels").list().firstNotNullOfOrNull {
            it.at("engagementPanelSectionListRenderer")?.takeIf { p ->
                p.at("targetId").str == "engagement-panel-comments-section"
            }
        }
        val sorts = panel.firstObject("sortFilterSubMenuRenderer").at("subMenuItems").list().mapNotNull {
            val token = it.firstString("token") ?: return@mapNotNull null
            CommentSort(it.at("title").textOf().orEmpty(), token, it.at("selected").bool == true)
        }
        val commentsToken = sorts.firstOrNull { it.selected }?.token
            ?: sorts.firstOrNull()?.token
            ?: commentSectionToken(primary)
        val commentsCount = panel.firstObject("engagementPanelTitleHeaderRenderer")?.at("contextualInfo").textOf()
            ?: root.firstObject("commentsEntryPointHeaderRenderer").at("commentCount").textOf()
            ?: root.firstObject("commentsHeaderRenderer").at("countText").textOf()

        val queuePanel = results.path("playlist", "playlist")
        val queue = queuePanel.at("contents").list().mapNotNull { parsePanelVideo(it.at("playlistPanelVideoRenderer")) }
        val autoplay = results.path("autoplay", "autoplay", "sets").at(0).path("autoplayVideo")
            .firstObject("watchEndpoint").at("videoId").str

        return WatchInfo(
            videoId = videoId,
            title = title,
            viewCountText = views,
            dateText = date,
            description = description,
            channel = channel,
            likeCountText = likeCount,
            likeStatus = likeStatus,
            related = related,
            relatedContinuation = relatedPage.continuation,
            commentsToken = commentsToken,
            commentSorts = sorts,
            commentsCountText = commentsCount,
            queue = queue,
            queueTitle = queuePanel.at("title").textOf(),
            queueIndex = queuePanel.at("currentIndex").int,
            autoplayVideoId = autoplay,
            chapters = chapters(root),
            descriptionLinks = (secondaryInfo.at("attributedDescription") ?: secondaryInfo.at("description")).textLinks(),
        )
    }

    internal fun chapters(root: JsonElement): List<vibe.tube.youtube.model.VideoChapter> {
        fun millis(e: JsonElement?): Long? = (e as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
        val markers = root.firstObject("multiMarkersPlayerBarRenderer").at("markersMap").list()
        val byKind = markers.associate { entry -> (entry.at("key").str ?: "") to entry.at("value").at("chapters").list() }
        val fromBar = (byKind["DESCRIPTION_CHAPTERS"].orEmpty().ifEmpty { byKind["AUTO_CHAPTERS"].orEmpty() })
            .ifEmpty { byKind.values.firstOrNull { it.isNotEmpty() }.orEmpty() }
            .mapNotNull { node ->
                val chapter = node.at("chapterRenderer") ?: return@mapNotNull null
                val title = chapter.at("title").textOf()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val start = millis(chapter.at("timeRangeStartMillis")) ?: return@mapNotNull null
                vibe.tube.youtube.model.VideoChapter(start, title)
            }
        val chapters = fromBar.ifEmpty {
            root.firstObject("macroMarkersListRenderer").at("contents").list().mapNotNull { node ->
                val item = node.at("macroMarkersListItemRenderer") ?: return@mapNotNull null
                val title = item.at("title").textOf()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val seconds = item.path("onTap", "watchEndpoint", "startTimeSeconds").let(::millis) ?: return@mapNotNull null
                vibe.tube.youtube.model.VideoChapter(seconds * 1000L, title)
            }
        }
        return chapters.distinctBy { it.startMs }.sortedBy { it.startMs }
    }

    /** The channels a collaboration's owner dialog lists, uploader first; empty for an ordinary owner. */
    private fun collaborators(owner: JsonElement): List<ChannelRef> =
        owner.at("navigationEndpoint").firstObject("listViewModel").at("listItems").list().mapNotNull { node ->
            val item = node.at("listItemViewModel") ?: return@mapNotNull null
            val avatar = item.path("leadingAccessory", "avatarViewModel")
            // The subtitle reads "@handle • 943K subscribers", wrapped in direction marks.
            val subtitle = item.path("subtitle", "content").str?.filterNot { c -> c in BIDI_MARKS }
            val parts = subtitle?.split('•')?.map(String::trim).orEmpty()
            ChannelRef(
                id = item.firstString("browseId"),
                name = item.path("title", "content").str?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null,
                avatar = avatar.path("image", "sources").list().lastOrNull().at("url").str,
                subscriberText = parts.getOrNull(1)?.takeIf(String::isNotBlank),
                subscribed = item.firstObject("subscribeState")?.at("subscribed").bool,
                handle = ItemParsers.decodeHandle(
                    avatar.firstString("canonicalBaseUrl")?.substringAfterLast('/')?.takeIf { h -> h.startsWith("@") }
                        ?: parts.firstOrNull()?.takeIf { h -> h.startsWith("@") },
                ),
            )
        }

    private const val BIDI_MARKS = "‎‏⁦⁧⁨⁩"

    private fun commentSectionToken(primary: List<JsonElement>): String? =
        primary.firstNotNullOfOrNull { c ->
            c.at("itemSectionRenderer")?.takeIf { it.at("sectionIdentifier").str == "comment-item-section" }
                ?.firstString("token")
        }

    fun entityPayloads(root: JsonElement): List<JsonElement> =
        root.path("frameworkUpdates", "entityBatchUpdate", "mutations").list().mapNotNull { it.at("payload") }

    fun parseQueuePanel(root: JsonElement): Pair<String?, List<VideoItem>> {
        val panel = root.path("contents", "twoColumnWatchNextResults", "playlist", "playlist")
        val items = panel.at("contents").list().mapNotNull { parsePanelVideo(it.at("playlistPanelVideoRenderer")) }
        return panel.at("title").textOf() to items
    }

    private fun parsePanelVideo(node: JsonElement?): VideoItem? {
        node ?: return null
        val id = node.at("videoId").str ?: return null
        val length = node.at("lengthText").textOf()
        return VideoItem(
            id = id,
            title = node.at("title").textOf().orEmpty(),
            channelName = node.at("shortBylineText").textOf() ?: node.at("longBylineText").textOf(),
            channelId = node.at("shortBylineText").firstString("browseId"),
            channelAvatar = null,
            thumbnails = ItemParsers.thumbs(node.at("thumbnail")),
            durationText = length,
            durationSeconds = ItemParsers.durationSeconds(length),
            viewCountText = null,
            publishedText = null,
            playlistIndex = node.path("navigationEndpoint", "watchEndpoint", "index").int,
            channelBadge = ItemParsers.badgeOf(node.at("longBylineText")),
        )
    }
}
