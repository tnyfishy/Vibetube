package vibe.tube.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import vibe.tube.R

object GoogleSansFlex {
    const val ROUNDNESS = 100f
    const val WIDTH_DEFAULT = 100f
    const val MIN_OPSZ = 1f
    const val MAX_OPSZ = 144f
    const val MIN_WEIGHT = 1
    const val MAX_WEIGHT = 1000

    private val cache = HashMap<Pair<Int, Int>, FontFamily>()

    fun settings(weight: Int, opticalSize: Float): FontVariation.Settings {
        val w = weight.coerceIn(MIN_WEIGHT, MAX_WEIGHT)
        val opsz = opticalSize.coerceIn(MIN_OPSZ, MAX_OPSZ)
        return FontVariation.Settings(
            FontVariation.weight(w),
            FontVariation.width(WIDTH_DEFAULT),
            FontVariation.Setting("opsz", opsz),
            FontVariation.Setting("ROND", ROUNDNESS),
        )
    }

    fun family(weight: Int, opticalSize: Float): FontFamily = synchronized(cache) {
        cache.getOrPut(weight to opticalSize.toInt()) {
            FontFamily(
                Font(
                    resId = R.font.google_sans_flex,
                    weight = FontWeight(weight),
                    style = FontStyle.Normal,
                    variationSettings = settings(weight, opticalSize),
                ),
            )
        }
    }
}

private fun role(
    sizeSp: Float,
    lineHeightSp: Float,
    weight: Int,
    letterSpacing: TextUnit,
): TextStyle = TextStyle(
    fontFamily = GoogleSansFlex.family(weight, sizeSp),
    fontWeight = FontWeight(weight),
    fontSize = sizeSp.sp,
    lineHeight = lineHeightSp.sp,
    letterSpacing = letterSpacing,
)

val VibeTypography = Typography(
    displayLarge = role(57f, 64f, 400, (-0.25).sp),
    displayMedium = role(45f, 52f, 400, 0.sp),
    displaySmall = role(36f, 44f, 400, 0.sp),
    headlineLarge = role(32f, 40f, 500, 0.sp),
    headlineMedium = role(28f, 36f, 500, 0.sp),
    headlineSmall = role(24f, 32f, 500, 0.sp),
    titleLarge = role(22f, 28f, 500, 0.sp),
    titleMedium = role(16f, 24f, 600, 0.15.sp),
    titleSmall = role(14f, 20f, 600, 0.1.sp),
    bodyLarge = role(16f, 24f, 400, 0.5.sp),
    bodyMedium = role(14f, 20f, 400, 0.25.sp),
    bodySmall = role(12f, 16f, 400, 0.4.sp),
    labelLarge = role(14f, 20f, 600, 0.1.sp),
    labelMedium = role(12f, 16f, 600, 0.5.sp),
    labelSmall = role(11f, 16f, 500, 0.5.sp),
    displayLargeEmphasized = role(57f, 64f, 700, (-0.25).sp),
    displayMediumEmphasized = role(45f, 52f, 700, 0.sp),
    displaySmallEmphasized = role(36f, 44f, 700, 0.sp),
    headlineLargeEmphasized = role(32f, 40f, 700, 0.sp),
    headlineMediumEmphasized = role(28f, 36f, 700, 0.sp),
    headlineSmallEmphasized = role(24f, 32f, 700, 0.sp),
    titleLargeEmphasized = role(22f, 28f, 700, 0.sp),
    titleMediumEmphasized = role(16f, 24f, 700, 0.15.sp),
    titleSmallEmphasized = role(14f, 20f, 700, 0.1.sp),
    bodyLargeEmphasized = role(16f, 24f, 600, 0.5.sp),
    bodyMediumEmphasized = role(14f, 20f, 600, 0.25.sp),
    bodySmallEmphasized = role(12f, 16f, 600, 0.4.sp),
    labelLargeEmphasized = role(14f, 20f, 700, 0.1.sp),
    labelMediumEmphasized = role(12f, 16f, 700, 0.5.sp),
    labelSmallEmphasized = role(11f, 16f, 700, 0.5.sp),
)
