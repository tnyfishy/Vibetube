package vibe.tube.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vibe.tube.ui.theme.LocalReducedMotion
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tanh

/** [iconScale] evens out symbols whose artwork reads smaller than its neighbours at the same box size. */
data class NavBarEntry(val label: String, @DrawableRes val icon: Int, @DrawableRes val iconSelected: Int, val iconScale: Float = 1f)

/** The Shorts symbol fills less of its box than the other tab symbols. */
const val SHORTS_ICON_SCALE = 1.14f

data class NavBarAppearance(
    val showLabels: Boolean = false,
    val height: Dp = 60.dp,
    val iconSize: Dp = 22.dp,
    val floating: Boolean = false,
    val blur: Boolean = false,
) {
    val needsBackdrop: Boolean get() = blur && glassSupported

    /**
     * Space below the bar body for a system navigation inset of [inset]. On gesture navigation the bar
     * sinks into part of that inset so it sits snug on the handle; 3-button insets are kept whole.
     */
    fun bottomSpace(inset: Dp): Dp {
        val gesture = inset <= GESTURE_INSET_MAX
        return when {
            floating -> if (gesture) (inset - 4.dp).coerceAtLeast(FLOATING_MIN_GAP) else inset + FLOATING_MIN_GAP
            gesture -> (inset - DOCKED_SINK).coerceAtLeast(0.dp)
            else -> inset
        }
    }
}

private val GESTURE_INSET_MAX = 32.dp
private val DOCKED_SINK = 10.dp
private val FLOATING_MIN_GAP = 6.dp
private val FLOATING_NUDGE = 6.dp
private val FLOATING_SIDE = 16.dp

/**
 * Snapshot of the content drawn behind the nav bar. The source records itself into [layer]; glass
 * surfaces replay it through their own blurred layer so only the bar is blurred, once.
 */
class GlassBackdrop(val layer: GraphicsLayer) {
    internal var coordinates: LayoutCoordinates? = null
}

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

val glassSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

fun Modifier.glassSource(backdrop: GlassBackdrop?): Modifier =
    if (backdrop == null) this
    else this
        .onPlaced { backdrop.coordinates = it }
        .drawWithContent {
            backdrop.layer.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop.layer)
        }

/**
 * Glass fill, drawn as its own node behind the bar's children so the effect never touches icons or
 * the chip. Paints an opaque [base] first (the live content under the bar must not show through
 * sharp), then the backdrop slice under a blur RenderEffect, then a translucent [tint].
 * Falls back to an opaque tint where RenderEffect is unavailable.
 */
@Composable
private fun GlassFill(
    backdrop: GlassBackdrop?,
    tint: Color,
    base: Color,
    modifier: Modifier,
    shape: androidx.compose.ui.graphics.Shape = androidx.compose.ui.graphics.RectangleShape,
) {
    if (backdrop == null || !glassSupported) {
        Box(modifier.background(tint, shape))
        return
    }
    val self = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // Bumped whenever the surface moves (hide-on-scroll, reveal, chip drag) so the slice is re-aligned.
    var moved by remember { mutableIntStateOf(0) }
    val radius = with(LocalDensity.current) { 28.dp.toPx() }
    Box(
        modifier
            .background(base, shape)
            .onGloballyPositioned { self[0] = it; moved++ }
            .graphicsLayer {
                renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                this.shape = shape
                clip = true
            }
            .drawBehind {
                moved
                val source = backdrop.coordinates
                val me = self[0]
                if (source != null && me != null && source.isAttached && me.isAttached) {
                    val at = source.localPositionOf(me, Offset.Zero)
                    translate(-at.x, -at.y) { drawLayer(backdrop.layer) }
                }
            },
    )
    Box(modifier.background(tint.copy(alpha = GLASS_TINT_ALPHA), shape))
}

private const val GLASS_TINT_ALPHA = 0.55f
private const val FROSTED_CHIP_ALPHA = 0.6f

/**
 * The whole bottom bar: background (solid or glass), docked or floating layout, plus [bottomInset]
 * of system navigation space below it.
 */
