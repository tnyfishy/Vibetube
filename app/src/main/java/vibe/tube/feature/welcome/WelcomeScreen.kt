@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package vibe.tube.feature.welcome

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import kotlinx.coroutines.launch
import vibe.tube.R
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.VIconImage
import vibe.tube.ui.components.WordmarkRed
import vibe.tube.ui.components.pressScale
import kotlin.math.abs
import kotlin.math.floor

private const val REVEAL_START = -0.25f

private enum class Page { Brand, AdFree, Playback, Background, Offline, Privacy, Setup, Done }

private val Pages = Page.entries

/** The morphing hero for a page; null pages (wordmark, setup) shrink it away. tone: 0 primary, 1 secondary, 2 tertiary. */
private class HeroSpec(val polygon: RoundedPolygon, val icon: Int, val tone: Int)

private fun heroFor(page: Page): HeroSpec? = when (page) {
    Page.Brand, Page.Setup -> null
    Page.AdFree -> HeroSpec(MaterialShapes.Cookie9Sided, VIcon.PlayArrowFilled, 0)
    Page.Playback -> HeroSpec(MaterialShapes.Sunny, VIcon.Speed, 2)
    Page.Background -> HeroSpec(MaterialShapes.Clover4Leaf, VIcon.PictureInPicture, 1)
    Page.Offline -> HeroSpec(MaterialShapes.Arch, VIcon.Download, 0)
    Page.Privacy -> HeroSpec(MaterialShapes.Gem, VIcon.Shield, 2)
    Page.Done -> HeroSpec(MaterialShapes.Cookie12Sided, VIcon.Check, 0)
}

private val HeroArea = 260.dp

private val PageSpring = spring<Float>(dampingRatio = 0.86f, stiffness = 260f)

@Composable
fun WelcomeScreen(onStart: () -> Unit, buttonModifier: Modifier, enabled: Boolean) {
    var ready by rememberSaveable { mutableStateOf(false) }
    val pager = rememberPagerState { Pages.size }
    val scope = rememberCoroutineScope()
    val reduced = vibe.tube.ui.theme.LocalReducedMotion.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val background = MaterialTheme.colorScheme.surface
    val foreground = if (dark) Color.White else Color.Black
    val exit = LocalWelcomeExit.current
    val position: () -> Float = { pager.currentPage + pager.currentPageOffsetFraction }
    val last = Pages.lastIndex
    val controls = androidx.compose.animation.core.animateFloatAsState(if (ready) 1f else 0f, tween(320), label = "controls")

    fun goTo(page: Int) {
        scope.launch {
            if (reduced) pager.scrollToPage(page) else pager.animateScrollToPage(page, animationSpec = PageSpring)
        }
    }

    BackHandler(enabled = enabled && pager.currentPage > 0) { goTo(pager.currentPage - 1) }

    Box(Modifier.fillMaxSize().drawBehind { drawRect(background.copy(alpha = 1f - (exit?.invoke() ?: 0f))) }) {
        // Exit: the page sinks back (scale + fade) while the app grows out of the button.
        // No blur here: re-blurring a full-screen layer at a new radius every frame is what made the entry stutter.
        Box(Modifier.fillMaxSize().graphicsLayer {
            val p = (exit?.invoke() ?: 0f).coerceIn(0f, 1f)
            val s = 1f - .1f * p
            scaleX = s; scaleY = s
            alpha = (1f - p * 1.6f).coerceIn(0f, 1f)
        }) {
            WelcomeShapes()
            // Floaters step aside on the setup page, where content fills the screen.
            FloatingShapes(position, visibility = { abs(position() - Page.Setup.ordinal).coerceIn(0f, 1f) })
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp)) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = ready && pager.targetPage < last,
                        modifier = Modifier.align(Alignment.CenterEnd),
                        enter = fadeIn(tween(220)),
                        exit = fadeOut(tween(160)),
                    ) {
                        TextButton(onClick = { goTo(last) }, enabled = enabled) {
                            Text(stringResource(R.string.welcome_skip), color = foreground.copy(alpha = .75f), fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    HorizontalPager(
                        state = pager,
                        userScrollEnabled = enabled && ready,
                        modifier = Modifier.fillMaxSize(),
                    ) { index ->
                        val offset: () -> Float = { position() - index }
                        when (Pages[index]) {
                            Page.Brand -> BrandIntro(enabled, ready, foreground, offset) { ready = true }
                            Page.AdFree -> IntroText(R.string.welcome_title_adfree, R.string.welcome_body_adfree, foreground, offset)
                            Page.Playback -> IntroText(R.string.welcome_title_2, R.string.welcome_body_2, foreground, offset)
                            Page.Background -> IntroText(R.string.welcome_title_background, R.string.welcome_body_background, foreground, offset)
                            Page.Offline -> IntroText(R.string.welcome_title_3, R.string.welcome_body_3, foreground, offset)
                            Page.Privacy -> IntroText(R.string.welcome_title_privacy, R.string.welcome_body_privacy, foreground, offset)
                            Page.Setup -> SetupChoices(foreground, Modifier.fillMaxSize().parallax(offset, .3f))
                            Page.Done -> IntroText(R.string.setup_done_title, R.string.setup_done_body, foreground, offset)
                        }
                    }
                    HeroMorph(position, Modifier.align(Alignment.TopCenter).padding(top = 24.dp).size(196.dp))
                }
                // Always laid out (only faded in) so the pager never changes height and the wordmark stays put.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp).height(64.dp).graphicsLayer {
                        alpha = controls.value
                        translationY = (1f - controls.value) * 24.dp.toPx()
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PageDots(Pages.size, position, foreground, Modifier.weight(1f))
                    NextButton(
                        isLast = pager.targetPage == last,
                        position = position,
                        enabled = enabled && ready,
                        modifier = buttonModifier,
                        onClick = { if (pager.currentPage == last) onStart() else goTo(pager.currentPage + 1) },
                    )
                }
            }
        }
    }
}

