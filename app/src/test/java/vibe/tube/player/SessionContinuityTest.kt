@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package vibe.tube.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import vibe.tube.support.video

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionContinuityTest {
    private fun drain() { shadowOf(Looper.getMainLooper()).idle() }
    private fun await(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 3_000
        while (!condition() && System.currentTimeMillis() < until) { drain(); Thread.sleep(10) }
        assertTrue("event arrived", condition())
    }

    @Test fun listenersNeverReceiveIdleOrEmptyTimelineDuringTransition() {
        val engine = PlayerEngine(ApplicationProvider.getApplicationContext<Context>())
        val sessionPlayer = engine.sessionPlayer
        val states = mutableListOf<Int>()
        val sizes = mutableListOf<Int>()
        sessionPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) { states += playbackState }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { sizes += timeline.windowCount }
        })
        try {
            // Establish the old snapshot, as MediaSession does on connection, before observing its transition.
            assertEquals(Player.STATE_IDLE, sessionPlayer.playbackState)
            engine.beginTransition(20_000, video("next0000001"))
            engine.player.playWhenReady = false
            engine.player.stop(); engine.player.clearMediaItems()
            drain()
            assertEquals(Player.STATE_BUFFERING, sessionPlayer.playbackState)
            assertTrue(sessionPlayer.playWhenReady)
            assertEquals(1, sessionPlayer.mediaItemCount)
            assertEquals("next0000001", sessionPlayer.currentMediaItem?.mediaId)
            assertTrue(states.isNotEmpty())
            assertFalse(states.contains(Player.STATE_IDLE))
            assertFalse(states.contains(Player.STATE_ENDED))
            assertTrue(sizes.isNotEmpty())
            assertTrue(sizes.all { it > 0 })
            sessionPlayer.pause(); drain()
            assertFalse(sessionPlayer.playWhenReady)
            sessionPlayer.play(); drain()
            assertTrue(sessionPlayer.playWhenReady)
            engine.endTransition(); drain()
            assertEquals(Player.STATE_IDLE, sessionPlayer.playbackState)
        } finally { engine.release() }
    }

    @Test fun realMediaControllerRetainsActiveItemAcrossRawStopAndClear() {
        val engine = PlayerEngine(ApplicationProvider.getApplicationContext<Context>())
        val session = MediaSession.Builder(ApplicationProvider.getApplicationContext(), engine.sessionPlayer).build()
        var controller: MediaController? = null
        try {
            val future = MediaController.Builder(ApplicationProvider.getApplicationContext(), session.token).buildAsync()
            await { future.isDone }
            controller = future.get()
            engine.beginTransition(20_000, video("next0000002"))
            engine.player.stop(); engine.player.clearMediaItems()
            await { controller.playbackState == Player.STATE_BUFFERING && controller.mediaItemCount == 1 }
            assertTrue(controller.playWhenReady)
            assertEquals("next0000002", controller.currentMediaItem?.mediaId)
            controller.pause()
            await { !engine.transitionPlayWhenReady }
            controller.play()
            await { engine.transitionPlayWhenReady }
        } finally { controller?.release(); session.release(); engine.release() }
    }

    @Test fun systemNextAndPreviousStillDispatchThroughLogicalQueue() {
        val engine = PlayerEngine(ApplicationProvider.getApplicationContext<Context>())
        val queue = listOf(video("first000001"), video("second00002"), video("third000003"))
        var next = 0; var previous = 0
        engine.queueHandler = object : QueueHandler {
            override fun hasNext() = true
            override fun hasPrevious() = true
            override fun next() { next++ }
            override fun previous() { previous++ }
            override fun sessionQueue() = queue
        }
        try {
            engine.beginTransition(20_000, queue[1])
            val p = engine.sessionPlayer
            assertEquals(3, p.mediaItemCount)
            assertEquals(1, p.currentMediaItemIndex)
            p.seekToNextMediaItem(); drain()
            p.seekToPreviousMediaItem(); drain()
            assertEquals(1, next)
            assertEquals(1, previous)
        } finally { engine.release() }
    }
}
