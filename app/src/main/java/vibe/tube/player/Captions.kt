package vibe.tube.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import vibe.tube.youtube.player.CaptionTrack

/** One line (or two) of subtitle text and the time it is on screen. */
data class CaptionCue(val startMs: Long, val endMs: Long, val text: String)

/**
 * Subtitles are fetched and drawn by the app itself rather than handed to ExoPlayer as text tracks. Every track
 * used to be downloaded and merged into the media source up front: playback could not start before all of them
 * answered, one that failed took the video down with it, and nothing could be switched without reloading the video.
 * Now only the chosen track is fetched, in the background, and a failure just means no subtitles.
 */
object CaptionUrls {
    /**
     * The timedtext request for a track: whatever format / translation the link already carried is dropped (a duplicated
     * `fmt` made the server answer in a format the parser did not expect) and exactly one is set.
     */
    fun timedText(baseUrl: String, translateTo: String?): String {
        val q = baseUrl.indexOf('?')
        val path = if (q >= 0) baseUrl.substring(0, q) else baseUrl
        val query = if (q >= 0) baseUrl.substring(q + 1) else ""
        val kept = query.split('&').filter { it.isNotEmpty() && !it.startsWith("fmt=") && !it.startsWith("tlang=") }
        val params = kept + "fmt=vtt" + listOfNotNull(translateTo?.let { "tlang=$it" })
        return "$path?${params.joinToString("&")}"
    }
}

object VttParser {
    private val timing = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{3})\s*-->\s*(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{3})""")
    private val tags = Regex("<[^>]*>")

    fun parse(body: String): List<CaptionCue> {
        val lines = body.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = ArrayList<CaptionCue>()
        var i = 0
        while (i < lines.size) {
            val m = timing.find(lines[i])
            i++
            if (m == null) continue
            val g = m.groupValues
            val start = millis(g[1], g[2], g[3], g[4])
            val end = millis(g[5], g[6], g[7], g[8])
            val text = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                val line = clean(lines[i])
                if (line.isNotEmpty()) {
                    if (text.isNotEmpty()) text.append('\n')
                    text.append(line)
                }
                i++
            }
            // The ten-millisecond cues YouTube puts between rolling lines of speech recognition only repeat the last line.
            if (text.isNotEmpty() && end - start >= 80) cues += CaptionCue(start, end, text.toString())
        }
        cues.sortBy { it.startMs }
        return cues
    }

    private fun millis(h: String, m: String, s: String, ms: String): Long =
        (h.toLongOrNull() ?: 0L) * 3_600_000L + m.toLong() * 60_000L + s.toLong() * 1_000L + ms.toLong()

    private fun clean(line: String): String =
        tags.replace(line, "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .replace("&lrm;", "").replace("&rlm;", "")
            .trim()
}

/** The cue(s) showing at [positionMs]; cues are sorted by start. */
fun List<CaptionCue>.textAt(positionMs: Long): String? {
    if (isEmpty()) return null
    var lo = 0
    var hi = size - 1
    var last = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].startMs <= positionMs) {
            last = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    if (last < 0) return null
    // Cues can overlap (two people talking): everything that has started and not yet ended shows together.
    var first = last
    while (first > 0 && last - first < 3 && this[first - 1].endMs > positionMs) first--
    val active = (first..last).map { this[it] }.filter { it.endMs > positionMs }
    return active.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.text }
}

class CaptionLoader(private val http: OkHttpClient) {
    /** Empty when the server had nothing (or refused): the caller decides what that means. */
    suspend fun load(track: CaptionTrack, userAgent: String): List<CaptionCue> = withContext(Dispatchers.IO) {
        val url = CaptionUrls.timedText(track.url, if (track.isTranslation) track.languageCode else null)
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) emptyList() else VttParser.parse(response.body?.string().orEmpty())
        }
    }
}

object CaptionPicker {
    private fun base(tag: String): String = tag.substringBefore('-').substringBefore('_').lowercase()

    /** A machine translation of the video's best track into [target], if YouTube offers one. */
    fun translation(captions: List<CaptionTrack>, target: String, name: String): CaptionTrack? {
        val source = captions.firstOrNull { it.isTranslatable && !it.isAutoGenerated && !it.isTranslation }
            ?: captions.firstOrNull { it.isTranslatable && !it.isTranslation }
            ?: return null
        return CaptionTrack(
            languageCode = target,
            name = name,
            url = source.url,
            isAutoGenerated = true,
            isTranslation = true,
        )
    }

    /**
     * The tracks to offer: authored ones first, then speech recognition, and (when the video has nothing in the wanted
     * language) one machine translation into it. Offering all 150+ translation languages would bury the real tracks.
     */
    fun choices(captions: List<CaptionTrack>, translations: List<String>, wanted: String, translatedName: (String) -> String): List<CaptionTrack> {
        if (captions.isEmpty()) return emptyList()
        val ordered = captions.sortedBy { it.isAutoGenerated }
        val target = translations.firstOrNull { base(it) == base(wanted) } ?: return ordered
        if (ordered.any { base(it.languageCode) == base(target) }) return ordered
        val extra = translation(ordered, target, translatedName(target)) ?: return ordered
        return ordered + extra
    }

    /** What YouTube would turn on: the viewer's language if there is any way to get it, else the video's own captions. */
    fun pick(choices: List<CaptionTrack>, wanted: String): CaptionTrack? {
        val w = base(wanted)
        val inLanguage = choices.filter { base(it.languageCode) == w }
        return inLanguage.firstOrNull { !it.isAutoGenerated }
            ?: inLanguage.firstOrNull { !it.isTranslation }
            ?: inLanguage.firstOrNull()
            ?: choices.firstOrNull { !it.isAutoGenerated }
            ?: choices.firstOrNull { !it.isTranslation }
    }
}
