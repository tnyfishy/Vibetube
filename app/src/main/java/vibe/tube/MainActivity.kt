package vibe.tube

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import vibe.tube.data.settings.AppSettings
import vibe.tube.data.settings.SettingsRepository
import vibe.tube.data.settings.ThemeMode
import vibe.tube.feature.AppRoot
import vibe.tube.feature.AppViewModel
import vibe.tube.player.PlayerController
import vibe.tube.ui.theme.FontStyleSettings
import vibe.tube.ui.theme.VibetubeTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var controller: PlayerController
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var uiEvents: vibe.tube.feature.common.UiEvents
    @Inject lateinit var newVideoPoller: vibe.tube.notifications.NewVideoPoller
    @Inject lateinit var updatePoller: vibe.tube.update.UpdatePoller
    @Inject lateinit var updateNotifier: vibe.tube.update.UpdateNotifier

    override fun onCreate(savedInstanceState: Bundle?) {
        if (SettingsRepository.readBootTheme(this) == ThemeMode.Amoled) {
            window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false

        if (savedInstanceState == null) handleIntent(intent)
        lifecycleScope.launch {
            settingsRepository.settings.map { it.uiLanguage }.distinctUntilChanged().collect { language ->
                val wanted = if (language == AppSettings.SYSTEM) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(language)
                if (AppCompatDelegate.getApplicationLocales() != wanted) AppCompatDelegate.setApplicationLocales(wanted)
            }
        }
        lifecycleScope.launch {
            settingsRepository.settings.map { it.notifyNewVideos }.distinctUntilChanged().collect { newVideoPoller.sync(it) }
        }
        updatePoller.schedule()
        if (savedInstanceState == null) lifecycleScope.launch {
            // While the app is open, a new release shows as a snackbar instead of a system notification.
            val manifest = updateNotifier.unannounced(vibe.tube.update.UpdateNotifier.MIN_FOREGROUND_INTERVAL) ?: return@launch
            uiEvents.post(
                vibe.tube.feature.common.UiMessage(
                    R.string.update_available_message,
                    listOf(manifest.versionName),
                    actionResId = R.string.update_view,
                    onAction = uiEvents::requestOpenUpdate,
                ),
            )
        }
        lifecycleScope.launch {
            controller.state.map { it.fullscreen && it.visible && it.expanded }.distinctUntilChanged().collect { applyFullscreen(it) }
        }
        lifecycleScope.launch {
            controller.state.map { it.isPlaying && it.visible && it.expanded }.distinctUntilChanged().collect { updatePipParams(it) }
        }

        setContent {
            val appViewModel: AppViewModel = hiltViewModel()
            val settings by appViewModel.settings.collectAsStateWithLifecycle()
            vibe.tube.ui.components.ThumbnailPreference.quality = settings.thumbnailQuality
            val playerVisible by remember { controller.state.map { it.visible && !it.staged }.distinctUntilChanged() }.collectAsStateWithLifecycle(false)
            val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) lifecycleScope.launch { settingsRepository.update { it.copy(notifyNewVideos = true) } }
            }
            LaunchedEffect(playerVisible) {
                if (playerVisible && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            val fontStyle = remember(settings.font, settings.fontWeightOffset, settings.fontWidth, settings.fontRoundness, settings.fontSlant) {
                FontStyleSettings(settings.font, settings.fontWeightOffset, settings.fontWidth, settings.fontRoundness, settings.fontSlant)
            }
            VibetubeTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                palette = settings.palette,
                fontStyle = fontStyle,
                displayScale = settings.displayScale,
                textScale = settings.textScale,
            ) {
                AppRoot(onEnterPip = if (supportsPip()) ::enterPip else null)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        if (intent?.action == android.content.Intent.ACTION_VIEW) {
            vibe.tube.youtube.model.NotificationTarget.fromUrl(intent.dataString)?.let {
                uiEvents.requestOpenTarget(vibe.tube.feature.common.UiEvents.OpenTarget(it))
                intent.action = null
            }
            return
        }

        if (intent?.action == vibe.tube.download.DownloadService.ACTION_OPEN_DOWNLOADS) {
            intent.action = null
            uiEvents.requestOpenDownloads()
        } else if (intent?.action == vibe.tube.update.UpdateNotifier.ACTION_OPEN_UPDATE) {
            intent.action = null
            uiEvents.requestOpenUpdate()
        } else if (intent?.action == vibe.tube.notifications.NewVideoNotifier.ACTION_OPEN_NOTIFICATIONS) {
            intent.action = null
            uiEvents.requestOpenNotifications()
        } else if (intent?.action == vibe.tube.notifications.NewVideoNotifier.ACTION_OPEN_TARGET) {
            
            intent.action = null
            val target = vibe.tube.youtube.model.NotificationTarget.fromUrl(intent.dataString)
            if (target != null) {
                uiEvents.requestOpenTarget(
                    vibe.tube.feature.common.UiEvents.OpenTarget(
                        target,
                        title = intent.getStringExtra(vibe.tube.notifications.NewVideoNotifier.EXTRA_TITLE),
                        thumbnail = intent.getStringExtra(vibe.tube.notifications.NewVideoNotifier.EXTRA_THUMBNAIL),
                    ),
                )
            } else {
                uiEvents.requestOpenNotifications()
            }
        }
    }

    private fun supportsPip(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParams(autoEnter: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(autoEnter).setSeamlessResizeEnabled(true)
        return builder.build()
    }

    private fun updatePipParams(playing: Boolean) {
        if (!supportsPip()) return
        runCatching { setPictureInPictureParams(pipParams(playing && settingsRepository.latest.value.pictureInPicture)) }
    }

    private fun enterPip() {
        if (supportsPip()) runCatching { enterPictureInPictureMode(pipParams(true)) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val s = controller.state.value
        if (s.visible && s.expanded && s.isPlaying && settingsRepository.latest.value.pictureInPicture) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        controller.setInPip(isInPictureInPictureMode)
    }

    private var portraitHold: android.view.OrientationEventListener? = null

    private fun autoRotateEnabled(): Boolean =
        runCatching { android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION) == 1 }.getOrDefault(false)

    // With auto-rotate on, releasing to UNSPECIFIED while the device is still held sideways keeps the
    // window landscape, so the player keeps rendering as fullscreen and exit never happens. Pin portrait
    // until the device is physically upright again, then hand orientation back to the sensor.
    private fun holdPortraitUntilUpright() {
        releasePortraitHold()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val listener = object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                if (degrees <= 25 || degrees >= 335) {
                    releasePortraitHold()
                    if (!controller.state.value.fullscreen) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
        if (listener.canDetectOrientation()) {
            portraitHold = listener
            listener.enable()
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun releasePortraitHold() {
        portraitHold?.disable()
        portraitHold = null
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotating a phone to landscape while the player is open enters real fullscreen so the
        // system bars are hidden and the exit controls are wired to an actual fullscreen state.
        val s = controller.state.value
        if (newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
            newConfig.screenHeightDp < 480 && portraitHold == null && autoRotateEnabled() &&
            s.visible && s.expanded && !s.fullscreen && !s.inPip
        ) controller.setFullscreen(true)
    }

    override fun onDestroy() {
        releasePortraitHold()
        super.onDestroy()
    }

    private var wasFullscreen = false

    private fun applyFullscreen(fullscreen: Boolean) {
        val leaving = wasFullscreen && !fullscreen
        wasFullscreen = fullscreen
        val insets = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen) {
            releasePortraitHold()
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insets.show(WindowInsetsCompat.Type.systemBars())
            val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            if (leaving && autoRotateEnabled() && (landscape || requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) && !isInPictureInPictureMode) {
                holdPortraitUntilUpright()
            } else {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
}
