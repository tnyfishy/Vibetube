@file:OptIn(UnstableApi::class)

package vibe.tube.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
)

/**
 * A light, separate player for the Shorts tab: one ExoPlayer that is pointed at whichever short is on screen (looping),
 * with small buffers. It never touches the main player's queue; starting a short only pauses the main video.
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

    private var job: Job? = null
    private var current: VideoItem? = null
    private var currentClient: String? = null
    private val failedClients = mutableSetOf<String>()
    private var retries = 0

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> mutable.update { if (it.videoId != null && !it.error) it.copy(loading = true) else it }
                Player.STATE_READY -> mutable.update { it.copy(loading = false, error = false) }
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            mutable.update { it.copy(playing = isPlaying) }
        }

        override fun onRenderedFirstFrame() {
            mutable.update { it.copy(ready = true, loading = false) }
        }

        override fun onPlayerError(error: PlaybackException) {
            val video = current ?: return
            if (retries < MAX_RETRIES) {
                // The stream URL was rejected or broke: try the next client profile once or twice before giving up.
                retries++
                currentClient?.let { failedClients += it }
                start(video)
            } else {
                mutable.update { it.copy(loading = false, error = true, playing = false) }
            }
        }
    }

    val player: ExoPlayer by lazy {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(6_000, 20_000, 400, 1_200)
                    .setTargetBufferBytes(16 * 1024 * 1024)
                    .setBackBuffer(0, false)
                    .build(),
            )
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                addListener(listener)
            }
    }

    /** Starts [video] (the short now on screen). Asking for the one that is already loaded just resumes it. */
    fun play(video: VideoItem) {
        if (mutable.value.videoId == video.id && !mutable.value.error) {
            player.play()
            return
        }
        job?.cancel()
        failedClients.clear()
        retries = 0
        current = video
        mainEngine.pauseIfCreated()
        mutable.value = ShortsPlayState(videoId = video.id, loading = true)
        player.stop()
        player.clearMediaItems()
        start(video)
    }

    private fun start(video: VideoItem) {
        job?.cancel()
        job = scope.launch {
            try {
                val ctx = session.currentContext()
                val auth = ctx?.let { c -> session.authFor(c.accountId) }?.takeIf { session.authState.value is AuthState.Verified }
                val result = resolver.resolve(video.id, settings.latest.value.contentLocale(), auth, failedClients.toSet())
                kotlin.coroutines.coroutineContext.ensureActive()
                if (current?.id != video.id) return@launch
                when (result) {
                    is ResolveResult.Blocked -> mutable.update { it.copy(loading = false, error = true) }
                    is ResolveResult.Playable -> {
                        val media = result.media
                        currentClient = media.clientName
                        val plan = MediaPlanner.plan(media, maxHeight = 1080, allowWebm = true, maxFps = 30)
                        if (plan == null) {
                            mutable.update { it.copy(loading = false, error = true) }
                        } else {
                            player.setMediaSource(builder.build(plan, media, video, emptyList()))
                            player.prepare()
                            player.playWhenReady = true
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(loading = false, error = true) }
            }
        }
    }

    fun retry() {
        current?.let { video ->
            retries = 0
            failedClients.clear()
            mutable.value = ShortsPlayState(videoId = video.id, loading = true)
            player.stop()
            start(video)
        }
    }

    fun toggle() {
        val p = player
        if (p.isPlaying) p.pause() else p.play()
    }

    fun pause() {
        player.playWhenReady = false
    }

    fun resume() {
        if (current != null && !mutable.value.error) player.playWhenReady = true
    }

    private companion object {
        const val MAX_RETRIES = 2
    }
}
