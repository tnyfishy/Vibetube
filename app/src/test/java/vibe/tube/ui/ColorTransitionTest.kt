package vibe.tube.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vibe.tube.ui.theme.lerpScheme
import vibe.tube.ui.theme.sameColorsAs

class ColorTransitionTest {

    private val light = lightColorScheme()
    private val dark = darkColorScheme()

    @Test
    fun endpointsAreTheSchemesThemselves() {
        assertTrue(lerpScheme(light, dark, 0f).sameColorsAs(light))
        assertTrue(lerpScheme(light, dark, 1f).sameColorsAs(dark))
    }

    @Test
    fun midwayEveryRoleIsBetweenBothEnds() {
        val mid = lerpScheme(light, dark, 0.5f)
        assertNotEquals(light.surface, mid.surface)
        assertNotEquals(dark.surface, mid.surface)
        // Fixed roles are part of the blend too, so nothing jumps at the end of the change.
        assertTrue(lerpScheme(light, light.copy(primaryFixed = Color.Red), 1f).primaryFixed == Color.Red)
    }

    @Test
    fun sameValuesInANewInstanceAreNotAChange() {
        assertTrue(lightColorScheme().sameColorsAs(lightColorScheme()))
        assertFalse(light.sameColorsAs(dark))
        assertFalse(light.sameColorsAs(light.copy(onTertiaryFixedVariant = Color.Black)))
        assertEquals(light.primary, lerpScheme(light, light, 0.3f).primary)
    }
}
