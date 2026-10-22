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
    private var refreshPreference = vibe.tube.ui.RefreshRate.MAX

    override fun onResume() {
        super.onResume()
        // Display modes can change while we are in the background (battery saver, external display).
        vibe.tube.ui.RefreshRate.apply(window, this, refreshPreference)
    }

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

        lifecycleScope.launch {
            settingsRepository.settings.map { it.uiLanguage }.distinctUntilChanged().collect { language ->
                val wanted = if (language == AppSettings.SYSTEM) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(language)
                if (AppCompatDelegate.getApplicationLocales() != wanted) AppCompatDelegate.setApplicationLocales(wanted)
            }
        }
        lifecycleScope.launch {
            settingsRepository.settings.map { it.refreshRate }.distinctUntilChanged().collect { refresh ->
                refreshPreference = refresh
                vibe.tube.ui.RefreshRate.apply(window, this@MainActivity, refresh)
            }
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
            val playerState by controller.state.collectAsStateWithLifecycle()
            val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            LaunchedEffect(playerState.visible) {
                if (playerState.visible && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            val fontStyle = remember(settings.font, settings.fontWeightOffset, settings.fontWidth, settings.fontRoundness) {
                FontStyleSettings(settings.font, settings.fontWeightOffset, settings.fontWidth, settings.fontRoundness)
            }
            VibetubeTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor, fontStyle = fontStyle) {
                AppRoot(onEnterPip = if (supportsPip()) ::enterPip else null)
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

    private fun applyFullscreen(fullscreen: Boolean) {
        val insets = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
