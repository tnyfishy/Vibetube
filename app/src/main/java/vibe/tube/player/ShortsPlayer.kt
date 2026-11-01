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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
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
import vibe.tube.youtube.model.bestFor
import vibe.tube.youtube.player.MediaPlanner
import vibe.tube.youtube.player.ResolveResult
import vibe.tube.youtube.player.ResolvedMedia
import vibe.tube.youtube.player.StreamResolver
import javax.inject.Inject
import javax.inject.Singleton

data class ShortsPlayState(
    val videoId: String? = null,
    val loading: Boolean = false,
                                                               
    val ready: Boolean = false,
    val playing: Boolean = false,
    val error: Boolean = false,
                                                               
    val aspect: Float = 0f,
                                 
    val fast: Boolean = false,
                                                                                                  
    val scrubbing: Boolean = false,
)

@Singleton
class ShortsPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val extractor: vibe.tube.youtube.extractor.NewPipeAdapter,
    private val resolver: StreamResolver,
    private val session: SessionManager,
    private val settings: SettingsRepository,
    private val mainEngine: PlayerEngine,
    private val history: vibe.tube.data.repo.LocalHistoryRepository,
    private val watchHistory: WatchHistorySync,
    okHttp: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val builder = MediaSourceBuilder(okHttp, StreamRenewer(resolver, locale = { settings.latest.value.contentLocale() }))
    init { PlayerCache.openAsync(context) }
    private val mutable = MutableStateFlow(ShortsPlayState())
    val state: StateFlow<ShortsPlayState> = mutable.asStateFlow()

    private inner class Slot {
        val player: ExoPlayer = newPlayer(this)
        var videoId: String? = null
        var client: String? = null
        var job: Job? = null
    }

    private var slotList: List<Slot>? = null
    private val slots: List<Slot> get() = slotList ?: List(SLOT_COUNT) { Slot() }.also { slotList = it }
    private var currentSlot: Slot? = null
    private var current: VideoItem? = null
    private val failedClients = mutableSetOf<String>()
    private var retries = 0
    private var resumeAfterScrub = false
                                                                                              
    private var userPaused = false
    private var foreground = false
    private var lastScrubSeekAt = 0L

    private class Cached(val media: ResolvedMedia, val at: Long)

    private val resolved = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > CACHE_SIZE
    }

    init {
        scope.launch {
            settings.latest.map { it.audioQuality }.distinctUntilChanged().drop(1).collect {
                slotList?.filter { it !== currentSlot }?.forEach { slot ->
                    slot.job?.cancel(); slot.videoId = null; slot.player.stop(); slot.player.clearMediaItems()
                }
                current?.let { video ->
                    currentSlot?.let { slot ->
                        if (slot.player.mediaItemCount > 0) load(slot, video, slot.player.playWhenReady, slot.player.currentPosition.coerceAtLeast(0))
                    }
                    preloadAll(video, lastUpcoming)
                }
            }
        }
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
                    .setBufferDurationsMs(3_000, 15_000, 250, 700)
                    .setTargetBufferBytes(20 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(true)
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
            if (id != null) { resolved.remove(id); extractor.invalidate(id) }
            val refusal = error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
            if (refusal != null && (refusal.responseCode == 403 || refusal.responseCode == 410)) {
                // A refusal before the URL's deadline is a verdict on the client, so the next short does not start from it.
                val url = refusal.dataSpec.uri.toString()
                val kind = resolver.gates.reportDenied(url)
                if (kind == vibe.tube.youtube.player.StreamDenialKind.TokenRejected) resolver.reportTokenRejected()
            }
            if (!isCurrent()) {
                slot.job?.cancel()
                slot.videoId = null
                return
            }
            val video = current ?: return
            if (retries < MAX_RETRIES) {
                retries++
                slot.client?.let { failedClients += it }
                load(slot, video, playWhenReady = true)
            } else {
                mutable.update { it.copy(loading = false, error = true, playing = false) }
            }
        }
    }

    private val pickedState = MutableStateFlow<PickedShorts?>(null)

    /** Shorts tapped outside the Shorts tab (a shelf, a search result), which lead the tab's feed until the next pick. */
    val picked: StateFlow<PickedShorts?> = pickedState.asStateFlow()

    fun pick(shorts: List<VideoItem>) {
        pickedState.value = PickedShorts(System.nanoTime(), shorts)
    }

    fun playerFor(videoId: String): ExoPlayer? = slotList?.firstOrNull { it.videoId == videoId }?.player


    fun play(video: VideoItem, upcoming: List<VideoItem> = emptyList()) {
        if (current?.id == video.id && mutable.value.error) {
            retry()
            preloadAll(video, upcoming)
            return
        }
        if (current?.id == video.id && !mutable.value.error && currentSlot?.videoId == video.id) {
            if (foreground && !userPaused) currentSlot?.player?.playWhenReady = true
            preloadAll(video, upcoming)
            return
        }
        mainEngine.pauseIfCreated()
        val all = slots
        val existing = all.firstOrNull { it.videoId == video.id }
        val wanted = (listOf(video.id) + upcoming.take(preloadCount()).map { it.id }).toSet()
        val slot = existing
            ?: all.firstOrNull { it !== currentSlot && it.videoId == null }
            ?: all.firstOrNull { it !== currentSlot && it.videoId !in wanted }
            ?: all.first { it !== currentSlot }
        currentSlot?.takeIf { it !== slot }?.let { old ->
            recordWatched(old)
            old.player.playWhenReady = false
            old.player.setPlaybackSpeed(1f)
        }
        currentSlot = slot
        current = video
        markWatchedSoon(video)
        failedClients.clear()
        retries = 0
        resumeAfterScrub = false
        userPaused = false
        slot.player.setPlaybackSpeed(1f)
        val alreadyBuffered = existing != null && slot.player.playbackState == Player.STATE_READY
        // A preloaded short already has its first frame: show it straight away instead of covering it with the poster.
        // A preloaded player will not report its video size again, so carry it over or the view falls back to letterboxing.
        val knownAspect = if (alreadyBuffered) slot.player.videoSize.let { if (it.height > 0) it.width * it.pixelWidthHeightRatio / it.height else 0f } else 0f
        mutable.value = ShortsPlayState(videoId = video.id, loading = !alreadyBuffered, ready = alreadyBuffered, aspect = knownAspect)
        if (existing != null) {
            slot.player.seekTo(0)
            slot.player.playWhenReady = foreground && !userPaused
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
        preloadAll(video, upcoming)
    }

    private fun preloadAll(video: VideoItem, upcoming: List<VideoItem>) {
        lastUpcoming = upcoming
        if (!foreground) return
        val all = slots
        val wanted = upcoming.take(preloadCount())
        val wantedIds = wanted.map { it.id }.toSet() + video.id
        for (next in wanted) {
            if (all.any { it.videoId == next.id }) continue
            val free = all.firstOrNull { it !== currentSlot && it.videoId == null }
                ?: all.firstOrNull { it !== currentSlot && it.videoId !in wantedIds }
                ?: break
            load(free, next, playWhenReady = false)
        }
        upcoming.drop(preloadCount()).take(WARM_AHEAD).forEach(::warm)
    }

    private fun warm(video: VideoItem) {
        if (resolved[video.id]?.let { System.currentTimeMillis() - it.at < CACHE_TTL_MS } == true) return
        if (!warming.add(video.id)) return
        scope.launch {
            try {
                mediaFor(video.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                warming.remove(video.id)
            }
        }
    }
    private val warming = mutableSetOf<String>()

    private fun preloadCount(): Int = if (isUnmetered()) SLOT_COUNT - 1 else 1

    private fun isUnmetered(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun cap(): vibe.tube.data.settings.QualityCap {
        val s = settings.latest.value
        return if (isUnmetered()) s.shortsWifiQuality else s.shortsMobileQuality
    }

    private fun load(slot: Slot, video: VideoItem, playWhenReady: Boolean, startPositionMs: Long = 0) {
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
                val cap = cap()
                val plan = MediaPlanner.plan(media, maxHeight = cap.maxHeight?.let { it * 16 / 9 }, preferredAudioTrack = audioTrackFor(media), allowWebm = true, maxFps = cap.maxFps, audioQuality = settings.latest.value.audioQuality, decodable = CodecSupport::canDecode)
                if (plan == null) {
                    resolved.remove(video.id)
                    if (slot === currentSlot) mutable.update { it.copy(loading = false, error = true) } else slot.videoId = null
                    return@launch
                }
                slot.client = media.clientName
                slot.player.setMediaSource(builder.build(plan, media, video), startPositionMs)
                slot.player.playWhenReady = playWhenReady && foreground && !userPaused && slot === currentSlot
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

    private suspend fun mediaFor(videoId: String): ResolvedMedia? {
        val now = System.currentTimeMillis()
        resolved[videoId]?.let { c ->
            
            val linksValid = c.media.expiresAtMillis?.let { now < it - 60_000 } ?: true
            if (now - c.at < CACHE_TTL_MS && linksValid && c.media.clientName !in failedClients) return c.media
            resolved.remove(videoId)
        }
        // Same client ladder, gates and extractor fallback as the main player.
        return when (val result = resolver.resolve(videoId, settings.latest.value.contentLocale(), null, failedClients.toSet())) {
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
        extractor.invalidate(video.id)
        mutable.value = ShortsPlayState(videoId = video.id, loading = true)
        load(slot, video, playWhenReady = true)
    }

    fun toggle(): Boolean {
        val p = currentSlot?.player ?: return false
        val play = foreground && !p.playWhenReady
        p.playWhenReady = play
        userPaused = !play
        return play
    }

    fun setFast(fast: Boolean) {
        val p = currentSlot?.player ?: return
        p.setPlaybackSpeed(if (fast) 2f else 1f)
        mutable.update { it.copy(fast = fast) }
    }

    fun fraction(): Float {
        val p = currentSlot?.player ?: return 0f
        val d = p.duration
        return if (d > 0 && d != C.TIME_UNSET) (p.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
    }

    fun durationMs(): Long = currentSlot?.player?.duration?.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L

    fun positionMs(): Long = currentSlot?.player?.currentPosition ?: 0L

    fun scrubStart() {
        val p = currentSlot?.player ?: return
        resumeAfterScrub = p.playWhenReady
        p.playWhenReady = false
        mutable.update { it.copy(scrubbing = true) }
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
            if (foreground && !userPaused && resumeAfterScrub) p.playWhenReady = true
            resumeAfterScrub = false
            mutable.update { it.copy(scrubbing = false) }
        }
    }

    /**
     * A dubbed short speaks Vietnamese when it can, else the language of the content region (the country the app
     * detected or was set to), else the app's own language; with none of those it keeps its original voice.
     */
    private fun audioTrackFor(media: ResolvedMedia): String? {
        val prefs = settings.latest.value
        val region = prefs.contentLocale().region
        val languages = listOfNotNull("vi", MediaPlanner.languageOf(region), prefs.appLanguage()).distinct()
        return MediaPlanner.audioTrackFor(media, languages)
    }

    private var watchJob: Job? = null

    /** A short counts as watched for the account once it has stayed on screen a moment; a swipe straight past does not. */
    private fun markWatchedSoon(video: VideoItem) {
        watchJob?.cancel()
        watchJob = scope.launch {
            delay(WATCHED_AFTER_MS)
            if (current?.id == video.id && foreground) watchHistory.markWatched(video.id, short = true)
        }
    }

    /** Puts the short [slot] was showing into the device history, with how far it got. */
    private fun recordWatched(slot: Slot) {
        val video = current?.takeIf { it.id == slot.videoId } ?: return
        if (!settings.latest.value.recordsLocalHistory) return
        val p = slot.player
        val duration = p.duration.takeIf { it > 0 } ?: return
        val position = p.currentPosition.coerceIn(0, duration)
        if (position < WATCHED_AFTER_MS) return
        // The reel feed names a short without its title or channel; the player's answer has both.
        val media = resolved[video.id]?.media
        val key = session.currentContext()?.scopeKey ?: vibe.tube.data.repo.FeedController.GUEST_SCOPE
        scope.launch {
            history.recordProgress(
                scopeKey = key,
                videoId = video.id,
                title = video.title.ifBlank { media?.title.orEmpty() },
                channelName = video.channelName ?: media?.author,
                thumbnailUrl = video.thumbnails.bestFor(480),
                durationSeconds = (duration / 1000).toInt(),
                positionMs = position,
                durationMs = duration,
                channelId = video.channelId,
                channelAvatar = video.channelAvatar,
            )
        }
    }

    fun pause() {
        currentSlot?.let(::recordWatched)
        foreground = false
        slotList?.forEach { it.player.playWhenReady = false }
        trimJob?.cancel()
        trimJob = scope.launch {
            delay(TRIM_AFTER_MS)
            trimIdle()
        }
    }

    private var trimJob: Job? = null
    private var lastUpcoming: List<VideoItem> = emptyList()

    private fun trimIdle() {
        slotList?.forEach { slot ->
            if (slot === currentSlot) return@forEach
            slot.job?.cancel()
            slot.player.stop()
            slot.player.clearMediaItems()
            slot.videoId = null
        }
    }

    fun resume() {
        foreground = true
        trimJob?.cancel()
        val slot = currentSlot ?: return
        current?.let { preloadAll(it, lastUpcoming) }
        if (!mutable.value.error && current != null && !userPaused) slot.player.playWhenReady = foreground && !userPaused
    }

    private companion object {
        const val MAX_RETRIES = 2
        const val WATCHED_AFTER_MS = 2_000L
        const val SLOT_COUNT = 3
        const val TRIM_AFTER_MS = 20_000L
        const val WARM_AHEAD = 3
        const val CACHE_SIZE = 12
        const val CACHE_TTL_MS = 90_000L
        const val SCRUB_SEEK_INTERVAL_MS = 120L
    }
}

/** A pick of shorts; [stamp] tells two picks of the same shorts apart, so tapping one again starts it again. */
data class PickedShorts(val stamp: Long, val shorts: List<VideoItem>)
