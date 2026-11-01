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
import androidx.compose.animation.core.Spring
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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

private val PressInSpec = spring<Float>(dampingRatio = 1f, stiffness = 1800f, visibilityThreshold = 0.0005f)

private val PressOutSpec = spring<Float>(dampingRatio = 0.7f, stiffness = 900f, visibilityThreshold = 0.0005f)

private const val DIP_DONE = 0.004f

@Composable
fun Modifier.pressScale(interactionSource: InteractionSource, pressedScale: Float = 0.96f): Modifier {
    val reduced = LocalReducedMotion.current
    val scale = remember { Animatable(1f) }
    LaunchedEffect(interactionSource, reduced) {
        if (reduced) {
            scale.snapTo(1f)
            return@LaunchedEffect
        }
        var motion: kotlinx.coroutines.Job? = null
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is androidx.compose.foundation.interaction.PressInteraction.Press -> {
                    motion?.cancel()
                    motion = launch { scale.animateTo(pressedScale, PressInSpec) }
                }
                is androidx.compose.foundation.interaction.PressInteraction.Release,
                is androidx.compose.foundation.interaction.PressInteraction.Cancel -> {
                    motion?.cancel()
                    motion = launch {

                        val tapped = interaction is androidx.compose.foundation.interaction.PressInteraction.Release
                        if (tapped && scale.value > pressedScale + DIP_DONE) scale.animateTo(pressedScale, PressInSpec)
                        scale.animateTo(1f, PressOutSpec)
                    }
                }
            }
        }
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

@Composable
fun Modifier.bouncyClickable(
    shape: Shape,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    pressedScale: Float = 0.97f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    return this
        .pressScale(source, pressedScale)
        .clip(shape)
        .combinedClickable(
            interactionSource = source,
            indication = LocalIndication.current,
            enabled = enabled,
            role = role,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}

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

val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

private const val SEARCH_BOUNDS_KEY = "vibetube-search-bounds"

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

val BrandMarkSize = 28.dp

/** The wordmark's cap height, held to the V inside the mark (about 60% of its height) so the name sits at the logo's scale. */
private val BrandWordCapHeight = BrandMarkSize * 0.6f

val BrandGap = Spacing.sm

val HeaderGlyphSize = 22.dp

private val NudgeIn = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 2400f)
private val NudgeOut = spring<Float>(dampingRatio = 0.5f, stiffness = 650f)
private val TuckIn = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 170f, visibilityThreshold = 0.01f)
private val TuckOut = spring<Float>(dampingRatio = 0.85f, stiffness = 95f, visibilityThreshold = 0.001f)
private const val TUCK_SCALE = 0.55f

private const val SEARCH_OPEN_SPEEDUP = 1.15f * 1.15f * 1.05f

fun searchOpenMs(baseMs: Int): Int = kotlin.math.round(baseMs / SEARCH_OPEN_SPEEDUP).toInt()

fun searchCloseMs(baseMs: Int): Int = kotlin.math.round(baseMs / 1.10f).toInt()

private val RevealEase = androidx.compose.animation.core.Easing { t -> 1f - (1f - t) * (1f - t) * (1f - t) * (1f - t) }
private fun revealMix(a: Float, b: Float, t: Float) = a + (b - a) * t
                                                                                                    
private fun revealExpand(p: Float) = ((p - 0.12f) / 0.88f).coerceIn(0f, 1f)

