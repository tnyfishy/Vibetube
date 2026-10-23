package vibe.tube.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot

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

/**
 * Remembers the thumbnail's layout node; the bounds are only computed at tap time, so scrolling does no extra work
 * (no per-frame `boundsInRoot()` allocation).
 */
class ThumbnailBounds {
    var coordinates: androidx.compose.ui.layout.LayoutCoordinates? = null

    val rect: Rect?
        get() = coordinates?.takeIf { it.isAttached }?.let { it.boundsInRoot() }
}
