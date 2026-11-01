package vibe.tube.feature.watch

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates

internal class ReplyThreadLines {
    var root: LayoutCoordinates? = null
    var parent: LayoutCoordinates? = null
    var toggle: LayoutCoordinates? = null
    val replyParents = LinkedHashMap<String, String>()
    val children = LinkedHashMap<String, LayoutCoordinates>()
    var epoch by mutableIntStateOf(0)
    fun rect(coordinates: LayoutCoordinates?): Rect? {
        val host = root ?: return null
        if (!host.isAttached || coordinates?.isAttached != true) return null
        return runCatching { host.localBoundingBoxOf(coordinates, clipBounds = false) }.getOrNull()
    }
}
internal val LocalReplyThreadLines = staticCompositionLocalOf<ReplyThreadLines?> { null }
internal val LocalCommentOwner = staticCompositionLocalOf<String?> { null }
