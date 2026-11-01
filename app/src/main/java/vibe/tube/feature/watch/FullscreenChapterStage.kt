package vibe.tube.feature.watch

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import vibe.tube.R
import vibe.tube.player.Chapter
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.TooltipIconButton
import kotlin.math.roundToInt

@Composable
internal fun FullscreenSideStage(open: Boolean, onDismiss: () -> Unit, panel: @Composable () -> Unit,
    video: @Composable (Modifier) -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(open) { progress.animateTo(if (open) 1f else 0f, tween(if (open) 260 else 220)) }
    val currentOpen by rememberUpdatedState(open)
    val shown by remember { derivedStateOf { currentOpen || progress.value > 0f } }
    BackHandler(shown, onDismiss)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).clipToBounds()) {
        val panelWidth = minOf(maxWidth * 0.42f, 420.dp)
        val density = androidx.compose.ui.platform.LocalDensity.current
        val panelPx = with(density) { panelWidth.toPx() }
        video(Modifier.layout { measurable, constraints ->
            val available = (constraints.maxWidth - panelPx * progress.value).roundToInt().coerceAtLeast(1)
            val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(available, constraints.maxHeight))
            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
        })
        if (shown) Surface(Modifier.align(Alignment.CenterEnd).width(panelWidth).fillMaxHeight()
            .offset { IntOffset((panelPx * (1f - progress.value)).roundToInt(), 0) }, color = MaterialTheme.colorScheme.surface) { panel() }
    }
}

@Composable
internal fun ChapterSideContent(chapters: List<Chapter>, positionMs: Long, onSeek: (Long) -> Unit, onDismiss: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.player_chapters), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TooltipIconButton(VIcon.Close, stringResource(R.string.action_close), onDismiss)
        }
        ChapterContent(chapters, positionMs, onSeek, Modifier.weight(1f))
    }
}

@Composable
internal fun FullscreenChapterStage(open: Boolean, chapters: List<Chapter>, positionMs: Long,
    onSeek: (Long) -> Unit, onDismiss: () -> Unit, video: @Composable (Modifier) -> Unit) {
    FullscreenSideStage(open, onDismiss, { ChapterSideContent(chapters, positionMs, onSeek, onDismiss) }, video)
}
