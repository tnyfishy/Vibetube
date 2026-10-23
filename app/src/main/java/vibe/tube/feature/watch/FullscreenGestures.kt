package vibe.tube.feature.watch

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import vibe.tube.R
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.VIconImage
import vibe.tube.ui.theme.MediaOverlayColors
import vibe.tube.ui.theme.Spacing
import kotlin.math.roundToInt

/** Whether the fullscreen brightness / volume swipes are enabled (Settings). */
val LocalFullscreenGestures = androidx.compose.runtime.compositionLocalOf { true }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private data class GestureHud(val brightness: Boolean, val value: Float)

/**
 * Fullscreen touch zones: swiping up/down on the left third changes the screen brightness, on the right third the
 * media volume, and in the middle [centerDrag] (leave fullscreen) keeps working as before.
 */
@Composable
internal fun FullscreenGestureZones(centerDrag: Modifier, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var hud by remember { mutableStateOf<GestureHud?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(hud) {
        if (hud != null) {
            visible = true
            delay(900)
            visible = false
        }
    }
    // Brightness is only changed for the fullscreen session; leaving it restores the system's own level.
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let { w ->
                val attrs = w.attributes
                attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = attrs
            }
        }
    }

    fun readBrightness(): Float {
        val set = activity?.window?.attributes?.screenBrightness ?: -1f
        if (set >= 0f) return set
        return runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
    }
    fun writeBrightness(v: Float) {
        val w = activity?.window ?: return
        val attrs = w.attributes
        attrs.screenBrightness = v.coerceIn(0.01f, 1f)
        w.attributes = attrs
    }
    val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    fun readVolume(): Float = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
    fun writeVolume(v: Float) {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * maxVolume).roundToInt().coerceIn(0, maxVolume), 0)
    }

    fun adjust(brightness: Boolean): Modifier = Modifier.pointerInput(brightness) {
        var value = 0f
        detectVerticalDragGestures(
            onDragStart = { value = if (brightness) readBrightness() else readVolume() },
            onVerticalDrag = { change, dy ->
                change.consume()
                // A swipe over 70% of the height covers the whole range.
                value = (value - dy / (size.height * 0.7f)).coerceIn(0f, 1f)
                if (brightness) writeBrightness(value) else writeVolume(value)
                hud = GestureHud(brightness, value)
            },
        )
    }

    Box(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(0.3f).fillMaxHeight().then(adjust(brightness = true)))
            Box(Modifier.weight(0.4f).fillMaxHeight().then(centerDrag))
            Box(Modifier.weight(0.3f).fillMaxHeight().then(adjust(brightness = false)))
        }
        val shown = hud
        AnimatedVisibility(
            visible = visible && shown != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(if (shown?.brightness != false) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = Spacing.xxl),
        ) {
            if (shown != null) GestureHudCard(shown)
        }
    }
}

@Composable
private fun GestureHudCard(hud: GestureHud) {
    Column(
        Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(MediaOverlayColors.scrim)
            .padding(horizontal = 14.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        VIconImage(
            if (hud.brightness) VIcon.LightMode else VIcon.VolumeUp,
            stringResource(if (hud.brightness) R.string.gesture_brightness else R.string.gesture_volume),
            Modifier.size(24.dp),
            tint = MediaOverlayColors.onScrim,
        )
        Box(
            Modifier
                .width(6.dp)
                .height(110.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MediaOverlayColors.onScrim.copy(alpha = 0.3f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier
                    .width(6.dp)
                    .fillMaxHeight(hud.value.coerceIn(0f, 1f))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Text(
            "${(hud.value * 100).roundToInt()}",
            style = MaterialTheme.typography.labelMedium,
            color = MediaOverlayColors.onScrim,
        )
    }
}
