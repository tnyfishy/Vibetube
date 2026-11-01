package vibe.tube.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import vibe.tube.ui.components.MorphingSymbol
import java.io.File

/** Renders FILL 0, 0.25, 0.5, 0.75, 0.9, 1 of every animated pair (build/screenshots/symbol_fill.png) and checks ink grows. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SymbolFillScreenshotTest {

    @get:Rule val rule = createComposeRule()

    private val pairs = listOf(
        VIcon.Home to VIcon.HomeFilled,
        VIcon.ThumbUp to VIcon.ThumbUpFilled,
        VIcon.Notifications to VIcon.NotificationsFilled,
        VIcon.Subscriptions to VIcon.SubscriptionsFilled,
        VIcon.VideoLibrary to VIcon.VideoLibraryFilled,
        VIcon.AccountCircle to VIcon.AccountCircleFilled,
    )
    private val steps = listOf(0f, 0.25f, 0.5f, 0.75f, 0.9f, 1f)

    @Test
    fun fillGrowsSmoothlyInPlace() {
        rule.setContent {
            Column(Modifier.background(Color.White).padding(8.dp)) {
                pairs.forEach { (outline, filled) ->
                    Row {
                        steps.forEach { f -> MorphingSymbol(outline, filled, { f }, null, Modifier.padding(4.dp).size(48.dp), tint = Color.Black) }
                    }
                }
            }
        }
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File("build/screenshots").apply { mkdirs() }.resolve("symbol_fill.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        // Ink per cell rises with FILL overall (the font's sweep may dip a few percent mid-way, never collapse).
        val density = bitmap.width / (8f * 2 + steps.size * 56f)
        pairs.indices.forEach { row ->
            val ink = steps.indices.map { col ->
                val x0 = ((8 + 4 + col * 56) * density).toInt()
                val y0 = ((8 + 4 + row * 56) * density).toInt()
                val n = (48 * density).toInt()
                var dark = 0
                for (y in y0 until y0 + n) for (x in x0 until x0 + n) if (android.graphics.Color.red(bitmap.getPixel(x, y)) < 128) dark++
                dark
            }
            assertTrue("row $row ink $ink", ink.zipWithNext().all { (a, b) -> b >= a * 0.95f } && ink.last() > ink.first())
        }
    }
}
