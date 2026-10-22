package vibe.tube.data.settings

import vibe.tube.youtube.model.ContentLocale
import java.util.Locale

enum class ThemeMode { System, Light, Dark, Amoled }

/**
 * Default playback quality. [maxFps] null means "no frame-rate limit"; entries named `*P60` allow
 * 60 fps while the plain ones prefer 30 fps. Enum names are persisted, so existing names must stay.
 */
enum class QualityCap(val maxHeight: Int?, val maxFps: Int? = null) {
    Auto(null),
    P4320F60(4320, 60),
    P4320(4320, 30),
    P2160F60(2160, 60),
    P2160(2160, 30),
    P1440F60(1440, 60),
    P1440(1440, 30),
    P1080F60(1080, 60),
    P1080(1080, 30),
    P720F60(720, 60),
    P720(720, 30),
    P480(480),
    P360(360),
    P240(240),
    P144(144),
    ;

    /** Human label such as `1080p60`; null for [Auto]. */
    val label: String?
        get() = maxHeight?.let { h -> if (maxFps == 60) "${h}p60" else "${h}p" }
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
    val uiLanguage: String = SYSTEM,
    val contentLanguage: String = SYSTEM,
    val contentRegion: String = SYSTEM,
    val wifiQuality: QualityCap = QualityCap.Auto,
    val mobileQuality: QualityCap = QualityCap.P480,
    val autoplay: Boolean = true,
    val backgroundPlayback: Boolean = true,
    val pictureInPicture: Boolean = true,
    val subtitlesEnabled: Boolean = false,
    val subtitleLanguage: String = SYSTEM,
    val saveLocalHistory: Boolean = true,
    val privateMode: Boolean = false,
    val hideShorts: Boolean = true,
) {
    fun contentLocale(device: Locale = Locale.getDefault()): ContentLocale {
        val language = if (contentLanguage == SYSTEM) device.language.ifBlank { "en" } else contentLanguage
        val region = if (contentRegion == SYSTEM) device.country.ifBlank { "US" } else contentRegion
        return ContentLocale(language, region)
    }

    val recordsLocalHistory: Boolean get() = saveLocalHistory && !privateMode

    companion object {
        const val SYSTEM = "system"
    }
}
