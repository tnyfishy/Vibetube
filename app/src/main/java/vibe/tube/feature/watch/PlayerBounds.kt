package vibe.tube.feature.watch

import androidx.compose.runtime.*
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect

internal class PlayerBounds {
    var coordinates: LayoutCoordinates? = null
    var measured by mutableStateOf<Rect?>(null)
    fun current(): Rect? { val observed = measured; return coordinates?.takeIf { it.isAttached }?.boundsInWindow()?.takeIf { it.width > 1 && it.height > 1 } ?: observed }
}
internal val LocalPlayerBounds = staticCompositionLocalOf { PlayerBounds() }
