package vibe.tube.ui

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Corner radius of the physical display (API 31+), so back-gesture previews can be clipped to the same
 * rounded shape as the device. Falls back to a typical phone radius when the system does not report one.
 */
@Composable
fun rememberDeviceCornerRadius(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    fun query(): Dp? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val insets = view.rootWindowInsets ?: return null
        val px = listOf(RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT, RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT)
            .mapNotNull { insets.getRoundedCorner(it)?.radius }
            .maxOrNull()
        return if (px != null && px > 0) with(density) { px.toDp() } else null
    }
    var radius by remember(view, density, configuration) { androidx.compose.runtime.mutableStateOf(query() ?: 44.dp) }
    androidx.compose.runtime.LaunchedEffect(view, density, configuration) {
        // Window insets are not always attached on first composition; keep asking until the system answers.
        repeat(20) {
            query()?.let { radius = it; return@LaunchedEffect }
            kotlinx.coroutines.delay(100)
        }
    }
    return radius
}
