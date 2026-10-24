package vibe.tube.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/** Like and dislike totals of a video. YouTube stopped publishing the dislikes, so they come from Return YouTube Dislike. */
data class Votes(val likes: Long?, val dislikes: Long?)

@Singleton
class VoteCounts @Inject constructor(private val http: OkHttpClient) {
    private val cache = object : LinkedHashMap<String, Votes>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Votes>?): Boolean = size > 96
    }

    /** Null when the service has nothing for the video or cannot be reached: the UI then simply shows no number. */
    suspend fun get(videoId: String): Votes? {
        synchronized(cache) { cache[videoId] }?.let { return it }
        val fetched = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url("https://returnyoutubedislikeapi.com/votes?videoId=$videoId").build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val root: JsonObject = Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                    Votes(
                        likes = (root["likes"] as? JsonPrimitive)?.longOrNull,
                        dislikes = (root["dislikes"] as? JsonPrimitive)?.longOrNull,
                    )
                }
            }.getOrNull()
        }
        if (fetched != null) synchronized(cache) { cache[videoId] = fetched }
        return fetched
    }
}
