package vibe.tube.data.settings

import vibe.tube.youtube.model.ContentLocale
import java.util.Locale

enum class AudioQuality { Highest, Medium, Lowest }

/** How large a video's thumbnail is fetched: the full-size image, a 480-wide one, or a 320-wide one. */
enum class ThumbnailQuality { Highest, Medium, Low }

enum class ThemeMode { System, Light, Dark, Amoled }

enum class SubtitleColor(val argb: Long) { White(0xFFFFFFFF), Yellow(0xFFFFEB3B), Cyan(0xFF80DEEA), Green(0xFFA5D6A7) }

enum class SubtitleBackground(val alpha: Float) { None(0f), Semi(0.55f), Solid(1f) }

data class FontAxis(val tag: String, val min: Float, val default: Float, val max: Float) {
    val range: ClosedFloatingPointRange<Float> get() = min..max
}

enum class AppFont(
    val resId: Int?,
    val label: String?,
    val axes: List<FontAxis> = emptyList(),
                                                       
    val statics: List<Pair<Int, Int>> = emptyList(),
) {
    System(null, null),
    GoogleSansFlex(
        vibe.tube.R.font.google_sans_flex, "Google Sans Flex",
        axes = listOf(
            FontAxis("opsz", 1f, 18f, 144f),
            FontAxis("wdth", 25f, 100f, 151f),
            FontAxis("wght", 1f, 400f, 1000f),
            FontAxis("ROND", 0f, 0f, 100f),
            FontAxis("slnt", -10f, 0f, 0f),
        ),
    ),
    TikTokSans(
        vibe.tube.R.font.tiktok_sans, "TikTok Sans",
        axes = listOf(
            FontAxis("opsz", 12f, 36f, 36f),
            FontAxis("wdth", 75f, 100f, 150f),
            FontAxis("wght", 300f, 300f, 900f),
            FontAxis("slnt", -6f, 0f, 0f),
        ),
    ),
    BeVietnamPro(
        null, "Be Vietnam Pro",
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
                                                                                   

    fun axis(tag: String): FontAxis? = axes.firstOrNull { it.tag == tag }

    val minWeight: Int get() = axis(WGHT)?.min?.toInt() ?: statics.firstOrNull()?.first ?: 1
    val maxWeight: Int get() = axis(WGHT)?.max?.toInt() ?: statics.lastOrNull()?.first ?: 1000
    val minWidth: Float get() = axis(WDTH)?.min ?: 100f
    val maxWidth: Float get() = axis(WDTH)?.max ?: 100f
    val hasRoundness: Boolean get() = axis(ROND) != null

    companion object {
        const val WGHT = "wght"
        const val WDTH = "wdth"
        const val ROND = "ROND"
        const val SLNT = "slnt"
        const val OPSZ = "opsz"
        const val GRAD = "GRAD"
    }
}

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

    val label: String?
        get() = maxHeight?.let { h -> if (maxFps == 60) "${h}p60" else "${h}p" }
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = false,
    val palette: vibe.tube.ui.theme.AppPalette = vibe.tube.ui.theme.AppPalette.Vibe,
    val font: AppFont = AppFont.GoogleSansFlex,
                                                                    
    val fontWeightOffset: Int = 0,
                                                            
    val fontWidth: Int = 100,
                                              
    val fontRoundness: Int = 100,
                                                                      
    val fontSlant: Int = 0,
                                                                                                               
    val displayScale: Int = 100,
                                                                              
    val textScale: Int = 100,
    val uiLanguage: String = "vi",
    val contentLanguage: String = "vi",
    val contentRegion: String = "VN",
    val wifiQuality: QualityCap = QualityCap.Auto,
    val mobileQuality: QualityCap = QualityCap.P480,
    val audioQuality: AudioQuality = AudioQuality.Highest,
    val thumbnailQuality: ThumbnailQuality = ThumbnailQuality.Highest,
    val voiceStabilization: Boolean = false,
    val ambientFullscreen: Boolean = true,
    val autoplay: Boolean = true,
                                                                              
    val autoplayOnHeadphones: Boolean = false,
                                                                                           
    val fullscreenGestures: Boolean = true,
    val fullscreenSwipeToggle: Boolean = true,
    val navHideLabels: Boolean = true,
    val navBarHeight: Int = NAV_HEIGHT_DEFAULT,
    val navIconSize: Int = NAV_ICON_DEFAULT,
    val navFloating: Boolean = false,
    val navBlur: Boolean = false,
    val backgroundPlayback: Boolean = true,
                                                        
    val seekSeconds: Int = 10,
    val pictureInPicture: Boolean = false,
                                                                                                      
    val subtitlesEnabled: Boolean = false,
    val subtitleLanguage: String = SYSTEM,
                                               
    val subtitleSize: Int = 100,
    val subtitleColor: SubtitleColor = SubtitleColor.White,
    val subtitleBackground: SubtitleBackground = SubtitleBackground.Semi,
    val saveLocalHistory: Boolean = true,
    val privateMode: Boolean = false,
    val hideShorts: Boolean = false,
                                                                                                                          
    val notifyNewVideos: Boolean = false,
                                                                                              
    val shortsWifiQuality: QualityCap = QualityCap.P720,
    val shortsMobileQuality: QualityCap = QualityCap.P480,
                                                                                                   
    val downloadDir: String = "",
                                                                                     
    val hiddenChannels: Set<String> = emptySet(),
) {
                                                                                                     
    fun contentLocale(device: Locale = Locale.getDefault(), ipRegion: String? = null): ContentLocale {
        val region = if (contentRegion == SYSTEM) ipRegion ?: device.country.ifBlank { "US" } else contentRegion
        val language = if (contentLanguage == SYSTEM) device.language.ifBlank { "en" } else contentLanguage
        return ContentLocale(language, region)
    }

    fun appLanguage(device: Locale = Locale.getDefault()): String =
        if (uiLanguage == SYSTEM) device.language.ifBlank { "en" } else uiLanguage

    val recordsLocalHistory: Boolean get() = saveLocalHistory && !privateMode

    val hiddenChannelIds: Set<String> get() = hiddenChannels.mapTo(HashSet()) { it.substringBefore('|') }

    companion object {
        const val SYSTEM = "system"
        const val NAV_HEIGHT_DEFAULT = 60
        val NAV_HEIGHT_RANGE = 48..80
        const val NAV_ICON_DEFAULT = 22
        val NAV_ICON_RANGE = 18..30
    }
}
