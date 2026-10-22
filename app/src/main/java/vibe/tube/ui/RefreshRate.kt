package vibe.tube.ui

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Window
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Screen refresh-rate selection. Preference values: [MAX] asks for the fastest mode the panel offers
 * at its current resolution (60 to 185 Hz and beyond), [SYSTEM] leaves the choice to Android, and any
 * positive value is a requested rate in Hz (the nearest supported mode is used).
 */
object RefreshRate {
    const val MAX = 0
    const val SYSTEM = -1

    private fun defaultDisplay(context: Context): Display? =
        (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)

    /** Distinct rates (whole Hz, ascending) the panel supports at its current resolution. */
    fun supportedRates(context: Context): List<Int> {
        val display = defaultDisplay(context) ?: return emptyList()
        val current = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .map { it.refreshRate.roundToInt() }
            .distinct()
            .sorted()
    }

    fun apply(window: Window, context: Context, preference: Int) {
        val display = defaultDisplay(context) ?: return
        val current = display.mode
        val sameResolution = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        val target = when {
            preference == SYSTEM -> null
            preference == MAX -> sameResolution.maxByOrNull { it.refreshRate }
            else -> sameResolution.minByOrNull { abs(it.refreshRate - preference) }
        }
        val attributes = window.attributes
        val modeId = target?.modeId ?: 0
        val rate = target?.refreshRate ?: 0f
        if (attributes.preferredDisplayModeId != modeId || attributes.preferredRefreshRate != rate) {
            attributes.preferredDisplayModeId = modeId
            attributes.preferredRefreshRate = rate
            window.attributes = attributes
        }
    }
}
