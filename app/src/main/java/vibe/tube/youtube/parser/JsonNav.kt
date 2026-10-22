package vibe.tube.youtube.parser

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

val JsonElement?.obj: JsonObject? get() = this as? JsonObject
val JsonElement?.arr: JsonArray? get() = this as? JsonArray
val JsonElement?.str: String?
    get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.booleanOrNull
val JsonElement?.int: Int? get() = (this as? JsonPrimitive)?.intOrNull

fun JsonElement?.at(key: String): JsonElement? = (this as? JsonObject)?.get(key)

fun JsonElement?.at(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)

fun JsonElement?.path(vararg keys: Any): JsonElement? {
    var cur: JsonElement? = this
    for (k in keys) {
        cur = when (k) {
            is String -> cur.at(k)
            is Int -> cur.at(k)
            else -> null
        } ?: return null
    }
    return cur
}

fun JsonElement?.textOf(): String? {
    return when (this) {
        null, JsonNull -> null
        is JsonPrimitive -> if (isString) content else null
        is JsonObject -> {
            this["simpleText"].str
                ?: this["content"].str
                ?: this["runs"].arr?.joinToString("") { it.at("text").str.orEmpty() }?.takeIf { it.isNotEmpty() }
                ?: this["text"].textOf()
        }
        else -> null
    }
}

fun JsonElement?.list(): List<JsonElement> = (this as? JsonArray)?.toList().orEmpty()

fun normalizeUrl(url: String?): String? = when {
    url == null -> null
    url.startsWith("//") -> "https:$url"
    else -> url
}

fun JsonElement?.firstString(key: String): String? {
    var found: String? = null
    walk { k, v ->
        if (found != null) return@walk false
        if (k == key && v is JsonPrimitive && v.isString) {
            found = v.content
            false
        } else true
    }
    return found
}

fun JsonElement?.firstObject(key: String): JsonObject? {
    var found: JsonObject? = null
    walk { k, v ->
        if (found != null) return@walk false
        if (k == key && v is JsonObject) {
            found = v
            false
        } else true
    }
    return found
}

fun JsonElement?.walk(visit: (key: String, value: JsonElement) -> Boolean) {
    when (this) {
        is JsonObject -> for ((k, v) in this) {
            if (visit(k, v)) v.walkChildren(k, visit)
        }
        is JsonArray -> for (v in this) v.walkChildren("", visit)
        else -> Unit
    }
}

private fun JsonElement.walkChildren(parentKey: String, visit: (String, JsonElement) -> Boolean) {
    when (this) {
        is JsonObject -> for ((k, v) in this) {
            if (visit(k, v)) v.walkChildren(k, visit)
        }
        is JsonArray -> for (v in this) {
            if (v is JsonObject || v is JsonArray) v.walkChildren(parentKey, visit)
        }
        else -> Unit
    }
}
