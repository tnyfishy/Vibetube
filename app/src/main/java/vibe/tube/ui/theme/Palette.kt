package vibe.tube.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Colour palettes users can pick. [seedHue] (degrees) and [seedChroma] drive a tonal scheme; Vibe keeps the hand-tuned mint scheme. */
enum class AppPalette(val seedHue: Float, val seedChroma: Float) {
    Vibe(160f, 36f),
    Indigo(272f, 48f),
    Coral(32f, 60f),
    Ocean(232f, 40f),
    Rose(355f, 52f),
    Violet(305f, 50f),
}

/**
 * Builds a Material 3 colour scheme following the role/tone table of the M3 colour system: primary / secondary /
 * tertiary / neutral / neutral-variant tonal palettes share the seed's hue family (tertiary is rotated by 60 degrees),
 * and each role maps to a fixed tone (light: primary 40, container 90, surface 98; dark: primary 80, container 30,
 * surface 6). Tones are CIE L*, converted to sRGB with chroma reduced until the colour fits the gamut.
 */
fun paletteScheme(palette: AppPalette, dark: Boolean): ColorScheme {
    if (palette == AppPalette.Vibe) return if (dark) VibeDarkColorScheme else VibeLightColorScheme
    val h = palette.seedHue
    val primary = Tonal(h, palette.seedChroma)
    val secondary = Tonal(h, palette.seedChroma / 3f)
    val tertiary = Tonal(h + 60f, palette.seedChroma / 2f)
    val neutral = Tonal(h, 4f)
    val neutralVariant = Tonal(h, 8f)
    val error = Tonal(25f, 84f)
    return if (!dark) {
        lightColorScheme(
            primary = primary(40), onPrimary = primary(100), primaryContainer = primary(90), onPrimaryContainer = primary(10),
            inversePrimary = primary(80),
            secondary = secondary(40), onSecondary = secondary(100), secondaryContainer = secondary(90), onSecondaryContainer = secondary(10),
            tertiary = tertiary(40), onTertiary = tertiary(100), tertiaryContainer = tertiary(90), onTertiaryContainer = tertiary(10),
            error = error(40), onError = error(100), errorContainer = error(90), onErrorContainer = error(10),
            background = neutral(98), onBackground = neutral(10),
            surface = neutral(98), onSurface = neutral(10), surfaceVariant = neutralVariant(90), onSurfaceVariant = neutralVariant(30),
            surfaceTint = primary(40),
            inverseSurface = neutral(20), inverseOnSurface = neutral(95),
            outline = neutralVariant(50), outlineVariant = neutralVariant(80), scrim = Color.Black,
            surfaceBright = neutral(98), surfaceDim = neutral(87),
            surfaceContainerLowest = neutral(100), surfaceContainerLow = neutral(96), surfaceContainer = neutral(94),
            surfaceContainerHigh = neutral(92), surfaceContainerHighest = neutral(90),
        )
    } else {
        darkColorScheme(
            primary = primary(80), onPrimary = primary(20), primaryContainer = primary(30), onPrimaryContainer = primary(90),
            inversePrimary = primary(40),
            secondary = secondary(80), onSecondary = secondary(20), secondaryContainer = secondary(30), onSecondaryContainer = secondary(90),
            tertiary = tertiary(80), onTertiary = tertiary(20), tertiaryContainer = tertiary(30), onTertiaryContainer = tertiary(90),
            error = error(80), onError = error(20), errorContainer = error(30), onErrorContainer = error(90),
            background = neutral(6), onBackground = neutral(90),
            surface = neutral(6), onSurface = neutral(90), surfaceVariant = neutralVariant(30), onSurfaceVariant = neutralVariant(80),
            surfaceTint = primary(80),
            inverseSurface = neutral(90), inverseOnSurface = neutral(20),
            outline = neutralVariant(60), outlineVariant = neutralVariant(30), scrim = Color.Black,
            surfaceBright = neutral(24), surfaceDim = neutral(6),
            surfaceContainerLowest = neutral(4), surfaceContainerLow = neutral(10), surfaceContainer = neutral(12),
            surfaceContainerHigh = neutral(17), surfaceContainerHighest = neutral(22),
        )
    }
}

/** Swatch colours (light primary container + primary) for the picker. */
fun paletteSwatch(palette: AppPalette): Pair<Color, Color> {
    val s = paletteScheme(palette, dark = false)
    return s.primary to s.primaryContainer
}

private class Tonal(private val hue: Float, private val chroma: Float) {
    operator fun invoke(tone: Int): Color = labToColor(tone.toFloat(), chroma, hue)
}

private fun labToColor(l: Float, chromaIn: Float, hueDeg: Float): Color {
    if (l >= 99.5f) return Color.White
    if (l <= 0.5f) return Color.Black
    var c = chromaIn
    val rad = Math.toRadians(hueDeg.toDouble())
    repeat(40) {
        val a = (c * cos(rad)).toFloat()
        val b = (c * sin(rad)).toFloat()
        val rgb = labToLinearSrgb(l, a, b)
        if (rgb.all { it in -0.0005f..1.0005f }) {
            return Color(gamma(rgb[0]), gamma(rgb[1]), gamma(rgb[2]))
        }
        c *= 0.93f
    }
    val grey = gamma(labToLinearSrgb(l, 0f, 0f)[0])
    return Color(grey, grey, grey)
}

private fun labToLinearSrgb(l: Float, a: Float, b: Float): FloatArray {
    val fy = (l + 16f) / 116f
    val fx = fy + a / 500f
    val fz = fy - b / 200f
    fun inv(t: Float): Float = if (t * t * t > 0.008856f) t * t * t else (t - 16f / 116f) / 7.787f
    val x = 0.95047f * inv(fx)
    val y = inv(fy)
    val z = 1.08883f * inv(fz)
    return floatArrayOf(
        3.2404542f * x - 1.5371385f * y - 0.4985314f * z,
        -0.9692660f * x + 1.8760108f * y + 0.0415560f * z,
        0.0556434f * x - 0.2040259f * y + 1.0572252f * z,
    )
}

private fun gamma(v: Float): Float {
    val c = v.coerceIn(0f, 1f)
    return if (c <= 0.0031308f) 12.92f * c else 1.055f * c.pow(1f / 2.4f) - 0.055f
}
