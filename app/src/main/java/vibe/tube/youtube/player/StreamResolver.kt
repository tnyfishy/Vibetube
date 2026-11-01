package vibe.tube.youtube.player

import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import vibe.tube.core.AppError
import vibe.tube.core.Clock
import vibe.tube.core.PlaybackFailure
import vibe.tube.network.ClientProfile
import vibe.tube.network.InnerTubeClient
import vibe.tube.network.RequestAuth
import vibe.tube.youtube.model.ContentLocale
import vibe.tube.youtube.parser.arr
import vibe.tube.youtube.parser.at
import vibe.tube.youtube.parser.bool
import vibe.tube.youtube.parser.firstObject
import vibe.tube.youtube.parser.int
import vibe.tube.youtube.parser.list
import vibe.tube.youtube.parser.path
import vibe.tube.youtube.parser.str
import vibe.tube.youtube.parser.textOf
import vibe.tube.youtube.potoken.BotGuardCodec
import vibe.tube.youtube.potoken.PoTokenMinter
import okhttp3.RequestBody.Companion.toRequestBody
import vibe.tube.youtube.cipher.CipherDeobfuscator
import vibe.tube.youtube.cipher.CipherHttp
import vibe.tube.youtube.cipher.PipePipeNsigDecoder
import vibe.tube.player.sabr.core.SabrBase64
import vibe.tube.player.sabr.integration.SabrFormat
import vibe.tube.player.sabr.integration.SabrOffer

