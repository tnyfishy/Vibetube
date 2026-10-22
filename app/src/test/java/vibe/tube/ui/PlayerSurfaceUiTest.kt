package vibe.tube.ui

import android.content.Context
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import vibe.tube.core.PlaybackFailure
import vibe.tube.data.settings.ThemeMode
import vibe.tube.feature.watch.VideoSurface
import vibe.tube.feature.watch.formatTime
import vibe.tube.player.PlayerError
import vibe.tube.player.PlayerPhase
import vibe.tube.player.PlayerUiState
import vibe.tube.support.video
import vibe.tube.ui.theme.VibetubeTheme
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class PlayerSurfaceUiTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var player: ExoPlayer

    @Before
    fun setUp() {
        player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
    }

    @After
    fun tearDown() = player.release()

    private fun show(state: PlayerUiState, events: MutableList<String> = mutableListOf(), mode: ThemeMode = ThemeMode.Dark) {
        rule.setContent {
            VibetubeTheme(mode, false) {
              androidx.compose.runtime.CompositionLocalProvider(vibe.tube.ui.components.LocalWaveTravel provides false) {
                VideoSurface(
                    player = player, state = state, showControls = true,
                    onCollapse = { events += "collapse" }, onToggleFullscreen = { events += "fullscreen" },
                    onOptions = { events += "options" }, onPip = { events += "pip" },
                    onTogglePlay = { events += "toggle" }, onNext = { events += "next" }, onPrevious = { events += "previous" },
                    onSeek = { events += "seek:$it" }, onSeekBy = { events += "seekBy:$it" },
                    onRetry = { events += "retry" }, onLogin = { events += "login" },
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
              }
            }
        }
        rule.waitForIdle()
    }

    private fun base() = PlayerUiState(
        visible = true, expanded = true, current = video("abc"), phase = PlayerPhase.Ready,
        isPlaying = true, positionMs = 61_000, durationMs = 212_000,
    )

    @Test
    fun controlsExposeAccessibleActionsAndDispatchThem() {
        val events = mutableListOf<String>()
        show(base(), events)
        rule.onNodeWithContentDescription("Pause").assertIsDisplayed().performClick()
        rule.onNodeWithContentDescription("Next").performClick()
        rule.onNodeWithContentDescription("Previous").performClick()
        rule.onNodeWithContentDescription("Minimize player").performClick()
        rule.onNodeWithContentDescription("Playback options").performClick()
        rule.onNodeWithContentDescription("Picture-in-picture").performClick()
        rule.onNodeWithContentDescription("Seek bar").assertIsDisplayed()
        rule.onNodeWithText("1:01 / 3:32").assertIsDisplayed()
        assertEquals(listOf("toggle", "next", "previous", "collapse", "options", "pip"), events)
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "player_controls.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun errorOverlaysStateTheRealReasonAndOfferTheRightAction() {
        val events = mutableListOf<String>()
        show(base().copy(phase = PlayerPhase.Error, isPlaying = false, error = PlayerError(PlaybackFailure.BotCheck, null)), events)
        rule.onNodeWithText("Vibetube asked for verification").assertIsDisplayed()
        rule.onNodeWithText("Retry").performClick()
        assertEquals(listOf("retry"), events)
    }

    @Test
    fun loginRequiredErrorOffersSignIn() {
        val events = mutableListOf<String>()
        show(base().copy(phase = PlayerPhase.Error, error = PlayerError(PlaybackFailure.LoginRequired, null)), events)
        rule.onNodeWithText("Sign in").performClick()
        assertEquals(listOf("login"), events)
    }

    @Test
    fun liveStreamsShowLiveLabelInsteadOfSeekBar() {
        show(base().copy(isLive = true, durationMs = 0))
        rule.onNodeWithText("LIVE").assertIsDisplayed()
    }

    @Test
    fun timeFormatting() {
        assertEquals("0:00", formatTime(0))
        assertEquals("1:05", formatTime(65_000))
        assertEquals("1:01:01", formatTime(3_661_000))
        assertEquals("0:00", formatTime(-5))
    }
}
