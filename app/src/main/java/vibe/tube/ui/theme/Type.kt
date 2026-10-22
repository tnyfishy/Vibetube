package vibe.tube.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import vibe.tube.R
import vibe.tube.data.settings.AppFont

object GoogleSansFlex {
    const val ROUNDNESS = 100f
    const val WIDTH_DEFAULT = 100f
    const val WIDTH_MIN = 25f
    const val WIDTH_MAX = 151f
    const val MIN_OPSZ = 1f
    const val MAX_OPSZ = 144f
    const val MIN_WEIGHT = 1
    const val MAX_WEIGHT = 1000

    fun settings(
        weight: Int,
        opticalSize: Float,
        width: Float = WIDTH_DEFAULT,
        roundness: Float = ROUNDNESS,
    ): FontVariation.Settings {
        val w = weight.coerceIn(MIN_WEIGHT, MAX_WEIGHT)
        val opsz = opticalSize.coerceIn(MIN_OPSZ, MAX_OPSZ)
        return FontVariation.Settings(
            FontVariation.weight(w),
            FontVariation.width(width.coerceIn(WIDTH_MIN, WIDTH_MAX)),
            FontVariation.Setting("opsz", opsz),
            FontVariation.Setting("ROND", roundness.coerceIn(0f, 100f)),
        )
    }
}

/** User-tunable typography. [weightOffset] is added to every role's weight (0 = stock Material 3). */
data class FontStyleSettings(
    val font: AppFont = AppFont.System,
    val weightOffset: Int = 0,
    val width: Int = GoogleSansFlex.WIDTH_DEFAULT.toInt(),
    val roundness: Int = GoogleSansFlex.ROUNDNESS.toInt(),
)

private val familyCache = HashMap<String, FontFamily>()

/** The standard weight stops a family resolves; each one is rendered at stop + offset. */
private val weightStops = listOf(100, 200, 300, 400, 500, 600, 700, 800, 900)

private fun variationFor(font: AppFont, weight: Int, opsz: Float, s: FontStyleSettings): FontVariation.Settings {
    val w = weight.coerceIn(font.minWeight, font.maxWeight)
    if (font == AppFont.GoogleSansFlex) return GoogleSansFlex.settings(w, opsz, s.width.toFloat(), s.roundness.toFloat())
    return FontVariation.Settings(FontVariation.weight(w), FontVariation.width(s.width.toFloat().coerceIn(font.minWidth, font.maxWidth)))
}

/**
 * Family for [s.font] at optical size [opsz]. A variable font is registered at every weight stop,
 * each stop carrying its own `wght` (stop + offset), so any `FontWeight` a composable requests lands
 * on the right instance instead of being synthesized.
 */
fun fontFamilyFor(s: FontStyleSettings, opsz: Float): FontFamily {
    val res = s.font.resId ?: return FontFamily.Default
    val axisKey = if (s.font == AppFont.GoogleSansFlex) "${s.width}/${s.roundness}" else "${s.width}"
    val opszKey = if (s.font == AppFont.GoogleSansFlex) opsz.toInt() else 0
    val key = "${s.font.name}|${s.weightOffset}|$axisKey|$opszKey"
    return synchronized(familyCache) {
        familyCache.getOrPut(key) {
            FontFamily(
                weightStops.map { stop ->
                    Font(
                        resId = res,
                        weight = FontWeight(stop),
                        style = FontStyle.Normal,
                        variationSettings = variationFor(s.font, stop + s.weightOffset, opsz, s),
                    )
                },
            )
        }
    }
}

private fun role(
    s: FontStyleSettings,
    sizeSp: Float,
    lineHeightSp: Float,
    weight: Int,
    letterSpacing: TextUnit,
): TextStyle = TextStyle(
    fontFamily = fontFamilyFor(s, sizeSp),
    // System font is not variable: apply the offset directly to the requested weight.
    fontWeight = FontWeight((if (s.font.resId == null) weight + s.weightOffset else weight).coerceIn(1, 1000)),
    fontSize = sizeSp.sp,
    lineHeight = lineHeightSp.sp,
    letterSpacing = letterSpacing,
)

/** Stock Material 3 weights; headline sizes are a little larger than stock. */
fun buildTypography(s: FontStyleSettings = FontStyleSettings()): Typography = Typography(
    displayLarge = role(s, 57f, 64f, 400, (-0.25).sp),
    displayMedium = role(s, 45f, 52f, 400, 0.sp),
    displaySmall = role(s, 36f, 44f, 400, 0.sp),
    headlineLarge = role(s, 34f, 42f, 400, 0.sp),
    headlineMedium = role(s, 30f, 38f, 400, 0.sp),
    headlineSmall = role(s, 24f, 32f, 400, 0.sp),
    titleLarge = role(s, 22f, 28f, 400, 0.sp),
    titleMedium = role(s, 16f, 24f, 500, 0.15.sp),
    titleSmall = role(s, 14f, 20f, 500, 0.1.sp),
    bodyLarge = role(s, 16f, 24f, 400, 0.5.sp),
    bodyMedium = role(s, 14f, 20f, 400, 0.25.sp),
    bodySmall = role(s, 12f, 16f, 400, 0.4.sp),
    labelLarge = role(s, 14f, 20f, 500, 0.1.sp),
    labelMedium = role(s, 12f, 16f, 500, 0.5.sp),
    labelSmall = role(s, 11f, 16f, 500, 0.5.sp),
    displayLargeEmphasized = role(s, 57f, 64f, 600, (-0.25).sp),
    displayMediumEmphasized = role(s, 45f, 52f, 600, 0.sp),
    displaySmallEmphasized = role(s, 36f, 44f, 600, 0.sp),
    headlineLargeEmphasized = role(s, 34f, 42f, 600, 0.sp),
    headlineMediumEmphasized = role(s, 30f, 38f, 600, 0.sp),
    headlineSmallEmphasized = role(s, 24f, 32f, 600, 0.sp),
    titleLargeEmphasized = role(s, 22f, 28f, 600, 0.sp),
    titleMediumEmphasized = role(s, 16f, 24f, 700, 0.15.sp),
    titleSmallEmphasized = role(s, 14f, 20f, 700, 0.1.sp),
    bodyLargeEmphasized = role(s, 16f, 24f, 600, 0.5.sp),
    bodyMediumEmphasized = role(s, 14f, 20f, 600, 0.25.sp),
    bodySmallEmphasized = role(s, 12f, 16f, 600, 0.4.sp),
    labelLargeEmphasized = role(s, 14f, 20f, 700, 0.1.sp),
    labelMediumEmphasized = role(s, 12f, 16f, 700, 0.5.sp),
    labelSmallEmphasized = role(s, 11f, 16f, 700, 0.5.sp),
)
