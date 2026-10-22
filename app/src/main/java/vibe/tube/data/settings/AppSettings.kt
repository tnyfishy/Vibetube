package vibe.tube.data.settings

import vibe.tube.youtube.model.ContentLocale
import java.util.Locale

enum class ThemeMode { System, Light, Dark, Amoled }

enum class QualityCap(val maxHeight: Int?) {
    Auto(null),
    P1080(1080),
    P720(720),
    P480(480),
    P360(360),
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
