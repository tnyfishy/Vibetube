package vibe.tube.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
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
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 520f),
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

private const val SEARCH_BOUNDS_KEY = "vibetube-search-bounds"

/**
 * Container transform between the home search pill and the search screen: the rounded pill springs
 * open until it fills the screen (and shrinks back on the way out). No-op outside a navigation host.
 */
@Composable
fun Modifier.searchBounds(visibilityScope: AnimatedVisibilityScope?, cornerRadius: Int = 28): Modifier {
    val transition = LocalSharedTransitionScope.current
    if (transition == null || visibilityScope == null) return this
    val reduced = LocalReducedMotion.current
    return with(transition) {
        this@searchBounds.sharedBounds(
            sharedContentState = rememberSharedContentState(SEARCH_BOUNDS_KEY),
            animatedVisibilityScope = visibilityScope,
            enter = fadeIn(tween(if (reduced) 0 else 140)),
            exit = fadeOut(tween(if (reduced) 0 else 100)),
            boundsTransform = BoundsTransform { _, _ ->
                if (reduced) tween(0) else spring(dampingRatio = 0.68f, stiffness = 340f, visibilityThreshold = Rect.VisibilityThreshold)
            },
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(cornerRadius.dp)),
        )
    }
}

/** Full-width pill that looks like a search field; tapping it squishes, then opens the search screen. */
@Composable
fun SearchPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    visibilityScope: AnimatedVisibilityScope? = null,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scaleX by animateFloatAsState(
        if (pressed && !reduced) 0.94f else 1f,
        spring(dampingRatio = 0.38f, stiffness = 420f),
        label = "pillX",
    )
    val scaleY by animateFloatAsState(
        if (pressed && !reduced) 0.86f else 1f,
        spring(dampingRatio = 0.38f, stiffness = 420f),
        label = "pillY",
    )
    val scope = rememberCoroutineScope()
    Surface(
        onClick = { scope.launch { if (!reduced) delay(70); onClick() } },
        interactionSource = source,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .height(48.dp)
            .searchBounds(visibilityScope)
            .graphicsLayer {
                this.scaleX = scaleX
                this.scaleY = scaleY
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
