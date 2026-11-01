package vibe.tube.player

data class Chapter(val startMs: Long, val title: String)

object Chapters {
                                                                                                        
    private val stamp = Regex("""(?<![\d:])(?:(\d{1,2}):)?(\d{1,3}):([0-5]\d)(?![\d:])""")

    private val emptyBrackets = Regex("""[\[(]\s*[\])]""")
    private val trimEdges = Regex("""^[\s•▶►\-–—*#>.:|]+|[\s•▶►\-–—*#>:|]+$""")

    fun of(marked: List<vibe.tube.youtube.model.VideoChapter>, description: String?, durationMs: Long = 0L): List<Chapter> {
        val fromMarkers = marked.map { Chapter(it.startMs, it.title) }.filter { durationMs <= 0 || it.startMs < durationMs }
        return if (fromMarkers.size >= 2) fromMarkers else parse(description, durationMs)
    }

    fun parse(description: String?, durationMs: Long = 0L): List<Chapter> {
        if (description.isNullOrBlank()) return emptyList()
        val entries = description.lineSequence().mapNotNull { raw ->
            val m = stamp.find(raw) ?: return@mapNotNull null
            val hours = m.groupValues[1].toLongOrNull() ?: 0L
            val minutes = m.groupValues[2].toLong()
            val seconds = m.groupValues[3].toLong()
            val title = stamp.replace(raw, " ").replace(emptyBrackets, " ").replace(trimEdges, "").replace(Regex("\\s{2,}"), " ").trim()
            if (title.isEmpty()) return@mapNotNull null
            Chapter(((hours * 60 + minutes) * 60 + seconds) * 1000L, title)
        }.toList()
        val start = entries.indexOfFirst { it.startMs == 0L }
        if (start < 0) return emptyList()
        val run = mutableListOf(entries[start])
        for (i in start + 1 until entries.size) {
            val next = entries[i]
            if (next.startMs <= run.last().startMs) break
            if (durationMs > 0 && next.startMs >= durationMs) break
            run += next
        }
        if (run.size < 3) return emptyList()
        for (i in 1 until run.size) {
            if (run[i].startMs - run[i - 1].startMs < 10_000L) return emptyList()
        }
        return run
    }
                                                  
    fun currentIndex(chapters: List<Chapter>, positionMs: Long): Int {
        var index = -1
        for (i in chapters.indices) if (chapters[i].startMs <= positionMs) index = i else break
        return index
    }
}
