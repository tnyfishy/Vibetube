package vibe.tube.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import vibe.tube.data.repo.FeedState
import vibe.tube.data.repo.FeedPhase
import vibe.tube.ui.components.FeedContent
import vibe.tube.ui.components.ShortSearchCard
import vibe.tube.support.video

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ShortsSearchGridTest {
    @get:Rule val rule = createComposeRule()

    @Test fun shortsUseTwoColumnsAndScrollVerticallyToLaterResults() {
        val items = (0..11).map { video("s$it").copy(title = "Short $it", isShort = true) }
        rule.setContent {
            FeedContent(FeedState(items = items, phase = FeedPhase.Idle), hideShorts = false,
                onRefresh = {}, onLoadMore = {}, onLogin = {}, fixedColumns = 2) { item, _ ->
                ShortSearchCard(item as vibe.tube.youtube.model.VideoItem)
            }
        }
        val first = rule.onNodeWithText("Short 0").fetchSemanticsNode().boundsInRoot
        val second = rule.onNodeWithText("Short 1").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 1f)
        assertTrue(second.left > first.right)
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(8)
        rule.onNodeWithText("Short 8").assertIsDisplayed()
    }
}
