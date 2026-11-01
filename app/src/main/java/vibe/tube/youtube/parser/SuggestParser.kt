package vibe.tube.youtube.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

object SuggestParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<String> {
        val start = body.indexOf('(')
        val end = body.lastIndexOf(')')
        val payload = if (start in 0 until end) body.substring(start + 1, end) else body
        val root = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonArray ?: return emptyList()
        return (root.getOrNull(1) as? JsonArray).orEmpty().mapNotNull { entry ->
            (entry as? JsonArray)?.getOrNull(0).str
        }
    }
}
