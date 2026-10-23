package vibe.tube.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect

/**
 * Hands the on-screen bounds of the thumbnail that was tapped to the player, so the video can fly out of that
 * thumbnail (a shared-element transition) instead of appearing from the mini-player corner.
 */
object PlayerLaunch {
    private var rect: Rect? = null

    /** Bumped on every launch so the player notices even when it is already on screen. */
    var seq by mutableIntStateOf(0)
        private set

    fun set(bounds: Rect?) {
        rect = bounds?.takeIf { it.width > 1f && it.height > 1f }
        seq++
    }

    /** Returns the pending origin once; later calls get null. */
    fun take(): Rect? = rect.also { rect = null }
}

/** Remembers where a thumbnail currently is, without making every scroll frame a state write. */
class ThumbnailBounds {
    var rect: Rect? = null
}
