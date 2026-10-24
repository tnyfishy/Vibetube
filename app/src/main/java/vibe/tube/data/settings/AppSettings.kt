package vibe.tube.data.settings

import vibe.tube.youtube.model.ContentLocale
import java.util.Locale

enum class ThemeMode { System, Light, Dark, Amoled }

/**
 * Selectable UI fonts. [resId] is null for the system font. All bundled fonts are variable and cover
 * Vietnamese; [minWeight]..[maxWeight] is the `wght` axis range of each file. Enum names are persisted.
 */
/**
 * Bundled fonts. All are variable, cover Vietnamese, and expose weight and width axes (ranges below are the real
 * axis ranges of the files). Only Google Sans Flex has a roundness (`ROND`) axis. Enum names are persisted.
 */
enum class AppFont(
    val resId: Int?,
    val label: String?,
    val minWeight: Int = 1,
    val maxWeight: Int = 1000,
    val minWidth: Float = 100f,
    val maxWidth: Float = 100f,
    val hasRoundness: Boolean = false,
    /** Static fonts only: (weight, resource) pairs. */
    val statics: List<Pair<Int, Int>> = emptyList(),
) {
    System(null, null),
    GoogleSansFlex(vibe.tube.R.font.google_sans_flex, "Google Sans Flex", 1, 1000, 25f, 151f, hasRoundness = true),
    TikTokSans(vibe.tube.R.font.tiktok_sans, "TikTok Sans", 300, 900, 75f, 150f),
    // Be Vietnam Pro ships as nine static weights (no variable build exists): weight steps in hundreds, no width axis.
    BeVietnamPro(
        null, "Be Vietnam Pro", 100, 900,
        statics = listOf(
            100 to vibe.tube.R.font.be_vietnam_pro_thin,
            200 to vibe.tube.R.font.be_vietnam_pro_extralight,
            300 to vibe.tube.R.font.be_vietnam_pro_light,
            400 to vibe.tube.R.font.be_vietnam_pro_regular,
            500 to vibe.tube.R.font.be_vietnam_pro_medium,
            600 to vibe.tube.R.font.be_vietnam_pro_semibold,
            700 to vibe.tube.R.font.be_vietnam_pro_bold,
            800 to vibe.tube.R.font.be_vietnam_pro_extrabold,
            900 to vibe.tube.R.font.be_vietnam_pro_black,
        ),
    ),
    ;

    val isSystem: Boolean get() = resId == null && statics.isEmpty()
    val isStatic: Boolean get() = statics.isNotEmpty()
    /** Anything but the system font can be previewed/loaded from bundled files. */
    val isBundled: Boolean get() = !isSystem
}

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
    val palette: vibe.tube.ui.theme.AppPalette = vibe.tube.ui.theme.AppPalette.Vibe,
    val font: AppFont = AppFont.GoogleSansFlex,
    /** Added to every text role's weight; 0 is stock Material 3. */
    val fontWeightOffset: Int = 0,
    /** Google Sans Flex only: `wdth` axis (default 100). */
    val fontWidth: Int = 100,
    /** Google Sans Flex only: `ROND` axis. */
    val fontRoundness: Int = 100,
    val uiLanguage: String = SYSTEM,
    val contentLanguage: String = SYSTEM,
    val contentRegion: String = SYSTEM,
    val wifiQuality: QualityCap = QualityCap.Auto,
    val mobileQuality: QualityCap = QualityCap.P480,
    val autoplay: Boolean = true,
    /** Resume a paused video when headphones (wired or Bluetooth) connect. */
    val autoplayOnHeadphones: Boolean = false,
    /** In fullscreen: swipe the left side for brightness and the right side for volume. */
    val fullscreenGestures: Boolean = true,
    val fullscreenSwipeToggle: Boolean = true,
    val backgroundPlayback: Boolean = true,
    /** Seconds skipped by a double tap on the video. */
    val seekSeconds: Int = 10,
    val pictureInPicture: Boolean = true,
    val subtitlesEnabled: Boolean = false,
    val subtitleLanguage: String = SYSTEM,
    val saveLocalHistory: Boolean = true,
    val privateMode: Boolean = false,
    val hideShorts: Boolean = false,
    /** Folder (SAF tree URI) chosen for downloads; blank means the default `Download/VibeTube`. */
    val downloadDir: String = "",
    /** Channels the user asked not to be recommended, stored as `channelId|name`. */
    val hiddenChannels: Set<String> = emptySet(),
) {
    /** [ipRegion] is the country of the public IP; it stands in for "system default" before the device locale. */
    fun contentLocale(device: Locale = Locale.getDefault(), ipRegion: String? = null): ContentLocale {
        val language = if (contentLanguage == SYSTEM) device.language.ifBlank { "en" } else contentLanguage
        val region = if (contentRegion == SYSTEM) ipRegion ?: device.country.ifBlank { "US" } else contentRegion
        return ContentLocale(language, region)
    }

    val recordsLocalHistory: Boolean get() = saveLocalHistory && !privateMode

    val hiddenChannelIds: Set<String> get() = hiddenChannels.mapTo(HashSet()) { it.substringBefore('|') }

    companion object {
        const val SYSTEM = "system"
    }
}
