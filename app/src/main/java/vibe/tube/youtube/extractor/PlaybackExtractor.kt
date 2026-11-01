package vibe.tube.youtube.extractor

import vibe.tube.youtube.model.ContentLocale
import vibe.tube.youtube.player.ResolvedMedia

interface PlaybackExtractor {
    suspend fun media(id: String, locale: ContentLocale): ResolvedMedia
    fun invalidate(id: String)
}
