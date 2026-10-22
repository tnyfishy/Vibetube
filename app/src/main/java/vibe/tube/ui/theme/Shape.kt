package vibe.tube.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

val VibeShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    largeIncreased = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    extraExtraLarge = RoundedCornerShape(48.dp),
)

object VibeShape {
    val thumbnail: Shape @Composable get() = MaterialTheme.shapes.medium
    val card: Shape @Composable get() = MaterialTheme.shapes.large
    val badge: Shape @Composable get() = MaterialTheme.shapes.extraSmall
    val chip: Shape @Composable get() = MaterialTheme.shapes.small
    val miniPlayer: Shape @Composable get() = MaterialTheme.shapes.large
    val sheet: Shape @Composable get() = MaterialTheme.shapes.extraLarge
    val dialog: Shape @Composable get() = MaterialTheme.shapes.extraLarge
    val playerSurface: Shape @Composable get() = MaterialTheme.shapes.large
    val avatar: Shape = CircleShape
}
