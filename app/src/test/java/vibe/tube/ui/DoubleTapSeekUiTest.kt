package vibe.tube.ui

import android.content.Context
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.media3.exoplayer.ExoPlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import vibe.tube.data.settings.ThemeMode
import vibe.tube.feature.watch.VideoSurface
import vibe.tube.player.PlayerPhase
import vibe.tube.player.PlayerUiState
import vibe.tube.support.video
import vibe.tube.ui.theme.VibetubeTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class DoubleTapSeekUiTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var player: ExoPlayer
    private val seeks = mutableListOf<Long>()

    @Before
    fun setUp() {
        player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
    }

    @After
    fun tearDown() = player.release()

    private fun show(applied: (Long) -> Long = { it }) {
        val state = PlayerUiState(
            visible = true, expanded = true, current = video("abc"), phase = PlayerPhase.Ready,
            isPlaying = true, positionMs = 61_000, durationMs = 212_000,
        )
        rule.setContent {
            VibetubeTheme(ThemeMode.Dark, false) {
                androidx.compose.runtime.CompositionLocalProvider(vibe.tube.ui.components.LocalWaveTravel provides false) {
                    VideoSurface(
                        player = player, state = state, showControls = false,
                        onCollapse = {}, onToggleFullscreen = {}, onOptions = {}, onPip = null,
                        onTogglePlay = {}, onNext = {}, onPrevious = {}, onSeek = {},
                        onSeekBy = { delta -> seeks += delta; applied(delta) },
                        onRetry = {}, onLogin = {},
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun right() = Offset(0.75f, 0.5f)

    private fun left() = Offset(0.25f, 0.5f)

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.tapAt(f: Offset, double: Boolean) = performTouchInput {
        val p = Offset(width * f.x, height * f.y)
        if (double) doubleClick(p) else click(p)
    }

    @Test
    fun doubleTapSeeksOnTheTappedSideAndShowsTheCard() {
        show()
        rule.onRoot().tapAt(right(), double = true)
        rule.waitForIdle()
        assertEquals(listOf(10_000L), seeks)
        rule.onNodeWithText("10").assertIsDisplayed()
    }

    @Test
    fun singleTapAfterDoubleTapDoesNotSeekAgain() {
        show()
        rule.onRoot().tapAt(right(), double = true)
        rule.waitForIdle()
        rule.onRoot().tapAt(right(), double = false)
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(listOf(10_000L), seeks)
    }

    @Test
    fun changingDirectionStartsANewCountOnTheOtherSide() {
        show()
        rule.onRoot().tapAt(right(), double = true)
        rule.waitForIdle()
        rule.onRoot().tapAt(right(), double = true)
        rule.waitForIdle()
        rule.onRoot().tapAt(left(), double = true)
        rule.waitForIdle()
        assertEquals(listOf(10_000L, 10_000L, -10_000L), seeks)
        rule.onNodeWithText("10").assertIsDisplayed()
    }

    @Test
    fun theCardClosesAfterTheChainGoesQuietAndATapThenDoesNotSeek() {
        show()
        rule.onRoot().tapAt(right(), double = true)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        rule.onNodeWithText("10").assertDoesNotExist()
        rule.onRoot().tapAt(right(), double = false)
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(listOf(10_000L), seeks)
    }

    @Test
    fun seekingAgainstTheBoundaryShowsNothingNewButKeepsTheCount() {
        var room = 15_000L
        show { delta ->
            val applied = if (delta > 0) minOf(delta, room) else delta
            room -= applied
            applied
        }
        rule.onRoot().tapAt(right(), double = true)
        rule.waitForIdle()
        rule.onRoot().tapAt(right(), double = true)
        rule.waitForIdle()
        rule.onRoot().tapAt(right(), double = true)
        rule.waitForIdle()
        rule.onNodeWithText("15").assertIsDisplayed()
    }

    @Test
    fun aDoubleTapThatCannotMoveShowsNoCard() {
        show { 0L }
        rule.onRoot().tapAt(left(), double = true)
        rule.waitForIdle()
        rule.onNodeWithText("0").assertDoesNotExist()
        rule.onNodeWithText("10").assertDoesNotExist()
    }
}
