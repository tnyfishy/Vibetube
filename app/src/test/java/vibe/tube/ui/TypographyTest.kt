package vibe.tube.ui

import androidx.compose.ui.text.font.FontVariation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vibe.tube.ui.theme.GoogleSansFlex

class TypographyTest {

    private val fontAxes = mapOf(
        "opsz" to (1f..144f),
        "wdth" to (25f..151f),
        "wght" to (1f..1000f),
        "ROND" to (0f..100f),
        "slnt" to (-10f..0f),
    )

    private fun axes(settings: FontVariation.Settings): Map<String, Float> =
        settings.settings.associate { s -> s.axisName to (s as FontVariation.Setting).toVariationValue(null) }

    @Test
    fun everyDeclaredAxisExistsInTheBundledFontAndIsInRange() {
        listOf(100, 400, 500, 600, 700, 900).forEach { weight ->
            listOf(11f, 14f, 16f, 22f, 57f, 500f).forEach { opsz ->
                val settings = GoogleSansFlex.settings(weight, opsz)
                val values = axes(settings)
                assertTrue("unknown axes ${values.keys}", values.keys.all { it in fontAxes })
                values.forEach { (axis, v) -> assertTrue("$axis=$v out of range", v in fontAxes.getValue(axis)) }
            }
        }
    }

    @Test
    fun roundnessIs100OnEveryWeightAndNoAxisIsDuplicated() {
        (1..1000 step 33).forEach { weight ->
            val settings = GoogleSansFlex.settings(weight, 16f)
            val names = settings.settings.map { it.axisName }
            assertEquals(names.size, names.toSet().size)
            assertEquals(100f, axes(settings).getValue("ROND"), 0f)
            assertEquals(weight.toFloat(), axes(settings).getValue("wght"), 0f)
        }
    }

    @Test
    fun opticalSizeIsClampedToFontRange() {
        assertEquals(144f, axes(GoogleSansFlex.settings(400, 999f)).getValue("opsz"), 0f)
        assertEquals(1f, axes(GoogleSansFlex.settings(400, 0f)).getValue("opsz"), 0f)
    }
}
