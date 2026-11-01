package vibe.tube.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.lerp

@Composable
internal fun animatedColorScheme(target: ColorScheme, reduced: Boolean): ColorScheme {
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    val latest by rememberUpdatedState(target)
    
    val key = remember(target) { SchemeKey(target) }
    LaunchedEffect(key) {
        val next = latest
        if (next.sameColorsAs(to)) return@LaunchedEffect
        from = lerpScheme(from, to, progress.value)
        to = next
        if (reduced) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 350, easing = FastOutSlowInEasing))
    }
    val p = progress.value
    return if (p >= 1f) to else lerpScheme(from, to, p)
}

private class SchemeKey(val scheme: ColorScheme) {
    override fun equals(other: Any?): Boolean = other is SchemeKey && scheme.sameColorsAs(other.scheme)
    override fun hashCode(): Int = scheme.primary.hashCode() * 31 + scheme.surface.hashCode()
}

internal fun ColorScheme.sameColorsAs(other: ColorScheme): Boolean =
    primary == other.primary &&
    onPrimary == other.onPrimary &&
    primaryContainer == other.primaryContainer &&
    onPrimaryContainer == other.onPrimaryContainer &&
    inversePrimary == other.inversePrimary &&
    secondary == other.secondary &&
    onSecondary == other.onSecondary &&
    secondaryContainer == other.secondaryContainer &&
    onSecondaryContainer == other.onSecondaryContainer &&
    tertiary == other.tertiary &&
    onTertiary == other.onTertiary &&
    tertiaryContainer == other.tertiaryContainer &&
    onTertiaryContainer == other.onTertiaryContainer &&
    background == other.background &&
    onBackground == other.onBackground &&
    surface == other.surface &&
    onSurface == other.onSurface &&
    surfaceVariant == other.surfaceVariant &&
    onSurfaceVariant == other.onSurfaceVariant &&
    surfaceTint == other.surfaceTint &&
    inverseSurface == other.inverseSurface &&
    inverseOnSurface == other.inverseOnSurface &&
    error == other.error &&
    onError == other.onError &&
    errorContainer == other.errorContainer &&
    onErrorContainer == other.onErrorContainer &&
    outline == other.outline &&
    outlineVariant == other.outlineVariant &&
    scrim == other.scrim &&
    surfaceBright == other.surfaceBright &&
    surfaceDim == other.surfaceDim &&
    surfaceContainer == other.surfaceContainer &&
    surfaceContainerHigh == other.surfaceContainerHigh &&
    surfaceContainerHighest == other.surfaceContainerHighest &&
    surfaceContainerLow == other.surfaceContainerLow &&
    surfaceContainerLowest == other.surfaceContainerLowest &&
    primaryFixed == other.primaryFixed &&
    primaryFixedDim == other.primaryFixedDim &&
    onPrimaryFixed == other.onPrimaryFixed &&
    onPrimaryFixedVariant == other.onPrimaryFixedVariant &&
    secondaryFixed == other.secondaryFixed &&
    secondaryFixedDim == other.secondaryFixedDim &&
    onSecondaryFixed == other.onSecondaryFixed &&
    onSecondaryFixedVariant == other.onSecondaryFixedVariant &&
    tertiaryFixed == other.tertiaryFixed &&
    tertiaryFixedDim == other.tertiaryFixedDim &&
    onTertiaryFixed == other.onTertiaryFixed &&
    onTertiaryFixedVariant == other.onTertiaryFixedVariant

