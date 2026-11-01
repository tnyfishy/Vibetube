package vibe.tube.youtube.parser

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import vibe.tube.youtube.model.ChannelNotify
import vibe.tube.youtube.model.NotificationItem
import vibe.tube.youtube.model.NotificationTarget
import vibe.tube.youtube.model.NotificationsPage
import vibe.tube.youtube.model.NotifyLevel

object NotifyParser {
    fun parse(root: JsonElement?): ChannelNotify? {
        if (root == null) return null
        val found = ArrayList<Pair<String?, String>>()
        root.walk { key, value ->
            when (key) {
                "menuServiceItemRenderer" -> {
                    val params = value.firstObject("modifyChannelNotificationPreferenceEndpoint").at("params").str
                    if (params != null) found += value.at("icon").at("iconType").str to params
                    false
                }
                "modifyChannelNotificationPreferenceEndpoint" -> {
                    value.at("params").str?.let { found += null to it }
                    false
                }
                else -> true
            }
        }
        if (found.isEmpty()) return null
        val params = LinkedHashMap<NotifyLevel, String>()
        found.forEachIndexed { index, (icon, p) ->
            val level = levelOf(icon) ?: NotifyLevel.entries.getOrNull(index) ?: return@forEachIndexed
            params.putIfAbsent(level, p)
        }
        if (params.isEmpty()) return null
        val toggle = root.firstObject("subscriptionNotificationToggleButtonRenderer")
        val currentId = (toggle.at("currentStateId") as? JsonPrimitive)?.content
        val currentIcon = toggle.at("states").list()
            .firstOrNull { (it.at("stateId") as? JsonPrimitive)?.content == currentId }
            ?.firstString("iconType")
        return ChannelNotify(current = levelOf(currentIcon) ?: NotifyLevel.Personalized, params = params)
    }

    private fun levelOf(icon: String?): NotifyLevel? = when {
        icon == null -> null
        icon.contains("ACTIVE") -> NotifyLevel.All
        icon.contains("OFF") -> NotifyLevel.None
        icon.contains("NONE") -> NotifyLevel.Personalized
        else -> null
    }
}

object NotificationsParser {
    fun parse(root: JsonElement): NotificationsPage {
        val items = ArrayList<NotificationItem>()
        var continuation: String? = null
        root.walk { key, value ->
            when (key) {
                "notificationRenderer" -> {
                    parseItem(value)?.let(items::add)
                    false
                }
                "continuationItemRenderer" -> {
                    FeedParser.tokenOf(key, value)?.let { continuation = it }
                    false
                }
                else -> true
            }
        }
        return NotificationsPage(items.distinctBy { it.id }, continuation)
    }

    private fun parseItem(r: JsonElement): NotificationItem? {
        val title = r.at("shortMessage").textOf() ?: return null
        val videoId = r.firstObject("watchEndpoint").at("videoId").str
        val navigation = r.at("navigationEndpoint")
        val url = navigation.path("commandMetadata", "webCommandMetadata", "url").str
            ?: navigation.firstObject("urlEndpoint").at("url").str
            ?: r.firstObject("watchEndpoint").path("commandMetadata", "webCommandMetadata", "url").str
            ?: run {
                var link: String? = null
                r.walk { k, value ->
                    if (k == "url" && NotificationTarget.fromUrl(value.str) != null && link == null) link = value.str
                    true
                }
                link
            }
        val id = r.at("notificationId").str ?: videoId ?: (title + r.at("sentTimeText").textOf())
        return NotificationItem(
            id = id,
            title = title,
            timeText = r.at("sentTimeText").textOf(),
            avatar = ItemParsers.thumbs(r.at("thumbnail")).lastOrNull()?.url,
            thumbnail = ItemParsers.thumbs(r.at("videoThumbnail")).lastOrNull()?.url,
            videoId = videoId ?: url?.takeIf { "watch?v=" in it }?.substringAfter("watch?v=")?.substringBefore('&'),
            channelPath = url?.takeIf { it.startsWith("/@") || it.startsWith("/channel/") },
            read = r.at("read").bool == true,
            target = targetOf(r, url),
        )
    }

    internal fun targetOf(r: JsonElement, url: String?): NotificationTarget? {
        val fromUrl = NotificationTarget.fromUrl(url)
        val watch = r.firstObject("watchEndpoint")
        val videoId = watch.at("videoId").str ?: r.firstString("videoId")
        if (videoId != null && vibe.tube.youtube.model.isVideoId(videoId)) {
            val linked = (fromUrl as? NotificationTarget.Video)?.takeIf { it.videoId == videoId }
            val comment = linked?.commentId ?: (watch.at("commentId").str ?: r.firstString("commentId") ?: r.firstString("targetCommentId"))
                ?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,512}")) }
            val message = r.at("shortMessage").textOf().orEmpty()
            val commentInteraction = comment != null || linked?.openComments == true ||
                Regex("comment|repl(?:y|ied)|heart|bình luận|trả lời|thả tim", RegexOption.IGNORE_CASE).containsMatchIn(message)
            return NotificationTarget.Video(
                videoId = videoId,
                startSeconds = (watch.at("startTimeSeconds") as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it > 0 }
                    ?: linked?.startSeconds,
                playlistId = watch.at("playlistId").str ?: linked?.playlistId,
                commentId = comment,
                openComments = commentInteraction,
            )
        }
        return NotificationTarget.fromBrowseId(r.firstObject("browseEndpoint").at("browseId").str) ?: fromUrl
    }
}
