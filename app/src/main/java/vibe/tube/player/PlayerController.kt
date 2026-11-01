@file:OptIn(UnstableApi::class)

package vibe.tube.player
import kotlinx.coroutines.flow.drop

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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
import vibe.tube.data.repo.ResumeRecord
import vibe.tube.data.repo.ResumeStore
import vibe.tube.data.settings.QualityCap
import vibe.tube.data.settings.SettingsRepository
import vibe.tube.feature.common.UiEvents
import vibe.tube.youtube.YouTubeProvider
import vibe.tube.youtube.model.VideoItem
import vibe.tube.youtube.model.bestFor
import vibe.tube.youtube.model.mixFirstVideoId
import vibe.tube.youtube.player.CaptionTrack
import vibe.tube.youtube.player.MediaPlanner
import vibe.tube.youtube.player.ResolveResult
import vibe.tube.youtube.player.ResolvedMedia
import vibe.tube.youtube.player.StreamDenial
import vibe.tube.youtube.player.PlanKind
import vibe.tube.youtube.player.MediaPlan
import vibe.tube.youtube.player.StreamDenialClassifier
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
    private val resumeStore: ResumeStore,
    private val provider: YouTubeProvider,
    private val actions: AccountActions,
    private val events: UiEvents,
    private val watchHistory: WatchHistorySync,
    okHttp: OkHttpClient,
    poTokens: vibe.tube.youtube.potoken.PoTokenMinter,
) : QueueHandler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val builder = MediaSourceBuilder(okHttp, StreamRenewer(resolver, locale = { settings.latest.value.contentLocale() }, auth = {
        session.currentContext()?.let { c -> session.authFor(c.accountId) }?.takeIf { session.authState.value is vibe.tube.account.AuthState.Verified }
    }), poTokens, vibe.tube.player.sabr.integration.SabrReloader { videoId, event, height, codec, cpn, clientId ->
        resolver.resolveSabr(videoId, settings.latest.value.contentLocale(), event.reloadToken, cpn, clientId)
            ?.select(height.takeIf { it > 0 }, codec)
    })
    private var currentPlan: MediaPlan? = null
    init {
        PlayerCache.openAsync(context)
        // A SABR session that cannot recover is a verdict on the session, not on the client: re-resolve without it.
        builder.onSabrFailed = {
            scope.launch {
                val video = mutable.value.current
                if (video != null && currentPlan?.kind == PlanKind.Sabr && mutable.value.phase != PlayerPhase.Error) {
                    failedClients += StreamResolver.SABR_CLIENT
                    log.warn(requestSeq, video.id, "sabr-failed")
                    recover(video, engine.player.currentPosition.coerceAtLeast(0), "sabr-failed")
                }
            }
        }
    }
    private val mutable = MutableStateFlow(PlayerUiState(ambientFullscreen = settings.latest.value.ambientFullscreen))
    val state: StateFlow<PlayerUiState> = mutable.asStateFlow()

    private val tuningPrefs = context.getSharedPreferences("player_tuning", Context.MODE_PRIVATE)
    private val effects = AudioEffects(context.getSharedPreferences("audio_effects", Context.MODE_PRIVATE))
                                                                                                          
    val autoplay: StateFlow<Boolean> = settings.latest
        .map { it.autoplay }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, settings.latest.value.autoplay)

    init {
        effects.setPreset(effects.preset)
        effects.processor = engine.equalizer
        scope.launch { settings.latest.map { it.ambientFullscreen }.distinctUntilChanged().collect { value ->
            mutable.update { it.copy(ambientFullscreen = value) }
        } }

    }

    fun setAmbientFullscreen(enabled: Boolean) {
        mutable.update { it.copy(ambientFullscreen = enabled) }
        scope.launch { settings.update { it.copy(ambientFullscreen = enabled) } }
    }

    fun setAutoplay(on: Boolean) {
        invalidateNext()
        scope.launch { settings.update { it.copy(autoplay = on) } }
    }

    private var loadJob: Job? = null
    private var watchdogJob: Job? = null
    private var stallRetries = 0
    private var startedOk = false
    private var rebufferJob: Job? = null
    private var midSwitches = 0
    private var lastMidSwitchAt = 0L
    private var resumePaused = false
    private var tickerJob: Job? = null
    private var queueJob: Job? = null
    private var currentMedia: ResolvedMedia? = null
    private var suggestedNext: VideoItem? = null
    private var recoveredFor: String? = null
    private var avoidWebm = false
    private val captionLoader = CaptionLoader(okHttp)
    private var captionJob: Job? = null
                                                                                                                   
    private var captionsChosenFor: String? = null
    private val captionCache = object : LinkedHashMap<String, List<CaptionCue>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<CaptionCue>>?): Boolean = size > 6
    }
    private val mutableCues = MutableStateFlow<List<CaptionCue>>(emptyList())
                                                                                              
    val captionCues: StateFlow<List<CaptionCue>> = mutableCues.asStateFlow()
    private val failedClients = mutableSetOf<String>()
    private var lastPersist = 0L
    private var wired = false
                                                                                                    
    private var requestSeq = 0L
                                                                                                                      
    private var recoveries = 0
                                                                                                             
    private var retryWhenOnline = false
    private val log = PlaybackLog()

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            promoteQueuedNext(mediaItem, reason)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying != engine.player.isPlaying) return
            mutable.update { it.copy(isPlaying = isPlaying) }
            
            if (isPlaying && engine.player.volume < 1f) engine.player.volume = 1f
            if (isPlaying) startTicker() else persistProgress()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != engine.player.playbackState) return
            if (playbackState == Player.STATE_READY && engine.player.currentMediaItem?.mediaId != mutable.value.current?.id) return
            if (playbackState == Player.STATE_READY && !startedOk) {
                watchdogJob?.cancel()
                mutable.value.current?.let {
                    log.step(requestSeq, it.id, "ready", "client=${currentMedia?.clientName} height=${mutable.value.activeHeight}")
                    watchHistory.markWatched(it.id, short = it.isShort)
                }
            }
            if (playbackState == Player.STATE_READY) {
                startedOk = true
                engine.endTransition()
            }
            if (playbackState == Player.STATE_BUFFERING && startedOk) watchRebuffer() else rebufferJob?.cancel()
            when (playbackState) {
                
                Player.STATE_BUFFERING -> mutable.update {
                    if (it.phase == PlayerPhase.Error || (it.phase == PlayerPhase.Recovering && !startedOk)) it else it.copy(phase = PlayerPhase.Buffering)
                }
                Player.STATE_READY -> mutable.update { it.copy(phase = PlayerPhase.Ready, durationMs = durationOf(), error = null) }
                Player.STATE_ENDED -> onEnded()
                else -> Unit
            }
        }

        override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
            mutable.update { it.copy(speed = parameters.speed, pitch = parameters.pitch) }
            if (mutable.value.tuningRemembered) saveTuning()
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            effects.attach(audioSessionId)
            mutable.update { it.copy(effectsAvailable = effects.available) }
        }

        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {
            mutable.update { it.copy(skipSilence = skipSilenceEnabled) }
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
        scope.launch { mutable.map { it.queue to it.queueIndex }.distinctUntilChanged().collect { engine.refreshSession() } }
        mutable.update { it.copy(eqPreset = effects.preset, bassBoost = effects.bassStrength, loudnessMb = effects.gainMb) }
        engine.player.skipSilenceEnabled = tuningPrefs.getBoolean("skip_silence", false)
        mutable.update { it.copy(skipSilence = engine.player.skipSilenceEnabled) }
        effects.attach(engine.player.audioSessionId)
        mutable.update { it.copy(effectsAvailable = effects.available) }
        if (tuningPrefs.getBoolean("remember", false)) {
            engine.player.playbackParameters = PlaybackParameters(tuningPrefs.getFloat("speed", 1f), tuningPrefs.getFloat("pitch", 1f))
            mutable.update { it.copy(tuningRemembered = true, linkPitch = tuningPrefs.getBoolean("link", false)) }
        }
        registerHeadphoneAutoplay()
        registerNetworkRecovery()
        scope.launch {
            settings.latest.map { it.audioQuality }.distinctUntilChanged().drop(1).collect {
                invalidateNext()
                val video = mutable.value.current
                val media = currentMedia
                if (video != null && media != null && mutable.value.phase == PlayerPhase.Ready) {
                    resumePaused = !engine.player.playWhenReady
                    val position = engine.player.currentPosition.coerceAtLeast(0)
                    engine.beginTransition(25_000L, video, !resumePaused)
                    startPlayback(media, video, position)
                }
            }
        }
        scope.launch {
            settings.latest.map { listOf(it.autoplay, it.wifiQuality, it.mobileQuality, it.audioQuality, it.contentLanguage, it.contentRegion) }.distinctUntilChanged().collect { invalidateNext() }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                persistProgress(sync = true)
                val s = mutable.value
                if (!settings.latest.value.backgroundPlayback && !s.inPip && engine.player.isPlaying) {
                    engine.player.pause()
                }
            }
        })
    }

    private fun registerHeadphoneAutoplay() {
        val audio = context.getSystemService(android.media.AudioManager::class.java) ?: return
        val registeredAt = android.os.SystemClock.elapsedRealtime()
        audio.registerAudioDeviceCallback(
            object : android.media.AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>) {
                    if (android.os.SystemClock.elapsedRealtime() - registeredAt < 1500) return
                    if (!settings.latest.value.autoplayOnHeadphones) return
                    if (addedDevices.none { it.isSink && it.type in HEADPHONE_TYPES }) return
                    val s = mutable.value
                    if (s.visible && s.current != null && s.error == null && s.phase == PlayerPhase.Ready && !engine.player.isPlaying) {
                        engine.player.play()
                    }
                }
            },
            android.os.Handler(android.os.Looper.getMainLooper()),
        )
    }

    private fun registerNetworkRecovery() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: android.net.Network, caps: NetworkCapabilities) {
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                    main.post {
                        val s = mutable.value
                        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
                        if (retryWhenOnline && s.visible && s.phase == PlayerPhase.Error && s.error?.failure == PlaybackFailure.Network &&
                            (foreground || settings.latest.value.backgroundPlayback)
                        ) {
                            retryWhenOnline = false
                            s.current?.let { log.step(requestSeq, it.id, "network-back", "retrying") }
                            retry()
                        }
                    }
                }
            })
        }
    }

    private fun durationOf(): Long = engine.player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L

    fun play(video: VideoItem, queue: List<VideoItem> = emptyList(), playlistId: String? = null, startAtMs: Long? = null) {
        queueJob?.cancel()
        startQueue(video, queue, playlistId, startAtMs = startAtMs)
    }

    private fun startQueue(video: VideoItem, queue: List<VideoItem>, playlistId: String?, title: String? = null, startAtMs: Long? = null, expand: Boolean = true) {
        wire()
        pendingMix = null
        val now = mutable.value
        if (now.current?.id != video.id) suggestedNext = null
        if (now.visible && !now.staged && now.current?.id == video.id && playlistId == null) {
            vibe.tube.ui.components.PlayerLaunch.clear()

            mutable.update { it.copy(expanded = expand, fullscreen = false) }
            if (now.phase == PlayerPhase.Error || (now.phase == PlayerPhase.Idle && engine.player.mediaItemCount == 0)) retry()
            else if (startAtMs != null) seekTo(startAtMs)
            return
        }
        leaveStaged()
        val list = if (queue.any { it.id == video.id }) queue else listOf(video)
        val index = list.indexOfFirst { it.id == video.id }
        mutable.update {
            it.copy(
                visible = true, expanded = expand, queue = list, queueIndex = index, playlistId = playlistId, fullscreen = false,
                queueTitle = title.takeIf { playlistId != null }, unshuffledQueue = null,
            )
        }
        load(video, startPositionMs = startAtMs, reason = "tap")
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
                startQueue(firstBatch.first(), firstBatch, playlistId, detail.title.takeIf { it.isNotBlank() })
                if (shuffle) mutable.update { s -> if (s.playlistId != playlistId) s else s.copy(unshuffledQueue = all) }
                var continuation = detail.continuation
                var pages = 0
                while (continuation != null && pages < MAX_QUEUE_PAGES && isActive) {
                    detail = provider.playlist(actions.publicCall(), playlistId, continuation)
                    val extra = detail.items.filter { it.availability == vibe.tube.youtube.model.VideoAvailability.Available }
                    all = all + extra
                    mutable.update { s -> if (s.playlistId != playlistId) s else QueueOps.append(s, extra) }
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

    private var pendingMix: vibe.tube.youtube.model.PlaylistItem? = null

    fun playMix(mix: vibe.tube.youtube.model.PlaylistItem) {
        wire()
        val now = mutable.value
        if (now.visible && !now.staged && now.playlistId == mix.id && now.phase != PlayerPhase.Error) {
            mutable.update { it.copy(expanded = true, fullscreen = false) }
            return
        }
        suggestedNext = null
        queueJob?.cancel()
        val seed = mix.mixFirstVideoId
        val placeholder = VideoItem(
            id = seed ?: mix.id,
            title = mix.title,
            channelName = mix.ownerName,
            channelId = null,
            channelAvatar = null,
            thumbnails = mix.thumbnails,
            durationText = null,
            durationSeconds = null,
            viewCountText = null,
            publishedText = null,
        )
        leaveStaged()
        mutable.update {
            it.copy(
                visible = true, expanded = true, queue = listOf(placeholder), queueIndex = 0, playlistId = mix.id, fullscreen = false,
                queueTitle = mix.title.takeIf { t -> t.isNotBlank() }, unshuffledQueue = null,
            )
        }
        pendingMix = if (seed == null) mix else null
        load(placeholder, startPositionMs = null, reason = "mix", resolve = seed != null)
        queueJob = scope.launch { fillMixQueue(mix, placeholder, seed) }
    }

    private suspend fun fillMixQueue(mix: vibe.tube.youtube.model.PlaylistItem, placeholder: VideoItem, seed: String?) {
        try {
            var detail = provider.playlist(actions.publicCall(), mix.id, null)
            val items = detail.items.filter { it.availability == vibe.tube.youtube.model.VideoAvailability.Available }
            if (mutable.value.playlistId != mix.id) return
            if (seed == null) {
                val first = items.firstOrNull()
                if (first == null) {
                    fail(requestSeq, placeholder, PlaybackFailure.Unavailable, "empty_mix")
                    return
                }
                if (mutable.value.current?.id != placeholder.id) return
                pendingMix = null
                mutable.update { it.copy(queue = items.distinctBy { v -> v.id }, queueIndex = 0) }
                load(first, startPositionMs = null, reason = "mix")
            } else {
                
                val list = (if (items.firstOrNull()?.id == seed) items else listOf(placeholder) + items.filter { it.id != seed }).distinctBy { it.id }
                mutable.update { s ->
                    if (s.playlistId != mix.id) return@update s
                    val index = list.indexOfFirst { it.id == s.current?.id }
                    if (index < 0) s else s.copy(queue = list, queueIndex = index, current = list[index])
                }
            }
            var continuation = detail.continuation
            var pages = 0
            while (continuation != null && pages < MAX_QUEUE_PAGES && kotlin.coroutines.coroutineContext.isActive) {
                detail = provider.playlist(actions.publicCall(), mix.id, continuation)
                val extra = detail.items.filter { it.availability == vibe.tube.youtube.model.VideoAvailability.Available }
                mutable.update { s -> if (s.playlistId != mix.id) s else QueueOps.append(s, extra) }
                continuation = detail.continuation
                pages++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            
            if (seed == null && mutable.value.current?.id == placeholder.id) {
                fail(requestSeq, placeholder, if (e is AppError.Network && e.httpCode == null) PlaybackFailure.Network else PlaybackFailure.Unavailable, e.message)
            }
        }
    }

    fun offerAutoplay(video: VideoItem?, forVideoId: String? = mutable.value.current?.id) {
        if (forVideoId != mutable.value.current?.id) return
        suggestedNext = video?.takeIf { it.id != forVideoId }
        if (pendingNext?.target?.id != upcoming()?.id) invalidateNext()
        engine.refreshSession()
    }

    private data class NextResolve(
        val owner: Long, val sourceId: String, val target: VideoItem, val scopeKey: String,
        val task: Deferred<ResolvedMedia?>, val createdAt: Long,
    )
    private data class PreparedNext(val ticket: NextResolve, val media: ResolvedMedia, val plan: vibe.tube.youtube.player.MediaPlan)
    private var pendingNext: NextResolve? = null
    private var preparedNext: PreparedNext? = null
    private var prefetchJob: Job? = null
    private var prefetchRetryAt = 0L

    private fun upcoming(): VideoItem? = NextPlaybackPolicy.next(mutable.value, settings.latest.value.autoplay, suggestedNext)

    private fun invalidateNext(keep: NextResolve? = null, removeSource: Boolean = true) {
        prefetchJob?.cancel(); prefetchJob = null
        if (pendingNext !== keep) pendingNext?.task?.cancel()
        pendingNext = null
        preparedNext = null
        prefetchRetryAt = 0L
        if (removeSource && wired) {
            val p = engine.player
            if (p.mediaItemCount > p.currentMediaItemIndex + 1) p.removeMediaItems(p.currentMediaItemIndex + 1, p.mediaItemCount)
        }
    }

    private fun mediaFresh(media: ResolvedMedia, createdAt: Long): Boolean =
        System.currentTimeMillis() - createdAt < PREFETCH_TTL_MS && !media.isLive &&
            (media.expiresAtMillis?.let { System.currentTimeMillis() < it - EXPIRY_MARGIN_MS } != false)

    private fun maybePrefetchNext(remainingMs: Long) {
        val next = upcoming()
        val pending = pendingNext
        if (preparedNext?.let { !mediaFresh(it.media, it.ticket.createdAt) } == true) invalidateNext()
        if (pending != null && (pending.owner != requestSeq || pending.target.id != next?.id || pending.scopeKey != scopeKey())) invalidateNext()
        if (remainingMs !in 1..PREFETCH_BEFORE_END_MS || mutable.value.isLive || engine.inTransition || next == null) return
        if (System.currentTimeMillis() < prefetchRetryAt) return
        if (pendingNext?.let { it.owner == requestSeq && it.target.id == next.id } == true) return
        val owner = requestSeq
        val source = mutable.value.current?.id ?: return
        val ctx = session.currentContext()
        val auth = ctx?.let { session.authFor(it.accountId) }?.takeIf { session.authState.value is vibe.tube.account.AuthState.Verified }
        val locale = settings.latest.value.contentLocale()
        val task = scope.async {
            try {
                val result = kotlinx.coroutines.withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolver.resolve(next.id, locale, auth, emptySet()) }
                (result as? ResolveResult.Playable)?.media
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { null }
        }
        val ticket = NextResolve(owner, source, next, scopeKey(), task, System.currentTimeMillis())
        pendingNext = ticket
        prefetchJob = scope.launch {
            val media = task.await()
            if (pendingNext !== ticket || owner != requestSeq || mutable.value.current?.id != source || scopeKey() != ticket.scopeKey || upcoming()?.id != next.id) return@launch
            if (media == null || !mediaFresh(media, ticket.createdAt)) {
                pendingNext = null
                prefetchRetryAt = System.currentTimeMillis() + 10_000L
                log.step(requestSeq, next.id, "prefetch_unavailable")
                return@launch
            }
            val cap = defaultCap()
            val plan = MediaPlanner.plan(media, cap.maxHeight, MediaPlanner.audioTrackFor(media, settings.latest.value.appLanguage()),
                allowWebm = !avoidWebm, maxFps = cap.maxFps, audioQuality = settings.latest.value.audioQuality, decodable = CodecSupport::canDecode) ?: return@launch
            
            try {
                preparedNext = PreparedNext(ticket, media, plan)
                engine.player.addMediaSource(builder.build(plan, media, next))
                engine.refreshSession()
                log.step(requestSeq, next.id, "next_source_queued", "client=${media.clientName} height=${plan.height}")
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                invalidateNext()
                prefetchRetryAt = System.currentTimeMillis() + 10_000L
                log.warn(requestSeq, next.id, "next_source_failed", e.javaClass.simpleName)
            }
        }
    }

    private fun useQueuedNext(videoId: String): Boolean {
        val prepared = preparedNext ?: return false
        val ticket = prepared.ticket
        if (ticket.owner != requestSeq || ticket.target.id != videoId || ticket.scopeKey != scopeKey() || !mediaFresh(prepared.media, ticket.createdAt)) return false
        val p = engine.player
        val index = p.currentMediaItemIndex + 1
        if (index >= p.mediaItemCount || p.getMediaItemAt(index).mediaId != videoId) return false
        persistProgress()
        p.seekToDefaultPosition(index)
        return true
    }

    private fun promoteQueuedNext(item: androidx.media3.common.MediaItem?, reason: Int) {
        val prepared = preparedNext ?: return
        val ticket = prepared.ticket
        if (item?.mediaId != ticket.target.id || engine.player.currentMediaItemIndex == 0 || ticket.owner != requestSeq) return
        if (ticket.scopeKey != scopeKey()) { invalidateNext(); return }
        rememberPrevious(ticket.target)
        val old = mutable.value
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) recordFinished(old)
        preparedNext = null; pendingNext = null
        prefetchJob?.cancel(); prefetchJob = null
        loadJob?.cancel(); watchdogJob?.cancel(); rebufferJob?.cancel(); captionJob?.cancel()
        mutableCues.value = emptyList()
        val request = ++requestSeq
        val video = ticket.target
        log.start(request, video.id, "queued_transition")
        currentMedia = prepared.media
        startedOk = engine.player.playbackState == Player.STATE_READY
        stallRetries = 0; midSwitches = 0; lastMidSwitchAt = 0L; recoveries = 0
        recoveredFor = null; failedClients.clear(); captionsChosenFor = null; suggestedNext = null
        resumePaused = !engine.player.playWhenReady
        val index = old.queue.indexOfFirst { it.id == video.id }
        val inQueue = index >= 0
        mutable.value = old.copy(
            current = video, queue = if (inQueue) old.queue else listOf(video), queueIndex = if (inQueue) index else 0,
            playlistId = if (inQueue) old.playlistId else null, queueTitle = if (inQueue) old.queueTitle else null,
            unshuffledQueue = if (inQueue) old.unshuffledQueue else null,
            phase = if (startedOk) PlayerPhase.Ready else PlayerPhase.Preparing, error = null,
            positionMs = engine.player.currentPosition.coerceAtLeast(0), durationMs = (prepared.media.durationSeconds ?: 0L) * 1000,
            bufferedMs = engine.player.bufferedPosition.coerceAtLeast(0), isPlaying = engine.player.isPlaying,
            qualities = MediaPlanner.availableHeights(prepared.media), selectedHeight = null, activeHeight = prepared.plan.height,
            audioTracks = MediaPlanner.audioTracks(prepared.media), selectedAudioTrack = prepared.plan.audio?.audioTrackId,
            captions = emptyList(), selectedCaption = null, captionTranslations = emptyList(), captionLoading = false,
            isLive = prepared.media.isLive, clientName = prepared.media.clientName, publishedAt = prepared.media.publishedAt,
            loopStartMs = null, loopEndMs = null, videoAspect = 16f / 9f,
        )
        
        val p = engine.player
        if (p.currentMediaItemIndex > 0) p.removeMediaItems(0, p.currentMediaItemIndex)
        if (startedOk) engine.endTransition() else {
            engine.beginTransition(START_TIMEOUT_MS + TRANSITION_MARGIN_MS, video, p.playWhenReady)
            startWatchdog(request, video)
        }
        offerCaptions(prepared.media, video)
        if (p.isPlaying) startTicker()
        engine.refreshSession()
    }

    private fun recordFinished(s: PlayerUiState) {
        val video = s.current ?: return
        if (!settings.latest.value.recordsLocalHistory || s.isLive || s.durationMs <= 0) return
        val key = scopeKey()
        updateResume(key, s, video, s.durationMs, s.durationMs, completed = true, sync = false)
        scope.launch {
            history.recordProgress(key, video.id, video.title, video.channelName, video.thumbnails.bestFor(480),
                (s.durationMs / 1000).toInt(), s.durationMs, s.durationMs, video.channelId, video.channelAvatar)
        }
    }

    private fun scopeKey(): String = session.currentContext()?.scopeKey ?: FeedController.GUEST_SCOPE

    private fun isUnmetered(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun defaultCap(): QualityCap {
        val s = settings.latest.value
        return if (isUnmetered()) (if (s.wifiQuality == QualityCap.Auto) QualityCap.P1080F60 else s.wifiQuality) else s.mobileQuality
    }

    private fun ensureService() {
        runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
    }

    private fun load(video: VideoItem, startPositionMs: Long?, keepSelection: Boolean = false, reason: String = "reload", resolve: Boolean = true) {
        rememberPrevious(video)
        builder.releaseSabr()
        val adopted = pendingNext?.takeIf { !keepSelection && it.target.id == video.id && it.scopeKey == scopeKey() && it.owner == requestSeq }
        invalidateNext(keep = adopted, removeSource = false)
        loadJob?.cancel()
        watchdogJob?.cancel()
        rebufferJob?.cancel()
        persistProgress()
        if (mutable.value.current?.id != video.id) suggestedNext = null
        val request = ++requestSeq
        log.start(request, video.id, reason)
        if (resumeStore.get(scopeKey())?.videoId != video.id) resumeStore.clear(scopeKey())
        recoveredFor = if (keepSelection) recoveredFor else null
        currentMedia = null
        startedOk = false
        if (!keepSelection) {
            stallRetries = 0
            midSwitches = 0
            lastMidSwitchAt = 0L
            recoveries = 0
            resumePaused = false
            failedClients.clear()
            captionsChosenFor = null
            captionJob?.cancel()
            mutableCues.value = emptyList()
        }
        mutable.update {
            it.copy(
                current = video,
                phase = if (keepSelection) PlayerPhase.Recovering else PlayerPhase.Resolving,
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
                captions = if (keepSelection) it.captions else emptyList(),
                selectedCaption = if (keepSelection) it.selectedCaption else null,
                captionTranslations = if (keepSelection) it.captionTranslations else emptyList(),
                captionLoading = false,
                isPlaying = false,
                publishedAt = if (keepSelection) it.publishedAt else null,
                loopStartMs = if (keepSelection) it.loopStartMs else null,
                loopEndMs = if (keepSelection) it.loopEndMs else null,
                
                videoAspect = if (keepSelection) it.videoAspect else 16f / 9f,
            )
        }
        ensureService()
        
        engine.beginTransition(RESOLVE_TIMEOUT_MS + START_TIMEOUT_MS + TRANSITION_MARGIN_MS, video, !resumePaused)
        
        engine.player.stop()
        engine.player.clearMediaItems()
        if (!resolve) {
            log.step(request, video.id, "waiting", "first video not known yet")
            return
        }
        loadJob = scope.launch {
            try {
                var resume = startPositionMs

                val result = kotlinx.coroutines.withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                    resume = startPositionMs ?: history.resumePosition(scopeKey(), video.id)
                    val ctx = session.currentContext()
                    val auth = ctx?.let { c -> session.authFor(c.accountId) }?.takeIf { session.authState.value is vibe.tube.account.AuthState.Verified }
                    log.step(request, video.id, "resolving", "skip=${failedClients.joinToString(",").ifEmpty { "-" }} auth=${auth != null} adopt_next=${adopted != null}")
                    val ready = if (adopted != null && failedClients.isEmpty()) adopted.task.await()?.takeIf {
                        adopted.scopeKey == scopeKey() && mediaFresh(it, adopted.createdAt)
                    } else null
                    ready?.let { ResolveResult.Playable(it) }
                        ?: resolver.resolve(video.id, settings.latest.value.contentLocale(), auth, failedClients.toSet())
                }
                kotlin.coroutines.coroutineContext.ensureActive()
                if (!isCurrent(request, video)) return@launch
                if (result == null) {
                    
                    fail(request, video, PlaybackFailure.Unavailable, "timeout")
                    return@launch
                }
                when (result) {
                    is ResolveResult.Blocked -> fail(
                        request,
                        video,
                        if (failedClients.isNotEmpty() && result.failure !in DEFINITE_FAILURES) PlaybackFailure.Expired else result.failure,
                        result.detail ?: result.clientName,
                    )
                    is ResolveResult.Playable -> {
                        log.step(request, video.id, "resolved", "client=${result.media.clientName}")
                        startPlayback(result.media, video, resume)
                    }
                }
            } catch (e: CancellationException) {
                if (requestSeq != request) log.step(request, video.id, "cancelled")
                throw e
            } catch (e: AppError.Playback) {
                fail(request, video, if (failedClients.isNotEmpty()) PlaybackFailure.Expired else e.reason, e.detail)
            } catch (e: AppError.Network) {
                
                if (e.httpCode != null) fail(request, video, PlaybackFailure.Unavailable, "http_${e.httpCode}")
                else fail(request, video, PlaybackFailure.Network, NETWORK_DETAIL)
            } catch (e: Exception) {
                fail(request, video, if (failedClients.isNotEmpty()) PlaybackFailure.Expired else PlaybackFailure.Unavailable, e.message)
            } finally {
                adopted?.task?.cancel()
            }
        }
    }

    /** A failure on a SABR plan belongs to the session, not to the direct client the media also carries. */
    private fun markClientFailed() {
        if (currentPlan?.kind == PlanKind.Sabr) failedClients += StreamResolver.SABR_CLIENT
        else currentMedia?.clientName?.let { failedClients += it }
    }

    private fun isCurrent(request: Long, video: VideoItem): Boolean = requestSeq == request && mutable.value.current?.id == video.id

    private fun recoverStall(video: VideoItem) {
        if (mutable.value.current?.id != video.id) return
        markClientFailed()
        if (stallRetries < MAX_STALL_RETRIES) {
            stallRetries++
            log.warn(requestSeq, video.id, "stalled", "client=${currentMedia?.clientName} attempt=$stallRetries")
            val at = engine.player.currentPosition.takeIf { it > 1_000 }
            load(video, at, keepSelection = true, reason = "stall")
        } else {
            fail(requestSeq, video, if (isOnline()) PlaybackFailure.Unavailable else PlaybackFailure.Network, "timeout")
        }
    }

    private fun startWatchdog(request: Long, video: VideoItem) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(START_TIMEOUT_MS)
            if (!startedOk && isCurrent(request, video) && mutable.value.phase.isBusy) recoverStall(video)
        }
    }

    private fun watchRebuffer() {
        val video = mutable.value.current ?: return
        rebufferJob?.cancel()
        rebufferJob = scope.launch {
            delay(REBUFFER_TIMEOUT_MS)
            val player = engine.player
            if (mutable.value.current?.id != video.id) return@launch
            if (player.playbackState != Player.STATE_BUFFERING || !player.playWhenReady) return@launch
            if (!isOnline()) {
                watchRebuffer()
                return@launch
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (midSwitches >= MAX_MIDSTREAM_SWITCHES || failedClients.size >= MAX_CLIENT_FAILOVERS) return@launch
            if (lastMidSwitchAt != 0L && now - lastMidSwitchAt < MIN_SWITCH_GAP_MS) return@launch
            log.warn(requestSeq, video.id, "rebuffering", "client=${currentMedia?.clientName} for>${REBUFFER_TIMEOUT_MS}ms")
            switchStream(video)
        }
    }

    private fun switchStream(video: VideoItem) {
        markClientFailed()
        midSwitches++
        lastMidSwitchAt = android.os.SystemClock.elapsedRealtime()
        resumePaused = !engine.player.playWhenReady
        recoveredFor = video.id
        load(video, engine.player.currentPosition.coerceAtLeast(0), keepSelection = true, reason = "rebuffer")
    }

    private fun isOnline(): Boolean = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(true)

    private fun fail(request: Long, video: VideoItem, failure: PlaybackFailure, detail: String?) {
        if (!isCurrent(request, video)) return
        watchdogJob?.cancel()
        retryWhenOnline = failure == PlaybackFailure.Network && !isOnline()
        log.warn(request, video.id, "failed", "reason=$failure offline=$retryWhenOnline")
        mutable.update { it.copy(phase = PlayerPhase.Error, error = PlayerError(failure, detail), isPlaying = false) }

        if (engine.inTransition) {
            engine.player.playWhenReady = false
            engine.endTransition()
        }
    }

    private fun mediaExpired(): Boolean {
        val media = currentMedia ?: return false
        val expires = media.expiresAtMillis ?: return false
        return !media.isLive && System.currentTimeMillis() > expires - EXPIRY_MARGIN_MS
    }

    private fun startPlayback(media: ResolvedMedia, video: VideoItem, resumeMs: Long?) {
        currentMedia = media

        mutable.update { state ->
            val current = state.current
            if (current?.id != video.id) state else state.copy(current = current.copy(
                title = media.title?.takeIf { it.isNotBlank() } ?: current.title,
                channelName = current.channelName ?: media.author,
            ))
        }
        val s = mutable.value
        val cap = defaultCap()
        val effectiveCap = s.selectedHeight ?: cap.maxHeight
        val effectiveFps = if (s.selectedHeight != null) null else cap.maxFps
        val prefs = settings.latest.value
        val audioChoice = s.selectedAudioTrack ?: MediaPlanner.audioTrackFor(media, prefs.appLanguage())
        val plan = MediaPlanner.plan(media, effectiveCap, audioChoice, allowWebm = !avoidWebm, maxFps = effectiveFps, audioQuality = prefs.audioQuality, decodable = CodecSupport::canDecode, allowSabr = true)
        if (plan == null) {
            fail(
                requestSeq,
                video,
                if (media.skippedCipherFormats > 0) PlaybackFailure.CipherUnsupported else PlaybackFailure.NoCompatibleStream,
                null,
            )
            return
        }
        currentPlan = plan
        if (plan.kind != PlanKind.Sabr) builder.releaseSabr()
        val knownMs0 = (media.durationSeconds ?: 0L) * 1000
        val sabrStartMs = (resumeMs ?: 0L).takeIf { knownMs0 <= 0 || it < knownMs0 - ResumePolicy.TAIL_MS } ?: 0L
        val source = builder.build(plan, media, video, sabrStartMs)
        val player = engine.player
        
        val knownMs = (media.durationSeconds ?: 0L) * 1000
        val startMs = (resumeMs ?: 0L).takeIf { knownMs <= 0 || it < knownMs - ResumePolicy.TAIL_MS } ?: 0L
        // A SABR session starts the stream at the resume point itself, so the player begins at the start of what it is given.
        player.setMediaSource(source, if (plan.kind == PlanKind.Sabr) 0L else startMs)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        player.prepare()
        player.playWhenReady = !resumePaused

        log.step(requestSeq, video.id, "preparing", "plan=${plan.kind} height=${plan.height} at=${startMs / 1000}s")
        if (!startedOk) startWatchdog(requestSeq, video)
        mutable.update {
            it.copy(
                phase = if (it.phase == PlayerPhase.Resolving) PlayerPhase.Preparing else it.phase,
                qualities = MediaPlanner.availableHeights(media),
                activeHeight = plan.height,
                audioTracks = MediaPlanner.audioTracks(media),
                selectedAudioTrack = audioChoice,
                isLive = media.isLive,
                clientName = media.clientName,
                publishedAt = media.publishedAt,
            )
        }
        offerCaptions(media, video)
    }

    private fun captionLanguage(): String {
        val prefs = settings.latest.value
        return if (prefs.subtitleLanguage == vibe.tube.data.settings.AppSettings.SYSTEM) prefs.appLanguage() else prefs.subtitleLanguage
    }

    private fun languageName(code: String): String =
        java.util.Locale.forLanguageTag(code).getDisplayName(java.util.Locale.getDefault()).replaceFirstChar { it.titlecase() }

    private fun translatedName(code: String): String = context.getString(R.string.player_caption_translated, languageName(code))

    private fun offerCaptions(media: ResolvedMedia, video: VideoItem) {
        val choices = CaptionPicker.choices(media.captions, media.captionTranslations, captionLanguage(), ::translatedName)
        mutable.update { s ->
            val kept = s.captions.firstOrNull { c -> c.id == s.selectedCaption && choices.none { it.id == c.id } }
            s.copy(captions = choices + listOfNotNull(kept), captionTranslations = media.captionTranslations)
        }
        if (captionsChosenFor == video.id) return
        captionsChosenFor = video.id
        if (!settings.latest.value.subtitlesEnabled || choices.isEmpty() || media.isLive) return
        CaptionPicker.pick(choices, captionLanguage())?.let { applyCaption(it, userInitiated = false) }
    }

    private fun applyCaption(track: CaptionTrack?, userInitiated: Boolean) {
        captionJob?.cancel()
        mutable.update { it.copy(selectedCaption = track?.id, captionLoading = false) }
        if (track == null) {
            mutableCues.value = emptyList()
            return
        }
        val videoId = mutable.value.current?.id ?: return
        val cached = captionCache["$videoId|${track.id}"]
        if (cached != null) {
            mutableCues.value = cached
            return
        }
        mutableCues.value = emptyList()
        mutable.update { it.copy(captionLoading = true) }
        captionJob = scope.launch {
            var cues = fetchCaption(track, currentMedia?.userAgent)
            if (cues.isEmpty()) {
                val alternate = runCatching {
                    resolver.alternateCaptions(videoId, settings.latest.value.contentLocale(), currentMedia?.clientName.orEmpty())
                }.getOrNull()
                val other = alternate?.let { m ->
                    CaptionPicker.choices(m.captions, m.captionTranslations, captionLanguage(), ::translatedName)
                        .firstOrNull { it.id == track.id }
                        ?: if (track.isTranslation) CaptionPicker.translation(m.captions, track.languageCode, track.name) else null
                }
                if (other != null) cues = fetchCaption(other, alternate.userAgent)
            }
            if (mutable.value.current?.id != videoId || mutable.value.selectedCaption != track.id) return@launch
            if (cues.isEmpty()) {
                mutable.update { it.copy(captionLoading = false, selectedCaption = null) }
                if (userInitiated) events.post(vibe.tube.R.string.player_caption_failed)
                return@launch
            }
            captionCache["$videoId|${track.id}"] = cues
            mutableCues.value = cues
            mutable.update { it.copy(captionLoading = false) }
        }
    }

    private suspend fun fetchCaption(track: CaptionTrack, userAgent: String?): List<CaptionCue> =
        try {
            captionLoader.load(track, userAgent ?: currentMedia?.userAgent.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }

    private fun handlePlayerError(error: PlaybackException) {
        val video = mutable.value.current ?: return
        val cause = error.cause
        val code = (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        log.warn(requestSeq, video.id, "player-error", "code=${error.errorCodeName} http=${code ?: "-"} client=${currentMedia?.clientName}")
        val position = engine.player.currentPosition.coerceAtLeast(0)
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            engine.player.seekToDefaultPosition()
            engine.player.prepare()
            return
        }
        // Whatever a SABR source fails with, the session is the suspect: leave it and resolve again without it.
        if (currentPlan?.kind == PlanKind.Sabr && recoveries < MAX_RECOVERIES) {
            failedClients += StreamResolver.SABR_CLIENT
            builder.releaseSabr()
            recover(video, position, "sabr-error")
            return
        }
        val badStatus = code == 403 || code == 410 || code == 404
        if (code == 403 || code == 410) {
            // A refusal on a URL whose deadline has not passed is a verdict on the client, not on the
            // URL: remember it so the next video does not start from the same refused client.
            val refusedUrl = (cause as? HttpDataSource.InvalidResponseCodeException)?.dataSpec?.uri?.toString()
            val kind = resolver.gates.reportDenied(refusedUrl)
            if (kind == vibe.tube.youtube.player.StreamDenialKind.TokenRejected) resolver.reportTokenRejected()
            if (kind == vibe.tube.youtube.player.StreamDenialKind.AttestationGated || kind == vibe.tube.youtube.player.StreamDenialKind.TokenRejected) {
                log.warn(requestSeq, video.id, "client-gated", "kind=$kind client=${StreamDenialClassifier.clientOf(refusedUrl) ?: currentMedia?.clientName}")
            }
        }
        val decoder = error.errorCode in listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        )
        val network = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        val canRecover = recoveries < MAX_RECOVERIES
        if (decoder && !avoidWebm && canRecover) {
            avoidWebm = true
            recover(video, position, "decoder")
            return
        }
        if (badStatus && mediaExpired() && canRecover) {
            
            recover(video, position, "expired")
            return
        }
        val serverSide = (code != null && (code == 429 || code >= 500)) ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        if ((badStatus || serverSide) && (badStatus || isOnline()) && canRecover) {
            markClientFailed()
            if (failedClients.size < MAX_CLIENT_FAILOVERS) {
                recoveredFor = video.id
                recover(video, position, "failover http=${code ?: "-"}")
                return
            }
        }
        val failure = when {
            network && !isOnline() -> PlaybackFailure.Network
            badStatus -> PlaybackFailure.Expired
            decoder -> PlaybackFailure.NoCompatibleStream
            network -> PlaybackFailure.Network
            else -> PlaybackFailure.Unavailable
        }
        fail(requestSeq, video, failure, if (network) NETWORK_DETAIL else error.errorCodeName)
    }

    private fun recover(video: VideoItem, positionMs: Long, why: String) {
        recoveries++
        resumePaused = !engine.player.playWhenReady
        log.step(requestSeq, video.id, "recovering", "$why attempt=$recoveries")
        load(video, positionMs, keepSelection = true, reason = why)
    }

    private fun onEnded() {
        persistProgress(completed = true)
        val s = mutable.value
        if (s.sleepAtEnd) {
            mutable.update { it.copy(phase = PlayerPhase.Ended, isPlaying = false, sleepAtEnd = false) }
            return
        }
        if (s.repeatMode == RepeatMode.One || (s.repeatMode == RepeatMode.All && s.queue.size <= 1)) {
            engine.beginTransition(START_TIMEOUT_MS + TRANSITION_MARGIN_MS, s.current, engine.player.playWhenReady)
            engine.player.seekTo(s.loopStartMs ?: 0L)
            engine.player.play()
            return
        }
        if (s.repeatMode == RepeatMode.All && !s.hasNext && s.queue.isNotEmpty()) {
            playAt(0)
            return
        }
        
        val autoplay = settings.latest.value.autoplay
        val go = upcoming() != null
        s.current?.let {
            val why = when {
                !go && !autoplay -> "autoplay_off"
                !go -> "nothing_next"
                s.hasNext -> "queue"
                else -> "suggested"
            }
            log.step(requestSeq, it.id, "ended", "next=$why foreground=${ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)}")
        }
        if (go) next() else mutable.update { it.copy(phase = PlayerPhase.Ended, isPlaying = false) }
    }

    private var sleepJob: Job? = null

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

    fun setRepeat(mode: RepeatMode) { invalidateNext(); mutable.update { it.copy(repeatMode = mode) } }

    fun setLoopRange(startMs: Long?, endMs: Long?) {
        invalidateNext()
        mutable.update { it.copy(loopStartMs = startMs, loopEndMs = if (startMs == null) null else endMs) }
        if (startMs != null && endMs != null) {
            val pos = engine.player.currentPosition
            if (pos < startMs || pos >= endMs) engine.player.seekTo(startMs)
        }
    }

    fun setSleepAtEnd(on: Boolean) {
        invalidateNext()
        sleepJob?.cancel()
        mutable.update { it.copy(sleepAtEnd = on, sleepUntilMs = null) }
    }

    fun retry() {
        val video = mutable.value.current ?: return
        pendingMix?.takeIf { it.id == video.id }?.let {
            playMix(it)
            return
        }
        recoveredFor = null
        avoidWebm = false
        failedClients.clear()
        load(video, startPositionMs = mutable.value.positionMs.takeIf { it > 0 }, reason = "retry")
    }

    override fun sessionPlayWhenReady(value: Boolean) {
        if (engine.inTransition) { resumePaused = !value; engine.setTransitionPlayWhenReady(value) }
        else if (value && mutable.value.phase == PlayerPhase.Error) retry()
    }

    override fun stopPlayback() = dismiss()

    fun togglePlay() {
        if (engine.inTransition) {
            val value = !engine.transitionPlayWhenReady
            sessionPlayWhenReady(value)
            engine.player.playWhenReady = value
            return
        }
        val s = mutable.value
        if (s.staged) {
            startStaged(expand = false)
            return
        }
        val p = engine.player
        when {
            
            s.phase == PlayerPhase.Error -> retry()
            s.phase == PlayerPhase.Ended -> { p.seekTo(0); p.play() }
            p.isPlaying -> p.pause()
            
            s.current != null && s.phase == PlayerPhase.Ready && mediaExpired() -> {
                log.step(requestSeq, s.current.id, "refresh", "links expired while paused")
                load(s.current, p.currentPosition.coerceAtLeast(0), keepSelection = true, reason = "expired")
            }
            s.current != null && p.mediaItemCount == 0 && !s.phase.isBusy -> retry()
            else -> {
                if (p.playbackState == Player.STATE_IDLE && p.mediaItemCount > 0) p.prepare()
                p.play()
            }
        }
    }

    fun enrichCurrent(info: vibe.tube.youtube.model.WatchInfo) {
        mutable.update { state ->
            val video = state.current
            if (video?.id != info.videoId) state else state.copy(current = video.copy(
                title = info.title.ifBlank { video.title },
                channelName = info.channel?.name ?: video.channelName,
                channelId = info.channel?.id ?: video.channelId,
                channelAvatar = info.channel?.avatar ?: video.channelAvatar,
            ))
        }
    }

    fun seekTo(positionMs: Long) {
        if (mutable.value.staged) {
            startStaged(expand = false, startPositionMs = positionMs)
            return
        }
        if (!sabrSeek(positionMs)) engine.player.seekTo(positionMs)
        mutable.update { it.copy(positionMs = positionMs) }
        if (!engine.player.isPlaying) persistProgress()
    }

    /**
     * A SABR session streams from one point forward, so a seek opens a new session there instead of moving the player.
     * Returns false when the current source is not SABR.
     */
    private fun sabrSeek(positionMs: Long): Boolean {
        val plan = currentPlan?.takeIf { it.kind == PlanKind.Sabr && builder.sabr != null } ?: return false
        val media = currentMedia ?: return false
        val video = mutable.value.current ?: return false
        val shouldPlay = engine.player.playWhenReady
        val duration = durationOf()
        val target = if (duration > 0) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        val source = builder.build(plan, media, video, target)
        val player = engine.player
        player.setMediaSource(source, 0L)
        player.prepare()
        player.playWhenReady = shouldPlay
        log.step(requestSeq, video.id, "sabr_seek", "at=${target / 1000}s")
        return true
    }

    fun seekBy(deltaMs: Long): Long {
        val p = engine.player
        val from = p.currentPosition.coerceAtLeast(0)
        val limit = durationOf().takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (from + deltaMs).coerceIn(0, limit)
        seekTo(target)
        return target - from
    }

    fun setSpeed(speed: Float) {
        if (!speed.isFinite()) return
        val p = engine.player
        val v = speed.coerceIn(0.25f, 3f)
        p.playbackParameters = PlaybackParameters(v, if (mutable.value.linkPitch) v.coerceIn(0.5f, 2f) else p.playbackParameters.pitch)
    }

    fun setLinkPitch(on: Boolean) {
        mutable.update { it.copy(linkPitch = on) }
        if (on) setSpeed(engine.player.playbackParameters.speed)
    }

    fun setPitch(pitch: Float) {
        if (!pitch.isFinite()) return
        val p = engine.player
        p.playbackParameters = PlaybackParameters(p.playbackParameters.speed, pitch.coerceIn(0.5f, 2f))
    }


    fun setEqPreset(preset: EqPreset) {
        effects.setPreset(preset)
        mutable.update { it.copy(eqPreset = preset) }
    }

    fun setBassBoost(strength: Int) {
        effects.setBass(strength)
        mutable.update { it.copy(bassBoost = effects.bassStrength) }
    }

    fun setLoudness(mb: Int) {
        effects.setGain(mb)
        mutable.update { it.copy(loudnessMb = effects.gainMb) }
    }

    fun setSkipSilence(on: Boolean) {
        engine.player.skipSilenceEnabled = on
        tuningPrefs.edit().putBoolean("skip_silence", on).apply()
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

    fun selectCaption(id: String?) {
        val track = id?.let { wanted -> mutable.value.captions.firstOrNull { it.id == wanted } }
        scope.launch { settings.update { it.copy(subtitlesEnabled = track != null) } }
        applyCaption(track, userInitiated = true)
    }

    fun setSubtitleStyle(
        size: Int? = null,
        color: vibe.tube.data.settings.SubtitleColor? = null,
        background: vibe.tube.data.settings.SubtitleBackground? = null,
    ) {
        scope.launch {
            settings.update {
                it.copy(
                    subtitleSize = size ?: it.subtitleSize,
                    subtitleColor = color ?: it.subtitleColor,
                    subtitleBackground = background ?: it.subtitleBackground,
                )
            }
        }
    }

    fun toggleCaptions() {
        val s = mutable.value
        if (s.selectedCaption != null || s.captionLoading) {
            selectCaption(null)
            return
        }
        val track = CaptionPicker.pick(s.captions, captionLanguage())
        if (track == null) events.post(vibe.tube.R.string.player_caption_none) else selectCaption(track.id)
    }

    fun selectTranslatedCaption(language: String) {
        val s = mutable.value
        val existing = s.captions.firstOrNull { it.isTranslation && it.languageCode == language }
        val track = existing ?: CaptionPicker.translation(s.captions, language, translatedName(language)) ?: return
        if (existing == null) mutable.update { it.copy(captions = it.captions + track) }
        scope.launch { settings.update { it.copy(subtitlesEnabled = true) } }
        applyCaption(track, userInitiated = true)
    }

    override fun sessionQueue(): List<VideoItem> {
        val s = mutable.value
        val current = s.current ?: return emptyList()
        val queue = s.queue.ifEmpty { listOf(current) }
        val suggested = suggestedNext
        return if (!s.hasNext && suggested != null && queue.none { it.id == suggested.id }) queue + suggested else queue
    }

    override fun hasNext(): Boolean = mutable.value.hasNext || suggestedNext != null

    override fun hasPrevious(): Boolean = mutable.value.hasPrevious || mutable.value.current != null

    override fun next() {
        val s = mutable.value
        val target = if (s.hasNext) s.queue[s.queueIndex + 1] else suggestedNext
        if (target != null && useQueuedNext(target.id)) return
        when {
            s.hasNext -> playAt(s.queueIndex + 1)
            suggestedNext != null -> suggestedNext?.let { video ->
                queueJob?.cancel()
                startQueue(video, emptyList(), null, startAtMs = 0L, expand = s.expanded)
            }
            else -> Unit
        }
    }

    private val playedBackStack = java.util.ArrayDeque<VideoItem>()
    private var returningToPrevious = false

    private fun rememberPrevious(video: VideoItem) {
        val current = mutable.value.current ?: return
        if (current.id == video.id) return
        if (returningToPrevious) { returningToPrevious = false; return }
        if (playedBackStack.peekLast()?.id != current.id) playedBackStack.addLast(current)
        while (playedBackStack.size > 100) playedBackStack.removeFirst()
    }

    override fun previous() {
        val s = mutable.value
        if (s.current == null) return
        if (engine.player.currentPosition > 15_000L) {
            seekTo(0)
            return
        }
        if (s.hasPrevious) {
            returningToPrevious = true
            if (playedBackStack.peekLast()?.id == s.queue.getOrNull(s.queueIndex - 1)?.id) playedBackStack.removeLast()
            playAt(s.queueIndex - 1)
        } else {
            val previous = playedBackStack.pollLast() ?: return
            returningToPrevious = true
            startQueue(previous, emptyList(), null, startAtMs = 0L, expand = s.expanded)
        }
    }

    fun playQueueIndex(index: Int) = playAt(index)

    private fun playAt(index: Int) {
        val s = mutable.value
        val video = s.queue.getOrNull(index) ?: return
        if (index == s.queueIndex && s.current?.id == video.id && s.phase != PlayerPhase.Error && s.phase != PlayerPhase.Ended && !s.staged) return
        wire()
        if (useQueuedNext(video.id)) return
        pendingMix = null
        leaveStaged()
        mutable.update { it.copy(visible = true, queueIndex = index) }
        load(video, startPositionMs = 0L, reason = "queue")
    }


    private val queuePanelRequests = kotlinx.coroutines.flow.MutableStateFlow(0)

    val queuePanelRequest: kotlinx.coroutines.flow.StateFlow<Int> = queuePanelRequests

    fun takeQueuePanelRequest(): Boolean = queuePanelRequests.getAndUpdate { 0 } > 0


    data class CommentsRequest(val videoId: String, val commentId: String?)

    private val commentsRequests = MutableStateFlow<CommentsRequest?>(null)
    val commentsRequest: StateFlow<CommentsRequest?> = commentsRequests.asStateFlow()

    fun requestComments(videoId: String, commentId: String?) {
        commentsRequests.value = CommentsRequest(videoId, commentId)
    }

    fun takeCommentsRequest(videoId: String): CommentsRequest? {
        val r = commentsRequests.value?.takeIf { it.videoId == videoId } ?: return null
        return if (commentsRequests.compareAndSet(r, null)) r else null
    }

    fun toggleShuffle() { invalidateNext(); mutable.update { if (it.shuffled) QueueOps.unshuffle(it) else QueueOps.shuffle(it) }; engine.refreshSession() }

    fun expand() {
        if (mutable.value.staged) {
            startStaged(expand = true)
            return
        }
        mutable.update { if (it.visible) it.copy(expanded = true) else it }
    }

    fun collapse() = mutable.update { it.copy(expanded = false, fullscreen = false, screenLocked = false) }


    fun setScreenLocked(value: Boolean) = mutable.update { it.copy(screenLocked = value) }

    fun setFullscreen(value: Boolean) = mutable.update { it.copy(fullscreen = value, screenLocked = if (value) it.screenLocked else false) }

    fun setInPip(value: Boolean) = mutable.update { it.copy(inPip = value) }

    fun dismiss() {
        vibe.tube.ui.components.PlayerLaunch.clear()
        pendingMix = null
        mutable.value.current?.let { log.step(requestSeq, it.id, "closed") }
        requestSeq++
        loadJob?.cancel()
        watchdogJob?.cancel()
        rebufferJob?.cancel()
        queueJob?.cancel()
        tickerJob?.cancel()
        builder.releaseSabr()
        persistProgress()
        resumeStore.clear(scopeKey())
        runCatching {
            engine.player.stop()
            engine.player.clearMediaItems()
        }
        currentMedia = null
        suggestedNext = null
        invalidateNext(removeSource = false)
        engine.endTransition()
        mutable.value = PlayerUiState(ambientFullscreen = settings.latest.value.ambientFullscreen)
        runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
    }

    private fun leaveStaged() {
        if (mutable.value.staged) mutable.update { it.copy(staged = false) }
    }

    private fun startStaged(expand: Boolean, startPositionMs: Long? = null) {
        val s = mutable.value
        val video = s.current ?: return
        wire()
        val position = startPositionMs ?: s.positionMs.takeIf { it > 0 }
        mutable.update { it.copy(staged = false, expanded = expand, fullscreen = false) }
        load(video, startPositionMs = position, reason = "resume-card")
    }

    private fun restoreResume() {
        if (mutable.value.visible) return
        val key = scopeKey()
        val record = resumeStore.get(key) ?: return
        if (!ResumePolicy.isWorthResuming(record.positionMs, record.durationMs)) {
            resumeStore.clear(key)
            return
        }
        val video = VideoItem(
            id = record.videoId,
            title = record.title,
            channelName = record.channelName,
            channelId = null,
            channelAvatar = null,
            thumbnails = record.thumbnailUrl?.let { listOf(vibe.tube.youtube.model.Thumb(it, 480, 360)) } ?: emptyList(),
            durationText = null,
            durationSeconds = (record.durationMs / 1000).toInt(),
            viewCountText = null,
            publishedText = null,
        )
        mutable.value = PlayerUiState(
            ambientFullscreen = settings.latest.value.ambientFullscreen,            visible = true,
            expanded = false,
            current = video,
            queue = listOf(video),
            queueIndex = 0,
            phase = PlayerPhase.Idle,
            positionMs = record.positionMs,
            durationMs = record.durationMs,
            staged = true,
        )
    }

    init {
        scope.launch {
            var coldStart = true
            session.context.map { it?.scopeKey ?: FeedController.GUEST_SCOPE }.distinctUntilChanged().collect {
                if (coldStart) {
                    coldStart = false
                    restoreResume()
                } else {
                    invalidateNext()
                    if (mutable.value.staged) mutable.value = PlayerUiState(ambientFullscreen = settings.latest.value.ambientFullscreen)
                }
            }
        }
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
                builder.sabr?.updatePlayhead(p.currentPosition.coerceAtLeast(0))
                maybePrefetchNext(p.duration - p.currentPosition)
                val loop = mutable.value
                val end = loop.loopEndMs
                if (end != null && p.currentPosition >= end) seekTo(loop.loopStartMs ?: 0L)
                val wait = if (end != null) (end - p.currentPosition).coerceIn(40L, TICK_MS) else TICK_MS
                delay(wait)
                if (!p.isPlaying && mutable.value.phase != PlayerPhase.Buffering) break
            }
        }
    }

    private fun persistProgress(completed: Boolean = false, sync: Boolean = false) {
        val s = mutable.value
        val video = s.current ?: return
        if (s.staged) return
        if (!settings.latest.value.recordsLocalHistory) return
        val p = engine.player

        if (p.currentMediaItem?.mediaId != video.id) return
        val position = if (completed) durationOf() else p.currentPosition
        val duration = durationOf()
        if (duration <= 0 || s.isLive) return
        lastPersist = System.currentTimeMillis()
        val key = scopeKey()
        updateResume(key, s, video, position, duration, completed, sync)
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
                channelId = video.channelId,
                channelAvatar = video.channelAvatar,
            )
        }
    }

    private fun updateResume(key: String, s: PlayerUiState, video: VideoItem, position: Long, duration: Long, completed: Boolean, sync: Boolean) {
        val pending = resumeStore.get(key)?.videoId == video.id
        when (ResumePolicy.decide(s, settings.latest.value.recordsLocalHistory, pending, position, duration, completed)) {
            ResumePolicy.Decision.Record -> resumeStore.put(
                key,
                ResumeRecord(video.id, video.title, video.channelName, video.thumbnails.bestFor(480), position, duration),
                sync,
            )
            ResumePolicy.Decision.Clear -> if (pending) resumeStore.clear(key)
            ResumePolicy.Decision.Ignore -> Unit
        }
    }

    companion object {
        private const val TICK_MS = 500L
                                                                                                           
        private const val RESOLVE_TIMEOUT_MS = 45_000L
                                                                                                                    
        private const val TRANSITION_MARGIN_MS = 10_000L
                                                                                                                    
        private const val PREFETCH_BEFORE_END_MS = 60_000L
        private const val PREFETCH_TTL_MS = 10 * 60_000L
                                                       
        private const val START_TIMEOUT_MS = 15_000L
        private const val MAX_RECOVERIES = 5
        private const val EXPIRY_MARGIN_MS = 60_000L
                                                                                                                   
        private val DEFINITE_FAILURES = setOf(
            PlaybackFailure.Private, PlaybackFailure.MembersOnly, PlaybackFailure.Removed,
            PlaybackFailure.RegionBlocked, PlaybackFailure.DrmUnsupported, PlaybackFailure.AgeRestricted,
            PlaybackFailure.LoginRequired, PlaybackFailure.BotCheck,
        )
        private const val MAX_STALL_RETRIES = 2
        private val HEADPHONE_TYPES = setOf(
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            android.media.AudioDeviceInfo.TYPE_HEARING_AID,
        ) + if (android.os.Build.VERSION.SDK_INT >= 31) setOf(android.media.AudioDeviceInfo.TYPE_BLE_HEADSET) else emptySet()
        private const val PERSIST_INTERVAL_MS = 5_000L
        private const val MAX_QUEUE_PAGES = 5
        const val NETWORK_DETAIL = "network"
        private const val MAX_CLIENT_FAILOVERS = 4
        private const val REBUFFER_TIMEOUT_MS = 20_000L
        private const val MAX_MIDSTREAM_SWITCHES = 2
        private const val MIN_SWITCH_GAP_MS = 45_000L
    }
}
