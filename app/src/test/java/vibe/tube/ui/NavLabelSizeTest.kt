package vibe.tube.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
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
import vibe.tube.data.settings.ThemeMode
import vibe.tube.ui.components.NavBarEntry
import vibe.tube.ui.components.NavBarRow
import vibe.tube.ui.theme.FontStyleSettings
import vibe.tube.ui.theme.VibetubeTheme

/** Vietnamese labels on a 360dp phone and a 411dp phone: one line each, whole, at one size larger than the old 12sp. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavLabelSizeTest {
    @get:Rule val rule = createComposeRule()

    @Test
    @Config(sdk = [35], qualifiers = "vi-w411dp-h891dp-xxhdpi")
    fun changingFontUpdatesTheExistingFooterLabels() {
        val font = androidx.compose.runtime.mutableStateOf(FontStyleSettings(AppFont.System))
        rule.setContent {
            VibetubeTheme(ThemeMode.Light, false, fontStyle = font.value) {
                NavBarRow(listOf(NavBarEntry("Home", vibe.tube.ui.VIcon.Home, vibe.tube.ui.VIcon.HomeFilled)), 0, {}, Modifier.fillMaxWidth().height(60.dp))
            }
        }
        fun family(): androidx.compose.ui.text.font.FontFamily? {
            val output = mutableListOf<TextLayoutResult>()
            rule.onNode(hasText("Home"), useUnmergedTree = true).fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult].action?.invoke(output)
            return output.single().layoutInput.style.fontFamily
        }
        val old = family()
        rule.runOnIdle { font.value = FontStyleSettings(AppFont.GoogleSansFlex) }
        rule.waitForIdle()
        assertTrue("Footer must adopt the newly selected family", old != family())
    }

    private val labels = listOf("Trang chủ", "Shorts", "Kênh đăng ký", "Thư viện", "Tài khoản")

    private fun check(): Float {
        rule.setContent {
            VibetubeTheme(ThemeMode.Light, dynamicColor = false, fontStyle = FontStyleSettings(AppFont.GoogleSansFlex)) {
                NavBarRow(labels.map { NavBarEntry(it, vibe.tube.ui.VIcon.Home, vibe.tube.ui.VIcon.HomeFilled) }, 2, {}, Modifier.fillMaxWidth().height(60.dp))
            }
        }
        val sizes = labels.map { label ->
            val node = rule.onNode(hasText(label), useUnmergedTree = true).fetchSemanticsNode()
            val out = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
            assertFalse("$label is cut", out.first().hasVisualOverflow)
            assertEquals(1, out.first().lineCount)
            out.first().layoutInput.style.fontSize.value
        }
        assertEquals("one size for the whole bar", 1, sizes.toSet().size)
        return sizes.first()
    }

    @Test
    @Config(sdk = [35], qualifiers = "vi-w411dp-h891dp-xxhdpi")
    fun phone411() {
        val size = check()
        assertTrue("larger than before: $size", size > 12f)
    }

    @Test
    @Config(sdk = [35], qualifiers = "vi-w360dp-h780dp-xxhdpi")
    fun phone360() {
        val size = check()
        assertTrue("readable: $size", size >= 10f)
    }
}
