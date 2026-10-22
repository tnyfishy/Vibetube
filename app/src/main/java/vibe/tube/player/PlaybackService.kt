@file:OptIn(UnstableApi::class)

package vibe.tube.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import vibe.tube.MainActivity
import javax.inject.Inject

@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var engine: PlayerEngine

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val activityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, engine.sessionPlayer)
            .setSessionActivity(activityIntent)
            .build()
        // Registering the session here is what makes the service post the media-control notification itself,
        // without waiting for an external controller to bind.
        session?.let(::addSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player: Player = engine.player
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            release()
        }
        session = null
        super.onDestroy()
    }
}
