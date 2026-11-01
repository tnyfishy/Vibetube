package vibe.tube.feature.welcome

import android.os.Build
import android.view.RoundedCorner
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.zIndex
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private data class ScreenOutline(
    val bounds: Rect,
    val topLeft: Float = 0f,
    val topRight: Float = 0f,
    val bottomRight: Float = 0f,
    val bottomLeft: Float = 0f,
)

private fun readScreenOutline(view: View, host: LayoutCoordinates): ScreenOutline {
    val root = view.rootView
    val rootLocation = IntArray(2)
    root.getLocationInWindow(rootLocation)
    val origin = host.positionInWindow()
    val left = rootLocation[0] - origin.x
    val top = rootLocation[1] - origin.y
    val bounds = Rect(left, top, left + root.width, top + root.height)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return ScreenOutline(bounds)
    val insets = root.rootWindowInsets ?: return ScreenOutline(bounds)
    fun radius(position: Int) = insets.getRoundedCorner(position)?.radius?.toFloat() ?: 0f
    return ScreenOutline(
        bounds,
        radius(RoundedCorner.POSITION_TOP_LEFT),
        radius(RoundedCorner.POSITION_TOP_RIGHT),
        radius(RoundedCorner.POSITION_BOTTOM_RIGHT),
        radius(RoundedCorner.POSITION_BOTTOM_LEFT),
    )
}

private val Expand = androidx.compose.animation.core.spring<Float>(
    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
    stiffness = 100f,
    visibilityThreshold = 0.0005f,
)

private const val COLOR_FADE_FROM = 0.1f
private const val COLOR_FADE_SPAN = 0.55f
/** The app starts slightly zoomed in and settles to 1x as it is revealed. */
private const val CONTENT_ZOOM = 0.15f
private const val CONTENT_FROM = 0.12f

private fun smooth(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private class RevealShape(private val rect: RoundRect) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = Outline.Rounded(rect)
}

val LocalWelcomeExit = androidx.compose.runtime.compositionLocalOf<(() -> Float)?> { null }

val LocalAppReveal = androidx.compose.runtime.compositionLocalOf<(() -> Float)?> { null }

@Composable
fun Modifier.appRevealRise(distance: androidx.compose.ui.unit.Dp, lag: Float = 0f): Modifier {
    val reveal = LocalAppReveal.current ?: return this
    return graphicsLayer {
        val r = smooth((reveal() - lag) / (1f - lag))
        translationY = (1f - r) * distance.toPx()
    }
}

