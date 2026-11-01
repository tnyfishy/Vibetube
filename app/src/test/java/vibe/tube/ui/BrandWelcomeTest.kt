package vibe.tube.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import vibe.tube.feature.welcome.WelcomeScreen

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BrandWelcomeTest {
    @get:Rule val rule = createComposeRule()
    @Test fun controlsOnlyAppearAfterTheWordmarkFinishes() {
        var starts = 0
        rule.mainClock.autoAdvance = false
        rule.setContent { WelcomeScreen({ starts++ }, Modifier, true) }
        rule.onNodeWithContentDescription("Next").assertIsNotEnabled()
        rule.onNodeWithText("Skip").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(1600)
        rule.onNodeWithText("Skip").assertIsDisplayed()
        rule.onNodeWithContentDescription("Next").assertIsDisplayed().performClick()
        rule.mainClock.advanceTimeBy(1500)
        // Next moves through the intro; only the final page's button enters the app.
        rule.onNodeWithText("No ads, no clutter").assertIsDisplayed()
        assertEquals(0, starts)
    }
}
