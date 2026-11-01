package vibe.tube.support

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

object Fixtures {
    val json = Json { ignoreUnknownKeys = true }

    fun text(name: String): String =
        checkNotNull(Fixtures::class.java.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun json(name: String): JsonElement = json.parseToJsonElement(text(name))
}
