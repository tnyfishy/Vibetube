package vibe.tube.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

val LocalReducedMotion = compositionLocalOf { false }

fun isReducedMotion(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

object VibeMotion {
    @Composable
    fun <T> spatial(): FiniteAnimationSpec<T> =
        if (LocalReducedMotion.current) snap() else MaterialTheme.motionScheme.defaultSpatialSpec()

    @Composable
    fun <T> fastSpatial(): FiniteAnimationSpec<T> =
        if (LocalReducedMotion.current) snap() else MaterialTheme.motionScheme.fastSpatialSpec()

    @Composable
    fun <T> effects(): FiniteAnimationSpec<T> =
        if (LocalReducedMotion.current) snap() else MaterialTheme.motionScheme.defaultEffectsSpec()

    @Composable
    fun <T> fastEffects(): FiniteAnimationSpec<T> =
        if (LocalReducedMotion.current) snap() else MaterialTheme.motionScheme.fastEffectsSpec()
}
