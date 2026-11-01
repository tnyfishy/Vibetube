package vibe.tube.feature.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import vibe.tube.BuildConfig
import vibe.tube.R
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.TooltipIconButton
import vibe.tube.ui.components.Wordmark

@Composable
internal fun VersionCard(origin: Rect?, onDismiss: () -> Unit) {
    val p = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val reduced = vibe.tube.ui.theme.LocalReducedMotion.current
    var closing by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf(Rect.Zero) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    fun close() { if (!closing) { closing = true; scope.launch { if (!reduced) p.animateTo(0f, tween(180)); onDismiss() } } }
    LaunchedEffect(Unit) { if (reduced) p.snapTo(1f) else p.animateTo(1f, tween(300)) }
    val clipboard = LocalClipboardManager.current
    val build = "VibeTube ${BuildConfig.VERSION_NAME}\nVersion code: ${BuildConfig.VERSION_CODE}\nBuild type: ${BuildConfig.BUILD_TYPE}\nPackage: ${BuildConfig.APPLICATION_ID}"
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { host = it.boundsInWindow() }
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = .32f * p.value))
            .pointerInput(Unit) { detectTapGestures { close() } }, contentAlignment = Alignment.Center) {
            Surface(Modifier.padding(24.dp).widthIn(max = 520.dp).fillMaxWidth().heightIn(max = maxHeight * .82f)
                .onSizeChanged { cardSize = it }
                .graphicsLayer {
                    val start = origin
                    val f = p.value
                    val fromX = if (start != null && cardSize.width > 0) start.width / cardSize.width else .92f
                    val fromY = if (start != null && cardSize.height > 0) start.height / cardSize.height else .92f
                    scaleX = fromX + (1f-fromX) * f
                    scaleY = fromY + (1f-fromY) * f
                    if (start != null && host != Rect.Zero) {
                        translationX = (start.center.x-host.center.x) * (1f-f)
                        translationY = (start.center.y-host.center.y) * (1f-f)
                    }
                    alpha = (f * 5).coerceIn(0f, 1f)
                }
                .pointerInput(Unit) { detectTapGestures { } },
                shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)
                    .graphicsLayer { alpha = ((p.value-.15f)/.85f).coerceIn(0f,1f) }) {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.Image(painterResource(R.drawable.ic_brand_mark), null, Modifier.size(36.dp))
                        Spacer(Modifier.width(12.dp)); Wordmark("VibeTube", Modifier.weight(1f))
                        TooltipIconButton(VIcon.Close, stringResource(R.string.action_close), ::close)
                    }
                    Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
                    Text("${BuildConfig.BUILD_TYPE} · ${BuildConfig.APPLICATION_ID}", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { clipboard.setText(AnnotatedString(build)) }, modifier = Modifier.padding(horizontal = 12.dp)) { Text(stringResource(R.string.version_copy)) }
                    HorizontalDivider(Modifier.padding(horizontal = 24.dp))
                    vibe.tube.update.UpdatePanel()
                }
            }
        }
    }
}
