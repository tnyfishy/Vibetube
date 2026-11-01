package vibe.tube.ui

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import vibe.tube.feature.watch.ChapterContent
import vibe.tube.player.Chapter

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ChaptersUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun selectingChaptersKeepsListAvailableForAnotherSelection() {
        val seeks = mutableListOf<Long>()
        rule.setContent {
            var position by remember { mutableLongStateOf(0L) }
            ChapterContent(listOf(Chapter(0, "Intro"), Chapter(60_000, "Part two"), Chapter(120_000, "Finale")),
                position, { position = it; seeks += it }, Modifier.height(400.dp))
        }
        rule.onNodeWithText("Part two").performClick()
        rule.onNodeWithText("Finale").assertExists().performClick()
        rule.onNodeWithText("Intro").assertExists()
        assertEquals(listOf(60_000L, 120_000L), seeks)
    }
}
