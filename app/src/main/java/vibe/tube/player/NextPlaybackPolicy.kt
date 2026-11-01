package vibe.tube.player

import vibe.tube.youtube.model.VideoItem

internal object NextPlaybackPolicy {
    fun next(s: PlayerUiState, autoplay: Boolean, suggested: VideoItem?): VideoItem? {
        if (s.sleepAtEnd || s.repeatMode == RepeatMode.One || s.loopEndMs != null) return null
        if (s.hasNext && (s.playlistId != null || autoplay || s.repeatMode == RepeatMode.All)) return s.queue[s.queueIndex + 1]
        if (s.repeatMode == RepeatMode.All) return s.queue.firstOrNull()?.takeIf { s.queue.size > 1 }
        return suggested?.takeIf { autoplay && it.id != s.current?.id }
    }
}
