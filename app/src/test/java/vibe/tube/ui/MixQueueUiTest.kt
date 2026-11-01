package vibe.tube.ui

import androidx.compose.foundation.layout.Box
import vibe.tube.feature.watch.mixBarReveal
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
import vibe.tube.feature.watch.MixPanelTransition
import vibe.tube.feature.watch.MixQueuePanel
import vibe.tube.feature.watch.QueueControls
import vibe.tube.feature.watch.QueueView
import vibe.tube.feature.watch.mixCardItem
import vibe.tube.player.RepeatMode
import vibe.tube.youtube.model.VideoItem
import java.io.File

/** The Mix card opens into its panel in place (no page), plays what is tapped, and folds back into the card. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class MixQueueUiTest {
    @get:Rule val rule = createComposeRule()

    private fun v(n: Int) = VideoItem("video$n", "Song number $n", "Artist $n", null, null, emptyList(), "3:0$n", 180 + n, null, null)

    private val played = mutableListOf<Int>()
    private var shuffles = 0
    private var repeat = RepeatMode.Off
    private val controls = object : QueueControls {
        override fun playQueueIndex(index: Int) { played += index }
        override fun toggleShuffle() { shuffles++ }
        override fun setRepeat(mode: RepeatMode) { repeat = mode }
    }
    private val view = mutableStateOf(QueueView("RDvideo1", "Mix – Artist 1", (1..12).map { v(it) }, 1, shuffled = false, repeat = RepeatMode.Off))
    private val expanded = mutableStateOf(true)
    private val tr = MixPanelTransition()

    private fun setContent() = rule.setContent {
        MaterialTheme {
            Surface {
                Box(Modifier.size(411.dp, 560.dp).onGloballyPositioned { tr.container = it }) {
                    LazyColumn(Modifier.fillMaxSize()) { mixCardItem(view.value, tr) }
                    MixQueuePanel(tr, view.value, expanded.value, controls)
                }
            }
        }
    }

    @Test
    fun theCardOpensThePanelInPlaceAndTapsPlayFromTheQueue() {
        setContent()
        rule.onNodeWithText("Mix – Artist 1").assertExists()
        rule.onNodeWithText("Song number 5", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Song number 3", substring = true).assertExists() // "Next: …" on the card
        rule.onNodeWithText("Mix – Artist 1").performClick()
        rule.waitForIdle()
        assertEquals(1f, tr.progress.value)
        rule.onNodeWithText("Song number 4", substring = true).performClick()
        assertEquals(listOf(3), played)
        rule.onNodeWithContentDescription("Shuffle").performClick()
        assertEquals(1, shuffles)
        rule.onNodeWithContentDescription("Repeat").performClick()
        assertEquals(RepeatMode.All, repeat)
        // Close folds it back into the card; playback is not touched.
        rule.onNodeWithContentDescription("Collapse").performClick()
        rule.waitForIdle()
        assertEquals(0f, tr.progress.value)
        assertEquals(listOf(3), played)
        rule.onAllNodesWithText("Song number 4", substring = true).fetchSemanticsNodes().let { assertEquals(0, it.size) }
    }

    @Test
    fun thePanelGoesDownWithThePlayerAndDoesNotComeBackByItself() {
        setContent()
        rule.onNodeWithText("Mix – Artist 1").performClick()
        rule.waitForIdle()
        expanded.value = false
        rule.waitForIdle()
        assertEquals(0f, tr.progress.value)
        expanded.value = true
        rule.waitForIdle()
        assertEquals(0f, tr.progress.value)
        assertTrue(!tr.open)
    }

    @Test
    fun containerTransformFrames() {
        setContent()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Mix – Artist 1").performClick()
        val frames = mutableListOf<android.graphics.Bitmap>()
        val seen = mutableListOf<Float>()
        repeat(6) {
            rule.mainClock.advanceTimeBy(48)
            seen += tr.progress.value
            frames += rule.onRoot().captureToImage().asAndroidBitmap()
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        frames += rule.onRoot().captureToImage().asAndroidBitmap()
        // The container grows continuously (no jump straight to the end).
        assertTrue("grows step by step: $seen", seen.zipWithNext().all { (a, b) -> b >= a } && seen.first() < 0.7f)
        val w = frames.first().width / 2
        val h = frames.first().height / 2
        val strip = android.graphics.Bitmap.createBitmap(w * frames.size, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(strip)
        frames.forEachIndexed { i, f ->
            canvas.drawBitmap(android.graphics.Bitmap.createScaledBitmap(f, w, h, true), (i * w).toFloat(), 0f, null)
        }
        File("build/screenshots").apply { mkdirs() }.resolve("mix_panel.png").outputStream().use { strip.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}

/** The Mix bar takes exactly its shown share of height and draws only that part (nothing spills over the bar below). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class MixBarRevealTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun takesAndDrawsOnlyTheShownPart() {
        val shown = mutableStateOf(0.5f)
        var full = 0
        rule.setContent {
            Box(Modifier.size(300.dp, 300.dp).background(androidx.compose.ui.graphics.Color.White)) {
                Box(
                    Modifier
                        .align(androidx.compose.ui.Alignment.BottomCenter)
                        .mixBarReveal({ shown.value }) { full = it }
                        .size(300.dp, 100.dp)
                        .background(androidx.compose.ui.graphics.Color.Black)
                        .testTag("bar"),
                )
            }
        }
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val black = (0 until bmp.height).count { y -> android.graphics.Color.red(bmp.getPixel(bmp.width / 2, y)) < 50 }
        assertEquals("only half of the 100dp bar is out", full / 2f, black.toFloat(), 2f)
        shown.value = 0f
        rule.waitForIdle()
        val none = rule.onRoot().captureToImage().asAndroidBitmap()
        assertEquals(0, (0 until none.height).count { y -> android.graphics.Color.red(none.getPixel(none.width / 2, y)) < 50 })
    }
}
