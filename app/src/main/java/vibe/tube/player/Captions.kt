package vibe.tube.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import vibe.tube.youtube.player.CaptionTrack

data class CaptionCue(val startMs: Long, val endMs: Long, val text: String, val words: List<TimedWord> = emptyList()) {
                                             
    fun textAt(positionMs: Long): String {
        if (words.isEmpty()) return text
        val spoken = buildString { for (w in words) if (w.atMs <= positionMs) append(w.text) }.trim()
        return when {
            spoken.isEmpty() -> text
            text.isEmpty() -> spoken
            else -> text + "\n" + spoken
        }
    }
}

data class TimedWord(val atMs: Long, val text: String)

object CaptionUrls {

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
    private val wordTime = Regex("""<(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{3})>""")

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
            var words = emptyList<TimedWord>()
            
            while (i < lines.size && lines[i].isNotEmpty()) {
                val raw = lines[i]
                if (wordTime.containsMatchIn(raw)) {
                    words = timedWords(raw, start)
                } else {
                    val line = clean(raw)
                    if (line.isNotEmpty()) {
                        if (text.isNotEmpty()) text.append('\n')
                        text.append(line)
                    }
                }
                i++
            }
            val hasContent = text.isNotEmpty() || words.any { it.text.isNotBlank() }
            if (hasContent && end - start >= 80) cues += CaptionCue(start, end, text.toString(), words)
        }
        cues.sortBy { it.startMs }
        return cues
    }

    private fun timedWords(raw: String, cueStart: Long): List<TimedWord> {
        val out = ArrayList<TimedWord>()
        var at = cueStart
        var last = 0
        for (m in wordTime.findAll(raw)) {
            val piece = clean(raw.substring(last, m.range.first), trim = false)
            if (piece.isNotEmpty()) out += TimedWord(at, piece)
            val g = m.groupValues
            at = millis(g[1], g[2], g[3], g[4])
            last = m.range.last + 1
        }
        val tail = clean(raw.substring(last), trim = false)
        if (tail.isNotEmpty()) out += TimedWord(at, tail)
        return out
    }

    private fun millis(h: String, m: String, s: String, ms: String): Long =
        (h.toLongOrNull() ?: 0L) * 3_600_000L + m.toLong() * 60_000L + s.toLong() * 1_000L + ms.toLong()

    private fun clean(line: String, trim: Boolean = true): String =
        tags.replace(line, "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .replace("&lrm;", "").replace("&rlm;", "")
            .let { if (trim) it.trim() else it }
}

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
    var first = last
    while (first > 0 && last - first < 3 && this[first - 1].endMs > positionMs) first--
    val active = (first..last).map { this[it] }.filter { it.endMs > positionMs }
        .map { it.textAt(positionMs) }.filter { it.isNotBlank() }.distinctBy { it.trim() }
    return active.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

class CaptionLoader(private val http: OkHttpClient) {
                                                                                              
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

    fun choices(captions: List<CaptionTrack>, translations: List<String>, wanted: String, translatedName: (String) -> String): List<CaptionTrack> {
        if (captions.isEmpty()) return emptyList()
        val ordered = captions.sortedBy { it.isAutoGenerated }
        val target = translations.firstOrNull { base(it) == base(wanted) } ?: return ordered
        if (ordered.any { base(it.languageCode) == base(target) }) return ordered
        val extra = translation(ordered, target, translatedName(target)) ?: return ordered
        return ordered + extra
    }

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
