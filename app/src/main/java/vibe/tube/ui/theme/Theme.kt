package vibe.tube.ui.theme

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

fun ColorScheme.toAmoled(): ColorScheme = copy(
    background = AmoledBlack,
    surface = AmoledBlack,
    surfaceDim = AmoledBlack,
    surfaceContainerLowest = AmoledBlack,
    surfaceContainerLow = Color(0xFF0B0B0B),
    surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF191919),
    surfaceContainerHighest = Color(0xFF212121),
    surfaceBright = Color(0xFF2A2A2A),
    inverseOnSurface = Color(0xFF1A1A1A),
)

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

@Composable
fun VibetubeTheme(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    palette: AppPalette = AppPalette.Vibe,
    fontStyle: FontStyleSettings = FontStyleSettings(),
    content: @Composable () -> Unit,
) {
    val typography = remember(fontStyle) { buildTypography(fontStyle) }
    val systemDark = isSystemInDarkTheme()
    val dark = resolveDark(themeMode, systemDark)
    val scheme = vibeColorScheme(themeMode, dynamicColor, systemDark, palette)
    val context = LocalContext.current
    val reduced = remember { isReducedMotion(context) }
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
    CompositionLocalProvider(LocalReducedMotion provides reduced) {
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
