package vibe.tube.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/** Where the Shorts seek handle is, in root coordinates, so it can be drawn above the bottom navigation bar. */
class ScrubThumbState {
    var alpha by mutableFloatStateOf(0f)
    var centerX by mutableFloatStateOf(0f)
    var centerY by mutableFloatStateOf(0f)
    var sizePx by mutableFloatStateOf(0f)
    var rotation by mutableFloatStateOf(0f)
}

/** Shared by the Shorts progress bar and its handle so they always match. */
val ShortsProgressRed = WordmarkRed

val LocalScrubThumb = staticCompositionLocalOf<ScrubThumbState?> { null }

/** Draws the handle published in [state]; place it after the navigation bar so it overlaps it. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ScrubThumbOverlay(state: ScrubThumbState) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        if (state.alpha > 0.001f && state.sizePx > 0f) {
            Box(
                Modifier
                    .offset { IntOffset((state.centerX - origin.x - state.sizePx / 2f).roundToInt(), (state.centerY - origin.y - state.sizePx / 2f).roundToInt()) }
                    .size(with(density) { state.sizePx.toDp() })
                    .graphicsLayer {
                        alpha = state.alpha
                        rotationZ = state.rotation
                    }
                    .background(ShortsProgressRed, MaterialShapes.Sunny.toShape()),
            )
        }
    }
}
