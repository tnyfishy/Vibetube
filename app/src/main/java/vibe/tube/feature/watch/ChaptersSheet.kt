package vibe.tube.feature.watch

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import kotlinx.coroutines.launch
import vibe.tube.R
import vibe.tube.player.Chapter
import vibe.tube.player.Chapters
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.TooltipIconButton
import vibe.tube.ui.components.bouncyClickable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChaptersSheet(chapters: List<Chapter>, positionMs: Long, onSeek: (Long) -> Unit, maxPanelHeight: Dp? = null, onDismiss: () -> Unit) {
    val bounds = LocalPlayerBounds.current.current()
    val view = LocalView.current
    val density = LocalDensity.current
    val height = view.rootView.height
    val portraitPanel = maxPanelHeight != null && bounds != null && height - bounds.bottom > with(density) { 120.dp.toPx() }
    if (!portraitPanel) {
        val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            val dialogView = LocalView.current
            DisposableEffect(dialogView) {
                val window = (dialogView.parent as? DialogWindowProvider)?.window
                window?.let { androidx.core.view.WindowInsetsControllerCompat(it, dialogView).hide(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
                onDispose { }
            }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).bouncyClickable(MaterialTheme.shapes.extraSmall, onClick = onDismiss))
                Surface(Modifier.align(Alignment.CenterEnd).width((androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp * 0.38f).coerceIn(280.dp, 420.dp)).fillMaxHeight(), shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp), color = MaterialTheme.colorScheme.surface) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.player_chapters), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            TooltipIconButton(VIcon.Close, stringResource(R.string.action_close), onDismiss)
                        }
                        ChapterContent(chapters, positionMs, onSeek, Modifier.weight(1f))
                    }
                }
            }
        }
        return
    }
    val video = bounds!!
    val p = remember { Animatable(0f).apply { updateBounds(0f, 1f) } }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val reduced = vibe.tube.ui.theme.LocalReducedMotion.current
    val available = (height - video.bottom).coerceAtLeast(1f)
    fun close() { if (!closing) { closing = true; scope.launch { if (!reduced) p.animateTo(0f, tween(200)); onDismiss() } } }
    LaunchedEffect(Unit) { if (reduced) p.snapTo(1f) else p.animateTo(1f, tween(280)) }
    val position = remember(video) { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = IntOffset(video.left.toInt(), video.bottom.toInt())
    } }
    Popup(popupPositionProvider = position, onDismissRequest = ::close, properties = PopupProperties(focusable = true, clippingEnabled = false)) {
        BackHandler { close() }
        Surface(Modifier.width(with(density) { video.width.toDp() }).height(with(density) { available.toDp() })
            .graphicsLayer { translationY = (1f - p.value) * available; alpha = (p.value * 5f).coerceIn(0f, 1f) },
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp), color = MaterialTheme.colorScheme.surface) {
            Column {
                Column(Modifier.fillMaxWidth().draggable(rememberDraggableState { delta ->
                    scope.launch { p.snapTo((p.value - delta / available).coerceIn(0f, 1f)) }
                }, Orientation.Vertical, onDragStopped = { speed -> if (p.value < .75f || speed > 1200) close() else p.animateTo(1f, tween(180)) })) {
                    Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).size(32.dp, 4.dp).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)))
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.player_chapters), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        TooltipIconButton(VIcon.Close, stringResource(R.string.action_close), ::close)
                    }
                }
                ChapterContent(chapters, positionMs, onSeek, Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun ChapterContent(chapters: List<Chapter>, positionMs: Long, onSeek: (Long) -> Unit, modifier: Modifier) {
    val current = Chapters.currentIndex(chapters, positionMs)
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { if (current > 1) list.scrollToItem(current - 1) }
    LazyColumn(state = list, modifier = modifier.navigationBarsPadding(), contentPadding = PaddingValues(8.dp)) {
        itemsIndexed(chapters, key = { _, chapter -> chapter.startMs }) { index, chapter ->
            Row(Modifier.fillMaxWidth().background(if (index == current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(16.dp))
                .bouncyClickable(RoundedCornerShape(16.dp)) { onSeek(chapter.startMs) }.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(chapter.startMs), Modifier.width(56.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text(chapter.title, Modifier.weight(1f), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}
