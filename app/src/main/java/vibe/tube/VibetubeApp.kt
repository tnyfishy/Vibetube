package vibe.tube

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import vibe.tube.account.SessionManager
import vibe.tube.core.ApplicationScope
import vibe.tube.data.repo.FeedCache
import vibe.tube.data.settings.SettingsRepository
import vibe.tube.data.settings.ThemeMode
import javax.inject.Inject

@HiltAndroidApp
class VibetubeApp : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var feedCache: FeedCache
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(
            when (SettingsRepository.readBootTheme(this)) {
                ThemeMode.System -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                ThemeMode.Light -> AppCompatDelegate.MODE_NIGHT_NO
                ThemeMode.Dark, ThemeMode.Amoled -> AppCompatDelegate.MODE_NIGHT_YES
            },
        )
        runBlocking { settings.prime() }
        sessionManager.restore()
        appScope.launch {
            feedCache.purgeOlderThan(System.currentTimeMillis() - CACHE_RETENTION_MS)
            if (sessionManager.currentSession() != null) sessionManager.verifyActive()
        }
    }

    /** Image cache tuned for scrolling feeds: moderate RAM share, no global crossfade (lists opt in per request). */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .build()

    private companion object {
        const val CACHE_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }
}
