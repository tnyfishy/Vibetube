package vibe.tube.ui.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

fun fullSizeAvatarUrl(url: String): String {
    val host = runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty()
    if (!(host.endsWith("googleusercontent.com") || host.endsWith("ggpht.com"))) return url
    val original = Regex("=s\\d+").replace(url, "=s0")
    if (original != url) return original
    return upscaleAvatarUrl(url, 2048) ?: url
}

private suspend fun saveAvatar(context: android.content.Context, url: String, onProgress: (Float?) -> Unit): Boolean =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
            }
            connection.connect()
            val total = connection.contentLengthLong
            val mime = connection.contentType?.substringBefore(';')?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
            val ext = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
            val name = "vibetube_avatar_${System.currentTimeMillis()}.$ext"
            val resolver = context.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VibeTube")
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("no uri")
            try {
                connection.inputStream.use { input ->
                    resolver.openOutputStream(uri)!!.use { out ->
                        val buffer = ByteArray(16 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            read += n
                            onProgress(if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else null)
                        }
                    }
                }
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                }
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            true
        }.getOrDefault(false)
    }

@Composable
fun AvatarViewer(url: String, onDismiss: () -> Unit) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 300f)) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val ringProgress = remember { Animatable(0f) }
    val ringAlpha = remember { Animatable(0f) }
    var busy by remember { androidx.compose.runtime.mutableStateOf(false) }
    var indeterminate by remember { androidx.compose.runtime.mutableStateOf(false) }
    val ringColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val spin = androidx.compose.animation.core.rememberInfiniteTransition(label = "ringSpin")
        .animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)), label = "spin")
    val savedText = androidx.compose.ui.res.stringResource(vibe.tube.R.string.avatar_saved)
    val failedText = androidx.compose.ui.res.stringResource(vibe.tube.R.string.avatar_save_failed)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.88f * enter.value))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .aspectRatio(1f)
                    .graphicsLayer {
                        val e = enter.value
                        scaleX = 0.6f + 0.4f * e
                        scaleY = scaleX
                        alpha = e.coerceIn(0f, 1f)
                    }
                    .drawWithContent {
                        drawContent()
                        val a = ringAlpha.value
                        if (a > 0f) {
                            val stroke = 7.dp.toPx()
                            val inset = stroke / 2f
                            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                            drawArc(
                                color = ringColor.copy(alpha = 0.25f * a),
                                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                                topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = arcSize,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                            )
                            drawArc(
                                color = ringColor.copy(alpha = a),
                                startAngle = if (indeterminate) spin.value - 90f else -90f,
                                sweepAngle = if (indeterminate) 90f else 360f * ringProgress.value,
                                useCenter = false,
                                topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = arcSize,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                            )
                        }
                    }
                    .padding(7.dp),
            ) {
                RemoteImage(
                    fullSizeAvatarUrl(url),
                    null,
                    Modifier.fillMaxSize().clip(CircleShape),
                    ContentScale.Crop,
                )
            }
            androidx.compose.material3.FilledTonalIconButton(
                onClick = {
                    if (busy) return@FilledTonalIconButton
                    busy = true
                    scope.launch {
                        ringProgress.snapTo(0f)
                        indeterminate = true
                        ringAlpha.snapTo(1f)
                        val ok = saveAvatar(context, fullSizeAvatarUrl(url)) { p ->
                            if (p == null) indeterminate = true else {
                                indeterminate = false
                                scope.launch { ringProgress.snapTo(p) }
                            }
                        }
                        indeterminate = false
                        if (ok) ringProgress.animateTo(1f, tween(180))
                        android.widget.Toast.makeText(context, if (ok) savedText else failedText, android.widget.Toast.LENGTH_SHORT).show()
                        ringAlpha.animateTo(0f, tween(600))
                        busy = false
                    }
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).statusBarsPadding(),
            ) {
                VIconImage(vibe.tube.ui.VIcon.Download, androidx.compose.ui.res.stringResource(vibe.tube.R.string.action_download))
            }
        }
    }
}
