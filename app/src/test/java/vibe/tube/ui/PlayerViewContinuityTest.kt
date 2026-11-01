package vibe.tube.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vibe.tube.feature.watch.LocalPlayerVideo
import vibe.tube.feature.watch.PlayerVideoHost

/** Layout switching must not detach/recreate the TextureView's PlayerView and lose its current image. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerViewContinuityTest {
    @get:Rule val rule = createComposeRule()
    private fun find(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
        return null
    }
    @Test fun movingBetweenPortraitAndFullscreenParentsRetainsTheVideoView() {
        val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
        val landscape = mutableStateOf(false)
        var root: View? = null
        try {
            rule.setContent {
                root = LocalView.current
                PlayerVideoHost {
                    val render = LocalPlayerVideo.current!!
                    if (landscape.value) Row { render(player, Modifier.size(240.dp, 120.dp), true) }
                    else Box { render(player, Modifier.size(120.dp, 200.dp), false) }
                }
            }
            rule.waitForIdle()
            val initial = find(root!!.rootView)
            assertNotNull(initial)
            repeat(3) {
                rule.runOnIdle { landscape.value = !landscape.value }
                rule.waitForIdle()
                assertSame(initial, find(root!!.rootView))
                assertSame(player, initial!!.player)
            }
        } finally { rule.runOnIdle { player.release() } }
    }
}