@Composable
fun NavBar(
    entries: List<NavBarEntry>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    appearance: NavBarAppearance,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop? = null,
) {
    val glassFrom = if (appearance.needsBackdrop) backdrop else null
    if (!appearance.floating) {
        Box(modifier.fillMaxWidth().height(appearance.height + appearance.bottomSpace(bottomInset))) {
            GlassFill(glassFrom, MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.background, Modifier.matchParentSize())
            NavBarRow(entries, selectedIndex, onSelect, Modifier.fillMaxWidth().height(appearance.height), appearance.showLabels, appearance.iconSize)
        }
    } else {
        Box(modifier.fillMaxWidth().height(appearance.height + appearance.bottomSpace(bottomInset))) {
            val shape = RoundedCornerShape(50)
            // Horizontal drift of the whole capsule that follows the chip's drag direction.
            val nudge = remember { Animatable(0f) }
            Box(
                Modifier
                    .padding(horizontal = FLOATING_SIDE)
                    .graphicsLayer { translationX = nudge.value }
                    .fillMaxWidth()
                    .height(appearance.height)
                    .shadow(if (glassFrom != null) 0.dp else 6.dp, shape)
                    .clip(shape)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape),
            ) {
                GlassFill(glassFrom, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.background, Modifier.matchParentSize(), shape)
                FloatingNavRow(
                    entries, selectedIndex, onSelect, appearance.showLabels, appearance.iconSize,
                    frosted = glassFrom != null, nudge = nudge, modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
fun NavBarRow(
    entries: List<NavBarEntry>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true,
    iconSize: Dp = 22.dp,
) {
    BoxWithConstraints(modifier) {
        val labelFit = rememberNavLabelSize(entries.map { it.label }, maxWidth / entries.size.coerceAtLeast(1))
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            entries.forEachIndexed { index, entry ->
                val selected = index == selectedIndex
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(index) }
                        .semantics { if (!showLabels) contentDescription = entry.label },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // No pill and no morph: the selected tab swaps to its filled symbol at once.
                    MorphingSymbol(entry.icon, entry.iconSelected, { if (selected) 1f else 0f }, null, Modifier.size(iconSize * entry.iconScale))
                    if (showLabels) {
                        Spacer(Modifier.height(2.dp))
                        NavLabel(
                            entry.label,
                            selected = selected,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            fit = labelFit,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Floating capsule row. The selection chip follows the finger while dragging, swells while pressed
 * and settles back on release, selecting the tab it lands on. The chip is a flat fill, translucent
 * over a frosted bar so it never stacks a second blur.
 */
@Composable
private fun FloatingNavRow(
    entries: List<NavBarEntry>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    showLabels: Boolean,
    iconSize: Dp,
    frosted: Boolean,
    nudge: Animatable<Float, *>,
    modifier: Modifier = Modifier,
) {
    val count = entries.size.coerceAtLeast(1)
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val chip = remember { Animatable(selectedIndex.coerceAtLeast(0).toFloat()) }
    var pressed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val chipScale by animateFloatAsState(
        if (pressed) 1.16f else 1f,
        if (reduced) snap() else spring(dampingRatio = 0.55f, stiffness = 600f),
        label = "chipScale",
    )
    LaunchedEffect(selectedIndex) {
        if (!dragging && selectedIndex >= 0) {
            if (reduced) chip.snapTo(selectedIndex.toFloat()) else chip.animateTo(selectedIndex.toFloat(), spring(dampingRatio = 0.75f, stiffness = 500f))
        }
    }
    val currentSelect by rememberUpdatedState(onSelect)
    // pointerInput outlives recompositions, so read the open tab through state, not the captured param.
    val openTab by rememberUpdatedState(selectedIndex)
    val chipColor = MaterialTheme.colorScheme.secondaryContainer
    BoxWithConstraints(
        modifier.pointerInput(count) {
            val cell = size.width / count.toFloat()
            fun slot(x: Float) = (x / cell - 0.5f).coerceIn(0f, (count - 1).toFloat())
            val nudgeMax = FLOATING_NUDGE.toPx()
            val soft = spring<Float>(dampingRatio = 0.9f, stiffness = 220f)
            awaitEachGesture {
                val down = awaitFirstDown()
                val nudgeAtDown = nudge.value
                pressed = true
                var moved = false
                val slop = viewConfiguration.touchSlop
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if (!moved && abs(change.position.x - down.position.x) > slop) {
                        moved = true
                        dragging = true
                    }
                    if (moved) {
                        change.consume()
                        val target = slot(change.position.x)
                        scope.launch { chip.snapTo(target) }
                        // Local x shifts by the capsule's own drift; add it back to get the finger's real travel.
                        val travel = change.position.x - down.position.x + (nudge.value - nudgeAtDown)
                        if (!reduced) scope.launch { nudge.animateTo(nudgeMax * tanh(travel / (size.width * 0.6f)), soft) }
                    }
                }
                pressed = false
                dragging = false
                scope.launch { nudge.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 180f)) }
                val index = if (moved) chip.value.roundToInt() else (down.position.x / cell).toInt().coerceIn(0, count - 1)
                scope.launch { chip.animateTo(index.toFloat(), spring(dampingRatio = 0.7f, stiffness = 500f)) }
                // Dragging back onto the open tab is a no-op; only a tap re-selects it (scroll to top / reload).
                if (!moved || index != openTab) currentSelect(index)
            }
        },
    ) {
        val cellW = maxWidth / count
        val labelFit = if (showLabels) rememberNavLabelSize(entries.map { it.label }, cellW) else null
        val pad = 6.dp
        // With labels the chip grows to nearly the full bar height so it wraps icon and text together.
        val padY = if (labelFit != null) 3.dp else pad
        val chipH = maxHeight - padY * 2
        Box(
            Modifier
                .offset { IntOffset(((cellW * chip.value) + pad).roundToPx(), padY.roundToPx()) }
                .size(width = cellW - pad * 2, height = chipH)
                .graphicsLayer { scaleX = chipScale; scaleY = chipScale }
                .drawBehind {
                    drawRoundRect(if (frosted) chipColor.copy(alpha = FROSTED_CHIP_ALPHA) else chipColor, cornerRadius = CornerRadius(size.height / 2f))
                },
        )
        Row(Modifier.fillMaxSize()) {
            entries.forEachIndexed { index, entry ->
                // Icons swap straight to filled on selection; the chip is the only thing that moves.
                val fill = { if (index == selectedIndex) 1f else 0f }
                Column(
                    Modifier.weight(1f).fillMaxHeight().semantics { role = Role.Tab; contentDescription = entry.label },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val near = index == selectedIndex
                    Box(Modifier.graphicsLayer { val k = 1f + (chipScale - 1f) * fill(); scaleX = k; scaleY = k }) {
                        MorphingSymbol(entry.icon, entry.iconSelected, fill, null, Modifier.size(iconSize * entry.iconScale))
                    }
                    if (labelFit != null) {
                        Spacer(Modifier.height(1.dp))
                        NavLabel(
                            entry.label,
                            selected = near,
                            color = if (near) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            fit = labelFit.copy(size = (labelFit.size - 1.5f).coerceAtLeast(NAV_LABEL_MIN)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NavLabel(text: String, selected: Boolean, color: Color, fit: NavLabelFit) {
    Text(
        text,
        style = navLabelStyle(fit.size, selected),
        color = color,
        maxLines = 1,
        softWrap = false,

        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = fit.pad),
    )
}

data class NavLabelFit(val size: Float, val pad: Dp)

@Composable
private fun navLabelStyle(size: Float, selected: Boolean): androidx.compose.ui.text.TextStyle {
    val font = vibe.tube.ui.theme.LocalFontStyleSettings.current
    val family = remember(font, size) { vibe.tube.ui.theme.fontFamilyFor(font, size) }
    return MaterialTheme.typography.labelMedium.copy(
        fontFamily = family, fontSize = size.sp, lineHeight = (size + 4f).sp, letterSpacing = 0.sp,
        fontWeight = FontWeight(((if (selected) 600 else 500) + if (font.font.isSystem) font.weightOffset else 0).coerceIn(1, 1000)),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
}

private val NAV_LABEL_PAD = 2.dp

const val NAV_LABEL_SIZE = 13.5f
const val NAV_LABEL_MIN = 9f

@Composable
fun rememberNavLabelSize(labels: List<String>, cell: Dp): NavLabelFit {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val font = vibe.tube.ui.theme.LocalFontStyleSettings.current
    val wide = navLabelStyle(NAV_LABEL_SIZE, selected = true)
    return remember(labels, cell, wide, density, font) {
        fun fits(size: Float, pad: Dp): Boolean {
            
            val room = with(density) { (cell - pad * 2).toPx() } - 1f
            return labels.all { measurer.measure(it, wide.copy(fontFamily = vibe.tube.ui.theme.fontFamilyFor(font, size), fontSize = size.sp, lineHeight = (size + 4f).sp), maxLines = 1, softWrap = false).size.width <= room }
        }
        
        val floor = NAV_LABEL_MIN / density.fontScale.coerceAtLeast(1f)
        val sizes = generateSequence(NAV_LABEL_SIZE) { it - 0.5f }.takeWhile { it >= floor }.toList()
        
        sizes.firstNotNullOfOrNull { size -> listOf(NAV_LABEL_PAD, 0.5.dp).firstOrNull { fits(size, it) }?.let { NavLabelFit(size, it) } }
            ?: NavLabelFit(sizes.lastOrNull() ?: floor, 0.dp)
    }
}
