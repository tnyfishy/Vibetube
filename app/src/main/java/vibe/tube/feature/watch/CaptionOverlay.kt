package vibe.tube.feature.watch

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import vibe.tube.data.settings.SubtitleBackground
import vibe.tube.data.settings.SubtitleColor
import vibe.tube.player.CaptionCue
import vibe.tube.player.textAt

data class SubtitleStyle(
    val size: Int = 100,
    val color: SubtitleColor = SubtitleColor.White,
    val background: SubtitleBackground = SubtitleBackground.Semi,
)

val LocalSubtitleStyle = staticCompositionLocalOf { SubtitleStyle() }

/**
 * Draws the current subtitle over the video, at the bottom (lifted above the seek bar while the controls show).
 * It follows the player's own clock, so it stays in step through seeks, pauses and speed changes.
 */
@Composable
internal fun CaptionOverlay(cues: List<CaptionCue>, player: Player, raised: Boolean, modifier: Modifier = Modifier) {
    val style = LocalSubtitleStyle.current
    var text by remember(cues) { mutableStateOf<String?>(null) }
    LaunchedEffect(cues, player) {
        while (cues.isNotEmpty()) {
            val next = cues.textAt(player.currentPosition)
            if (next != text) text = next
            delay(if (player.isPlaying) 40L else 160L)
        }
    }
    val shown = text ?: return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val bottom by animateDpAsState(if (raised) 58.dp else 14.dp, tween(180), label = "captionLift")
        // Scales with the width of the picture, so it reads the same on a phone and fullscreen on a tablet.
        val fontSize = (maxWidth.value * 0.04f * style.size / 100f).coerceIn(11f, 34f)
        Text(
            text = shown,
            color = Color(style.color.argb),
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.25f).sp,
            textAlign = TextAlign.Center,
            style = TextStyle(shadow = if (style.background == SubtitleBackground.None) Shadow(Color.Black, Offset.Zero, 9f) else null),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 28.dp, end = 28.dp, bottom = bottom)
                .background(Color.Black.copy(alpha = style.background.alpha), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