@Composable
fun SearchPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    visibilityScope: AnimatedVisibilityScope? = null,
    expansion: Animatable<Float, AnimationVector1D>? = null,
    expandDistance: () -> Float = { 0f },
    expandEndDistance: () -> Float = { 0f },
    iconSize: androidx.compose.ui.unit.Dp = 24.dp,
) {
    val source = remember { MutableInteractionSource() }
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .zIndex(1f)
            .size(48.dp)
            .drawBehind {
                val p = expansion?.value ?: 0f
                if (p > 0f) {
                    val e = revealExpand(p)
                    val d = 6.dp.toPx()
                    val left = revealMix(d, -expandDistance().coerceAtLeast(0f), e)
                    val right = revealMix(size.width - d, size.width + expandEndDistance().coerceAtLeast(0f), e)
                    val top = d * (1f - e)
                    val height = size.height - 2f * top
                    drawRoundRect(
                        color = fill.copy(alpha = (p / 0.18f).coerceIn(0f, 1f)),
                        topLeft = androidx.compose.ui.geometry.Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(right - left, height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(height / 2f),
                    )
                }
            }
            .clickable(interactionSource = source, indication = null) {
                scope.launch {
                    if (expansion != null && !reduced) {
                        val remaining = 1f - expansion.value.coerceIn(0f, 1f)
                        expansion.animateTo(1f, tween(kotlin.math.round(searchOpenMs(400) * remaining).toInt().coerceAtLeast(1), easing = RevealEase))
                    }
                    onClick()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        
        Text(
            stringResource(R.string.search_placeholder),
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .wrapContentSize(Alignment.CenterStart, unbounded = true)
                .graphicsLayer {
                    val p = expansion?.value ?: 0f
                    val e = revealExpand(p)
                    val left = revealMix(6.dp.toPx(), -expandDistance().coerceAtLeast(0f), e)
                    alpha = ((p - 0.72f) / 0.28f).coerceIn(0f, 1f)
                    translationX = left + 48.dp.toPx()
                },
        )
        Box(
            Modifier.graphicsLayer {
                val e = revealExpand(expansion?.value ?: 0f)
                val left = revealMix(6.dp.toPx(), -expandDistance().coerceAtLeast(0f), e)
                translationX = (left - 6.dp.toPx()) + 6.dp.toPx() * e
            },
        ) {
            VIconImage(VIcon.Search, stringResource(R.string.action_search), Modifier.size(iconSize), tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun SearchHeaderRow(
    title: String,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    visibilityScope: AnimatedVisibilityScope? = null,
    wordmark: Boolean = false,
                                                                                                                   
    shown: () -> Boolean = { true },
    trailing: (@Composable () -> Unit)? = null,
) {
    var returning by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val expansion = remember { Animatable(if (returning) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (returning) {
            returning = false
            expansion.animateTo(0f, tween(searchCloseMs(kotlin.math.round(360 / (1.15f * 1.15f)).toInt()), easing = RevealEase))
        }
    }
    var titleWidth by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    var spacerWidth by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    var trailingWidth by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val fade = with(density) { 36.dp.toPx() }
    val searchFieldStart = 0f
    val markBlock = if (wordmark) with(density) { (BrandMarkSize + BrandGap).toPx() } else 0f
    val titleLocalPillLeft = { (titleWidth + spacerWidth).toFloat() }
    val pillLeft = { markBlock + titleWidth + spacerWidth }
    val edgeInset = with(density) { 4.dp.toPx() }
    val soft = with(density) { 18.dp.toPx() }
    val six = with(density) { 6.dp.toPx() }
    
    val reduced = LocalReducedMotion.current
    var tucked by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val tuck = remember { Animatable(if (tucked) 1f else 0f) }
    val squash = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var tuckJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun playTuck(nudge: Boolean) {
        if (!wordmark || expansion.value > 0f) return
        tucked = !tucked
        tuckJob?.cancel()
        tuckJob = scope.launch {
            if (nudge) {
                squash.animateTo(0.88f, NudgeIn)
                launch { squash.animateTo(1f, NudgeOut) }
            }
            if (reduced) tuck.snapTo(if (tucked) 1f else 0f)
            else tuck.animateTo(if (tucked) 1f else 0f, if (tucked) TuckIn else TuckOut)
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (wordmark) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(R.drawable.ic_brand_mark),
                contentDescription = title,
                modifier = Modifier.zIndex(1f).size(BrandMarkSize).clickable(interactionSource = null, indication = null, onClickLabel = title) { playTuck(nudge = true) }.graphicsLayer {
                    alpha = 1f - ((revealExpand(expansion.value) - 0.55f) / 0.4f).coerceIn(0f, 1f)
                },
            )
            Spacer(Modifier.width(BrandGap))
        }
        val tuckModifier = if (!wordmark) Modifier else Modifier
            .clickable(enabled = !tucked, interactionSource = null, indication = null, onClickLabel = title) { playTuck(nudge = true) }
            .drawWithContent {
                val t = tuck.value
                if (t <= 0f) {
                    drawContent()
                } else {
                    
                    val cut = -(BrandGap.toPx() + BrandMarkSize.toPx() / 2f)
                    clipRect(left = cut, top = -size.height, right = size.width * 2f, bottom = size.height * 2f) { this@drawWithContent.drawContent() }
                }
            }
            .graphicsLayer {
                val t = tuck.value
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                val s = 1f + (TUCK_SCALE - 1f) * t
                scaleX = s
                scaleY = s
                
                val cut = -(BrandGap.toPx() + BrandMarkSize.toPx() / 2f)
                translationX = (cut - titleWidth * TUCK_SCALE - 6.dp.toPx()) * t
            }
            .graphicsLayer {
                scaleX = squash.value
                scaleY = squash.value
            }
        val titleModifier = tuckModifier
            .onSizeChanged { titleWidth = it.width }
            .graphicsLayer {
                compositingStrategy = if (expansion.value > 0f) androidx.compose.ui.graphics.CompositingStrategy.Offscreen else androidx.compose.ui.graphics.CompositingStrategy.Auto
                alpha = if (expansion.value >= 1f) 0f else 1f
            }
            .drawWithContent {
                drawContent()
                val p = expansion.value
                if (p > 0f && p < 1f) {
                    val e = revealExpand(p)
                    val edge = (titleLocalPillLeft() + revealMix(six, -(pillLeft() - searchFieldStart), e) - edgeInset).coerceAtLeast(0.01f)
                    drawRect(
                        brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                            colors = listOf(androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color.Transparent),
                            startX = (edge - soft).coerceAtLeast(0f),
                            endX = edge,
                        ),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                    )
                }
            }
        if (wordmark) {
            // As tall as the logo beside it. Its line box is held to the logo's height so the larger type leaves the
            // header row (and the search pill it lines up with) where it was; "VibeTube" has nothing below the line.
            Wordmark(title, titleModifier.height(BrandMarkSize).wrapContentHeight(unbounded = true), capHeight = BrandWordCapHeight)
        } else {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, modifier = titleModifier)
        }
        Spacer(Modifier.weight(1f).onSizeChanged { spacerWidth = it.width })
        SearchPill(
            onClick = {
                tuckJob?.cancel()
                scope.launch { tuck.snapTo(0f); squash.snapTo(1f) }
                returning = true
                onSearch()
            },
            iconSize = HeaderGlyphSize,
            visibilityScope = visibilityScope,
            expansion = expansion,
            expandDistance = { pillLeft() - searchFieldStart },
            expandEndDistance = { trailingWidth.toFloat() },
        )
        if (trailing != null) {
            Box(
                Modifier
                    .onSizeChanged { trailingWidth = it.width }
                    .graphicsLayer { alpha = 1f - (expansion.value / 0.6f).coerceIn(0f, 1f) },
            ) { trailing() }
        }
    }
}

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
