package vibe.tube.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

enum class AppPalette(val seedHue: Float, val seedChroma: Float) {
                                                                                                                
    Classic(0f, 0f),
    Vibe(160f, 36f),
    Indigo(272f, 48f),
    Coral(32f, 60f),
    Ocean(232f, 40f),
    Rose(355f, 52f),
    Violet(305f, 50f),
}

fun paletteScheme(palette: AppPalette, dark: Boolean): ColorScheme {
    if (palette == AppPalette.Vibe) return if (dark) VibeDarkColorScheme else VibeLightColorScheme
    if (palette == AppPalette.Classic) return if (dark) ClassicDarkColorScheme else ClassicLightColorScheme
    val h = palette.seedHue
    val primary = Tonal(h, palette.seedChroma)
    val secondary = Tonal(h, palette.seedChroma / 2.4f)
    val tertiary = Tonal(h + 60f, palette.seedChroma / 2f)
    val neutral = Tonal(h, 4f)
    val neutralVariant = Tonal(h, 8f)
    val error = Tonal(25f, 84f)
    return if (!dark) {
        lightColorScheme(
            primary = primary(40), onPrimary = primary(100), primaryContainer = primary(90), onPrimaryContainer = primary(10),
            inversePrimary = primary(80),
            secondary = secondary(40), onSecondary = secondary(100), secondaryContainer = secondary(88), onSecondaryContainer = secondary(10),
            tertiary = tertiary(40), onTertiary = tertiary(100), tertiaryContainer = tertiary(90), onTertiaryContainer = tertiary(10),
            error = error(40), onError = error(100), errorContainer = error(90), onErrorContainer = error(10),
            background = neutral(98), onBackground = neutral(10),
            surface = neutral(98), onSurface = neutral(10), surfaceVariant = neutralVariant(88), onSurfaceVariant = neutralVariant(26),
            surfaceTint = primary(40),
            inverseSurface = neutral(20), inverseOnSurface = neutral(95),
            outline = neutralVariant(42), outlineVariant = neutralVariant(72), scrim = Color.Black,
            surfaceBright = neutral(98), surfaceDim = neutral(87),
            surfaceContainerLowest = neutral(100), surfaceContainerLow = neutral(95), surfaceContainer = neutral(92),
            surfaceContainerHigh = neutral(89), surfaceContainerHighest = neutral(86),
        )
    } else {
        darkColorScheme(
            primary = primary(80), onPrimary = primary(20), primaryContainer = primary(30), onPrimaryContainer = primary(90),
            inversePrimary = primary(40),
            secondary = secondary(80), onSecondary = secondary(20), secondaryContainer = secondary(30), onSecondaryContainer = secondary(90),
            tertiary = tertiary(80), onTertiary = tertiary(20), tertiaryContainer = tertiary(30), onTertiaryContainer = tertiary(90),
            error = error(80), onError = error(20), errorContainer = error(30), onErrorContainer = error(90),
            background = neutral(6), onBackground = neutral(90),
            surface = neutral(6), onSurface = neutral(90), surfaceVariant = neutralVariant(32), onSurfaceVariant = neutralVariant(78),
            surfaceTint = primary(80),
            inverseSurface = neutral(90), inverseOnSurface = neutral(20),
            outline = neutralVariant(68), outlineVariant = neutralVariant(38), scrim = Color.Black,
            surfaceBright = neutral(30), surfaceDim = neutral(6),
            surfaceContainerLowest = neutral(4), surfaceContainerLow = neutral(11), surfaceContainer = neutral(15),
            surfaceContainerHigh = neutral(20), surfaceContainerHighest = neutral(26),
        )
    }
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

private val ClassicLightColorScheme = lightColorScheme(
    primary = Color(0xFF000000), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFCFCF), onPrimaryContainer = Color(0xFF000000),
    inversePrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF2B2B2B), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCCCCC), onSecondaryContainer = Color(0xFF000000),
    tertiary = Color(0xFFD71A1A), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDAD6), onTertiaryContainer = Color(0xFF410002),
    background = Color(0xFFFFFFFF), onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFE7E7E7), onSurfaceVariant = Color(0xFF4F4F4F),
    surfaceTint = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFF000000), inverseOnSurface = Color(0xFFFFFFFF),
    outline = Color(0xFF6E6E6E), outlineVariant = Color(0xFFC4C4C4), scrim = Color.Black,
    surfaceBright = Color(0xFFFFFFFF), surfaceDim = Color(0xFFD9D9D9),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF6F6F6), surfaceContainer = Color(0xFFEFEFEF),
    surfaceContainerHigh = Color(0xFFE8E8E8), surfaceContainerHighest = Color(0xFFE0E0E0),
)

private val ClassicDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF), onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF4D4D4D), onPrimaryContainer = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFF000000),
    secondary = Color(0xFFD4D4D4), onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF4A4A4A), onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFFFF8A80), onTertiary = Color(0xFF4A0003),
    tertiaryContainer = Color(0xFF7A0A0E), onTertiaryContainer = Color(0xFFFFDAD6),
    background = Color(0xFF000000), onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000), onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF262626), onSurfaceVariant = Color(0xFFBDBDBD),
    surfaceTint = Color(0xFF000000),
    inverseSurface = Color(0xFFFFFFFF), inverseOnSurface = Color(0xFF000000),
    outline = Color(0xFF8C8C8C), outlineVariant = Color(0xFF454545), scrim = Color.Black,
    surfaceBright = Color(0xFF3A3A3A), surfaceDim = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF000000), surfaceContainerLow = Color(0xFF131313), surfaceContainer = Color(0xFF1B1B1B),
    surfaceContainerHigh = Color(0xFF252525), surfaceContainerHighest = Color(0xFF2F2F2F),
)
