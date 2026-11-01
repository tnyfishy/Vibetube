package vibe.tube.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import vibe.tube.data.settings.AppFont
import vibe.tube.data.settings.AppSettings
import vibe.tube.feature.settings.FontSheet

/**
 * Switching to a font with fewer axes closes their sliders gradually: the controls below move up over several
 * frames (no one-frame collapse), and the closing slider is still drawn while its space closes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class FontSwitchMotionTest {
    @get:Rule val rule = createComposeRule()

    // The sheet sits on the bottom edge, so what moves is the space between the first slider and the button.
    private fun resetTop() = rule.onNodeWithText("Reset to default").getUnclippedBoundsInRoot().top.value -
        rule.onNodeWithText("Weight").getUnclippedBoundsInRoot().top.value

    private fun track(): List<Float> {
        val tops = mutableListOf(resetTop())
        repeat(30) {
            rule.mainClock.advanceTimeByFrame()
            tops += resetTop()
        }
        return tops
    }

    @Test
    fun closingAndOpeningAxesMoveTheRowsBelowContinuously() {
        var settings by mutableStateOf(AppSettings(font = AppFont.GoogleSansFlex))
        rule.setContent { MaterialTheme { FontSheet(settings, onChange = { settings = settings.it() }, onDismiss = {}) } }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false

        rule.onNodeWithText(AppFont.BeVietnamPro.label!!).performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("Width").assertExists() // still drawn while it closes
        val up = track()
        val firstStep = up[0] - up[1]
        val total = up.first() - up.last()
        assertTrue("rows below move up: $up", total > 40f)
        assertTrue("no one-frame collapse (step $firstStep of $total): $up", firstStep < total * 0.5f)
        assertTrue("monotonic: $up", up.zipWithNext().all { (a, b) -> b <= a + 0.5f })

        rule.onNodeWithText(AppFont.GoogleSansFlex.label!!).performClick()
        rule.mainClock.advanceTimeByFrame()
        val down = track()
        val back = down.last() - down.first()
        assertTrue("rows below move down again: $down", back > 40f)
        assertTrue("no one-frame jump: $down", (down[1] - down[0]) < back * 0.5f)
    }
}
