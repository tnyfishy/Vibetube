@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package vibe.tube.feature.welcome

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Cubic
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import kotlin.math.*

/** Writes normalized (0..1) cubics into [path], scaled to [size]. */
internal fun cubicsToPath(cubics: List<Cubic>, size: Size, path: Path = Path()): Path {
    path.rewind()
    if (cubics.isEmpty()) return path
    val w = size.width; val h = size.height
    path.moveTo(cubics[0].anchor0X * w, cubics[0].anchor0Y * h)
    cubics.forEach { c -> path.cubicTo(c.control0X * w, c.control0Y * h, c.control1X * w, c.control1Y * h, c.anchor1X * w, c.anchor1Y * h) }
    path.close()
    return path
}

internal fun RoundedPolygon.toComposePath(size: Size, path: Path = Path()) = cubicsToPath(cubics, size, path)

internal fun Morph.toComposePath(progress: Float, size: Size, path: Path = Path()) = cubicsToPath(asCubics(progress), size, path)

@Composable
private fun rememberPhase(periodMs: Int, label: String): () -> Float {
    if (vibe.tube.ui.theme.LocalReducedMotion.current) return { 0f }
    val clock = rememberInfiniteTransition(label = label)
    val value = clock.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart), label = "${label}Phase")
    return { value.value }
}

@Composable
internal fun WelcomeShapes(modifier: Modifier = Modifier) {
    val phase = rememberPhase(16_000, "organicWelcome")
    val first = MaterialTheme.colorScheme.primary.copy(alpha = .11f)
    val second = MaterialTheme.colorScheme.tertiary.copy(alpha = .09f)
    Box(modifier.fillMaxSize().drawWithCache {
        val path = Path()
        val n = 16
        val angles = FloatArray(n) { (it * 2 * PI / n).toFloat() }
        val xs = FloatArray(n); val ys = FloatArray(n)
        fun contour(centerX: Float, centerY: Float, radius: Float, p: Float) {
            for (i in 0 until n) {
                val a = angles[i]
                val r = radius * (1f + .10f * sin(3 * a + p) + .055f * cos(2 * a - p))
                xs[i] = centerX + r * cos(a); ys[i] = centerY + r * sin(a)
            }
            path.rewind()
            path.moveTo((xs[n-1]+xs[0])/2, (ys[n-1]+ys[0])/2)
            for (i in 0 until n) {
                val next = (i + 1) % n
                path.quadraticTo(xs[i], ys[i], (xs[i]+xs[next])/2, (ys[i]+ys[next])/2)
            }
            path.close()
        }
        onDrawBehind {
            val p = phase()
            val radius = size.minDimension * .30f
            contour(size.width * .04f, size.height * .09f, radius, p); drawPath(path, first)
            contour(size.width * .96f, size.height * .91f, radius * 1.06f, -p); drawPath(path, second)
        }
    })
}

private class Floater(
    val polygon: RoundedPolygon,
    val x: Float,
    val y: Float,
    val sizeDp: Float,
    /** How far it sways sideways between pages; >1 reads as "closer" than the content. */
    val depth: Float,
    val spin: Float,
    val tone: Int,
    val filled: Boolean,
)

/**
 * Small expressive shapes in the margins that sway with parallax as [position] (pager page + offset) changes.
 * Each one keeps to its own slot, clear of the hero, the text column, the corner blobs and the bottom bar,
 * so nothing ever stacks; [visibility] fades them out where a page needs the whole screen.
 */
@Composable
internal fun FloatingShapes(position: () -> Float, modifier: Modifier = Modifier, visibility: () -> Float = { 1f }) {
    val phase = rememberPhase(22_000, "floaters")
    val scheme = MaterialTheme.colorScheme
    val tones = listOf(scheme.primary, scheme.tertiary, scheme.secondary)
    val floaters = remember {
        listOf(
            Floater(MaterialShapes.Flower, .08f, .33f, 36f, 1f, 1f, 0, true),
            Floater(MaterialShapes.Heart, .90f, .15f, 22f, 1.3f, -.7f, 2, true),
            Floater(MaterialShapes.Pill, .92f, .31f, 32f, .7f, -1f, 2, false),
            Floater(MaterialShapes.Gem, .07f, .70f, 30f, .8f, -.8f, 1, false),
            Floater(MaterialShapes.Triangle, .93f, .69f, 26f, 1.2f, .6f, 0, true),
            Floater(MaterialShapes.SoftBurst, .50f, .79f, 24f, .9f, -1.2f, 1, false),
        )
    }
    Box(modifier.fillMaxSize().drawWithCache {
        val paths = floaters.map { f -> f.sizeDp.dp.toPx().let { s -> f.polygon.normalized().toComposePath(Size(s, s)) } }
        val stroke = Stroke(2.dp.toPx())
        val bob = 6.dp.toPx()
        val sway = 30.dp.toPx()
        onDrawBehind {
            val shown = visibility()
            if (shown <= .01f) return@onDrawBehind
            val pos = position()
            val t = phase()
            floaters.forEachIndexed { i, f ->
                val s = f.sizeDp.dp.toPx()
                // Bounded sway instead of travelling across the screen, so a shape never crosses the content.
                val x = f.x * size.width - sin(pos * PI.toFloat() / 2f + i) * sway * f.depth
                val y = f.y * size.height + sin(t * 2 + i * 1.3f) * bob
                val color = tones[f.tone]
                translate(x - s / 2, y - s / 2) {
                    rotate(f.spin * (Math.toDegrees(t.toDouble()).toFloat() + pos * 50f), pivot = Offset(s / 2, s / 2)) {
                        if (f.filled) drawPath(paths[i], color.copy(alpha = .16f * shown))
                        else drawPath(paths[i], color.copy(alpha = .32f * shown), style = stroke)
                    }
                }
            }
        }
    })
}
