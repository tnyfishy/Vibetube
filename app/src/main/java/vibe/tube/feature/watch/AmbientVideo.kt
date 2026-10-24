package vibe.tube.feature.watch

import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

/** The texture of the player view that is currently showing the main video, so the page under it can borrow its edge colours. */
internal object AmbientVideo {
    @Volatile
    var texture: TextureView? = null

    fun attach(view: PlayerView) {
        texture = view.videoSurfaceView as? TextureView
    }

    fun detach(view: PlayerView) {
        if (texture === view.videoSurfaceView) texture = null
    }
}

private const val COLS = 32
private const val ROWS = 18
private const val INTERVAL_MS = 110L

/**
 * Live colours of the video's bottom edge, left to right ([COLS] samples, eased so they glide instead of flickering).
 * They are read from the running frame a few times a second, so the strip under the video always follows what is playing.
 * Black until the first frame is available.
 */
@Composable
internal fun rememberVideoBottomColors(): State<List<Color>> {
    val colors = remember { mutableStateOf(List(COLS) { Color.Black }) }
    LaunchedEffect(Unit) {
        val bitmap = Bitmap.createBitmap(COLS, ROWS, Bitmap.Config.ARGB_8888)
        val row = IntArray(COLS)
        val smooth = FloatArray(COLS * 3)
        var seeded = false
        while (true) {
            val view = AmbientVideo.texture
            if (view != null && view.isAvailable && view.width > 0 && view.height > 0) {
                val ok = runCatching {
                    view.getBitmap(bitmap)
                    bitmap.getPixels(row, 0, COLS, 0, ROWS - 1, COLS, 1)
                }.isSuccess
                if (ok) {
                    for (i in 0 until COLS) {
                        val px = row[i]
                        val r = ((px shr 16) and 0xFF) / 255f
                        val g = ((px shr 8) and 0xFF) / 255f
                        val b = (px and 0xFF) / 255f
                        if (!seeded) {
                            smooth[i * 3] = r; smooth[i * 3 + 1] = g; smooth[i * 3 + 2] = b
                        } else {
                            smooth[i * 3] += (r - smooth[i * 3]) * 0.45f
                            smooth[i * 3 + 1] += (g - smooth[i * 3 + 1]) * 0.45f
                            smooth[i * 3 + 2] += (b - smooth[i * 3 + 2]) * 0.45f
                        }
                    }
                    seeded = true
                    colors.value = List(COLS) { i -> Color(smooth[i * 3], smooth[i * 3 + 1], smooth[i * 3 + 2]) }
                }
            }
            delay(INTERVAL_MS)
        }
    }
    return colors
}
