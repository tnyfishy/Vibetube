package vibe.tube.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import vibe.tube.ui.components.bouncyClickable

/**
 * A quick tap on a card still shows the whole press: it dips to its pressed size and then springs back, as one motion
 * (no timer holds it), and the click is delivered at once, so the player can grow out of the dipped thumbnail.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PressDipTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun aQuickTapDipsFullyThenSettles() {
        var clicks = 0
        rule.setContent {
            MaterialTheme {
                Box(Modifier.size(200.dp).testTag("card").bouncyClickable(RoundedCornerShape(12.dp)) { clicks++ })
            }
        }
        val full = rule.onNodeWithTag("card").getBoundsInRoot().let { it.right - it.left }.value
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("card").performClick()
        val widths = (0 until 40).map {
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithTag("card").getBoundsInRoot().let { b -> b.right - b.left }.value
        }
        assertEquals("delivered at once", 1, clicks)
        val deepest = widths.min()
        assertTrue("dips to ~0.97: $deepest of $full", deepest <= full * 0.972f)
        assertTrue("settles back: ${widths.last()}", kotlin.math.abs(widths.last() - full) < 0.5f)
    }
}
