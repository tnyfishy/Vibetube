package vibe.tube.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

/**
 * Material 3 Expressive building blocks used across the app. Keeping them in one file makes
 * the expressive API surface (wavy progress, connected button groups) easy to audit and swap.
 */

/** Determinate linear progress drawn as the expressive wavy indicator. */
@Composable
fun VibeLinearProgress(progress: () -> Float, modifier: Modifier = Modifier) {
    LinearWavyProgressIndicator(progress = progress, modifier = modifier)
}

/** Indeterminate linear progress drawn as the expressive wavy indicator. */
@Composable
fun VibeLinearProgress(modifier: Modifier = Modifier) {
    LinearWavyProgressIndicator(modifier = modifier)
}

/** Determinate circular progress drawn as the expressive wavy indicator. */
@Composable
fun VibeCircularProgress(progress: () -> Float, modifier: Modifier = Modifier) {
    CircularWavyProgressIndicator(progress = progress, modifier = modifier)
}

/** Indeterminate circular progress drawn as the expressive wavy indicator. */
@Composable
fun VibeCircularProgress(modifier: Modifier = Modifier) {
    CircularWavyProgressIndicator(modifier = modifier)
}

/**
 * Single-choice connected button group (M3 Expressive): a row of [ToggleButton]s with shared,
 * morphing corners. [label] must return the visible text for an option.
 */
@Composable
fun <T> ConnectedChoiceGroup(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { if (it) onSelect(option) },
                modifier = Modifier.semantics { role = Role.RadioButton },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) {
                Text(label(option))
            }
        }
    }
}

/** Horizontal inset (each side) of [WavyProgress], so round caps and the thumb stay inside the bounds. */
val WavyInset = 4.dp

/**
 * Wavy progress line drawn directly on a canvas. [amplitude] (0..1) is read in the draw phase, so flattening
 * the wave is instant and exact; [animate] makes the wave travel while the media plays.
 */
@Composable
fun WavyProgress(
    progress: () -> Float,
    amplitude: () -> Float,
    animate: Boolean,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
    trackColor: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
    strokeWidth: androidx.compose.ui.unit.Dp = 3.dp,
    wavelength: androidx.compose.ui.unit.Dp = 20.dp,
    maxAmplitude: androidx.compose.ui.unit.Dp = 2.5.dp,
    thumb: androidx.compose.ui.unit.DpSize? = null,
) {
    val phase = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (last != 0L) phase.floatValue = (phase.floatValue + (now - last) / 1_000_000_000f * 1.1f) % 1f
                last = now
            }
        }
    }
    androidx.compose.foundation.Canvas(modifier) {
        val inset = WavyInset.toPx()
        val x0 = inset
        val x1 = size.width - inset
        val cy = size.height / 2f
        val sw = strokeWidth.toPx()
        val frac = progress().coerceIn(0f, 1f)
        val px = x0 + (x1 - x0) * frac
        val gap = (if (thumb != null) 6.dp.toPx() else 2.dp.toPx())
        val amp = maxAmplitude.toPx() * amplitude().coerceIn(0f, 1f)
        val wl = wavelength.toPx()
        val activeEnd = px - gap
        if (activeEnd > x0) {
            val path = androidx.compose.ui.graphics.Path()
            var x = x0
            val step = 2f
            while (x <= activeEnd) {
                val y = cy + amp * kotlin.math.sin(((x - x0) / wl - phase.floatValue) * 2f * Math.PI.toFloat())
                if (x == x0) path.moveTo(x, y) else path.lineTo(x, y)
                x += step
            }
            path.lineTo(activeEnd, cy + amp * kotlin.math.sin(((activeEnd - x0) / wl - phase.floatValue) * 2f * Math.PI.toFloat()))
            drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
        val trackStart = px + gap
        if (trackStart < x1) {
            drawLine(trackColor, androidx.compose.ui.geometry.Offset(trackStart, cy), androidx.compose.ui.geometry.Offset(x1, cy), strokeWidth = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
        if (thumb != null) {
            val tw = thumb.width.toPx()
            val th = thumb.height.toPx()
            drawRoundRect(
                color,
                topLeft = androidx.compose.ui.geometry.Offset(px - tw / 2f, cy - th / 2f),
                size = androidx.compose.ui.geometry.Size(tw, th),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(tw / 2f),
            )
        }
    }
}
