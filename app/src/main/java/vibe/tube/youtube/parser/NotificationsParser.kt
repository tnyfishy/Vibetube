package vibe.tube.youtube.parser

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import vibe.tube.youtube.model.ChannelNotify
import vibe.tube.youtube.model.NotificationItem
import vibe.tube.youtube.model.NotificationsPage
import vibe.tube.youtube.model.NotifyLevel

/** The bell next to a subscribed channel: which level is on, and the request that switches to each of the three. */
object NotifyParser {
    fun parse(root: JsonElement?): ChannelNotify? {
        if (root == null) return null
        // (icon, params) of each choice, in the order the menu lists them: All, Personalized, None.
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
        val url = r.firstString("url")
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
        )
    }
}
