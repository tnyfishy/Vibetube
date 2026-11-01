package vibe.tube.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vibe.tube.ui.components.searchOpenMs

class SearchMotionTest {

    @Test fun closingTimingAndFasterOpening() {
        assertEquals(200, vibe.tube.ui.components.searchCloseMs(220))
        assertEquals(247, vibe.tube.ui.components.searchCloseMs(kotlin.math.round(360 / (1.15f * 1.15f)).toInt()))
        assertEquals(288, searchOpenMs(400))
    }

    @Test
    fun openingIsFifteenPercentFasterThanThePreviousBuild() {
        assertEquals(288, searchOpenMs(400))
        assertEquals(158, searchOpenMs(220))
        assertEquals(115, searchOpenMs(160))
        listOf(400, 220, 160).forEach { assertEquals(it / (1.15 * 1.15 * 1.05), searchOpenMs(it).toDouble(), 0.5) }
    }

    @Test
    fun scaledDurationsStayOrdered() {
        assertTrue(searchOpenMs(400) > searchOpenMs(220))
        assertTrue(searchOpenMs(220) > searchOpenMs(160))
    }
}
