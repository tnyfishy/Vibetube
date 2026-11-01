package vibe.tube.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import vibe.tube.feature.search.SearchBackGlyph
import java.io.File

/** Magnifier → back arrow: every step draws a glyph (no empty frame), saved as build/screenshots/search_glyph.png. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SearchGlyphScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun everyStepIsAGlyph() {
        val steps = (0..8).map { it / 8f }
        rule.setContent {
            Row(Modifier.background(Color.White).padding(8.dp)) {
                steps.forEach { p -> SearchBackGlyph({ p }, Color.Black, "", Modifier.padding(3.dp).size(36.dp)) }
            }
        }
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File("build/screenshots").apply { mkdirs() }.resolve("search_glyph.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val cell = bmp.width / steps.size
        steps.indices.forEach { i ->
            var ink = 0
            for (y in 0 until bmp.height) for (x in i * cell until (i + 1) * cell) if (android.graphics.Color.red(bmp.getPixel(x, y)) < 100) ink++
            assertTrue("step $i has ink", ink > 200)
        }
    }
}
