package vibe.tube.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import vibe.tube.data.settings.ThemeMode
import vibe.tube.ui.components.BrandGap
import vibe.tube.ui.components.BrandMarkSize
import vibe.tube.ui.components.SearchHeaderRow
import vibe.tube.ui.components.WordmarkRed
import vibe.tube.ui.theme.Spacing
import vibe.tube.ui.theme.VibetubeTheme
import java.io.File

/** Tapping the wordmark tucks it behind the logo (nothing of it shows to the right of the logo) and brings it back. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class HeaderWordmarkUiTest {

    @get:Rule val rule = createComposeRule()

    private fun redRightOfLogo(bmp: Bitmap, fromX: Int): Int {
        val red = WordmarkRed.toArgb()
        var n = 0
        for (y in 0 until bmp.height) for (x in fromX until bmp.width) if (bmp.getPixel(x, y) == red) n++
        return n
    }

    @Test
    fun wordmarkStaysHiddenUntilTheLogoIsTappedAgain() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            VibetubeTheme(ThemeMode.Light, dynamicColor = false) {
                SearchHeaderRow(
                    title = "Vibetube",
                    onSearch = {},
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                    wordmark = true,
                )
            }
        }
        rule.mainClock.advanceTimeByFrame()
        val density = rule.density.density * vibe.tube.ui.theme.UI_SCALE
        val logoRight = ((Spacing.lg.value + BrandMarkSize.value + 1f) * density).toInt()
        fun shot() = rule.onRoot().captureToImage().asAndroidBitmap()
        val before = redRightOfLogo(shot(), logoRight)
        assertTrue("wordmark visible at rest: $before", before > 500)

        rule.onNodeWithText("Vibetube").performClick()
        val path = mutableListOf<Int>()
        val frames = mutableListOf<Bitmap>()
        repeat(60) {
            rule.mainClock.advanceTimeByFrame()
            val b = shot()
            path += redRightOfLogo(b, logoRight)
            if (it % 6 == 0) frames += b.copy(Bitmap.Config.ARGB_8888, false)
        }
        // Somewhere along the way the word is entirely behind the logo.
        assertTrue("hidden at some point: $path", path.min() < before / 50)
        repeat(240) { rule.mainClock.advanceTimeByFrame() }
        assertTrue("stays tucked after waiting", redRightOfLogo(shot(), logoRight) < before / 50)
        rule.onNodeWithContentDescription("Vibetube").performClick()
        repeat(240) { rule.mainClock.advanceTimeByFrame() }
        val after = redRightOfLogo(shot(), logoRight)
        assertTrue("back where it was: $after vs $before", kotlin.math.abs(after - before) < before / 20)

        val strip = Bitmap.createBitmap(frames[0].width, frames[0].height * frames.size, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(strip)
        frames.forEachIndexed { i, f -> c.drawBitmap(f, 0f, (i * f.height).toFloat(), null) }
        File("build/screenshots").apply { mkdirs() }.resolve("wordmark_tuck.png").outputStream().use { strip.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
