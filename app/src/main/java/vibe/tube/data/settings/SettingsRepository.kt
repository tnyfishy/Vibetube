package vibe.tube.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class SettingsRepository(
    private val store: DataStore<Preferences>,
    scope: CoroutineScope,
    private val context: Context? = null,
) {
    val settings: Flow<AppSettings> = store.data.map { it.toSettings() }.distinctUntilChanged()

    val latest: StateFlow<AppSettings> = settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun prime(): AppSettings = settings.first().also { }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs.write(next)
            context?.let { writeBootMirror(it, next) }
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            themeMode = enumOr(this[THEME], d.themeMode),
            dynamicColor = this[DYNAMIC] ?: d.dynamicColor,
            font = enumOr(this[FONT], d.font),
            fontWeightOffset = this[FONT_WEIGHT] ?: d.fontWeightOffset,
            fontWidth = this[FONT_WIDTH] ?: d.fontWidth,
            fontRoundness = this[FONT_ROUND] ?: d.fontRoundness,
            uiLanguage = this[UI_LANG] ?: d.uiLanguage,
            contentLanguage = this[CONTENT_LANG] ?: d.contentLanguage,
            contentRegion = this[CONTENT_REGION] ?: d.contentRegion,
            wifiQuality = enumOr(this[WIFI_Q], d.wifiQuality),
            mobileQuality = enumOr(this[MOBILE_Q], d.mobileQuality),
            autoplay = this[AUTOPLAY] ?: d.autoplay,
            backgroundPlayback = this[BACKGROUND] ?: d.backgroundPlayback,
            seekSeconds = this[SEEK_SECONDS] ?: d.seekSeconds,
            pictureInPicture = this[PIP] ?: d.pictureInPicture,
            subtitlesEnabled = this[SUBS] ?: d.subtitlesEnabled,
            subtitleLanguage = this[SUB_LANG] ?: d.subtitleLanguage,
            saveLocalHistory = this[LOCAL_HISTORY] ?: d.saveLocalHistory,
            privateMode = this[PRIVATE] ?: d.privateMode,
            hideShorts = this[HIDE_SHORTS] ?: d.hideShorts,
            downloadDir = this[DOWNLOAD_DIR] ?: d.downloadDir,
            hiddenChannels = this[HIDDEN_CHANNELS] ?: d.hiddenChannels,
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.write(s: AppSettings) {
        this[THEME] = s.themeMode.name
        this[DYNAMIC] = s.dynamicColor
        this[FONT] = s.font.name
        this[FONT_WEIGHT] = s.fontWeightOffset
        this[FONT_WIDTH] = s.fontWidth
        this[FONT_ROUND] = s.fontRoundness
        this[UI_LANG] = s.uiLanguage
        this[CONTENT_LANG] = s.contentLanguage
        this[CONTENT_REGION] = s.contentRegion
        this[WIFI_Q] = s.wifiQuality.name
        this[MOBILE_Q] = s.mobileQuality.name
        this[AUTOPLAY] = s.autoplay
        this[BACKGROUND] = s.backgroundPlayback
        this[SEEK_SECONDS] = s.seekSeconds
        this[PIP] = s.pictureInPicture
        this[SUBS] = s.subtitlesEnabled
        this[SUB_LANG] = s.subtitleLanguage
        this[LOCAL_HISTORY] = s.saveLocalHistory
        this[PRIVATE] = s.privateMode
        this[HIDE_SHORTS] = s.hideShorts
        this[DOWNLOAD_DIR] = s.downloadDir
        this[HIDDEN_CHANNELS] = s.hiddenChannels
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    companion object {
        private val THEME = stringPreferencesKey("theme_mode")
        private val DYNAMIC = booleanPreferencesKey("dynamic_color")
        private val FONT = stringPreferencesKey("font_family")
        private val FONT_WEIGHT = intPreferencesKey("font_weight_offset")
        private val FONT_WIDTH = intPreferencesKey("font_width")
        private val FONT_ROUND = intPreferencesKey("font_roundness")
        private val UI_LANG = stringPreferencesKey("ui_language")
        private val CONTENT_LANG = stringPreferencesKey("content_language")
        private val CONTENT_REGION = stringPreferencesKey("content_region")
        private val WIFI_Q = stringPreferencesKey("wifi_quality")
        private val MOBILE_Q = stringPreferencesKey("mobile_quality")
        private val AUTOPLAY = booleanPreferencesKey("autoplay")
        private val BACKGROUND = booleanPreferencesKey("background_playback")
        private val SEEK_SECONDS = intPreferencesKey("seek_seconds")
        private val PIP = booleanPreferencesKey("pip")
        private val SUBS = booleanPreferencesKey("subtitles")
        private val SUB_LANG = stringPreferencesKey("subtitle_language")
        private val LOCAL_HISTORY = booleanPreferencesKey("local_history")
        private val PRIVATE = booleanPreferencesKey("private_mode")
        private val HIDE_SHORTS = booleanPreferencesKey("hide_shorts")
        private val DOWNLOAD_DIR = stringPreferencesKey("download_dir")
        private val HIDDEN_CHANNELS = stringSetPreferencesKey("hidden_channels")

        private const val BOOT_PREFS = "boot_theme"

        fun readBootTheme(context: Context): ThemeMode {
            val name = context.getSharedPreferences(BOOT_PREFS, Context.MODE_PRIVATE).getString("mode", null)
            return ThemeMode.entries.firstOrNull { it.name == name } ?: ThemeMode.System
        }

        private fun writeBootMirror(context: Context, s: AppSettings) {
            context.getSharedPreferences(BOOT_PREFS, Context.MODE_PRIVATE).edit().putString("mode", s.themeMode.name).apply()
        }
    }
}
