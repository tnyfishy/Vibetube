package vibe.tube.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import vibe.tube.data.settings.ThemeMode
import vibe.tube.feature.watch.ResumeChip
import vibe.tube.ui.theme.VibetubeTheme
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h400dp-xxhdpi")
class ResumeChipUiTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun chipFitsOnTheMiniCardInBothLanguages() {
        rule.setContent {
            VibetubeTheme(ThemeMode.Dark, dynamicColor = false) {
                Box(Modifier.padding(16.dp).width(218.dp).height(123.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF557799))) {
                    ResumeChip(Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 9.dp))
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.35f)))
                }
            }
        }
        rule.onNodeWithText("Continue watching").assertIsDisplayed()
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "resume_chip.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
