package vibe.tube.feature.watch

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
internal fun ImmersiveDialogEffect(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
        val window = (view.parent as? DialogWindowProvider)?.window
        fun hide() {
            if (enabled) window?.let {
                WindowInsetsControllerCompat(it, view).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
        val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { if (it) hide() }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        view.post { hide() }
        onDispose {
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
            if (enabled) activity?.let { WindowInsetsControllerCompat(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.systemBars()) }
        }
    }
}