class StreamResolver(
    private val client: InnerTubeClient,
    private val clock: Clock = Clock.System,
    private val profiles: List<ClientProfile> = DEFAULT_PROFILES,
                                                                                                                        
    private val profileTimeoutMs: Long = PROFILE_TIMEOUT_MS,
    val gates: ClientGateRegistry = ClientGateRegistry(clockMs = { clock.nowMillis() }),
    private val fallback: (suspend (videoId: String, locale: ContentLocale) -> ResolvedMedia)? = null,
    private val fallbackTimeoutMs: Long = FALLBACK_TIMEOUT_MS,
    private val poTokens: PoTokenMinter? = null,
) {
    init {
        // GVS walls these two per visitor, not per client: a fresh visitor is served on them again.
        gates.onGated = { client -> if (client in VISITOR_WALLED_CLIENTS) { rerollVisitor(); gates.clear() } }
    }

    /**
     * The client ladder, then two last chances before giving up: a fresh visitor identity when every
     * client was walled by the bot check, and the independent extractor when the ladder resolved
     * nothing playable. Each runs at most once.
     */
    suspend fun resolve(
        videoId: String,
        locale: ContentLocale,
        auth: RequestAuth?,
        skipClients: Set<String> = emptySet(),
    ): ResolveResult =
        // A queued-next prefetch and the load that follows it join one ladder instead of opening two.
        coalescer.run(ResolveKey(videoId, locale, auth?.accountId, skipClients)) { resolveOnce(videoId, locale, auth, skipClients) }

    private data class ResolveKey(val videoId: String, val locale: ContentLocale, val accountId: String?, val skip: Set<String>)

    private val coalescer = InFlightRequestCoalescer<ResolveKey, ResolveResult>(
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
    )

    private suspend fun resolveOnce(
        videoId: String,
        locale: ContentLocale,
        auth: RequestAuth?,
        skipClients: Set<String>,
    ): ResolveResult {
        var outcome = ladder(videoId, locale, auth, skipClients)
        if (outcome is ResolveResult.Blocked && outcome.failure == PlaybackFailure.BotCheck) {
            android.util.Log.w("Resolver", "$videoId: every client was bot-walled, retrying with a fresh visitor")
            rerollVisitor()
            val retry = try {
                ladder(videoId, locale, auth, skipClients)
            } catch (e: AppError.Network) {
                null
            } catch (e: AppError.Parse) {
                null
            }
            if (retry != null) outcome = retry
        }
        if (outcome is ResolveResult.Blocked && outcome.failure !in TERMINAL_FAILURES && SABR_ENABLED && poTokens != null && SABR_CLIENT !in skipClients) {
            // Nothing direct plays: a durable web session is worth more than the extractor unattested URLs.
            val offer = try {
                withTimeoutOrNull(SABR_LAST_RESORT_MS) { resolveSabr(videoId, locale) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (offer != null) {
                android.util.Log.i("Resolver", "$videoId sabr-only: playable at ${offer.maxHeight}p")
                return sabrOnly(videoId, offer)
            }
        }
        if (outcome is ResolveResult.Blocked && outcome.failure !in TERMINAL_FAILURES &&
            fallback != null && FALLBACK_CLIENT !in skipClients
        ) {
            val media = try {
                withTimeoutOrNull(fallbackTimeoutMs) { fallback.invoke(videoId, locale) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Resolver", "$videoId fallback: ${e.javaClass.simpleName}")
                null
            }
            if (media != null && (media.muxed.isNotEmpty() || media.videoOnly.isNotEmpty() || media.audioOnly.isNotEmpty() || media.hlsUrl != null)) {
                android.util.Log.i("Resolver", "$videoId fallback: playable")
                return ResolveResult.Playable(media)
            }
        }
        if (outcome is ResolveResult.Playable) return upgradeIfLow(outcome, videoId, locale, skipClients)
        return outcome
    }

    /**
     * YouTube increasingly serves the high rungs without direct URLs, so a client can succeed while exposing only a
     * low ladder. The independent extractor gets a short budget to do better; the direct result survives unless it does.
     */
    private suspend fun upgradeIfLow(
        direct: ResolveResult.Playable,
        videoId: String,
        locale: ContentLocale,
        skipClients: Set<String>,
    ): ResolveResult {
        val media = direct.media
        if (media.isLive || media.clientName == FALLBACK_CLIENT) return direct
        val directHeight = MediaPlanner.availableHeights(media).firstOrNull() ?: 0
        if (directHeight >= UPGRADE_FLOOR) return direct
        val wantExtractor = fallback != null && FALLBACK_CLIENT !in skipClients
        val wantSabr = SABR_ENABLED && poTokens != null && SABR_CLIENT !in skipClients
        if (!wantExtractor && !wantSabr) return direct
        // The extractor and a SABR session race inside one budget; whichever reaches a higher rung wins, and a SABR
        // offer rides along on the winner so the planner can still prefer it under the quality cap.
        val (extracted, offer) = kotlinx.coroutines.coroutineScope {
            val ext = async {
                if (!wantExtractor) null else try {
                    withTimeoutOrNull(UPGRADE_BUDGET_MS) { fallback!!.invoke(videoId, locale) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            val sabr = async {
                if (!wantSabr) null else try {
                    withTimeoutOrNull(UPGRADE_BUDGET_MS) { resolveSabr(videoId, locale) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            ext.await() to sabr.await()
        }
        val extractedHeight = extracted?.let { MediaPlanner.availableHeights(it).firstOrNull() } ?: 0
        val base = if (extracted != null && extractedHeight > directHeight) extracted else media
        val baseHeight = maxOf(directHeight, extractedHeight)
        val withOffer = if (offer != null && offer.maxHeight > baseHeight) base.copy(sabr = offer) else base
        if (withOffer === media) return direct
        android.util.Log.i("Resolver", "$videoId upgraded ${directHeight}p -> extractor=${extractedHeight}p sabr=${offer?.maxHeight ?: 0}p")
        return ResolveResult.Playable(withOffer)
    }

    private fun sabrOnly(videoId: String, offer: SabrOffer) = ResolveResult.Playable(
        ResolvedMedia(
            videoId = videoId, title = null, author = null, durationSeconds = offer.durationMs / 1000,
            isLive = false, hlsUrl = null, muxed = emptyList(), videoOnly = emptyList(), audioOnly = emptyList(),
            captions = emptyList(), expiresAtMillis = null, clientName = SABR_CLIENT,
            userAgent = offer.clientUserAgent, skippedCipherFormats = 0, sabr = offer,
        ),
    )

    /** A bot wall is a verdict about one visitor identity; start the next attempt from a new one. */
    fun rerollVisitor() {
        nativeVisitor = null
        webVisitor = null
        client.resetGuestVisitor()
    }

    private suspend fun ladder(
        videoId: String,
        locale: ContentLocale,
        auth: RequestAuth?,
        skipClients: Set<String>,
    ): ResolveResult {
        var firstBlocked: ResolveResult.Blocked? = null
        var lastError: AppError? = null
        var webTried = false
        for (profile in gates.demoted(profiles) { it.name }) {
            if (profile.name in skipClients) continue
            val started = clock.nowMillis()
            val result = try {
                withTimeoutOrNull(profileTimeoutMs) { request(videoId, locale, null, profile) }
            } catch (e: AppError.Network) {
                android.util.Log.w("Resolver", "$videoId ${profile.name}: network ${e.httpCode ?: e.message}")
                lastError = e
                continue
            } catch (e: AppError.Parse) {
                android.util.Log.w("Resolver", "$videoId ${profile.name}: unreadable response")
                lastError = e
                continue
            }
            if (result == null) {
                android.util.Log.w("Resolver", "$videoId ${profile.name}: timeout after ${clock.nowMillis() - started}ms")
                lastError = AppError.Network(null, "timeout")
                continue
            }
            when (result) {
                is ResolveResult.Playable -> {
                    android.util.Log.i("Resolver", "$videoId ${profile.name}: playable")
                    return result
                }
                is ResolveResult.Blocked -> {
                    android.util.Log.w("Resolver", "$videoId ${profile.name}: blocked ${result.failure} ${result.detail}")
                    if (firstBlocked == null || firstBlocked.failure == PlaybackFailure.BotCheck) firstBlocked = result
                    if (result.failure in TERMINAL_FAILURES) return result
                    // Kids and label videos are UNPLAYABLE on the app clients. A web session serves them at once, where
                    // the TV client holds every byte until its pre-roll window has passed.
                    if (result.failure == PlaybackFailure.Unavailable && !webTried && poTokens != null && WEB_DIRECT_CLIENT !in skipClients) {
                        webTried = true
                        webDirect(videoId, locale)?.let { return it }
                    }
                }
            }
        }
        if (auth != null && client.clientConfig.clientName !in skipClients) {
            val authenticated = try {
                withTimeoutOrNull(profileTimeoutMs) { request(videoId, locale, auth, null) }
                    .also { if (it == null) lastError = AppError.Network(null, "timeout") }
            } catch (e: AppError.SessionExpired) {
                throw e
            } catch (e: AppError.Network) {
                lastError = e
                null
            } catch (e: AppError.Parse) {
                lastError = e
                null
            }
            if (authenticated is ResolveResult.Playable) return authenticated
            if (authenticated is ResolveResult.Blocked && (firstBlocked == null || firstBlocked.failure == PlaybackFailure.BotCheck)) {
                firstBlocked = authenticated
            }
        }
        firstBlocked?.let { return it }
        throw lastError ?: AppError.Playback(PlaybackFailure.Unavailable)
    }

    /**
     * The web player's own direct URLs, attested with the tokens the app mints: the video-bound token on the request and
     * the visitor-bound one on every URL. Null, inside a short budget, sends the ladder on to the next client.
     */
    private suspend fun webDirect(videoId: String, locale: ContentLocale): ResolveResult.Playable? {
        val started = clock.nowMillis()
        val result = try {
            withTimeoutOrNull(WEB_DIRECT_BUDGET_MS) { requestWebDirect(videoId, locale) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val playable = result as? ResolveResult.Playable
        if (playable == null) {
            android.util.Log.w("Resolver", "$videoId $WEB_DIRECT_CLIENT: ${(result as? ResolveResult.Blocked)?.failure ?: "unavailable"} after ${clock.nowMillis() - started}ms")
            return null
        }
        android.util.Log.i("Resolver", "$videoId $WEB_DIRECT_CLIENT: playable +${clock.nowMillis() - started}ms")
        return playable
    }

    private suspend fun requestWebDirect(videoId: String, locale: ContentLocale): ResolveResult? {
        val minter = poTokens ?: return null
        val profile = SABR_PROFILES.first { it.name == WEB_DIRECT_CLIENT }
        val visitor = webVisitorData(profile, locale) ?: return null
        val pot = withTimeoutOrNull(POT_WAIT_MS) { minter.mint(videoId) } ?: return null
        val streamPot = BotGuardCodec.visitorId(visitor)?.let { id -> withTimeoutOrNull(POT_WAIT_MS) { minter.mint(id) } } ?: return null
        val sts = withTimeoutOrNull(SIGNATURE_TIMESTAMP_WAIT_MS) { PipePipeNsigDecoder.signatureTimestamp() } ?: return null
        android.util.Log.d("Resolver", "$videoId $WEB_DIRECT_CLIENT: pot player=${pot.length} stream=${streamPot.length} visitor=${BotGuardCodec.visitorId(visitor)}")
        val response = client.post(
            "player", playerBody(videoId, null, false, pot, sts), locale, null,
            skipLoggedOutCheck = true, profile = profile, visitorOverride = visitor,
        )
        return parse(FormatSolver.solve(response.root), videoId, WEB_DIRECT_CLIENT, profile.userAgent, null, streamPot)
    }

    suspend fun alternateCaptions(videoId: String, locale: ContentLocale, excludeClient: String): ResolvedMedia? {
        for (profile in profiles) {
            if (profile.name == excludeClient) continue
            val media = try {
                (request(videoId, locale, null, profile) as? ResolveResult.Playable)?.media
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (media != null && media.captions.isNotEmpty()) return media
        }
        return null
    }

    @Volatile private var nativeVisitor: Pair<String, Long>? = null
    @Volatile private var webVisitor: Pair<String, Long>? = null

    private suspend fun nativeVisitorData(profile: ClientProfile, locale: ContentLocale): String? {
        val cached = nativeVisitor
        if (cached != null && clock.nowMillis() - cached.second < VISITOR_TTL_MS) return cached.first
        val fresh = try {
            client.post("visitor_id", JsonObject(emptyMap()), locale, null, skipLoggedOutCheck = true, profile = profile, withoutVisitor = true).visitorData
        } catch (e: kotlinx.coroutines.CancellationException) {
            
            throw e
        } catch (e: Exception) {
            null
        }
        if (fresh != null) nativeVisitor = fresh to clock.nowMillis()
        return fresh
    }

    private suspend fun request(videoId: String, locale: ContentLocale, auth: RequestAuth?, profile: ClientProfile?): ResolveResult {
        val native = profile?.nativeApp == true
        val cpn = if (native) randomToken(16) else null
        val tokens = if (profile?.poToken == true) tokensFor(videoId, profile, locale) else null
        val sts = if (profile?.signatureTimestamp == true) {
            withTimeoutOrNull(SIGNATURE_TIMESTAMP_WAIT_MS) { PipePipeNsigDecoder.signatureTimestamp() }
                ?: return ResolveResult.Blocked(PlaybackFailure.Unavailable, "no signature timestamp", profile.name)
        } else null
        val response = if (native && profile != null) {
            val ua = nativeUserAgent(profile, locale)
            val tuned = profile.copy(userAgent = ua)
            client.post(
                "player", playerBody(videoId, cpn, native, tokens?.player, sts), locale, auth, skipLoggedOutCheck = true, profile = tuned,
                query = mapOf("t" to randomToken(12), "id" to videoId),
                visitorOverride = tokens?.visitor ?: nativeVisitorData(tuned, locale),
            )
        } else {
            client.post(
                "player", playerBody(videoId, cpn, native, tokens?.player, sts), locale, auth, skipLoggedOutCheck = true, profile = profile,
                visitorOverride = tokens?.visitor,
            )
        }
        val name = profile?.name ?: client.clientConfig.clientName
        val ua = if (native && profile != null) nativeUserAgent(profile, locale) else profile?.userAgent ?: client.clientConfig.userAgent
        val answeredAt = clock.nowMillis()
        val root = if (profile?.solvesFormats == true) FormatSolver.solve(response.root) else response.root
        val result = parse(root, videoId, name, ua, cpn, tokens?.stream)
        if (result is ResolveResult.Playable && profile?.solvesFormats == true) {
            // An answer with a start ad is refused on every byte of the content until the ad window passes. The wait is
            // bounded by that window but ends as soon as the host serves the first stream.
            val boundMs = PrerollWait.of(root["adPlacements"] as? kotlinx.serialization.json.JsonArray) - (clock.nowMillis() - answeredAt)
            if (boundMs > 0) awaitServed(result.media, boundMs)
        }
        return result
    }

    private class RequestTokens(val visitor: String, val player: String?, val stream: String?)

    /**
     * The tokens for one request. The player token is bound to the video and the stream token to the
     * visitor the request carries, so the visitor is fixed first and sent with the request: a token
     * minted for any other visitor is refused by the stream host. Null means "go without".
     */
    private suspend fun tokensFor(videoId: String, profile: ClientProfile, locale: ContentLocale): RequestTokens? {
        val minter = poTokens ?: return null
        val visitor = nativeVisitorData(profile.copy(userAgent = nativeUserAgent(profile, locale)), locale) ?: return null
        val visitorId = BotGuardCodec.visitorId(visitor) ?: return null
        val player = withTimeoutOrNull(POT_WAIT_MS) { minter.mint(videoId) }
        val stream = withTimeoutOrNull(POT_WAIT_MS) { minter.mint(visitorId) }
        android.util.Log.d("Resolver", "$videoId ${profile.name}: pot player=${player != null} stream=${stream != null}")
        return RequestTokens(visitor, player, stream)
    }

    private fun playerBody(videoId: String, cpn: String?, native: Boolean, playerPot: String?, sts: Int? = null, reloadToken: String? = null) = buildJsonObject {
        put("videoId", videoId)
        if (cpn != null) put("cpn", cpn)
        put("contentCheckOk", true)
        put("racyCheckOk", true)
        if (!native) {
            put("playbackContext", buildJsonObject {
                put("contentPlaybackContext", buildJsonObject {
                    put("html5Preference", "HTML5_PREF_WANTS")
                    if (sts != null) put("signatureTimestamp", sts)
                })
                if (reloadToken != null) {
                    put("reloadPlaybackContext", buildJsonObject {
                        put("reloadPlaybackParams", buildJsonObject { put("token", reloadToken) })
                    })
                }
            })
        }
        if (playerPot != null) {
            put("serviceIntegrityDimensions", buildJsonObject { put("poToken", playerPot) })
        }
    }

    /**
     * A SABR-capable answer from a web client: the player request carries the video-bound token and the signature
     * timestamp, and the answer's `serverAbrStreamingUrl` and ustreamer config open the streaming session. Null when
     * any step is unavailable; callers keep whatever they had.
     *
     * @param reloadToken set when the streaming host demanded a fresh player response mid-session.
     * @param cpn the session playback nonce, kept stable across reloads.
     */
    suspend fun resolveSabr(
        videoId: String,
        locale: ContentLocale,
        reloadToken: String? = null,
        cpn: String? = null,
        clientNameId: Int = MWEB_NAME_ID,
    ): SabrOffer? {
        val minter = poTokens ?: return null
        val profile = SABR_PROFILES.firstOrNull { it.nameId == clientNameId.toString() } ?: SABR_PROFILES.first()
        val visitor = webVisitorData(profile, locale) ?: return null
        val pot = withTimeoutOrNull(POT_WAIT_MS) { minter.mint(videoId) } ?: return null
        val sts = withTimeoutOrNull(SIGNATURE_TIMESTAMP_WAIT_MS) { CipherDeobfuscator.ensureSignatureTimestamp() }
        val sessionCpn = cpn ?: randomToken(16)
        // The streaming host binds its token to the visitor, the player endpoint binds its to the video.
        val sessionPot = BotGuardCodec.visitorId(visitor)?.let { id -> withTimeoutOrNull(POT_WAIT_MS) { minter.mint(id) } } ?: pot
        val response = try {
            withTimeoutOrNull(WEB_PLAYER_TIMEOUT_MS) {
                client.post(
                    "player", playerBody(videoId, sessionCpn, false, pot, sts, reloadToken), locale, null,
                    skipLoggedOutCheck = true, profile = profile, visitorOverride = visitor,
                )
            }
        } catch (e: AppError.Network) {
            null
        } catch (e: AppError.Parse) {
            null
        } ?: return null
        val root = response.root
        if (root.path("playabilityStatus", "status").str != "OK") return null
        val streaming = root.at("streamingData") ?: return null
        val rawUrl = streaming.at("serverAbrStreamingUrl").str?.takeIf { it.isNotEmpty() } ?: return null
        val config = root.path("playerConfig", "mediaCommonConfig", "mediaUstreamerRequestConfig", "videoPlaybackUstreamerConfig").str
            ?.takeIf { it.isNotEmpty() }?.let(SabrBase64::decode)?.takeIf { it.isNotEmpty() } ?: return null
        val formats = streaming.at("adaptiveFormats").list().mapNotNull { f ->
            SabrFormat(
                itag = f.at("itag").int ?: return@mapNotNull null,
                lastModified = f.at("lastModified").str?.toLongOrNull() ?: 0L,
                xtags = f.at("xtags").str.orEmpty(),
                mimeType = f.at("mimeType").str ?: return@mapNotNull null,
                bitrate = f.at("averageBitrate").int ?: f.at("bitrate").int ?: 0,
                height = f.at("height").int ?: 0,
                audioTrackId = f.path("audioTrack", "id").str,
                isAutoDubbed = f.path("audioTrack", "isAutoDubbed").bool == true,
                isAudio = (f.at("mimeType").str ?: "").startsWith("audio/"),
            )
        }
        if (formats.none { !it.isAudio } || formats.none { it.isAudio }) return null
        // The streaming URL carries a throttling `n` like any other web URL.
        val url = CipherDeobfuscator.transformNParamInUrl(rawUrl).takeIf { it != rawUrl }
            ?: if (PipePipeNsigDecoder.rawN(rawUrl) != null) PipePipeNsigDecoder.deobfuscateUrl(rawUrl) ?: rawUrl else rawUrl
        val durationMs = streaming.at("adaptiveFormats").list().firstNotNullOfOrNull { it.at("approxDurationMs").str?.toLongOrNull() }
            ?: root.path("videoDetails", "lengthSeconds").str?.toLongOrNull()?.times(1000L) ?: 0L
        return SabrOffer(
            streamingUrl = url,
            ustreamerConfig = config,
            poToken = sessionPot,
            visitorId = visitor,
            cpn = sessionCpn,
            clientNameId = profile.nameId.toInt(),
            clientVersion = profile.version,
            clientUserAgent = profile.userAgent,
            durationMs = durationMs,
            formats = formats,
        )
    }

    private suspend fun webVisitorData(profile: ClientProfile, locale: ContentLocale): String? {
        val cached = webVisitor
        if (cached != null && clock.nowMillis() - cached.second < VISITOR_TTL_MS) return cached.first
        val fresh = try {
            client.post("visitor_id", JsonObject(emptyMap()), locale, null, skipLoggedOutCheck = true, profile = profile, withoutVisitor = true).visitorData
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (fresh != null) webVisitor = fresh to clock.nowMillis()
        return fresh
    }

    /** A visitor identity for callers outside the ladder (the fallback extractor), so its tokens match what it sends. */
    suspend fun visitorData(locale: ContentLocale = ContentLocale.Default): String? =
        profiles.firstOrNull { it.nativeApp }?.let { nativeVisitorData(it.copy(userAgent = nativeUserAgent(it, locale)), locale) }

    /** The stream host refused a token it was given; the next attempt must attest again. */
    fun reportTokenRejected() {
        poTokens?.invalidate()
    }

    /** Polls the first video stream with a one-byte range until it is served or [maxMs] has passed. */
    private suspend fun awaitServed(media: ResolvedMedia, maxMs: Long) {
        val url = (media.videoOnly + media.muxed).firstOrNull()?.url ?: media.audioOnly.firstOrNull()?.url
        if (url == null) {
            kotlinx.coroutines.delay(maxMs)
            return
        }
        val deadline = clock.nowMillis() + maxMs
        val http = CipherHttp.get()
        var attempt = 0
        while (true) {
            val served = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val probe = okhttp3.Request.Builder()
                        .url(url + "&rn=${attempt++}&range=0-1")
                        .post(byteArrayOf(0x78, 0).toRequestBody())
                        .header("User-Agent", media.userAgent)
                        .build()
                    http.newCall(probe).execute().use { it.isSuccessful }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (served || clock.nowMillis() >= deadline) return
            kotlinx.coroutines.delay(PROBE_INTERVAL_MS)
        }
    }

    private fun nativeUserAgent(profile: ClientProfile, locale: ContentLocale): String =
        profile.userAgent.replace("{region}", locale.region)

    fun parse(root: JsonElement, videoId: String, clientName: String, userAgent: String, cpn: String? = null, pot: String? = null): ResolveResult {
        val status = root.path("playabilityStatus", "status").str
        val reason = root.path("playabilityStatus", "reason").str
            ?: root.firstObject("playerErrorMessageRenderer").at("reason").textOf()
        if (status != "OK" && status != null) {
            val scheduled = root.path("playabilityStatus", "liveStreamability", "liveStreamabilityRenderer", "offlineSlate", "liveStreamOfflineSlateRenderer", "scheduledStartTime")
                .str?.toLongOrNull()?.times(1000L)
            return ResolveResult.Blocked(classify(status, reason, root), reason, clientName, scheduled)
        }
        val streaming = root.at("streamingData")
        if (streaming == null) {
            return ResolveResult.Blocked(PlaybackFailure.Unavailable, reason, clientName)
        }
        if (root.toString().contains("\"drmFamilies\"") || streaming.toString().contains("\"drmTrackType\"")) {
            return ResolveResult.Blocked(PlaybackFailure.DrmUnsupported, reason, clientName)
        }
        android.util.Log.d(
            "Resolver",
            "$videoId $clientName: formats direct=${streaming.at("formats").list().count { it.at("url").str != null } + streaming.at("adaptiveFormats").list().count { it.at("url").str != null }} " +
                "ciphered=${streaming.at("formats").list().count { it.at("signatureCipher") != null } + streaming.at("adaptiveFormats").list().count { it.at("signatureCipher") != null }} " +
                "abr=${streaming.at("serverAbrStreamingUrl").str != null} hls=${streaming.at("hlsManifestUrl").str != null}",
        )
        var skipped = 0
        fun parseFormats(key: String): List<StreamFormat> = streaming.at(key).list().mapNotNull { f ->
            val url = f.at("url").str?.let { raw -> raw + (if (cpn != null) "&cpn=$cpn" else "") + (if (pot != null) "&pot=$pot" else "") }
            if (url == null) {
                if (f.at("signatureCipher") != null || f.at("cipher") != null) skipped++
                return@mapNotNull null
            }
            val mime = f.at("mimeType").str ?: return@mapNotNull null
            StreamFormat(
                itag = f.at("itag").int ?: return@mapNotNull null,
                url = url,
                mimeType = mime.substringBefore(';').trim(),
                codecs = Regex("codecs=\"([^\"]+)\"").find(mime)?.groupValues?.get(1),
                bitrate = f.at("bitrate").int ?: f.at("averageBitrate").int ?: 0,
                width = f.at("width").int,
                height = f.at("height").int,
                fps = f.at("fps").int,
                qualityLabel = f.at("qualityLabel").str,
                audioTrackId = f.path("audioTrack", "id").str,
                audioTrackName = f.path("audioTrack", "displayName").str,
                isDefaultAudio = f.path("audioTrack", "audioIsDefault").bool == true,
                contentLength = f.at("contentLength").str?.toLongOrNull(),
                audioQuality = f.at("audioQuality").str,
                audioSampleRate = f.at("audioSampleRate").str?.toIntOrNull(),
                audioChannels = f.at("audioChannels").int,
                isDrc = f.at("isDrc").bool == true || f.at("xtags").str?.contains("drc") == true,
            )
        }
        val muxed = parseFormats("formats")
        val adaptive = parseFormats("adaptiveFormats")
        val details = root.at("videoDetails")
        val isLive = details.at("isLive").bool == true || details.at("isLiveContent").bool == true && streaming.at("hlsManifestUrl") != null
        val hls = streaming.at("hlsManifestUrl").str
        if (muxed.isEmpty() && adaptive.isEmpty() && hls == null && streaming.at("dashManifestUrl").str == null) {
            return ResolveResult.Blocked(
                if (skipped > 0) PlaybackFailure.CipherUnsupported else PlaybackFailure.NoCompatibleStream,
                reason,
                clientName,
            )
        }
        val expires = streaming.at("expiresInSeconds").str?.toLongOrNull()?.let { clock.nowMillis() + it * 1000 }
        val captions = root.path("captions", "playerCaptionsTracklistRenderer", "captionTracks").list().mapNotNull { c ->
            val base = c.at("baseUrl").str ?: return@mapNotNull null
            CaptionTrack(
                languageCode = c.at("languageCode").str ?: return@mapNotNull null,
                name = c.at("name").textOf() ?: c.at("languageCode").str.orEmpty(),
                url = base,
                isAutoGenerated = c.at("kind").str == "asr",
                isTranslatable = c.at("isTranslatable").bool == true,
            )
        }
        val captionTranslations = root.path("captions", "playerCaptionsTracklistRenderer", "translationLanguages").list()
            .mapNotNull { it.at("languageCode").str }
        return ResolveResult.Playable(
            ResolvedMedia(
                videoId = videoId,
                title = details.at("title").str,
                author = details.at("author").str,
                durationSeconds = details.at("lengthSeconds").str?.toLongOrNull(),
                isLive = isLive,
                hlsUrl = hls,
                dashUrl = streaming.at("dashManifestUrl").str,
                muxed = muxed.filter { it.isVideo },
                videoOnly = adaptive.filter { it.isVideo },
                audioOnly = adaptive.filter { it.isAudio },
                captions = captions,
                captionTranslations = captionTranslations,
                expiresAtMillis = expires,
                loudnessDb = root.path("playerConfig", "audioConfig", "loudnessDb")?.toString()?.toDoubleOrNull(),
                clientName = clientName,
                userAgent = userAgent,
                skippedCipherFormats = skipped,
                publishedAt = root.path("microformat", "playerMicroformatRenderer", "publishDate").str
                    ?: root.path("microformat", "playerMicroformatRenderer", "uploadDate").str,
            ),
        )
    }

    companion object {
        private const val VISITOR_TTL_MS = 60L * 60 * 1000
        const val PROFILE_TIMEOUT_MS = 7_000L
        const val FALLBACK_TIMEOUT_MS = 12_000L
        private const val POT_WAIT_MS = 3_000L
        private const val WEB_PLAYER_TIMEOUT_MS = 10_000L
        private const val MWEB_NAME_ID = 2

        /** Skip-list entry that keeps a failed SABR session from being offered again for the same video. */
        const val SABR_CLIENT = "SABR"

        /** Web clients whose token the app can mint; MWEB first, as it carries every dubbed audio track. */
        val SABR_PROFILES: List<ClientProfile> = listOf(
            ClientProfile(
                name = "MWEB",
                version = "2.20250122.04.00",
                nameId = "2",
                userAgent = ClientUserAgents.MWEB,
                platform = "MOBILE",
                signatureTimestamp = true,
                extras = mapOf("utcOffsetMinutes" to JsonPrimitive(0)),
            ),
            ClientProfile(
                name = "WEB",
                version = "2.20260710.06.00",
                nameId = "1",
                userAgent = ClientUserAgents.WEB,
                platform = "DESKTOP",
                signatureTimestamp = true,
                extras = mapOf("utcOffsetMinutes" to JsonPrimitive(0)),
            ),
        )
        const val UPGRADE_FLOOR = 720
        private const val PROBE_INTERVAL_MS = 250L

        /**
         * SABR sessions open but the streaming host keeps them at "attestation pending" and serves no media for the
         * token this app can mint, so offering them only costs time before the next client. The engine stays in the
         * tree; flip this when a token the host trusts is available.
         */
        const val SABR_ENABLED = false
        private const val SABR_LAST_RESORT_MS = 12_000L

        /** Kids and label videos go to the web player before the TV client; see [webDirect]. */
        const val WEB_DIRECT_CLIENT = "MWEB"
        private const val WEB_DIRECT_BUDGET_MS = 4_000L
        const val UPGRADE_BUDGET_MS = 3_000L
        private val VISITOR_WALLED_CLIENTS = setOf("VISIONOS", "ANDROID_VR")
        private const val SIGNATURE_TIMESTAMP_WAIT_MS = 4_000L

        /** The name the fallback extractor stamps on its media, so a failed one can be skipped on the next load. */
        const val FALLBACK_CLIENT = "NewPipeExtractor"
        private const val TOKEN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        private val random = java.security.SecureRandom()

        private fun randomToken(length: Int): String =
            buildString(length) { repeat(length) { append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)]) } }

        private val TERMINAL_FAILURES = setOf(
            PlaybackFailure.Private,
            PlaybackFailure.MembersOnly,
            PlaybackFailure.Removed,
            PlaybackFailure.RegionBlocked,
            PlaybackFailure.DrmUnsupported,
            PlaybackFailure.Upcoming,
        )

        fun classify(status: String, reason: String?, root: JsonElement): PlaybackFailure {
            val text = (reason ?: "").lowercase()
            return when {
                text.contains("members-only") || text.contains("members only") || text.contains("join this channel") || text.contains("hội viên") -> PlaybackFailure.MembersOnly
                text.contains("not a bot") || text.contains("bot") -> PlaybackFailure.BotCheck
                text.contains("age") || status == "AGE_VERIFICATION_REQUIRED" || status == "CONTENT_CHECK_REQUIRED" -> PlaybackFailure.AgeRestricted
                text.contains("private") -> PlaybackFailure.Private
                text.contains("country") || text.contains("region") || text.contains("location") -> PlaybackFailure.RegionBlocked
                text.contains("removed") || text.contains("deleted") || text.contains("terminated") || text.contains("no longer available") -> PlaybackFailure.Removed
                status == "LOGIN_REQUIRED" -> PlaybackFailure.LoginRequired
                status == "LIVE_STREAM_OFFLINE" || text.contains("premiere") || text.contains("will begin") || text.contains("công chiếu") -> PlaybackFailure.Upcoming
                else -> PlaybackFailure.Unavailable
            }
        }

        /**
         * Token-free VISIONOS first, then the clients whose proof of origin the app can mint (IOS, ANDROID). ANDROID_VR comes
         * last: unattested, it is the client the stream host cuts off about a minute in.
         */
        private fun vrProfile(version: String, osVersion: String, model: String, userAgent: String) = ClientProfile(
            name = "ANDROID_VR",
            version = version,
            nameId = "28",
            userAgent = userAgent,
            extras = mapOf(
                "deviceMake" to JsonPrimitive("Oculus"),
                "deviceModel" to JsonPrimitive("Quest 3"),
                "osName" to JsonPrimitive("Android"),
                "osVersion" to JsonPrimitive(osVersion),
                "androidSdkVersion" to JsonPrimitive(32),
            ),
        )

        val DEFAULT_PROFILES: List<ClientProfile> = listOf(
            
            ClientProfile(
                name = "VISIONOS",
                version = "1.04",
                nameId = "101",
                userAgent = ClientUserAgents.VISIONOS,
                platform = "MOBILE",
                baseUrl = "https://youtubei.googleapis.com/",
                nativeApp = true,
                extraHeaders = mapOf("X-Goog-Api-Format-Version" to "2"),
                extras = mapOf(
                    "clientScreen" to JsonPrimitive("WATCH"),
                    "deviceMake" to JsonPrimitive("Apple"),
                    "deviceModel" to JsonPrimitive("RealityDevice17,1"),
                    "osName" to JsonPrimitive("visionOS"),
                    "osVersion" to JsonPrimitive("26.6.0.23O770"),
                    "utcOffsetMinutes" to JsonPrimitive(0),
                ),
            ),
            // Keeps serving a visitor the app clients have been walled for; every format is signed, so the
            // signature timestamp and the solve are part of the request.
            ClientProfile(
                name = "TVHTML5",
                version = "5.20260707",
                nameId = "7",
                userAgent = ClientUserAgents.TV_TIZEN,
                signatureTimestamp = true,
                solvesFormats = true,
                extras = mapOf(
                    "osName" to JsonPrimitive("Tizen"),
                    "osVersion" to JsonPrimitive("2.4.0"),
                    "deviceMake" to JsonPrimitive("Samsung"),
                    "deviceModel" to JsonPrimitive("SmartTV"),
                ),
            ),
            ClientProfile(
                name = "IOS",
                poToken = true,
                version = "20.10.4",
                nameId = "5",
                userAgent = ClientUserAgents.IOS,
                extras = mapOf(
                    "deviceMake" to JsonPrimitive("Apple"),
                    "deviceModel" to JsonPrimitive("iPhone16,2"),
                    "osName" to JsonPrimitive("iPhone"),
                    "osVersion" to JsonPrimitive("18.3.2.22D82"),
                ),
            ),
            ClientProfile(
                name = "ANDROID",
                poToken = true,
                version = "20.10.38",
                nameId = "3",
                userAgent = ClientUserAgents.ANDROID,
                extras = mapOf(
                    "osName" to JsonPrimitive("Android"),
                    "osVersion" to JsonPrimitive("11"),
                    "androidSdkVersion" to JsonPrimitive(30),
                ),
            ),
            vrProfile("1.65.10", "12L", "eureka-user", ClientUserAgents.ANDROID_VR),
            vrProfile("1.61.48", "12", "Quest 3", ClientUserAgents.ANDROID_VR_1_61_48),
            vrProfile("1.43.32", "12", "Quest 3", ClientUserAgents.ANDROID_VR_1_43_32),
        )
    }
}