/** Content trails the pager by [amount] of its travel and fades as it leaves, so pages slide in layers. */
private fun Modifier.parallax(offset: () -> Float, amount: Float) = graphicsLayer {
    val o = offset()
    translationX = o * size.width * amount
    alpha = (1f - abs(o) * 1.4f).coerceIn(0f, 1f)
    val s = 1f - .06f * abs(o).coerceAtMost(1f)
    scaleX = s; scaleY = s
}

@Composable
private fun IntroText(title: Int, body: Int, color: Color, offset: () -> Float) {
    Column(
        Modifier.fillMaxSize().padding(top = HeroArea, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.parallax(offset, .2f),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodyLarge,
            color = color.copy(alpha = .72f),
            textAlign = TextAlign.Center,
            modifier = Modifier.parallax(offset, .45f),
        )
    }
}

@Composable
private fun BrandIntro(active: Boolean, ready: Boolean, color: Color, offset: () -> Float, onFinished: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp).parallax(offset, .5f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrandPage(active, Modifier.fillMaxWidth().height(80.dp), onFinished = onFinished)
        Box(Modifier.height(72.dp), contentAlignment = Alignment.TopCenter) {
            androidx.compose.animation.AnimatedVisibility(visible = ready, enter = fadeIn(tween(360, 120)) + slideInVertically(tween(360, 120)) { it / 3 }) {
                Text(
                    stringResource(R.string.welcome_body_1),
                    style = MaterialTheme.typography.titleMedium,
                    color = color.copy(alpha = .72f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** One shape that morphs, spins and recolours between pages, carrying a cross-fading icon. */
@Composable
private fun HeroMorph(position: () -> Float, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val specs = remember { Pages.map(::heroFor) }
    val morphs = remember {
        List(Pages.size - 1) { i ->
            val a = specs[i]?.polygon ?: specs[i + 1]?.polygon
            val b = specs[i + 1]?.polygon ?: a
            if (a != null && b != null) Morph(a.normalized(), b.normalized()) else null
        }
    }
    val containers = listOf(scheme.primaryContainer, scheme.secondaryContainer, scheme.tertiaryContainer)
    val contents = listOf(scheme.onPrimaryContainer, scheme.onSecondaryContainer, scheme.onTertiaryContainer)
    val reduced = vibe.tube.ui.theme.LocalReducedMotion.current
    val spin = remember { Animatable(0f) }
    LaunchedEffect(reduced) {
        if (reduced) return@LaunchedEffect
        while (true) {
            spin.animateTo(spin.value + 360f, tween(24_000, easing = LinearEasing))
        }
    }
    fun presence(pos: Float): Float {
        val i = floor(pos).toInt().coerceIn(0, Pages.lastIndex - 1)
        val f = FastOutSlowInEasing.transform((pos - i).coerceIn(0f, 1f))
        val a = if (specs[i] == null) 0f else 1f
        val b = if (specs[i + 1] == null) 0f else 1f
        return a + (b - a) * f
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().drawWithCache {
            val path = Path()
            onDrawBehind {
                val pos = position().coerceIn(0f, Pages.lastIndex.toFloat())
                val shown = presence(pos)
                if (shown <= .01f) return@onDrawBehind
                val i = floor(pos).toInt().coerceIn(0, Pages.lastIndex - 1)
                val f = FastOutSlowInEasing.transform((pos - i).coerceIn(0f, 1f))
                val morph = morphs[i] ?: return@onDrawBehind
                val from = (specs[i] ?: specs[i + 1])!!.tone
                val to = (specs[i + 1] ?: specs[i])!!.tone
                val fill = lerp(containers[from], containers[to], f)
                val side = size.minDimension * .78f
                morph.toComposePath(f, Size(side, side), path)
                val pivot = Offset(side / 2, side / 2)
                translate((size.width - side) / 2, (size.height - side) / 2) {
                    scale(shown, pivot) {
                        rotate(spin.value + pos * 72f, pivot) { drawPath(path, fill) }
                    }
                }
            }
        })
        specs.forEachIndexed { index, spec ->
            if (spec == null) return@forEachIndexed
            VIconImage(
                spec.icon,
                null,
                Modifier.size(64.dp).graphicsLayer {
                    val d = position() - index
                    val a = (1f - abs(d) * 1.8f).coerceIn(0f, 1f)
                    alpha = a
                    val s = .5f + .5f * a
                    scaleX = s; scaleY = s
                    rotationZ = -d * 90f
                },
                tint = contents[spec.tone],
            )
        }
    }
}

@Composable
private fun PageDots(count: Int, position: () -> Float, color: Color, modifier: Modifier = Modifier) {
    val active = WordmarkRed
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            Box(
                Modifier
                    .layout { measurable, constraints ->
                        val near = (1f - abs(position() - i)).coerceIn(0f, 1f)
                        val w = (8.dp + 18.dp * near).roundToPx()
                        val h = 8.dp.roundToPx()
                        val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w, minHeight = h, maxHeight = h))
                        layout(w, h) { placeable.place(0, 0) }
                    }
                    .drawBehind {
                        val near = (1f - abs(position() - i)).coerceIn(0f, 1f)
                        drawRoundRect(
                            lerp(color.copy(alpha = .22f), active, near),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
                        )
                    },
            )
        }
    }
}