internal fun lerpScheme(from: ColorScheme, to: ColorScheme, t: Float): ColorScheme = to.copy(
    primary = lerp(from.primary, to.primary, t),
    onPrimary = lerp(from.onPrimary, to.onPrimary, t),
    primaryContainer = lerp(from.primaryContainer, to.primaryContainer, t),
    onPrimaryContainer = lerp(from.onPrimaryContainer, to.onPrimaryContainer, t),
    inversePrimary = lerp(from.inversePrimary, to.inversePrimary, t),
    secondary = lerp(from.secondary, to.secondary, t),
    onSecondary = lerp(from.onSecondary, to.onSecondary, t),
    secondaryContainer = lerp(from.secondaryContainer, to.secondaryContainer, t),
    onSecondaryContainer = lerp(from.onSecondaryContainer, to.onSecondaryContainer, t),
    tertiary = lerp(from.tertiary, to.tertiary, t),
    onTertiary = lerp(from.onTertiary, to.onTertiary, t),
    tertiaryContainer = lerp(from.tertiaryContainer, to.tertiaryContainer, t),
    onTertiaryContainer = lerp(from.onTertiaryContainer, to.onTertiaryContainer, t),
    background = lerp(from.background, to.background, t),
    onBackground = lerp(from.onBackground, to.onBackground, t),
    surface = lerp(from.surface, to.surface, t),
    onSurface = lerp(from.onSurface, to.onSurface, t),
    surfaceVariant = lerp(from.surfaceVariant, to.surfaceVariant, t),
    onSurfaceVariant = lerp(from.onSurfaceVariant, to.onSurfaceVariant, t),
    surfaceTint = lerp(from.surfaceTint, to.surfaceTint, t),
    inverseSurface = lerp(from.inverseSurface, to.inverseSurface, t),
    inverseOnSurface = lerp(from.inverseOnSurface, to.inverseOnSurface, t),
    error = lerp(from.error, to.error, t),
    onError = lerp(from.onError, to.onError, t),
    errorContainer = lerp(from.errorContainer, to.errorContainer, t),
    onErrorContainer = lerp(from.onErrorContainer, to.onErrorContainer, t),
    outline = lerp(from.outline, to.outline, t),
    outlineVariant = lerp(from.outlineVariant, to.outlineVariant, t),
    scrim = lerp(from.scrim, to.scrim, t),
    surfaceBright = lerp(from.surfaceBright, to.surfaceBright, t),
    surfaceDim = lerp(from.surfaceDim, to.surfaceDim, t),
    surfaceContainer = lerp(from.surfaceContainer, to.surfaceContainer, t),
    surfaceContainerHigh = lerp(from.surfaceContainerHigh, to.surfaceContainerHigh, t),
    surfaceContainerHighest = lerp(from.surfaceContainerHighest, to.surfaceContainerHighest, t),
    surfaceContainerLow = lerp(from.surfaceContainerLow, to.surfaceContainerLow, t),
    surfaceContainerLowest = lerp(from.surfaceContainerLowest, to.surfaceContainerLowest, t),
    primaryFixed = lerp(from.primaryFixed, to.primaryFixed, t),
    primaryFixedDim = lerp(from.primaryFixedDim, to.primaryFixedDim, t),
    onPrimaryFixed = lerp(from.onPrimaryFixed, to.onPrimaryFixed, t),
    onPrimaryFixedVariant = lerp(from.onPrimaryFixedVariant, to.onPrimaryFixedVariant, t),
    secondaryFixed = lerp(from.secondaryFixed, to.secondaryFixed, t),
    secondaryFixedDim = lerp(from.secondaryFixedDim, to.secondaryFixedDim, t),
    onSecondaryFixed = lerp(from.onSecondaryFixed, to.onSecondaryFixed, t),
    onSecondaryFixedVariant = lerp(from.onSecondaryFixedVariant, to.onSecondaryFixedVariant, t),
    tertiaryFixed = lerp(from.tertiaryFixed, to.tertiaryFixed, t),
    tertiaryFixedDim = lerp(from.tertiaryFixedDim, to.tertiaryFixedDim, t),
    onTertiaryFixed = lerp(from.onTertiaryFixed, to.onTertiaryFixed, t),
    onTertiaryFixedVariant = lerp(from.onTertiaryFixedVariant, to.onTertiaryFixedVariant, t),
)
