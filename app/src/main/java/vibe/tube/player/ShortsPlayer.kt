@file:OptIn(UnstableApi::class)

package vibe.tube.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import vibe.tube.account.AuthState
import vibe.tube.account.SessionManager
import vibe.tube.data.settings.SettingsRepository
import vibe.tube.youtube.model.VideoItem
import vibe.tube.youtube.player.MediaPlanner
import vibe.tube.youtube.player.ResolveResult
import vibe.tube.youtube.player.ResolvedMedia
import vibe.tube.youtube.player.StreamResolver
import javax.inject.Inject
import javax.inject.Singleton

data class ShortsPlayState(
    val videoId: String? = null,
    val loading: Boolean = false,
    /** True once the first frame of [videoId] is on screen. */
    val ready: Boolean = false,
    val playing: Boolean = false,
    val error: Boolean = false,
    /** Width / height of the playing video (0 until known). */
    val aspect: Float = 0f,
    /** Held-down 2x playback. */
    val fast: Boolean = false,
)

/**
 * Light, separate player for the Shorts tab, built for fast starts: two small ExoPlayers take turns. While one plays the
 * short on screen (looping), the other already has the *next* short resolved, prepared and buffered, so swiping to it is
 * instant. Resolved stream URLs are also cached for a while (swiping back, retries). It never touches the main player's
 * queue; starting a short only pauses the main video.
 */