@Composable
private fun NextButton(isLast: Boolean, position: () -> Float, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val width by animateDpAsState(
        if (isLast) 200.dp else 64.dp,
        spring(dampingRatio = .72f, stiffness = Spring.StiffnessMediumLow),
        label = "nextWidth",
    )
    val label = stringResource(if (isLast) R.string.welcome_enter else R.string.welcome_next)
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        interactionSource = press,
        modifier = modifier.width(width).height(64.dp).pressScale(press, .9f),
        colors = ButtonDefaults.buttonColors(
            containerColor = WordmarkRed, contentColor = Color.White,
            disabledContainerColor = WordmarkRed, disabledContentColor = Color.White,
        ),
    ) {
        AnimatedContent(
            targetState = isLast,
            transitionSpec = { (fadeIn(tween(220, 90)) + scaleIn(tween(220, 90), .8f)) togetherWith (fadeOut(tween(90)) + scaleOut(tween(90), .8f)) },
            label = "nextContent",
        ) { last ->
            if (last) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    Spacer(Modifier.width(8.dp))
                    VIconImage(VIcon.ArrowForward, null, Modifier.size(22.dp))
                }
            } else {
                // The arrow nudges forward while a page is mid-swipe.
                VIconImage(
                    VIcon.ArrowForward,
                    label,
                    Modifier.size(28.dp).graphicsLayer {
                        val frac = position() - floor(position())
                        translationX = kotlin.math.sin(frac * Math.PI).toFloat() * 6.dp.toPx()
                    },
                )
            }
        }
    }
}

@Composable
private fun BrandPage(active: Boolean, modifier: Modifier = Modifier, onFinished: () -> Unit = {}) {
    val reveal = remember { androidx.compose.animation.core.Animatable(0f) }
    val reduced = vibe.tube.ui.theme.LocalReducedMotion.current
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        if (reduced) {
            reveal.snapTo(1f)
            onFinished()
            return@LaunchedEffect
        }

        reveal.snapTo(REVEAL_START)
        reveal.animateTo(1f, tween(980, easing = androidx.compose.animation.core.LinearEasing))
        onFinished()
    }
    val style = MaterialTheme.typography.displaySmall.copy(
        fontFamily = vibe.tube.ui.components.BrandFont,
        fontWeight = FontWeight.Bold,
        fontSize = 38.sp,
        color = WordmarkRed,
    )
    val rest = "ibeTube"
    Box(modifier, contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("V", style = style, maxLines = 1, softWrap = false)
            rest.forEachIndexed { i, ch ->
                Text(
                    ch.toString(),
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Int.MAX_VALUE))
                            val p = FastOutSlowInEasing.transform(((reveal.value - i * 0.1f) / 0.4f).coerceIn(0f, 1f))
                            val width = (placeable.width * p).toInt()
                            layout(width, placeable.height) { placeable.place(0, 0) }
                        }
                        .graphicsLayer {
                            clip = true
                            alpha = ((reveal.value - i * 0.1f) / 0.3f).coerceIn(0f, 1f)
                        },
                )
            }
        }
    }
}
