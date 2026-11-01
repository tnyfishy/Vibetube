package vibe.tube.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vibe.tube.feature.watch.fullscreenEntryScale

class FullscreenScaleTest {
    @Test fun collapsedPlayerRetainsItsScaleAfterFullscreenSwipe() {
        assertEquals(0.42f, fullscreenEntryScale(0.42f, 0f, 200f), 0.0001f)
        assertEquals(0.7f, fullscreenEntryScale(0.7f, 0.5f, 200f), 0.0001f)
    }
    @Test fun expandedSwipeOnlyAddsTheFlowZoom() {
        assertTrue(fullscreenEntryScale(1f, 1f, 200f) in 1f..1.06f)
        assertEquals(1f, fullscreenEntryScale(1f, 1f, 0f), 0.0001f)
    }
}
