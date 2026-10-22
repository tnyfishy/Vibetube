package vibe.tube.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import vibe.tube.R
import vibe.tube.ui.VIcon
import vibe.tube.ui.theme.LocalReducedMotion
import vibe.tube.ui.theme.Spacing
import kotlin.math.min

/**
 * Touch feedback and transition helpers shared by cards, buttons and the search pill.
 * Springs are intentionally a little under-damped so presses feel physical (M3 Expressive).
 */

/** Shrinks the element while a finger is down and springs back with a small overshoot on release. */
@Composable
fun Modifier.pressScale(interactionSource: InteractionSource, pressedScale: Float = 0.96f): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduced) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 700f),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** `clickable` with a springy press scale and a ripple clipped to [shape]. */
@Composable
fun Modifier.bouncyClickable(
    shape: Shape,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    pressedScale: Float = 0.97f,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    return this
        .pressScale(source, pressedScale)
        .clip(shape)
        .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, role = role, onClick = onClick)
}

/** Fades an element in from slightly above, one step later per [index] (top to bottom). */
@Composable
fun Modifier.staggeredReveal(index: Int, baseDelayMs: Int = 170, stepMs: Int = 45, maxSteps: Int = 12): Modifier {
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableStateOf(reduced) }
    LaunchedEffect(Unit) { shown = true }
    val delayMs = if (reduced) 0 else baseDelayMs + min(index, maxSteps) * stepMs
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = if (reduced) 0 else 320, delayMillis = delayMs, easing = LinearOutSlowInEasing),
        label = "reveal",
    )
    return graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * -14.dp.toPx()
    }
}

val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** Material 3 emphasized-decelerate: fast start, long soft landing, never overshoots. */
val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

private const val SEARCH_BOUNDS_KEY = "vibetube-search-bounds"

/**
 * Container transform between the home search pill and the search screen: the rounded pill springs
 * open until it fills the screen (and shrinks back on the way out). No-op outside a navigation host.
 */
@Composable
fun Modifier.searchBounds(visibilityScope: AnimatedVisibilityScope?, cornerRadius: Int = 28): Modifier {
    if (true) return this
    val transition = LocalSharedTransitionScope.current ?: return this
    if (visibilityScope == null) return this
    val reduced = LocalReducedMotion.current
    return with(transition) {
        this@searchBounds.sharedBounds(
            sharedContentState = rememberSharedContentState(SEARCH_BOUNDS_KEY),
            animatedVisibilityScope = visibilityScope,
            enter = fadeIn(tween(if (reduced) 0 else 140)),
            exit = fadeOut(tween(if (reduced) 0 else 100)),
            boundsTransform = BoundsTransform { _, _ ->
                if (reduced) tween(0) else tween(durationMillis = 340, easing = EmphasizedDecelerate)
            },
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(cornerRadius.dp)),
        )
    }
}

/**
 * Pill that looks like a search field. Tapping stretches it
 * leftwards over the heading ([expansion] 0..1 over [expandDistance] px) before the search screen opens.
 */
@Composable
fun SearchPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    visibilityScope: AnimatedVisibilityScope? = null,
    expansion: Animatable<Float, AnimationVector1D>? = null,
    expandDistance: () -> Float = { 0f },
) {
    val source = remember { MutableInteractionSource() }
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .layout { measurable, constraints ->
                val extra = ((expansion?.value ?: 0f) * expandDistance()).roundToInt().coerceAtLeast(0)
                val width = constraints.maxWidth + extra
                val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
            }
            .height(48.dp)
            .clip(CircleShape)
            .clickable(interactionSource = source, indication = null) {
                scope.launch {
                    if (expansion != null && !reduced) expansion.animateTo(1f, tween(260, easing = EmphasizedDecelerate))
                    onClick()
                }
            },
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            VIconImage(VIcon.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Spacing.md))
            Text(
                stringResource(R.string.search_placeholder),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * Header row of a top-level screen: heading, the search pill, then [trailing] (e.g. the account avatar).
 * The pill pushes across the heading when tapped and the heading fades out right at the pill's leading edge.
 */
@Composable
fun SearchHeaderRow(
    title: String,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    visibilityScope: AnimatedVisibilityScope? = null,
    trailing: @Composable () -> Unit = {},
) {
    val expansion = remember { Animatable(0f) }
    var titleWidth by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { Spacing.md.toPx() }
    val fade = with(androidx.compose.ui.platform.LocalDensity.current) { 36.dp.toPx() }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            modifier = Modifier
                .onSizeChanged { titleWidth = it.width }
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val edge = titleWidth + gap - expansion.value * (titleWidth + gap)
                    if (expansion.value > 0f) {
                        drawRect(
                            brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                colors = listOf(androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color.Transparent),
                                startX = edge - fade,
                                endX = edge,
                            ),
                            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                        )
                    }
                },
        )
        SearchPill(
            onClick = onSearch,
            modifier = Modifier.weight(1f),
            visibilityScope = visibilityScope,
            expansion = expansion,
            expandDistance = { titleWidth + gap },
        )
        trailing()
    }
}

/**
 * Opaque app-bar surface. It is always solid (so scrolled content never shows through) and picks up a
 * tonal fill plus a divider once [scrolled], which separates the header from the list under it.
 */
@Composable
fun HeaderSurface(
    scrolled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val tone by animateFloatAsState(if (scrolled) 1f else 0f, if (reduced) tween(0) else tween(220), label = "headerTone")
    val surface = MaterialTheme.colorScheme.surface
    val raised = MaterialTheme.colorScheme.surfaceContainer
    Surface(color = androidx.compose.ui.graphics.lerp(surface, raised, tone), modifier = modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
            content()
            HorizontalDivider(Modifier.graphicsLayer { alpha = tone }, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
