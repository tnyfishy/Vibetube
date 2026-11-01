package vibe.tube.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import vibe.tube.ui.components.ExpandingSection

/** A section opens and closes continuously, rows below follow it every frame, and a toggle mid-way reverses in place. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ExpandingSectionUiTest {

    @get:Rule val rule = createComposeRule()

    private var visible by mutableStateOf(false)

    private fun show() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Column {
                Box(Modifier.fillMaxWidth().height(40.dp).testTag("above"))
                ExpandingSection(visible = visible) { Box(Modifier.fillMaxWidth().height(120.dp).testTag("inner")) }
                Box(Modifier.fillMaxWidth().height(40.dp).testTag("below"))
            }
        }
    }

    private fun belowTop() = rule.onNodeWithTag("below").getBoundsInRoot().top.value

    @Test
    fun rowsBelowMoveContinuouslyAndAToggleMidwayReverses() {
        show()
        val closed = belowTop()
        assertEquals(40f, closed, 0.5f)
        visible = true
        val path = mutableListOf<Float>()
        repeat(8) { rule.mainClock.advanceTimeByFrame(); path += belowTop() }
        // Opening: the row below moves down steadily, never jumping straight to the open position.
        assertTrue("moved: $path", path.last() > closed + 10f)
        assertTrue("not snapped open: $path", path.last() < 40f + 120f - 1f)
        assertTrue("monotonic: $path", path.zipWithNext().all { (a, b) -> b >= a - 0.01f })
        val turnAt = path.last()
        visible = false
        repeat(2) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() }
        val afterTurn = belowTop()
        // Reversed from where it was, keeping its momentum for a moment: no jump to fully open or fully closed.
        assertTrue("continued from $turnAt, got $afterTurn", afterTurn > closed + 1f && afterTurn < 40f + 120f - 1f)
        repeat(10) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() }
        assertTrue("heading back: ${belowTop()} vs $turnAt", belowTop() < turnAt)
        repeat(150) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() }
        assertEquals(closed, belowTop(), 0.5f)
    }

    @Test
    fun settlesAtTheContentsFullHeight() {
        show()
        assertEquals(40f, belowTop(), 0.5f)
        visible = true
        repeat(150) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() }
        assertEquals(40f + 120f, belowTop(), 0.5f)
    }
}
