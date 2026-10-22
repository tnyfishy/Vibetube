package vibe.tube.player

/** A named section of a video (YouTube "chapters"), starting at [startMs]. */
data class Chapter(val startMs: Long, val title: String)

object Chapters {
    private val line = Regex("""^[\s•▶►\-–—*#>.]*(?:(\d{1,2}):)?(\d{1,2}):(\d{2})\b[\s\-–—:.)\]|]*(.+)$""")

    /**
     * Reads chapters out of a video description the way YouTube does: a list of "m:ss Title" lines, starting at 0:00,
     * in ascending order, at least three of them and each at least ten seconds long.
     */
    fun parse(description: String?, durationMs: Long = 0L): List<Chapter> {
        if (description.isNullOrBlank()) return emptyList()
        val found = description.lineSequence().mapNotNull { raw ->
            val m = line.matchEntire(raw.trim()) ?: return@mapNotNull null
            val hours = m.groupValues[1].toLongOrNull() ?: 0L
            val minutes = m.groupValues[2].toLong()
            val seconds = m.groupValues[3].toLong()
            if (seconds >= 60) return@mapNotNull null
            val title = m.groupValues[4].trim().trim('-', '–', '—', ':', '|').trim()
            if (title.isEmpty()) return@mapNotNull null
            Chapter(((hours * 60 + minutes) * 60 + seconds) * 1000L, title)
        }.toList()
        if (found.size < 3 || found.first().startMs != 0L) return emptyList()
        for (i in 1 until found.size) {
            if (found[i].startMs - found[i - 1].startMs < 10_000L) return emptyList()
        }
        if (durationMs > 0 && found.last().startMs >= durationMs) return emptyList()
        return found
    }

    /** The chapter that contains [positionMs]. */
    fun currentIndex(chapters: List<Chapter>, positionMs: Long): Int {
        var index = -1
        for (i in chapters.indices) if (chapters[i].startMs <= positionMs) index = i else break
        return index
    }
}
