@file:OptIn(UnstableApi::class)

package vibe.tube.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface QueueHandler {
    fun hasNext(): Boolean
    fun hasPrevious(): Boolean
    fun next()
    fun previous()
}

@Singleton
class PlayerEngine @Inject constructor(@ApplicationContext private val context: Context) {

    @Volatile
    var queueHandler: QueueHandler? = null

    private val playerHolder = lazy {
        ExoPlayer.Builder(context, androidx.media3.exoplayer.DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    // Smaller buffers keep memory and network wake-ups down; 60 s ahead is plenty for streaming.
                    .setBufferDurationsMs(25_000, 60_000, 500, 2_000)
                    .setTargetBufferBytes(48 * 1024 * 1024)
                    .setBackBuffer(10_000, false)
                    .build(),
            )
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
    }

    val player: ExoPlayer get() = playerHolder.value

    /** Pauses the main player without creating it when it has not been used yet (the Shorts tab does this). */
    fun pauseIfCreated() {
        if (playerHolder.isInitialized()) playerHolder.value.pause()
    }

    val sessionPlayer: Player by lazy { QueueAwarePlayer(player, this) }

    fun release() {
        runCatching { player.release() }
    }
}

private class QueueAwarePlayer(player: Player, private val engine: PlayerEngine) : ForwardingPlayer(player) {

    override fun getAvailableCommands(): Player.Commands {
        val builder = super.getAvailableCommands().buildUpon()
        val handler = engine.queueHandler
        if (handler?.hasNext() == true) {
            builder.add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        } else {
            builder.remove(Player.COMMAND_SEEK_TO_NEXT).remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }
        if (handler?.hasPrevious() == true) {
            builder.add(Player.COMMAND_SEEK_TO_PREVIOUS).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        } else {
            builder.remove(Player.COMMAND_SEEK_TO_PREVIOUS).remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        }
        return builder.build()
    }

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun hasNextMediaItem(): Boolean = engine.queueHandler?.hasNext() == true

    override fun hasPreviousMediaItem(): Boolean = engine.queueHandler?.hasPrevious() == true

    override fun seekToNext() { engine.queueHandler?.next() }

    override fun seekToNextMediaItem() { engine.queueHandler?.next() }

    override fun seekToPrevious() { engine.queueHandler?.previous() }

    override fun seekToPreviousMediaItem() { engine.queueHandler?.previous() }
}
