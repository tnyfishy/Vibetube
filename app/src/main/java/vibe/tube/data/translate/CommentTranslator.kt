package vibe.tube.data.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/** A comment put into the reader's language, with the language it was written in. */
data class Translation(val text: String, val sourceLanguage: String)

/**
 * Translates comments into Vietnamese through Google Translate's public web endpoint, as YouTube's own "Translate to
 * Vietnamese" does. A comment already in Vietnamese is never sent; answers are remembered for the session.
 */
@Singleton
class CommentTranslator @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
) {
    private val cache = object : LinkedHashMap<String, Translation?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Translation?>?) = size > CACHE_SIZE
    }

    /** Whether [text] is worth sending: something to read that is not plainly Vietnamese already. */
    fun needsTranslation(text: String): Boolean =
        text.any(Char::isLetter) && !looksVietnamese(text)

    /** The Vietnamese for [text], or null when it is Vietnamese already or the translation failed. */
    suspend fun translate(text: String, target: String = TARGET): Translation? {
        val key = "$target|$text"
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$target&dt=t")
                    .post(FormBody.Builder().add("q", text).build())
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    parse(response.body.string(), target)
                }
            }.onFailure { android.util.Log.w("Translate", "comment not translated: ${it.javaClass.simpleName}") }.getOrNull()
        }
        // A failure is not remembered, so the next look tries again.
        if (result != null) synchronized(cache) { cache[key] = result.takeIf { it.sourceLanguage != target } }
        return result?.takeIf { it.sourceLanguage != target }
    }

    private fun parse(body: String, target: String): Translation? {
        val root = json.parseToJsonElement(body) as? JsonArray ?: return null
        val pieces = root.getOrNull(0) as? JsonArray ?: return null
        val text = pieces.joinToString("") { ((it as? JsonArray)?.getOrNull(0) as? JsonPrimitive)?.contentOrNull.orEmpty() }
        val source = (root.getOrNull(2) as? JsonPrimitive)?.contentOrNull ?: return null
        if (text.isBlank()) return null
        return Translation(text, if (source.substringBefore('-') == target) target else source)
    }

    private companion object {
        const val TARGET = "vi"
        const val CACHE_SIZE = 500

        /** Letters only Vietnamese writes: đ, and the vowels with a breve, horn or circumflex plus a tone mark. */
        val VIETNAMESE = Regex("[đĐăĂơƠưƯạảấầẩẫậắằẳẵặẹẻẽếềểễệỉịọỏốồổỗộớờởỡợụủứừửữựỳỵỷỹ]")

        fun looksVietnamese(text: String) = VIETNAMESE.containsMatchIn(text)
    }
}
