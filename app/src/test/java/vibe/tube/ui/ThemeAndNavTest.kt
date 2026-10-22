package vibe.tube.ui

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.toRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vibe.tube.data.settings.ThemeMode
import vibe.tube.feature.nav.ChannelRoute
import vibe.tube.feature.nav.PlaylistRoute
import vibe.tube.feature.nav.SearchRoute
import vibe.tube.ui.theme.AmoledBlack
import vibe.tube.ui.theme.VibeDarkColorScheme
import vibe.tube.ui.theme.VibeLightColorScheme
import vibe.tube.ui.theme.resolveDark
import vibe.tube.ui.theme.toAmoled

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThemeAndNavTest {

    @Test
    fun amoledUsesTrueBlackBackgroundButKeepsElevatedSurfacesDistinct() {
        val s = VibeDarkColorScheme.toAmoled()
        assertEquals(Color(0xFF000000), s.background)
        assertEquals(AmoledBlack, s.surface)
        val ramp = listOf(s.surfaceContainerLowest, s.surfaceContainerLow, s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest, s.surfaceBright)
        assertEquals(ramp.size, ramp.toSet().size)
        assertTrue(ramp.zipWithNext().all { (a, b) -> luminance(a) <= luminance(b) })
        assertEquals(VibeDarkColorScheme.primary, s.primary)
    }

    @Test
    fun darkSchemeIsNotPureBlackAndLightIsLight() {
        assertNotEquals(AmoledBlack, VibeDarkColorScheme.surfaceContainerHigh)
        assertTrue(luminance(VibeLightColorScheme.background) > 0.8f)
        assertTrue(luminance(VibeDarkColorScheme.background) < 0.1f)
    }

    @Test
    fun darkResolutionFollowsMode() {
        assertTrue(resolveDark(ThemeMode.System, true))
        assertFalse(resolveDark(ThemeMode.System, false))
        assertFalse(resolveDark(ThemeMode.Light, true))
        assertTrue(resolveDark(ThemeMode.Dark, false))
        assertTrue(resolveDark(ThemeMode.Amoled, false))
    }

    @Test
    fun textRolesKeepContrastInEveryScheme() {
        listOf(VibeLightColorScheme, VibeDarkColorScheme, VibeDarkColorScheme.toAmoled()).forEach { s ->
            assertTrue(contrast(s.onSurface, s.surface) >= 7f)
            assertTrue(contrast(s.onSurfaceVariant, s.surface) >= 4.5f)
            assertTrue(contrast(s.onPrimary, s.primary) >= 4.5f)
            assertTrue(contrast(s.onPrimaryContainer, s.primaryContainer) >= 4.5f)
            assertTrue(contrast(s.onErrorContainer, s.errorContainer) >= 4.5f)
        }
    }

    @Test
    fun typedRoutesRoundTripThroughSavedState() {
        val playlist = SavedStateHandle(mapOf("id" to "PL123", "title" to "Mix"))
        assertEquals(PlaylistRoute("PL123", "Mix"), playlist.toRoute<PlaylistRoute>())
        assertEquals(ChannelRoute("UC9"), SavedStateHandle(mapOf("id" to "UC9")).toRoute<ChannelRoute>())
        assertEquals(SearchRoute("nhạc"), SavedStateHandle(mapOf("query" to "nhạc")).toRoute<SearchRoute>())
    }

    private fun luminance(c: Color): Float = 0.2126f * lin(c.red) + 0.7152f * lin(c.green) + 0.0722f * lin(c.blue)

    private fun lin(v: Float): Float = if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

    private fun contrast(a: Color, b: Color): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }
}
