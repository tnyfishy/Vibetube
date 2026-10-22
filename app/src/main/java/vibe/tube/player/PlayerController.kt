@file:OptIn(UnstableApi::class)

package vibe.tube.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.HttpDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import vibe.tube.R
import vibe.tube.account.SessionManager
import vibe.tube.core.AppError
import vibe.tube.core.PlaybackFailure
import vibe.tube.data.repo.AccountActions
import vibe.tube.data.repo.FeedController
import vibe.tube.data.repo.LocalHistoryRepository
import vibe.tube.data.settings.QualityCap
import vibe.tube.data.settings.SettingsRepository
import vibe.tube.feature.common.UiEvents
import vibe.tube.youtube.YouTubeProvider
import vibe.tube.youtube.model.VideoItem
import vibe.tube.youtube.model.bestFor
import vibe.tube.youtube.player.MediaPlanner
import vibe.tube.youtube.player.ResolveResult
import vibe.tube.youtube.player.ResolvedMedia
import vibe.tube.youtube.player.StreamResolver
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: PlayerEngine,
    private val resolver: StreamResolver,
    private val session: SessionManager,
    private val settings: SettingsRepository,
    private val history: LocalHistoryRepository,
    private val provider: YouTubeProvider,
    private val actions: AccountActions,
    private val events: UiEvents,
    okHttp: OkHttpClient,
) : QueueHandler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val builder = MediaSourceBuilder(okHttp)
    private val mutable = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = mutable.asStateFlow()

    private val tuningPrefs = context.getSharedPreferences("player_tuning", Context.MODE_PRIVATE)
    private var loadJob: Job? = null
    private var tickerJob: Job? = null
    private var queueJob: Job? = null
    private var currentMedia: ResolvedMedia? = null
    private var suggestedNext: VideoItem? = null
    private var recoveredFor: String? = null
    private var avoidWebm = false
    private val failedClients = mutableSetOf<String>()
    private var lastPersist = 0L
    private var wired = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            mutable.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startTicker() else persistProgress()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> mutable.update { if (it.phase == PlayerPhase.Error) it else it.copy(phase = PlayerPhase.Buffering) }
                Player.STATE_READY -> mutable.update { it.copy(phase = PlayerPhase.Ready, durationMs = durationOf(), error = null) }
                Player.STATE_ENDED -> onEnded()
                else -> Unit
            }
        }

        override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
            mutable.update { it.copy(speed = parameters.speed, pitch = parameters.pitch) }
            if (mutable.value.tuningRemembered) saveTuning()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                val aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                mutable.update { it.copy(videoAspect = aspect, activeHeight = videoSize.height) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlayerError(error)
        }
    }

    private fun wire() {
        if (wired) return
        wired = true
        engine.queueHandler = this
        engine.player.addListener(listener)
        if (tuningPrefs.getBoolean("remember", false)) {
            engine.player.playbackParameters = PlaybackParameters(tuningPrefs.getFloat("speed", 1f), tuningPrefs.getFloat("pitch", 1f))
            mutable.update { it.copy(tuningRemembered = true, linkPitch = tuningPrefs.getBoolean("link", false)) }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                val s = mutable.value
                if (!settings.latest.value.backgroundPlayback && !s.inPip && engine.player.isPlaying) {
                    engine.player.pause()
                }
            }
        })
    }

    private fun durationOf(): Long = engine.player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L

    fun play(video: VideoItem, queue: List<VideoItem> = emptyList(), playlistId: String? = null) {
        wire()
        val list = if (queue.any { it.id == video.id }) queue else listOf(video)
        val index = list.indexOfFirst { it.id == video.id }
        queueJob?.cancel()
        mutable.update {
            it.copy(visible = true, expanded = true, queue = list, queueIndex = index, playlistId = playlistId, fullscreen = false)
        }
        load(video, startPositionMs = null)
    }

    fun playPlaylist(playlistId: String, shuffle: Boolean) {
        wire()
        queueJob?.cancel()
        queueJob = scope.launch {
            try {
                val call = actions.publicCall()
                var detail = provider.playlist(call, playlistId, null)
                var all = detail.items.filter { it.availability == vibe.tube.youtube.model.VideoAvailability.Available }
                if (all.isEmpty()) {
                    events.post(R.string.msg_playlist_nothing_playable)
                    return@launch
                }
                val firstBatch = if (shuffle) all.shuffled() else all
                play(firstBatch.first(), firstBatch, playlistId)
                var continuation = detail.continuation
                var pages = 0
                while (continuation != null && pages < MAX_QUEUE_PAGES && isActive) {
                    detail = provider.playlist(actions.publicCall(), playlistId, continuation)
                    val extra = detail.items.filter { it.availability == vibe.tube.youtube.model.VideoAvailability.Available }
                    all = all + extra
                    mutable.update { s ->
                        if (s.playlistId != playlistId) s else s.copy(queue = s.queue + if (shuffle) extra.shuffled() else extra)
                    }
                    continuation = detail.continuation
                    pages++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                events.postError(e)
            }
        }
    }

    fun offerAutoplay(video: VideoItem?) {
        suggestedNext = video
    }

    private fun scopeKey(): String = session.currentContext()?.scopeKey ?: FeedController.GUEST_SCOPE

    private fun isUnmetered(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun defaultCap(): QualityCap {
        val s = settings.latest.value
        return if (isUnmetered()) s.wifiQuality else s.mobileQuality
    }

    private fun ensureService() {
        runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
    }

    private fun load(video: VideoItem, startPositionMs: Long?, keepSelection: Boolean = false) {
        loadJob?.cancel()
        persistProgress()
        recoveredFor = if (keepSelection) recoveredFor else null
        if (!keepSelection) failedClients.clear()
        mutable.update {
            it.copy(
                current = video,
                phase = PlayerPhase.Resolving,
                error = null,
                positionMs = startPositionMs ?: 0,
                durationMs = 0,
                bufferedMs = 0,
                isLive = false,
                qualities = if (keepSelection) it.qualities else emptyList(),
                selectedHeight = if (keepSelection) it.selectedHeight else null,
                activeHeight = null,
                audioTracks = if (keepSelection) it.audioTracks else emptyList(),
                selectedAudioTrack = if (keepSelection) it.selectedAudioTrack else null,
                captions = emptyList(),
                selectedCaption = null,
                isPlaying = false,
            )
        }
        ensureService()
        loadJob = scope.launch {
            try {
                val resume = startPositionMs ?: history.resumePosition(scopeKey(), video.id)
                val ctx = session.currentContext()
                val auth = ctx?.let { c -> session.authFor(c.accountId) }?.takeIf { session.authState.value is vibe.tube.account.AuthState.Verified }
                val result = resolver.resolve(video.id, settings.latest.value.contentLocale(), auth, failedClients.toSet())
                when (result) {
                    is ResolveResult.Blocked -> fail(
                        if (failedClients.isNotEmpty()) PlaybackFailure.Expired else result.failure,
                        result.detail ?: result.clientName,
                    )
                    is ResolveResult.Playable -> startPlayback(result.media, video, resume)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.Playback) {
                fail(if (failedClients.isNotEmpty()) PlaybackFailure.Expired else e.reason, e.detail)
            } catch (e: Exception) {
                fail(if (failedClients.isNotEmpty()) PlaybackFailure.Expired else PlaybackFailure.Unavailable, e.message)
            }
        }
    }

    private fun fail(failure: PlaybackFailure, detail: String?) {
        mutable.update { it.copy(phase = PlayerPhase.Error, error = PlayerError(failure, detail), isPlaying = false) }
    }

    private fun startPlayback(media: ResolvedMedia, video: VideoItem, resumeMs: Long?) {
        currentMedia = media
        val s = mutable.value
        val cap = defaultCap()
        val effectiveCap = s.selectedHeight ?: cap.maxHeight
        // An explicit height picked in the player options sheet is not frame-rate limited.
        val effectiveFps = if (s.selectedHeight != null) null else cap.maxFps
        val plan = MediaPlanner.plan(media, effectiveCap, s.selectedAudioTrack, allowWebm = !avoidWebm, maxFps = effectiveFps)
        if (plan == null) {
            fail(
                if (media.skippedCipherFormats > 0) PlaybackFailure.CipherUnsupported else PlaybackFailure.NoCompatibleStream,
                null,
            )
            return
        }
        val prefs = settings.latest.value
        val captionPref = if (prefs.subtitlesEnabled) {
            val wanted = if (prefs.subtitleLanguage == vibe.tube.data.settings.AppSettings.SYSTEM) prefs.contentLocale().language else prefs.subtitleLanguage
            media.captions.firstOrNull { it.languageCode.startsWith(wanted) && !it.isAutoGenerated }
                ?: media.captions.firstOrNull { it.languageCode.startsWith(wanted) }
        } else null
        val source = builder.build(plan, media, video, media.captions)
        val player = engine.player
        player.setMediaSource(source, resumeMs ?: 0L)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, captionPref == null)
            .setPreferredTextLanguage(captionPref?.languageCode)
            .build()
        player.prepare()
        player.playWhenReady = true
        mutable.update {
            it.copy(
                qualities = MediaPlanner.availableHeights(media),
                activeHeight = plan.height,
                audioTracks = MediaPlanner.audioTracks(media),
                captions = media.captions,
                selectedCaption = captionPref?.languageCode,
                isLive = media.isLive,
                clientName = media.clientName,
            )
        }
    }

    private fun handlePlayerError(error: PlaybackException) {
        val video = mutable.value.current ?: return
        // A broken subtitle file (empty / unparsable response) must never end playback: drop the text track and resume.
        val renderer = error as? androidx.media3.exoplayer.ExoPlaybackException
        val textMime = renderer?.rendererFormat?.sampleMimeType.orEmpty().startsWith("text/") ||
            renderer?.rendererFormat?.sampleMimeType == androidx.media3.common.MimeTypes.APPLICATION_SUBRIP ||
            generateSequence(error.cause) { it.cause }.any { it.javaClass.name.contains("Subtitle", ignoreCase = true) || it.javaClass.name.contains("Cue", ignoreCase = true) }
        if (textMime && mutable.value.selectedCaption != null) {
            val p = engine.player
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).setPreferredTextLanguage(null).build()
            mutable.update { it.copy(selectedCaption = null) }
            p.prepare()
            p.play()
            events.post(vibe.tube.R.string.player_caption_failed)
            return
        }
        val cause = error.cause
        val badStatus = cause is HttpDataSource.InvalidResponseCodeException &&
            (cause.responseCode == 403 || cause.responseCode == 410 || cause.responseCode == 404)
        val decoder = error.errorCode in listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        )
        if (decoder && !avoidWebm) {
            avoidWebm = true
            load(video, engine.player.currentPosition.coerceAtLeast(0), keepSelection = true)
            return
        }
        if (badStatus) {
            // The stream URL was rejected: remember which client produced it and fail over to the next one.
            currentMedia?.clientName?.let { failedClients += it }
            if (failedClients.size < MAX_CLIENT_FAILOVERS) {
                recoveredFor = video.id
                load(video, engine.player.currentPosition.coerceAtLeast(0), keepSelection = true)
                return
            }
        }
        val failure = when {
            badStatus -> PlaybackFailure.Expired
            decoder -> PlaybackFailure.NoCompatibleStream
            else -> PlaybackFailure.Unavailable
        }
        val network = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        mutable.update {
            it.copy(
                phase = PlayerPhase.Error,
                error = PlayerError(failure, if (network) NETWORK_DETAIL else error.errorCodeName),
                isPlaying = false,
            )
        }
    }

    private fun onEnded() {
        persistProgress(completed = true)
        mutable.update { it.copy(phase = PlayerPhase.Ended, isPlaying = false) }
        if (mutable.value.sleepAtEnd) {
            mutable.update { it.copy(sleepAtEnd = false) }
            return
        }
        if (settings.latest.value.autoplay) next()
    }

    private var sleepJob: Job? = null

    /** Pause playback after [minutes]; null cancels. */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        mutable.update { it.copy(sleepAtEnd = false, sleepUntilMs = null) }
        if (minutes == null) return
        val until = System.currentTimeMillis() + minutes * 60_000L
        mutable.update { it.copy(sleepUntilMs = until) }
        sleepJob = scope.launch {
            kotlinx.coroutines.delay(minutes * 60_000L)
            engine.player.pause()
            mutable.update { it.copy(sleepUntilMs = null) }
        }
    }

    /** Stop once the current video finishes instead of moving to the next one. */
    fun setSleepAtEnd(on: Boolean) {
        sleepJob?.cancel()
        mutable.update { it.copy(sleepAtEnd = on, sleepUntilMs = null) }
    }

    fun retry() {
        val video = mutable.value.current ?: return
        recoveredFor = null
        avoidWebm = false
        failedClients.clear()
        load(video, startPositionMs = mutable.value.positionMs.takeIf { it > 0 })
    }

    fun togglePlay() {
        val p = engine.player
        when {
            mutable.value.phase == PlayerPhase.Ended -> { p.seekTo(0); p.play() }
            p.isPlaying -> p.pause()
            else -> p.play()
        }
    }

    fun seekTo(positionMs: Long) {
        engine.player.seekTo(positionMs)
        mutable.update { it.copy(positionMs = positionMs) }
    }

    fun seekBy(deltaMs: Long) {
        val p = engine.player
        val target = (p.currentPosition + deltaMs).coerceIn(0, durationOf().takeIf { it > 0 } ?: Long.MAX_VALUE)
        seekTo(target)
    }

    fun setSpeed(speed: Float) {
        val p = engine.player
        val v = speed.coerceIn(0.25f, 3f)
        p.playbackParameters = PlaybackParameters(v, if (mutable.value.linkPitch) v.coerceIn(0.5f, 2f) else p.playbackParameters.pitch)
    }

    /** When linked, pitch follows speed (like a tape / vinyl), so both change together. */
    fun setLinkPitch(on: Boolean) {
        mutable.update { it.copy(linkPitch = on) }
        if (on) setSpeed(engine.player.playbackParameters.speed)
    }

    fun setPitch(pitch: Float) {
        val p = engine.player
        p.playbackParameters = PlaybackParameters(p.playbackParameters.speed, pitch.coerceIn(0.5f, 2f))
    }

    fun setRememberTuning(on: Boolean) {
        mutable.update { it.copy(tuningRemembered = on) }
        if (on) saveTuning() else tuningPrefs.edit().putBoolean("remember", false).apply()
    }

    private fun saveTuning() {
        val params = engine.player.playbackParameters
        tuningPrefs.edit().putBoolean("remember", true).putFloat("speed", params.speed).putFloat("pitch", params.pitch).putBoolean("link", mutable.value.linkPitch).apply()
    }

    fun selectQuality(height: Int?) {
        val media = currentMedia ?: return
        val video = mutable.value.current ?: return
        val position = engine.player.currentPosition
        mutable.update { it.copy(selectedHeight = height) }
        startPlayback(media, video, position)
    }

    fun selectAudioTrack(id: String) {
        val media = currentMedia ?: return
        val video = mutable.value.current ?: return
        val position = engine.player.currentPosition
        mutable.update { it.copy(selectedAudioTrack = id) }
        startPlayback(media, video, position)
    }

    fun selectCaption(languageCode: String?) {
        val player = engine.player
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, languageCode == null)
            .setPreferredTextLanguage(languageCode)
            .build()
        mutable.update { it.copy(selectedCaption = languageCode) }
    }

    override fun hasNext(): Boolean = mutable.value.hasNext || suggestedNext != null

    override fun hasPrevious(): Boolean = mutable.value.hasPrevious || mutable.value.current != null

    override fun next() {
        val s = mutable.value
        when {
            s.hasNext -> play(s.queue[s.queueIndex + 1], s.queue, s.playlistId)
            suggestedNext != null -> suggestedNext?.let { play(it, emptyList(), null) }
            else -> Unit
        }
    }

    override fun previous() {
        val s = mutable.value
        if (engine.player.currentPosition > RESTART_THRESHOLD_MS || !s.hasPrevious) {
            seekTo(0)
        } else {
            play(s.queue[s.queueIndex - 1], s.queue, s.playlistId)
        }
    }

    fun playQueueIndex(index: Int) {
        val s = mutable.value
        s.queue.getOrNull(index)?.let { play(it, s.queue, s.playlistId) }
    }

    fun expand() = mutable.update { if (it.visible) it.copy(expanded = true) else it }

    fun collapse() = mutable.update { it.copy(expanded = false, fullscreen = false) }

    fun setFullscreen(value: Boolean) = mutable.update { it.copy(fullscreen = value) }

    fun setInPip(value: Boolean) = mutable.update { it.copy(inPip = value) }

    fun dismiss() {
        loadJob?.cancel()
        queueJob?.cancel()
        tickerJob?.cancel()
        persistProgress()
        runCatching {
            engine.player.stop()
            engine.player.clearMediaItems()
        }
        currentMedia = null
        suggestedNext = null
        mutable.value = PlayerUiState()
        runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                val p = engine.player
                mutable.update {
                    it.copy(
                        positionMs = p.currentPosition.coerceAtLeast(0),
                        bufferedMs = p.bufferedPosition.coerceAtLeast(0),
                        durationMs = durationOf().takeIf { d -> d > 0 } ?: it.durationMs,
                    )
                }
                val now = System.currentTimeMillis()
                if (now - lastPersist > PERSIST_INTERVAL_MS) persistProgress()
                delay(TICK_MS)
                if (!p.isPlaying && mutable.value.phase != PlayerPhase.Buffering) break
            }
        }
    }

    private fun persistProgress(completed: Boolean = false) {
        val s = mutable.value
        val video = s.current ?: return
        if (!settings.latest.value.recordsLocalHistory) return
        val p = engine.player
        val position = if (completed) durationOf() else p.currentPosition
        val duration = durationOf()
        if (duration <= 0 || s.isLive) return
        lastPersist = System.currentTimeMillis()
        val key = scopeKey()
        scope.launch {
            history.recordProgress(
                scopeKey = key,
                videoId = video.id,
                title = video.title,
                channelName = video.channelName,
                thumbnailUrl = video.thumbnails.bestFor(480),
                durationSeconds = (duration / 1000).toInt(),
                positionMs = position,
                durationMs = duration,
            )
        }
    }

    companion object {
        private const val TICK_MS = 500L
        private const val PERSIST_INTERVAL_MS = 15_000L
        private const val RESTART_THRESHOLD_MS = 3_000L
        private const val MAX_QUEUE_PAGES = 5
        const val NETWORK_DETAIL = "network"
        private const val MAX_CLIENT_FAILOVERS = 4
    }
}
