package vibe.tube.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot

object PlayerLaunch {
                                                                                                                            
    data class Origin(val rect: Rect?, val image: String?, val videoId: String?)

    private var rect: Rect? = null
    private var image: String? = null
    private var videoId: String? = null

    var seq by mutableIntStateOf(0)
        private set

    fun set(bounds: Rect?, image: String? = null, videoId: String? = null) {
        rect = bounds?.takeIf { it.width > 1f && it.height > 1f }
        this.image = image
        this.videoId = videoId
        seq++
    }

    fun clear() { rect = null; image = null; videoId = null; seq++ }

    fun take(): Origin? {
        val origin = if (rect == null && image == null) null else Origin(rect, image, videoId)
        rect = null
        image = null
        videoId = null
        return origin
    }
}

class ThumbnailBounds {
    var coordinates: androidx.compose.ui.layout.LayoutCoordinates? = null

    val rect: Rect?
        get() = coordinates?.takeIf { it.isAttached }?.let { it.boundsInRoot() }
}
