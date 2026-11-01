package vibe.tube.ui

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import vibe.tube.feature.welcome.StartRevealHost
import vibe.tube.ui.components.WordmarkRed
import java.io.File

/** The start button grows as one red container, keeps its colour while growing, then gives way to the app. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class StartRevealUiTest {

    @get:Rule val rule = createComposeRule()

    private val home = Color(0xFF2E7D32)
    private val welcome = Color(0xFFFFF3E0)

    private fun share(bmp: Bitmap, color: Color): Float {
        val c = color.toArgb()
        var n = 0
        var total = 0
        for (y in 0 until bmp.height step 6) for (x in 0 until bmp.width step 6) {
            total++
            if (bmp.getPixel(x, y) == c) n++
        }
        return n.toFloat() / total
    }

    @Test fun feedCannotReceiveTouchOrScrollUntilRevealFinishes() {
        var clicks = 0
        var finished = false
        lateinit var scroll: androidx.compose.foundation.ScrollState
        rule.mainClock.autoAdvance = false
        rule.setContent {
            StartRevealHost(true, { start, bounds, _ ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Button(start, bounds.padding(bottom = 48.dp)) { Text("Start") }
                }
            }, {}, { finished = true }) {
                scroll = rememberScrollState()
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    Box(Modifier.height(2000.dp).fillMaxSize().clickable { clicks++ })
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.onRoot().performTouchInput { click(Offset(center.x, 80f)); swipeUp() }
        rule.runOnIdle { org.junit.Assert.assertEquals(0, clicks); org.junit.Assert.assertEquals(0, scroll.value) }
        rule.onNodeWithText("Start").performClick()
        rule.mainClock.advanceTimeBy(32)
        rule.onRoot().performTouchInput { click(Offset(center.x, 80f)) }
        rule.runOnIdle { org.junit.Assert.assertEquals(0, clicks) }
        rule.mainClock.advanceTimeBy(3000)
        rule.runOnIdle { assertTrue(finished) }
        rule.onRoot().performTouchInput { click(Offset(center.x, 80f)); swipeUp() }
        rule.runOnIdle { assertTrue(clicks > 0); assertTrue(scroll.value > 0) }
    }

    @Test
    fun entryCrossfadesIntoTheAppWithoutABlankFrame() {
        var finished = false
        rule.mainClock.autoAdvance = false
        rule.setContent {
            StartRevealHost(
                showStart = true,
                startScreen = { onStart, buttonModifier, _ ->
                    Box(Modifier.fillMaxSize().background(welcome), contentAlignment = Alignment.BottomCenter) {
                        Button(
                            onClick = onStart,
                            modifier = buttonModifier.padding(bottom = 48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = WordmarkRed),
                        ) { Text("Start") }
                    }
                },
                onRevealStart = {},
                onFinished = { finished = true },
            ) { Box(Modifier.fillMaxSize().background(home)) }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("Start").performClick()
        val reds = mutableListOf<Float>()
        val homes = mutableListOf<Float>()
        val frames = mutableListOf<Bitmap>()
        var i = 0
        while (!finished && i < 240) {
            rule.mainClock.advanceTimeByFrame()
            val b = rule.onRoot().captureToImage().asAndroidBitmap()
            reds += share(b, WordmarkRed)
            homes += share(b, home)
            if (i % 5 == 0) frames += Bitmap.createScaledBitmap(b, b.width / 4, b.height / 4, true)
            i++
        }
        val strip = Bitmap.createBitmap(frames[0].width * frames.size, frames[0].height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(strip)
        frames.forEachIndexed { k, f -> canvas.drawBitmap(f, (k * f.width).toFloat(), 0f, null) }
        File("build/screenshots").apply { mkdirs() }.resolve("start_reveal.png").outputStream().use { strip.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("finished", finished)
        // The container grows from the button, then gives way early: the old requirement that pure red
        // cover most of the screen is intentionally removed by the new Welcome colour timeline.
        assertTrue("entry produced multiple frames", frames.size > 2)
        val middle = frames[frames.size / 2]
        assertTrue("welcome yields before completion", share(middle, welcome) < 0.9f)
        assertTrue("ends on the app", homes.last() > 0.97f)
        // No blank flash: no frame shows a sizeable plain white or black area.
        frames.forEach { f ->
            val blank = share(f, Color.White) + share(f, Color.Black)
            assertTrue("blank area in a frame: $blank", blank < 0.15f)
        }
    }
}
