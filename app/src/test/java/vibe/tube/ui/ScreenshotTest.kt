package vibe.tube.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import vibe.tube.data.settings.ThemeMode
import vibe.tube.feature.debug.ComponentGalleryScreen
import vibe.tube.ui.theme.VibetubeTheme
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h2200dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, mode: ThemeMode) {
        rule.setContent {
            VibetubeTheme(themeMode = mode, dynamicColor = false) {
                ComponentGalleryScreen(onBack = {}, onDiagnostics = {})
            }
        }
        rule.waitForIdle()
        val image = rule.onRoot().captureToImage()
        val out = File("build/screenshots").apply { mkdirs() }
        val bmp = image.asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun gallery_light() = shot("gallery_light", ThemeMode.Light)
    @Test fun gallery_dark() = shot("gallery_dark", ThemeMode.Dark)
    @Test fun gallery_amoled() = shot("gallery_amoled", ThemeMode.Amoled)
}
