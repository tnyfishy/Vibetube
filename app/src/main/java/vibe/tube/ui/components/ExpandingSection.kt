package vibe.tube.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import vibe.tube.ui.theme.LocalReducedMotion

object SectionMotion {
                                                                                                                            
    val size = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f, visibilityThreshold = IntSize.VisibilityThreshold)

    val fadeIn = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 260f)

    val fadeOut = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 900f)

    val enter: EnterTransition = expandVertically(size, expandFrom = Alignment.Top, clip = true) + fadeIn(fadeIn)
    val exit: ExitTransition = shrinkVertically(size, shrinkTowards = Alignment.Top, clip = true) + fadeOut(fadeOut)
}

@Composable
fun ExpandingSection(
    visible: Boolean,
    modifier: Modifier = Modifier,
    onShown: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val state = remember { MutableTransitionState(visible) }
    state.targetState = visible
    val shown by rememberUpdatedState(onShown)
    if (onShown != null) {
        LaunchedEffect(state) {
            snapshotFlow { state.isIdle && state.currentState }.collect { if (it) shown?.invoke() }
        }
    }
    AnimatedVisibility(
        visibleState = state,
        modifier = modifier,
        enter = if (reduced) EnterTransition.None else SectionMotion.enter,
        exit = if (reduced) ExitTransition.None else SectionMotion.exit,
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}
