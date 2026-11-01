package vibe.tube.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import vibe.tube.data.settings.AppFont
import vibe.tube.feature.settings.FeedPreview
import vibe.tube.ui.theme.FontStyleSettings
import vibe.tube.ui.theme.UI_SCALE
import vibe.tube.ui.theme.UiScaling
import vibe.tube.ui.theme.VibetubeTheme
import vibe.tube.data.settings.ThemeMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class DisplaySizeTest {
    @get:Rule val rule = createComposeRule()

    // A 411dp-wide phone at xxhdpi.
    private val base = 2.625f
    private val width = 411 * base

    @Test
    fun displaySizeScalesComponentsAndTextTogether() {
        val s = UiScaling.effective(base, 1f, width, 110, 100)
        assertEquals(base * UI_SCALE * 1.1f, s.density, 0.001f)
        assertEquals(1f, s.fontScale, 0.0001f)
        assertFalse(s.displayLimited)
        assertEquals(110, s.displayPercent)
    }

    @Test
    fun displaySizeIsHeldBackToKeepTheNarrowestLayoutAndSaysSo() {
        // 125% would leave 411 / (0.945 × 1.25) ≈ 348dp; on a 360dp phone it would be 305dp, under 320.
        val narrow = 360 * base
        val s = UiScaling.effective(base, 1f, narrow, 125, 100)
        assertTrue(s.displayLimited)
        assertEquals(320f, narrow / s.density, 0.01f)
        assertTrue(s.displayPercent in 101..124)
        // The text setting is untouched by that.
        assertEquals(1f, s.fontScale, 0.0001f)
    }

    @Test
    fun textSizeMultipliesTheSystemFontScaleAndNeverOverridesALargerOne() {
        assertEquals(1.3f, UiScaling.effective(base, 1f, width, 100, 130).fontScale, 0.0001f)
        assertEquals(1.15f * 0.85f, UiScaling.effective(base, 1.15f, width, 100, 85).fontScale, 0.0001f)
        val big = UiScaling.effective(base, 1.3f, width, 100, 130)
        assertTrue(big.textLimited)
        assertEquals(UiScaling.MAX_TEXT_RATIO, big.fontScale, 0.0001f)
        // An accessibility font size above the cap is kept as it is.
        val access = UiScaling.effective(base, 2f, width, 100, 130)
        assertEquals(2f, access.fontScale, 0.0001f)
        assertEquals(2f, UiScaling.effective(base, 2f, width, 100, 100).fontScale, 0.0001f)
    }

    @Test
    fun previewAtPhoneWidthFitsHeaderCardsAndBar() {
        // Each size on a full 411dp-wide frame, like the edge-to-edge preview in the sheet.
        val sizes = listOf(85 to 100, 100 to 100, 125 to 130, 125 to 85)
        val current = androidx.compose.runtime.mutableStateOf(sizes.first())
        rule.setContent {
            VibetubeTheme(ThemeMode.Light, dynamicColor = false, fontStyle = FontStyleSettings(AppFont.GoogleSansFlex)) {
                // The frame is measured at the device density (like the sheet), the content at the chosen size.
                CompositionLocalProvider(LocalDensity provides Density(base, 1f)) {
                    val (d, t) = current.value
                    FeedPreview(UiScaling.effective(base, 1f, width, d, t), vibe.tube.data.settings.AppSettings(navHideLabels = false), Modifier.size(411.dp, 560.dp))
                }
            }
        }
        val shots = sizes.map { size ->
            current.value = size
            rule.waitForIdle()
            rule.onRoot().captureToImage().asAndroidBitmap()
        }
        // Every navigation label is drawn whole, on one line.
        sizes.forEach { (d, t) ->
            current.value = d to t
            rule.waitForIdle()
            listOf("Home", "Shorts", "Subscriptions", "Library", "Account").forEach { label ->
                val node = rule.onNode(androidx.compose.ui.test.hasText(label), useUnmergedTree = true).fetchSemanticsNode()
                val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
                assertFalse("$label cut at $d/$t", layout.first().hasVisualOverflow)
                assertEquals(1, layout.first().lineCount)
            }
        }
        val w = shots.first().width / 2
        val h = shots.first().height / 2
        val strip = android.graphics.Bitmap.createBitmap(w * shots.size, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(strip)
        shots.forEachIndexed { i, b -> canvas.drawBitmap(android.graphics.Bitmap.createScaledBitmap(b, w, h, true), (i * w).toFloat(), 0f, null) }
        File("build/screenshots").apply { mkdirs() }.resolve("display_size_preview.png").outputStream().use { strip.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
