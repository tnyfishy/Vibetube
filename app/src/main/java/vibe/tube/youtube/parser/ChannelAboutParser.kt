package vibe.tube.youtube.parser

import kotlinx.serialization.json.JsonElement
import vibe.tube.youtube.model.ChannelDetail

object ChannelAboutParser {
    fun parse(root: JsonElement, id: String): ChannelDetail {
        val about = root.firstObject("aboutChannelViewModel") ?: root.firstObject("channelAboutFullMetadataRenderer")
        val metadata = root.path("metadata", "channelMetadataRenderer")
        val rows = listOf("joinedDateText", "country", "subscriberCountText", "viewCountText", "videoCountText").mapNotNull { key ->
            (about.at(key).textOf() ?: about.at(key).str)?.takeIf { it.isNotBlank() }?.let { key to it }
        }
        val links = ArrayList<Pair<String, String>>()
        about.walk { key, value ->
            if (key == "channelExternalLinkViewModel" || key == "primaryLink") {
                val url = value.firstObject("urlEndpoint").at("url").str ?: value.firstString("url")
                if (url?.startsWith("https://") == true || url?.startsWith("http://") == true) {
                    links += (value.at("title").textOf() ?: value.at("title").str ?: url!!) to url!!
                }
                false
            } else true
        }
        return ChannelDetail(id, metadata.at("title").str.orEmpty(), null, null, null,
            about.at("description").textOf() ?: about.at("description").str ?: metadata.at("description").str,
            null, aboutDetails = rows, links = links.distinct())
    }
}
