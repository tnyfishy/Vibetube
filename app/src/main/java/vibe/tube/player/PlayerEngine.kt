@file:OptIn(UnstableApi::class)

package vibe.tube.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import android.net.Uri
import vibe.tube.youtube.model.VideoItem
import vibe.tube.youtube.model.bestFor
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
    fun sessionQueue(): List<VideoItem> = emptyList()
    fun sessionPlayWhenReady(value: Boolean) {}
    fun stopPlayback() {}
}

@Singleton
class PlayerEngine @Inject constructor(@ApplicationContext private val context: Context) {

    @Volatile
    var queueHandler: QueueHandler? = null

    val equalizer = vibe.tube.player.audio.eq.EqualizerAudioProcessor()

    /** Small-heap and low-RAM devices get a shorter, smaller read-ahead so the buffer does not compete with the app for memory. */
    private val lowMemory: Boolean = run {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        am != null && (am.isLowRamDevice || am.memoryClass < 192)
    }

    private val playerHolder = lazy {
        ExoPlayer.Builder(context, object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    .setEnableFloatOutput(false)
                    .setAudioProcessors(arrayOf(equalizer)).build()
        }.setEnableDecoderFallback(true))
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
                    .setBufferDurationsMs(25_000, if (lowMemory) 30_000 else 60_000, 500, 2_000)
                    .setTargetBufferBytes(if (lowMemory) 24 * 1024 * 1024 else 48 * 1024 * 1024)
                    .setBackBuffer(10_000, false)
                    .build(),
            )
            .setPauseAtEndOfMediaItems(false)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build().apply {
                
                preloadConfiguration = ExoPlayer.PreloadConfiguration(5_000_000L)
            }
    }

    val player: ExoPlayer get() = playerHolder.value

    fun pauseIfCreated() {
        if (playerHolder.isInitialized()) playerHolder.value.pause()
    }

    private val sessionHolder = lazy { QueueAwarePlayer(player, this) }
    val sessionPlayer: Player get() = sessionHolder.value
    internal fun refreshSession() { if (sessionHolder.isInitialized()) sessionHolder.value.refresh() }
    internal var transitionItem: MediaItem? = null
        private set
    internal var transitionPlayWhenReady = true
        private set
    fun setTransitionPlayWhenReady(value: Boolean) {
        transitionPlayWhenReady = value
        refreshSession()
    }

    @Volatile
    var inTransition: Boolean = false
        private set

    private val wakeLock by lazy {
        context.getSystemService(android.os.PowerManager::class.java)
            ?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "vibetube:next-stream")
            ?.apply { setReferenceCounted(false) }
    }
    private val wifiLock by lazy {
        @Suppress("DEPRECATION")
        context.getSystemService(android.net.wifi.WifiManager::class.java)
            ?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "vibetube:next-stream")
            ?.apply { setReferenceCounted(false) }
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val releaseLocks = Runnable { endTransition() }

    fun beginTransition(maxMs: Long, video: VideoItem? = null, playWhenReady: Boolean = true) {
        transitionItem = video?.let {
            MediaItem.Builder().setMediaId(it.id).setMediaMetadata(MediaMetadata.Builder()
                .setTitle(it.title).setArtist(it.channelName)
                .setArtworkUri(it.thumbnails.bestFor(480)?.let(Uri::parse)).build()).build()
        } ?: player.currentMediaItem ?: MediaItem.Builder().setMediaId("vibetube:pending").build()
        transitionPlayWhenReady = playWhenReady
        inTransition = true
        refreshSession()
        runCatching { wakeLock?.acquire(maxMs) }
        runCatching { wifiLock?.acquire() }
        main.removeCallbacks(releaseLocks)
        main.postDelayed(releaseLocks, maxMs)
    }

    fun endTransition() {
        main.removeCallbacks(releaseLocks)
        if (!inTransition) return
        inTransition = false
        transitionItem = null
        refreshSession()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
    }

    fun release() {
        endTransition()
        if (playerHolder.isInitialized()) runCatching { playerHolder.value.release() }
    }
}

internal class QueueAwarePlayer(player: Player, private val engine: PlayerEngine) : ForwardingSimpleBasePlayer(player) {
    fun refresh() = invalidateState()

    override fun getState(): SimpleBasePlayer.State {
        val delegate = super.getState()
        val commands = delegate.availableCommands.buildUpon()
        if (engine.queueHandler?.hasNext() == true) commands.add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        else commands.remove(Player.COMMAND_SEEK_TO_NEXT).remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        if (engine.queueHandler?.hasPrevious() == true) commands.add(Player.COMMAND_SEEK_TO_PREVIOUS).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        else commands.remove(Player.COMMAND_SEEK_TO_PREVIOUS).remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        
        commands.remove(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
        val builder = delegate.buildUpon().setAvailableCommands(commands.build())
        if (engine.inTransition) {
            val item = engine.transitionItem!!
            
            builder.setPlaylist(listOf(SimpleBasePlayer.MediaItemData.Builder("pending:" + item.mediaId).setMediaItem(item).setMediaMetadata(item.mediaMetadata).build()))
                .setCurrentMediaItemIndex(0).setContentPositionMs(0L).setContentBufferedPositionMs { 0L }
                .setTotalBufferedDurationMs { 0L }
                .setPlaybackState(Player.STATE_BUFFERING).setPlayerError(null).setIsLoading(true)
                .setPlayWhenReady(engine.transitionPlayWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        }
        val queue = engine.queueHandler?.sessionQueue().orEmpty()
        val currentItem = if (engine.inTransition) engine.transitionItem else delegate.playlist.getOrNull(delegate.currentMediaItemIndex)?.mediaItem
        val index = queue.indexOfFirst { it.id == currentItem?.mediaId }
        if (index >= 0) {
            val currentData = if (engine.inTransition) {
                SimpleBasePlayer.MediaItemData.Builder("pending:" + currentItem!!.mediaId).setMediaItem(currentItem).setMediaMetadata(currentItem.mediaMetadata).build()
            } else delegate.playlist[delegate.currentMediaItemIndex]
            builder.setPlaylist(queue.map { video ->
                if (video.id == currentItem?.mediaId) currentData else {
                    val item = MediaItem.Builder().setMediaId(video.id).setMediaMetadata(MediaMetadata.Builder()
                        .setTitle(video.title).setArtist(video.channelName).setArtworkUri(video.thumbnails.bestFor(480)?.let(Uri::parse)).build()).build()
                    SimpleBasePlayer.MediaItemData.Builder("queue:" + video.id).setMediaItem(item).setMediaMetadata(item.mediaMetadata)
                        .setDurationUs(video.durationSeconds?.toLong()?.times(1_000_000L) ?: C.TIME_UNSET).build()
                }
            }).setCurrentMediaItemIndex(index)
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): com.google.common.util.concurrent.ListenableFuture<*> {
        if (engine.inTransition) engine.setTransitionPlayWhenReady(playWhenReady)
        engine.queueHandler?.sessionPlayWhenReady(playWhenReady)
        return super.handleSetPlayWhenReady(playWhenReady)
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): com.google.common.util.concurrent.ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> engine.queueHandler?.next()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> engine.queueHandler?.previous()
            else -> return super.handleSeek(player.currentMediaItemIndex, positionMs, seekCommand)
        }
        return com.google.common.util.concurrent.Futures.immediateVoidFuture()
    }

    override fun handleStop(): com.google.common.util.concurrent.ListenableFuture<*> {
        engine.queueHandler?.stopPlayback()
        return super.handleStop()
    }
}
