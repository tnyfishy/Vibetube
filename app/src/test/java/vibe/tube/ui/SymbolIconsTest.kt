package vibe.tube.ui

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vibe.tube.ui.components.SymbolMorph
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SymbolIconsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun vector(id: Int) = ImageVector.vectorResource(null, context.resources, id)

    private fun drawables(): File = listOf(File("src/main/res/drawable"), File("app/src/main/res/drawable")).first { it.exists() }

    /** Every outline / filled pair the app animates is the same glyph, so FILL morphs point by point. */
    @Test
    fun everyFilledPairIsMorphable() {
        val pairs = VIcon::class.java.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType && it.name.endsWith("Filled") && it.name != "ShortsFilled" }
            .mapNotNull { f ->
                val outline = runCatching { VIcon::class.java.getDeclaredField(f.name.removeSuffix("Filled")) }.getOrNull() ?: return@mapNotNull null
                f.isAccessible = true; outline.isAccessible = true
                f.name to (outline.getInt(VIcon) to f.getInt(VIcon))
            }
        assertTrue("pairs found: ${pairs.map { it.first }}", pairs.size >= 8)
        pairs.forEach { (name, ids) ->
            val target = VIcon.fillMorphTarget(ids.second)
            assertTrue("$name has no morph target", target != null)
            val morph = SymbolMorph(vector(ids.first), vector(target!!))
            assertTrue("$name is not morphable", morph.isMorphable)
            val mid = morph.path(0.5f, androidx.compose.ui.graphics.Path())
            assertTrue("$name midway path is empty", !mid.isEmpty)
        }
    }

    /** All generated icons carry the required axes; brand and launcher assets are left as they were. */
    @Test
    fun generatedIconsUseTheRequiredAxes() {
        val generated = drawables().listFiles()!!.filter { it.name.startsWith("ic_") && it.readText().contains("Material Symbols Rounded") && !it.readText().contains("exact user-specified") }
        assertTrue(generated.size >= 90)
        generated.forEach { f ->
            assertTrue(f.name, f.readText().contains("wght 400 GRAD 100 opsz 28"))
            val filled = f.readText().contains("FILL 1")
            val expectFilled = f.name.endsWith("_filled.xml") || f.name.endsWith("_filled_morph.xml") || f.name == "ic_pause.xml"
            assertEquals("${f.name} FILL", expectFilled, filled)
        }
        listOf("ic_brand_mark.xml", "ic_launcher_foreground.xml", "ic_artist_badge.xml").forEach {
            assertTrue("$it must stay hand-made", !File(drawables(), it).readText().contains("Material Symbols Rounded"))
        }
    }
}
