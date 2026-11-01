package vibe.tube.ui.theme

import kotlin.math.min
import kotlin.math.max
import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import vibe.tube.data.settings.ThemeMode

val AmoledBlack = Color(0xFF000000)

/**
 * True black for OLED. Containers keep the palette's own hue and are only pulled towards black,
 * so cards, chips and sheets stay in the same colour family as the light and dark themes.
 */
fun ColorScheme.toAmoled(): ColorScheme {
    fun deepen(color: Color, keep: Float) = androidx.compose.ui.graphics.lerp(AmoledBlack, color, keep)
    return copy(
        background = AmoledBlack,
        surface = AmoledBlack,
        surfaceDim = AmoledBlack,
        surfaceContainerLowest = AmoledBlack,
        surfaceContainerLow = deepen(surfaceContainerLow, 0.7f),
        surfaceContainer = deepen(surfaceContainer, 0.75f),
        surfaceContainerHigh = deepen(surfaceContainerHigh, 0.8f),
        surfaceContainerHighest = deepen(surfaceContainerHighest, 0.85f),
        surfaceBright = deepen(surfaceBright, 0.9f),
        surfaceVariant = deepen(surfaceVariant, 0.75f),
    )
}

fun resolveDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.System -> systemDark
    ThemeMode.Light -> false
    ThemeMode.Dark, ThemeMode.Amoled -> true
}

@Composable
fun vibeColorScheme(mode: ThemeMode, dynamicColor: Boolean, systemDark: Boolean, palette: AppPalette = AppPalette.Vibe): ColorScheme {
    val dark = resolveDark(mode, systemDark)
    val context = LocalContext.current
    val base = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> paletteScheme(palette, dark)
    }
    return if (mode == ThemeMode.Amoled) base.toAmoled() else base
}

const val UI_SCALE = 0.945f

data class EffectiveScale(
    val density: Float,
    val fontScale: Float,
    val displayPercent: Int,
    val textPercent: Int,
    val displayLimited: Boolean,
    val textLimited: Boolean,
)

object UiScaling {
    const val DISPLAY_MIN = 85
    const val DISPLAY_MAX = 125
    const val TEXT_MIN = 85
    const val TEXT_MAX = 130
    const val STEP = 5
    const val MIN_LAYOUT_WIDTH_DP = 320f
    const val MAX_TEXT_RATIO = 1.5f

    fun effective(baseDensity: Float, baseFontScale: Float, screenWidthPx: Float, displayPercent: Int, textPercent: Int): EffectiveScale {
        val display = displayPercent.coerceIn(DISPLAY_MIN, DISPLAY_MAX)
        val text = textPercent.coerceIn(TEXT_MIN, TEXT_MAX)
        val standard = baseDensity * UI_SCALE
        val wanted = standard * display / 100f
        val widest = max(screenWidthPx / MIN_LAYOUT_WIDTH_DP, standard)
        val density = if (wanted > widest) widest else wanted
        val wantedFont = baseFontScale * text / 100f
        val fontCap = max(baseFontScale, MAX_TEXT_RATIO)
        val fontScale = min(wantedFont, fontCap)
        return EffectiveScale(
            density = density,
            fontScale = fontScale,
            displayPercent = kotlin.math.round(density / standard * 100f).toInt(),
            textPercent = kotlin.math.round(fontScale / baseFontScale * 100f).toInt(),
            displayLimited = density < wanted - 0.0001f,
            textLimited = fontScale < wantedFont - 0.0001f,
        )
    }
}

val LocalDeviceDensity = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.ui.unit.Density?> { null }

@Composable
fun ScaledDensity(scale: EffectiveScale, content: @Composable () -> Unit) {
    val density = remember(scale.density, scale.fontScale) { androidx.compose.ui.unit.Density(scale.density, scale.fontScale) }
    CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides density, content = content)
}

@Composable
fun rememberEffectiveScale(displayPercent: Int, textPercent: Int): EffectiveScale {
    val base = LocalDeviceDensity.current ?: androidx.compose.ui.platform.LocalDensity.current
    val widthPx = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * base.density
    return remember(base, widthPx, displayPercent, textPercent) {
        UiScaling.effective(base.density, base.fontScale, widthPx, displayPercent, textPercent)
    }
}

val LocalFontStyleSettings = androidx.compose.runtime.staticCompositionLocalOf { FontStyleSettings() }

val LocalClassicPalette = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
fun VibetubeTheme(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    palette: AppPalette = AppPalette.Vibe,
    fontStyle: FontStyleSettings = FontStyleSettings(),
    displayScale: Int = 100,
    textScale: Int = 100,
    content: @Composable () -> Unit,
) {
    val typography = remember(fontStyle) { buildTypography(fontStyle) }
    val systemDark = isSystemInDarkTheme()
    val dark = resolveDark(themeMode, systemDark)
    val context = LocalContext.current
    val reduced = remember { isReducedMotion(context) }
    val scheme = animatedColorScheme(vibeColorScheme(themeMode, dynamicColor, systemDark, palette), reduced)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.setBackgroundDrawable(ColorDrawable(scheme.background.toArgb()))
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    val base = androidx.compose.ui.platform.LocalDensity.current
    
    val widthPx = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * base.density
    val effective = remember(base, widthPx, displayScale, textScale) { UiScaling.effective(base.density, base.fontScale, widthPx, displayScale, textScale) }
    val scaled = remember(effective) { androidx.compose.ui.unit.Density(effective.density, effective.fontScale) }
    CompositionLocalProvider(
        LocalReducedMotion provides reduced,
        LocalFontStyleSettings provides fontStyle,
        LocalClassicPalette provides (palette == AppPalette.Classic && !dynamicColor),
        LocalDeviceDensity provides base,
        androidx.compose.ui.platform.LocalDensity provides scaled,
    ) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            shapes = VibeShapes,
            typography = typography,
        ) {
            Surface(color = scheme.background, content = content)
        }
    }
}

object MediaOverlayColors {
    val scrim = Color(0xB8000000)
    val onScrim = Color(0xFFFFFFFF)
    val videoBackground = Color(0xFF000000)
    val controlsScrim = Color(0x66000000)
}