@Composable
fun StartRevealHost(
    showStart: Boolean,
    startScreen: @Composable (onStart: () -> Unit, buttonModifier: Modifier, enabled: Boolean) -> Unit,
    onRevealStart: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    buttonColor: androidx.compose.ui.graphics.Color = vibe.tube.ui.components.WordmarkRed,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    // Starts at 1 when there is no welcome, so the reveal lambdas below already read "done".
    val progress = remember { Animatable(if (showStart) 0f else 1f) }
    val hadStart = remember { showStart }
    var stage by remember { mutableIntStateOf(if (showStart) 0 else 2) }
    var sourceRect by remember { mutableStateOf(Rect.Zero) }
    var hostCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var buttonCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var outline by remember { mutableStateOf<ScreenOutline?>(null) }
    val reduced = vibe.tube.ui.theme.LocalReducedMotion.current

    val surface = MaterialTheme.colorScheme.surface

    LaunchedEffect(stage) {
        if (stage != 1) return@LaunchedEffect
        onRevealStart()
        if (reduced) progress.snapTo(1f) else progress.animateTo(1f, Expand)
        stage = 2
        onFinished()
    }

    fun containerRect(screen: ScreenOutline): RoundRect {
        val t = progress.value.coerceIn(0f, 1f)
        fun mix(a: Float, b: Float) = a + (b - a) * t
        // One radius for all corners keeps this a plain round-rect clip, which the GPU does far cheaper than a path.
        val screenRadius = (screen.topLeft + screen.topRight + screen.bottomRight + screen.bottomLeft) / 4f
        return RoundRect(
            left = mix(sourceRect.left, screen.bounds.left),
            top = mix(sourceRect.top, screen.bounds.top),
            right = mix(sourceRect.right, screen.bounds.right),
            bottom = mix(sourceRect.bottom, screen.bounds.bottom),
            cornerRadius = CornerRadius(mix(sourceRect.height / 2f, screenRadius)),
        )
    }
    // Stable lambdas: providing new values on click or on finish would recompose the whole app mid-animation.
    val contentReveal: () -> Float = remember { { smooth((progress.value - CONTENT_FROM) / (1f - CONTENT_FROM)) } }
    val welcomeExit: () -> Float = remember { { progress.value } }

    Box(
        modifier.fillMaxSize().background(surface).onGloballyPositioned {
            hostCoordinates = it
            outline = readScreenOutline(view, it)
        },
    ) {
        if (stage < 2) {
            Box(
                Modifier.fillMaxSize()
                    .zIndex(1f)
                    .graphicsLayer { alpha = 1f - smooth(progress.value / 0.9f) },
            ) {
                androidx.compose.runtime.CompositionLocalProvider(LocalWelcomeExit provides welcomeExit) {
                startScreen(
                    {
                        val host = hostCoordinates
                        val button = buttonCoordinates
                        if (stage == 0 && host?.isAttached == true && button?.isAttached == true) {
                            val bounds = host.localBoundingBoxOf(button, clipBounds = false)
                            if (bounds.width > 0f && bounds.height > 0f) {
                                outline = readScreenOutline(view, host)
                                sourceRect = bounds
                                stage = 1
                            }
                        }
                    },
                    Modifier.onGloballyPositioned { buttonCoordinates = it },
                    stage == 0,
                )
                }
            }
        }

        // The app is drawn at full opacity under the opaque welcome from the start, so its images are already on the
        // GPU when the reveal begins. The modifier chain never changes shape between stages; only values read inside
        // the layer/draw lambdas do, so starting and finishing the reveal never rebuilds the app's layers.
        Box(
            Modifier.fillMaxSize()
                .zIndex(if (stage == 1) 2f else 0f)
                .graphicsLayer {
                    // The button grows into a screen-sized container that clips the app.
                    val growing = stage == 1 && progress.value < 1f
                    clip = growing
                    if (growing) {
                        val screen = outline ?: ScreenOutline(Rect(0f, 0f, size.width, size.height))
                        shape = RevealShape(containerRect(screen))
                    }
                }
                .focusProperties { canFocus = stage == 2 }
                .then(if (stage < 2) Modifier.clearAndSetSemantics { } else Modifier),
        ) {
            Box(
                Modifier.fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        if (stage == 1) {
                            // The container keeps the button's colour at first, then dissolves into the app.
                            val tint = 1f - smooth((progress.value - COLOR_FADE_FROM) / COLOR_FADE_SPAN)
                            if (tint > 0f) drawRect(buttonColor.copy(alpha = tint))
                        }
                    }
                    .graphicsLayer {
                        // The app starts zoomed in around the button and settles to 1x.
                        val s = if (stage == 1) 1f + CONTENT_ZOOM * (1f - contentReveal()) else 1f
                        scaleX = s
                        scaleY = s
                        if (size.width > 0f && size.height > 0f && sourceRect != Rect.Zero) {
                            transformOrigin = TransformOrigin(
                                (sourceRect.center.x / size.width).coerceIn(0f, 1f),
                                (sourceRect.center.y / size.height).coerceIn(0f, 1f),
                            )
                        }
                    },
            ) {
                androidx.compose.runtime.CompositionLocalProvider(LocalAppReveal provides if (hadStart) contentReveal else null) {
                    content()
                }
            }
        }

        if (stage < 2) {
            Box(
                Modifier.fillMaxSize()
                    .zIndex(if (stage == 1) 3f else 0.5f)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    },
            )
        }
    }
}
