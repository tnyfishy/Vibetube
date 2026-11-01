package vibe.tube.youtube.parser

import kotlinx.serialization.json.JsonElement
import vibe.tube.youtube.model.FeedItem
import vibe.tube.youtube.model.Page

object FeedParser {

    fun parse(root: JsonElement, hideShorts: Boolean = false): Page<FeedItem> {
        val chips = ArrayList<vibe.tube.youtube.model.HomeChip>()
        val groups = HashMap<String, String>()
        root.walk { key, value ->
            if (key == "chipCloudChipRenderer") {
                val title = value.at("text").textOf()
                val endpoint = value.at("navigationEndpoint")
                val browse = endpoint.at("browseEndpoint")
                val token = endpoint.firstObject("continuationCommand").at("token").str
                if (title != null && (browse != null || token != null)) chips += vibe.tube.youtube.model.HomeChip(
                    title, browse.at("browseId").str, browse.at("params").str, token, value.at("isSelected").bool == true)
                false
            } else if (key == "itemSectionRenderer") {
                val title = value.at("header").firstObject("itemSectionHeaderRenderer").at("title").textOf()
                    ?: value.at("header").firstObject("headerRenderer").at("title").textOf()
                if (title != null) value.at("contents").walk { k, v ->
                    if (k in ItemParsers.itemKeys) { ItemParsers.parse(k, v)?.let { groups[it.id] = title }; false } else true
                }
                true
            } else true
        }
        val items = LinkedHashMap<String, FeedItem>()
        var continuation: String? = null
        var adsSkipped = 0
        root.walk { key, value ->
            when {
                ItemParsers.isAdKey(key) -> {
                    adsSkipped++
                    false
                }
                key == "chipCloudChipRenderer" -> false
                key == "continuationItemRenderer" || key == "continuationItemViewModel" -> {
                    tokenOf(key, value)?.let { continuation = it }
                    false
                }
                key == "nextContinuationData" || key == "reloadContinuationData" -> {
                    value.at("continuation").str?.let { continuation = it }
                    false
                }
                key in ItemParsers.itemKeys -> {
                    ItemParsers.parse(key, value)?.let { item ->
                        val unique = item.id.let { id -> if (item is vibe.tube.youtube.model.PlaylistItem) "pl:$id" else id }
                        if (unique !in items) items[unique] = item
                    }
                    false
                }
                else -> true
            }
        }
        val filtered = items.values.map { if (it is vibe.tube.youtube.model.VideoItem) it.copy(historyGroup = groups[it.id]) else it }.filter { !(hideShorts && it is vibe.tube.youtube.model.VideoItem && it.isShort) }
        return Page(items = filtered, continuation = continuation, adsSkipped = adsSkipped, chips = chips.distinctBy { it.key })
    }

    fun tokenOf(key: String, value: JsonElement): String? = when (key) {
        "continuationItemRenderer" ->
            value.at("continuationEndpoint").at("continuationCommand").at("token").str
                ?: value.firstString("token")
        "continuationItemViewModel" ->
            value.at("continuationCommand").at("innertubeCommand").at("continuationCommand").at("token").str
                ?: value.firstString("token")
        else -> null
    }
}
