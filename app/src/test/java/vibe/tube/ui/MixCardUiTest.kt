package vibe.tube.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import vibe.tube.ui.components.LocalVideoActions
import vibe.tube.ui.components.NoOpVideoActions
import vibe.tube.ui.components.PlaylistCard
import vibe.tube.ui.components.PlayerLaunch
import vibe.tube.ui.components.VideoActions
import vibe.tube.youtube.model.PlaylistItem
import vibe.tube.youtube.model.Thumb

/** Tapping a Mix hands the player that very card's thumbnail (bounds and image) and plays it; a playlist still opens. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class MixCardUiTest {

    @get:Rule val rule = createComposeRule()

    private val played = mutableListOf<String>()
    private val opened = mutableListOf<String>()
    private val actions: VideoActions = object : VideoActions by NoOpVideoActions {
        override fun playMix(mix: PlaylistItem) { played += mix.id }
        override fun openPlaylist(playlistId: String, title: String?) { opened += playlistId }
    }

    private fun item(id: String, n: Int) = PlaylistItem(
        id = id, title = "List $n", ownerName = "o", ownerId = null, videoCountText = "10 videos",
        thumbnails = listOf(Thumb("https://i.ytimg.com/vi/thumb$n/hqdefault.jpg", 480, 360)), firstVideoId = "abcdefghij$n",
    )

    @Test
    fun theTappedMixIsTheOriginEvenMidList() {
        PlayerLaunch.take()
        rule.setContent {
            CompositionLocalProvider(LocalVideoActions provides actions) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PlaylistCard(item("RDabcdefghij1", 1), Modifier.width(300.dp).testTag("first"))
                    Spacer(Modifier.height(40.dp))
                    PlaylistCard(item("RDabcdefghij2", 2), Modifier.width(300.dp).testTag("second"))
                    PlaylistCard(item("PLnormal", 3), Modifier.width(300.dp).testTag("normal"))
                }
            }
        }
        rule.onNodeWithTag("second").performClick()
        assertEquals(listOf("RDabcdefghij2"), played)
        val origin = PlayerLaunch.take()
        assertNotNull(origin?.rect)
        // The second card's thumbnail: below the first card, 16:9, and its own picture for its own first video.
        assertTrue("rect ${origin!!.rect}", origin.rect!!.top > 200f)
        assertEquals(16f / 9f, origin.rect!!.width / origin.rect!!.height, 0.05f)
        assertEquals("https://i.ytimg.com/vi/thumb2/hqdefault.jpg", origin.image)
        assertEquals("abcdefghij2", origin.videoId)

        rule.onNodeWithTag("normal").performClick()
        assertEquals(listOf("PLnormal"), opened)
        assertEquals(1, played.size)
    }
}
