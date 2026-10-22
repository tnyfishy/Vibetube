package vibe.tube.youtube.parser

import kotlinx.serialization.json.JsonElement
import vibe.tube.youtube.model.FeedItem
import vibe.tube.youtube.model.Page

object FeedParser {

    fun parse(root: JsonElement, hideShorts: Boolean = false): Page<FeedItem> {
        val items = LinkedHashMap<String, FeedItem>()
        var continuation: String? = null
        var adsSkipped = 0
        root.walk { key, value ->
            when {
                ItemParsers.isAdKey(key) -> {
                    adsSkipped++
                    false
                }
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
        val filtered = items.values.filter { !(hideShorts && it is vibe.tube.youtube.model.VideoItem && it.isShort) }
        return Page(items = filtered, continuation = continuation, adsSkipped = adsSkipped)
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
