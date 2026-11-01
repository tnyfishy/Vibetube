package vibe.tube.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import vibe.tube.ui.theme.AppPalette
import vibe.tube.ui.theme.paletteScheme
import vibe.tube.ui.theme.toAmoled

class PaletteQualityTest {

    private data class Case(val name: String, val scheme: ColorScheme)

    private val cases: List<Case> = AppPalette.entries.flatMap { palette ->
        listOf(
            Case("${palette.name}/light", paletteScheme(palette, dark = false)),
            Case("${palette.name}/dark", paletteScheme(palette, dark = true)),
            Case("${palette.name}/amoled", paletteScheme(palette, dark = true).toAmoled()),
        )
    }

    private fun luminance(c: Color): Float = 0.2126f * lin(c.red) + 0.7152f * lin(c.green) + 0.0722f * lin(c.blue)

    private fun lin(v: Float): Float = if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

    private fun contrast(a: Color, b: Color): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    private val problems = mutableListOf<String>()

    private fun check(name: String, ratio: Float, min: Float, what: String) {
        if (ratio < min) problems += "$name: $what is $ratio, needs $min"
    }

    private fun report() = assertTrue(problems.joinToString("\n"), problems.isEmpty())

    @Test
    fun textStaysReadableOnEverySurfaceItIsUsedOn() {
        cases.forEach { (n, s) ->
            check(n, contrast(s.onSurface, s.surface), 7f, "onSurface/surface")
            check(n, contrast(s.onSurface, s.surfaceContainerHighest), 7f, "onSurface/containerHighest")
            check(n, contrast(s.onSurfaceVariant, s.surface), 4.5f, "onSurfaceVariant/surface")
            check(n, contrast(s.onSurfaceVariant, s.surfaceContainerHighest), 4.5f, "onSurfaceVariant/containerHighest")
            check(n, contrast(s.onPrimary, s.primary), 4.2f, "onPrimary/primary")
            check(n, contrast(s.onSecondary, s.secondary), 4.5f, "onSecondary/secondary")
            check(n, contrast(s.onPrimaryContainer, s.primaryContainer), 4.5f, "onPrimaryContainer/primaryContainer")
            check(n, contrast(s.onSecondaryContainer, s.secondaryContainer), 4.5f, "onSecondaryContainer/secondaryContainer")
            check(n, contrast(s.onTertiaryContainer, s.tertiaryContainer), 4.5f, "onTertiaryContainer/tertiaryContainer")
            check(n, contrast(s.onErrorContainer, s.errorContainer), 4.5f, "onErrorContainer/errorContainer")
            check(n, contrast(s.onError, s.error), 4.5f, "onError/error")
        }
        report()
    }

    @Test
    fun outlinesAndAccentsAreVisibleAgainstTheSurface() {
        cases.forEach { (n, s) ->
            check(n, contrast(s.outline, s.surface), 3f, "outline/surface")
            check(n, contrast(s.primary, s.surface), 3f, "primary/surface")
            check(n, contrast(s.error, s.surface), 3f, "error/surface")
        }
        report()
    }

    private fun apart(a: Color, b: Color): Float {
        val dr = a.red - b.red
        val dg = a.green - b.green
        val db = a.blue - b.blue
        return kotlin.math.sqrt(dr * dr + dg * dg + db * db)
    }

    private fun distinct(name: String, a: Color, b: Color, what: String) {
        if (contrast(a, b) < 1.08f && apart(a, b) < 0.08f) problems += "$name: $what are indistinguishable"
    }

    @Test
    fun selectedStatesStandOutFromTheNeutralSurfaces() {
        cases.forEach { (n, s) ->
            distinct(n, s.secondaryContainer, s.surface, "secondaryContainer/surface")
            distinct(n, s.secondaryContainer, s.surfaceContainer, "secondaryContainer/surfaceContainer")
            distinct(n, s.secondaryContainer, s.surfaceContainerHigh, "secondaryContainer/surfaceContainerHigh")
            distinct(n, s.secondaryContainer, s.surfaceContainerHighest, "secondaryContainer/surfaceContainerHighest")
            distinct(n, s.primaryContainer, s.surfaceContainerHighest, "primaryContainer/surfaceContainerHighest")
        }
        report()
    }

    @Test
    fun primaryTextIsClearlyStrongerThanSecondaryText() {
        cases.forEach { (n, s) ->
            val primary = contrast(s.onSurface, s.surface)
            val secondary = contrast(s.onSurfaceVariant, s.surface)
            if (primary < secondary * 1.15f) problems += "$n: no text hierarchy ($primary vs $secondary)"
        }
        report()
    }

    @Test
    fun surfaceLadderOnlyGetsStrongerAndNeverMerges() {
        cases.forEach { (n, s) ->
            val ladder = listOf(s.surfaceContainerLowest, s.surfaceContainerLow, s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest)
            val dark = luminance(s.surface) < 0.5f
            val ordered = if (dark) ladder else ladder.reversed()
            ordered.zipWithNext().forEach { (a, b) ->
                assertTrue("$n: ladder steps merge", luminance(a) != luminance(b))
                assertTrue("$n: ladder out of order", luminance(a) <= luminance(b) + 0.0001f)
            }
        }
    }
}
