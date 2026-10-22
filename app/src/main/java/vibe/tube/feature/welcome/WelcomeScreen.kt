package vibe.tube.feature.welcome

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import vibe.tube.R
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.VIconImage
import vibe.tube.ui.theme.Spacing

private data class WelcomePage(val icon: Int, val title: Int, val body: Int)

private val pages = listOf(
    WelcomePage(VIcon.PlayArrowFilled, R.string.welcome_title_1, R.string.welcome_body_1),
    WelcomePage(VIcon.Speed, R.string.welcome_title_2, R.string.welcome_body_2),
    WelcomePage(VIcon.Download, R.string.welcome_title_3, R.string.welcome_body_3),
)

/** First-run introduction: a pager of expressive, slowly turning shapes. Shown once. */
@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex
    val cs = MaterialTheme.colorScheme
    val looks = listOf(
        PageLook(cs.primaryContainer, cs.onPrimaryContainer, cs.primary, cs.onPrimary),
        PageLook(cs.tertiaryContainer, cs.onTertiaryContainer, cs.tertiary, cs.onTertiary),
        PageLook(cs.secondaryContainer, cs.onSecondaryContainer, cs.secondary, cs.onSecondary),
    )
    // Blend between neighbouring pages while swiping so the background glides rather than snaps.
    val pos = pager.currentPage + pager.currentPageOffsetFraction
    val lo = pos.toInt().coerceIn(0, looks.lastIndex)
    val hi = (lo + 1).coerceAtMost(looks.lastIndex)
    val t = (pos - lo).coerceIn(0f, 1f)
    val bg = lerp(looks[lo].container, looks[hi].container, t)
    val onBg = lerp(looks[lo].onContainer, looks[hi].onContainer, t)
    val accent = lerp(looks[lo].accent, looks[hi].accent, t)
    val onAccent = lerp(looks[lo].onAccent, looks[hi].onAccent, t)
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Box(Modifier.fillMaxSize().background(bg)) {
        // Large soft decorative shapes bleeding off the corners.
        Box(
            Modifier.size(360.dp).align(Alignment.TopEnd).graphicsLayer { translationX = 120.dp.toPx(); translationY = -110.dp.toPx(); rotationZ = angle }
                .clip(MaterialShapes.Sunny.toShape()).background(accent.copy(alpha = 0.14f)),
        )
        Box(
            Modifier.size(320.dp).align(Alignment.BottomStart).graphicsLayer { translationX = -120.dp.toPx(); translationY = 110.dp.toPx(); rotationZ = -angle }
                .clip(MaterialShapes.Cookie9Sided.toShape()).background(accent.copy(alpha = 0.14f)),
        )
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDone, colors = ButtonDefaults.textButtonColors(contentColor = onBg)) { Text(stringResource(R.string.welcome_skip)) }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { index ->
            val page = pages[index]
            val offset = (pager.currentPage - index) + pager.currentPageOffsetFraction
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.xxl)
                    .graphicsLayer { alpha = 1f - kotlin.math.abs(offset).coerceIn(0f, 1f) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Hero(index, page.icon, accent, onAccent)
                Spacer(Modifier.height(Spacing.xxl))
                Text(stringResource(page.title), style = MaterialTheme.typography.headlineMedium, color = onBg, textAlign = TextAlign.Center)
                Spacer(Modifier.height(Spacing.md))
                Text(
                    stringResource(page.body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = onBg.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                pages.indices.forEach { i ->
                    val selected = pager.currentPage == i
                    val width by animateDpAsState(if (selected) 28.dp else 8.dp, tween(260), label = "dot")
                    val color by animateColorAsState(if (selected) accent else onBg.copy(alpha = 0.25f), tween(260), label = "dotColor")
                    Box(Modifier.size(width, 8.dp).clip(CircleShape).background(color))
                }
            }
            Button(
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = onAccent),
                onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
            ) { Text(stringResource(if (last) R.string.welcome_start else R.string.welcome_next)) }
        }
    }
    }
}

private class PageLook(val container: Color, val onContainer: Color, val accent: Color, val onAccent: Color)

@Composable
private fun Hero(index: Int, icon: Int, accent: Color, onAccent: Color) {
    val shape = when (index) {
        0 -> MaterialShapes.Cookie9Sided
        1 -> MaterialShapes.Sunny
        else -> MaterialShapes.Clover4Leaf
    }
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = angle }
                .clip(shape.toShape())
                .background(accent.copy(alpha = 0.28f)),
        )
        Box(
            Modifier.size(120.dp).clip(MaterialShapes.Pill.toShape()).background(accent),
            contentAlignment = Alignment.Center,
        ) {
            VIconImage(icon, null, Modifier.size(56.dp), tint = onAccent)
        }
    }
}
