package vibe.tube.youtube.parser

import kotlinx.serialization.json.JsonElement
import vibe.tube.youtube.model.TextLink

fun JsonElement?.textLinks(): List<TextLink> {
    val text = textOf() ?: return emptyList()
    fun target(command: JsonElement?): String? {
        val raw = command.firstObject("urlEndpoint").at("url").str ?: return null
        val url = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "https://www.youtube.com$raw"
            else -> raw
        }
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        return url.takeIf { uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank() }
    }
    val links = mutableListOf<TextLink>()
    var offset = 0
    at("runs").list().forEach { run ->
        val end = offset + run.at("text").str.orEmpty().length
        target(run.at("navigationEndpoint"))?.let { if (end > offset) links += TextLink(offset, end, it) }
        offset = end
    }
    at("commandRuns").list().forEach { run ->
        val start = run.at("startIndex").int ?: return@forEach
        val end = start + (run.at("length").int ?: return@forEach)
        if (start >= 0 && end > start && end <= text.length) target(run)?.let { links += TextLink(start, end, it) }
    }
    return links.distinct()
}