@Singleton
class ShortsPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolver: StreamResolver,
    private val session: SessionManager,
    private val settings: SettingsRepository,
    private val mainEngine: PlayerEngine,
    okHttp: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val builder = MediaSourceBuilder(okHttp)
    private val mutable = MutableStateFlow(ShortsPlayState())
    val state: StateFlow<ShortsPlayState> = mutable.asStateFlow()

    private inner class Slot {
        val player: ExoPlayer = newPlayer(this)
        var videoId: String? = null
        var client: String? = null
        var job: Job? = null
    }

    private var slotList: List<Slot>? = null
    private val slots: List<Slot> get() = slotList ?: listOf(Slot(), Slot()).also { slotList = it }
    private var currentSlot: Slot? = null
    private var current: VideoItem? = null
    private val failedClients = mutableSetOf<String>()
    private var retries = 0
    private var resumeAfterScrub = false
    /** The user tapped to pause: coming back to the tab must not start it again by itself. */
    private var userPaused = false
    private var lastScrubSeekAt = 0L

    private class Cached(val media: ResolvedMedia, val at: Long)

    private val resolved = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > CACHE_SIZE
    }

    private fun newPlayer(slot: Slot): ExoPlayer =
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    // Start as soon as a fraction of a second is buffered; a standby player fills up to the max and waits.
                    .setBufferDurationsMs(3_000, 15_000, 250, 700)
                    .setTargetBufferBytes(12 * 1024 * 1024)
                    .setBackBuffer(0, false)
                    .build(),
            )
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                setSeekParameters(SeekParameters.CLOSEST_SYNC)
                addListener(listenerFor(slot))
            }

    private fun listenerFor(slot: Slot) = object : Player.Listener {
        private fun isCurrent() = slot === currentSlot

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!isCurrent()) return
            when (playbackState) {
                Player.STATE_BUFFERING -> mutable.update { if (it.videoId != null && !it.error) it.copy(loading = true) else it }
                Player.STATE_READY -> mutable.update { it.copy(loading = false, error = false) }
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isCurrent()) mutable.update { it.copy(playing = isPlaying) }
        }

        override fun onRenderedFirstFrame() {
            if (isCurrent()) mutable.update { it.copy(ready = true, loading = false) }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (isCurrent() && videoSize.height > 0) {
                mutable.update { it.copy(aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val id = slot.videoId
            if (id != null) resolved.remove(id)
            if (!isCurrent()) {
                // A preloaded short broke: forget it; it is loaded afresh if the user does get there.
                slot.job?.cancel()
                slot.videoId = null
                return
            }
            val video = current ?: return
            if (retries < MAX_RETRIES) {
                // The stream URL was rejected or broke: try the next client profile once or twice before giving up.
                retries++
                slot.client?.let { failedClients += it }
                load(slot, video, playWhenReady = true)
            } else {
                mutable.update { it.copy(loading = false, error = true, playing = false) }
            }
        }
    }

    /** The ExoPlayer that holds [videoId] (the short on screen), for the page's video view. Null while it is not loaded. */
    fun playerFor(videoId: String): ExoPlayer? = slotList?.firstOrNull { it.videoId == videoId }?.player

    /** The player of the short on screen. */
    val currentPlayer: ExoPlayer? get() = currentSlot?.player

    /**
     * Starts [video] (the short now on screen) and prepares [next] in the background. A short that was preloaded just
     * starts; one that is already playing just resumes.
     */
    fun play(video: VideoItem, next: VideoItem? = null) {
        if (current?.id == video.id && mutable.value.error) {
            retry()
            next?.let(::preload)
            return
        }
        if (current?.id == video.id && !mutable.value.error && currentSlot?.videoId == video.id) {
            if (!userPaused) currentSlot?.player?.playWhenReady = true
            next?.let(::preload)
            return
        }
        mainEngine.pauseIfCreated()
        val all = slots
        val existing = all.firstOrNull { it.videoId == video.id }
        val slot = existing ?: all.first { it !== currentSlot }
        currentSlot?.takeIf { it !== slot }?.let { old ->
            old.player.playWhenReady = false
            old.player.setPlaybackSpeed(1f)
        }
        currentSlot = slot
        current = video
        failedClients.clear()
        retries = 0
        resumeAfterScrub = false
        userPaused = false
        slot.player.setPlaybackSpeed(1f)
        val alreadyBuffered = existing != null && slot.player.playbackState == Player.STATE_READY
        mutable.value = ShortsPlayState(videoId = video.id, loading = !alreadyBuffered)
        if (existing != null) {
            slot.player.seekTo(0)
            slot.player.playWhenReady = true
            // Frame events can be missed when the video view is attached to an already-prepared player.
            scope.launch {
                delay(700)
                val p = slot.player
                if (currentSlot === slot && mutable.value.videoId == video.id && !mutable.value.ready && p.playbackState == Player.STATE_READY && p.isPlaying) {
                    mutable.update { it.copy(ready = true, loading = false) }
                }
            }
        } else {
            load(slot, video, playWhenReady = true)
        }
        next?.let(::preload)
    }

    /** Resolves, prepares and buffers [video] in the idle player so that [play] can switch to it instantly. */
    fun preload(video: VideoItem) {
        val all = slots
        if (all.any { it.videoId == video.id }) return
        val slot = all.firstOrNull { it !== currentSlot } ?: return
        load(slot, video, playWhenReady = false)
    }

    private fun load(slot: Slot, video: VideoItem, playWhenReady: Boolean) {
        slot.job?.cancel()
        slot.videoId = video.id
        slot.player.stop()
        slot.player.clearMediaItems()
        slot.job = scope.launch {
            try {
                val media = mediaFor(video.id)
                ensureActive()
                if (slot.videoId != video.id) return@launch
                if (media == null) {
                    if (slot === currentSlot) mutable.update { it.copy(loading = false, error = true) } else slot.videoId = null
                    return@launch
                }
                // 720p-class vertical video: sharp enough for a phone, and starts quickly.
                val plan = MediaPlanner.plan(media, maxHeight = MAX_HEIGHT, allowWebm = true, maxFps = 30)
                if (plan == null) {
                    resolved.remove(video.id)
                    if (slot === currentSlot) mutable.update { it.copy(loading = false, error = true) } else slot.videoId = null
                    return@launch
                }
                slot.client = media.clientName
                slot.player.setMediaSource(builder.build(plan, media, video, emptyList()))
                slot.player.playWhenReady = playWhenReady && slot === currentSlot
                slot.player.prepare()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (slot.videoId == video.id) {
                    if (slot === currentSlot) mutable.update { it.copy(loading = false, error = true) } else slot.videoId = null
                }
            }
        }
    }

    /** Stream info for [videoId]: from the short-lived cache when the client behind it has not failed, otherwise resolved. */
    private suspend fun mediaFor(videoId: String): ResolvedMedia? {
        val now = System.currentTimeMillis()
        resolved[videoId]?.let { c ->
            if (now - c.at < CACHE_TTL_MS && c.media.clientName !in failedClients) return c.media
            resolved.remove(videoId)
        }
        val ctx = session.currentContext()
        val auth = ctx?.let { c -> session.authFor(c.accountId) }?.takeIf { session.authState.value is AuthState.Verified }
        return when (val result = resolver.resolve(videoId, settings.latest.value.contentLocale(), auth, failedClients.toSet())) {
            is ResolveResult.Playable -> result.media.also { resolved[videoId] = Cached(it, System.currentTimeMillis()) }
            is ResolveResult.Blocked -> null
        }
    }

    fun retry() {
        val video = current ?: return
        val slot = currentSlot ?: return
        retries = 0
        failedClients.clear()
        resolved.remove(video.id)
        mutable.value = ShortsPlayState(videoId = video.id, loading = true)
        load(slot, video, playWhenReady = true)
    }

    /** Pauses / resumes the short on screen. Returns true when it is playing afterwards. */
    fun toggle(): Boolean {
        val p = currentSlot?.player ?: return false
        val play = !p.playWhenReady
        p.playWhenReady = play
        userPaused = !play
        return play
    }

    /** Hold-to-speed-up: 2x while held, back to 1x on release. */
    fun setFast(fast: Boolean) {
        val p = currentSlot?.player ?: return
        p.setPlaybackSpeed(if (fast) 2f else 1f)
        mutable.update { it.copy(fast = fast) }
    }

    /** 0..1 position of the short on screen (0 while unknown). */
    fun fraction(): Float {
        val p = currentSlot?.player ?: return 0f
        val d = p.duration
        return if (d > 0 && d != C.TIME_UNSET) (p.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
    }

    fun durationMs(): Long = currentSlot?.player?.duration?.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L

    fun positionMs(): Long = currentSlot?.player?.currentPosition ?: 0L

    /** Scrubbing: the short is held still while the thumb moves, and follows it with throttled seeks. */
    fun scrubStart() {
        val p = currentSlot?.player ?: return
        resumeAfterScrub = p.playWhenReady
        p.playWhenReady = false
    }

    fun scrubTo(fraction: Float, final: Boolean) {
        val p = currentSlot?.player ?: return
        val d = durationMs()
        if (d <= 0) return
        val now = System.currentTimeMillis()
        if (final || now - lastScrubSeekAt >= SCRUB_SEEK_INTERVAL_MS) {
            lastScrubSeekAt = now
            p.seekTo((fraction.coerceIn(0f, 1f) * d).toLong())
        }
        if (final) {
            if (resumeAfterScrub) p.playWhenReady = true
            resumeAfterScrub = false
        }
    }

    fun pause() {
        slotList?.forEach { it.player.playWhenReady = false }
    }

    fun resume() {
        val slot = currentSlot ?: return
        if (!mutable.value.error && current != null && !userPaused) slot.player.playWhenReady = true
    }

    private companion object {
        const val MAX_RETRIES = 2
        const val MAX_HEIGHT = 1280
        const val CACHE_SIZE = 12
        const val CACHE_TTL_MS = 30 * 60_000L
        const val SCRUB_SEEK_INTERVAL_MS = 120L
    }
}
