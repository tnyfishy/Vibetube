package vibe.tube.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
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
import vibe.tube.feature.watch.FullscreenChapterStage
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import vibe.tube.player.Chapter

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w891dp-h411dp-land-xxhdpi")
class FullscreenChapterUiTest {
    @get:Rule val rule = createComposeRule()
    @Test fun sheetStaysAtRightAndSelectingChapterKeepsItOpen() {
        var sought = -1L
        var dismissed = false
        rule.setContent {
            vibe.tube.ui.theme.VibetubeTheme(vibe.tube.data.settings.ThemeMode.Light, false) {
                val open = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
                FullscreenChapterStage(open.value, listOf(Chapter(0, "First chapter"), Chapter(60000, "Second chapter")), 0,
                    { sought = it }, onDismiss = { dismissed = true; open.value = false }) { modifier ->
                    Box(modifier) { Box(Modifier.fillMaxSize().testTag("stage-video")) }
                }
            }
        }
        assertTrue(rule.onNodeWithText("Chapters").getUnclippedBoundsInRoot().left > 300.dp)
        rule.onNodeWithText("Second chapter").performClick()
        assertEquals(60000L, sought)
        assertTrue(!dismissed)
        val openWidth = rule.onNodeWithTag("stage-video").getUnclippedBoundsInRoot().let { it.right - it.left }
        rule.onNodeWithContentDescription("Close").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Chapters").assertDoesNotExist()
        assertTrue(rule.onNodeWithTag("stage-video").getUnclippedBoundsInRoot().let { it.right - it.left } > openWidth)
    }
}
