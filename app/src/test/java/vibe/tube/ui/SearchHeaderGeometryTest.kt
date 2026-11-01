package vibe.tube.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import vibe.tube.data.settings.ThemeMode
import vibe.tube.feature.search.SearchCapsule
import vibe.tube.ui.components.SearchHeaderRow
import vibe.tube.ui.theme.Spacing
import vibe.tube.ui.theme.VibeLightColorScheme
import vibe.tube.ui.theme.VibetubeTheme
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SearchHeaderGeometryTest {
    @get:Rule val rule = createComposeRule()

    private val capsule = VibeLightColorScheme.surfaceContainerHigh.toArgb()

    private fun bounds(bmp: Bitmap): IntArray {
        var minX = Int.MAX_VALUE
        var maxX = -1
        var minY = Int.MAX_VALUE
        var maxY = -1
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            if (bmp.getPixel(x, y) == capsule) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        return intArrayOf(minX, minY, maxX, maxY)
    }

    private fun shot(name: String, bmp: Bitmap) {
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun band(bmp: Bitmap, from: Int, to: Int): IntArray {
        val part = Bitmap.createBitmap(bmp, 0, from, bmp.width, to - from)
        return bounds(part)
    }

    @Test
    fun headerCapsuleEndsExactlyWhereTheSearchScreenFieldIs() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            VibetubeTheme(ThemeMode.Light, dynamicColor = false) {
                androidx.compose.foundation.layout.Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                    Box(Modifier.fillMaxWidth().height(120.dp)) {
                        SearchHeaderRow(
                            title = "Vibetube",
                            onSearch = {},
                            modifier = Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.xs, bottom = Spacing.sm),
                            wordmark = true,
                        ) {
                            Box(Modifier.size(48.dp).background(Color.Red))
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(120.dp)) {
                        SearchCapsule(
                            query = "",
                            onQueryChange = {},
                            onSearch = {},
                            onBack = {},
                            focus = FocusRequester(),
                            modifier = Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.xs, bottom = Spacing.xs),
                        )
                    }
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.onAllNodesWithContentDescription("Search").onFirst().performClick()
        val frames = mutableListOf<Bitmap>()
        repeat(12) {
            rule.mainClock.advanceTimeBy(40)
            frames += rule.onRoot().captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        }
        rule.mainClock.advanceTimeBy(300)
        val last = rule.onRoot().captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        val bandH = last.height / 2
        val strip = 150
        val sheet = Bitmap.createBitmap(last.width, strip * (frames.size + 1), Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(sheet)
        (frames + last).forEachIndexed { i, f ->
            canvas.drawBitmap(Bitmap.createBitmap(f, 0, 0, f.width, strip), 0f, strip.toFloat() * i, null)
        }
        shot("search_header_frames", sheet)
        shot("search_final", last)
        val header = band(last, 0, bandH)
        val search = band(last, bandH, last.height)
        assertEquals("left edge", search[0], header[0])
        assertEquals("right edge", search[2], header[2])
        assertEquals("top edge", search[1], header[1])
        assertEquals("bottom edge", search[3], header[3])
        // The row's left edge in px moves with UI_SCALE (≈48 px at 1.05, ≈45 px at 0.945).
        assertTrue("capsule starts at the row's left edge: ${header[0]}", header[0] in 42..50)
    }
}
