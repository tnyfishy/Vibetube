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
            ChannelRef(
                id = it.firstString("browseId") ?: subscribeButton.at("channelId").str,
                name = it.at("title").textOf().orEmpty(),
                avatar = ItemParsers.thumbs(it.at("thumbnail")).lastOrNull()?.url,
                subscriberText = it.at("subscriberCountText").textOf(),
                subscribed = subscribeButton.at("subscribed").bool ?: subscribedEntity,
                badge = ItemParsers.badgeOf(it.at("badges")),
                handle = it.firstString("canonicalBaseUrl")?.substringAfterLast('/')?.takeIf { h -> h.startsWith("@") },
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
        )
    }

    private fun commentSectionToken(primary: List<JsonElement>): String? =
        primary.firstNotNullOfOrNull { c ->
            c.at("itemSectionRenderer")?.takeIf { it.at("sectionIdentifier").str == "comment-item-section" }
                ?.firstString("token")
        }

    fun entityPayloads(root: JsonElement): List<JsonElement> =
        root.path("frameworkUpdates", "entityBatchUpdate", "mutations").list().mapNotNull { it.at("payload") }

    /** Title and videos of the queue panel in a `next` response (how YouTube Mixes, "RD…" lists, are delivered). */
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
