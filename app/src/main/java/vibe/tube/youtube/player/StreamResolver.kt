package vibe.tube.youtube.player

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

class StreamResolver(
    private val client: InnerTubeClient,
    private val clock: Clock = Clock.System,
    private val profiles: List<ClientProfile> = DEFAULT_PROFILES,
) {

    /**
     * Resolves playable streams, trying each client profile in turn. [skipClients] lists client names
     * whose stream URLs already failed during playback (e.g. HTTP 403), so the next client is used.
     */
    suspend fun resolve(
        videoId: String,
        locale: ContentLocale,
        auth: RequestAuth?,
        skipClients: Set<String> = emptySet(),
    ): ResolveResult {
        var firstBlocked: ResolveResult.Blocked? = null
        var lastError: AppError? = null
        for (profile in profiles) {
            if (profile.name in skipClients) continue
            val result = try {
                request(videoId, locale, null, profile)
            } catch (e: AppError.Network) {
                lastError = e
                continue
            }
            when (result) {
                is ResolveResult.Playable -> return result
                is ResolveResult.Blocked -> {
                    if (firstBlocked == null || firstBlocked.failure == PlaybackFailure.BotCheck) firstBlocked = result
                    if (result.failure in TERMINAL_FAILURES) return result
                }
            }
        }
        if (auth != null && client.clientConfig.clientName !in skipClients) {
            val authenticated = try {
                request(videoId, locale, auth, null)
            } catch (e: AppError.SessionExpired) {
                throw e
            } catch (e: AppError.Network) {
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
     * Captions as another client reports them, for when the playing client's caption files turn out to be empty (some
     * clients' timedtext links need extra proof tokens). Returns the first other client that lists any caption track.
     */
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

    /** Visitor id for the native (visionOS) client, fetched from its own `visitor_id` endpoint like NewPipe does. */
    private suspend fun nativeVisitorData(profile: ClientProfile, locale: ContentLocale): String? {
        val cached = nativeVisitor
        if (cached != null && clock.nowMillis() - cached.second < VISITOR_TTL_MS) return cached.first
        val fresh = runCatching {
            client.post("visitor_id", JsonObject(emptyMap()), locale, null, skipLoggedOutCheck = true, profile = profile, withoutVisitor = true).visitorData
        }.getOrNull()
        if (fresh != null) nativeVisitor = fresh to clock.nowMillis()
        return fresh
    }

    private suspend fun request(videoId: String, locale: ContentLocale, auth: RequestAuth?, profile: ClientProfile?): ResolveResult {
        val native = profile?.nativeApp == true
        val cpn = if (native) randomToken(16) else null
        val body = buildJsonObject {
            put("videoId", videoId)
            if (cpn != null) put("cpn", cpn)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            if (!native) {
                put("playbackContext", buildJsonObject {
                    put("contentPlaybackContext", buildJsonObject {
                        put("html5Preference", "HTML5_PREF_WANTS")
                    })
                })
            }
        }
        val response = if (native && profile != null) {
            val ua = nativeUserAgent(profile, locale)
            val tuned = profile.copy(userAgent = ua)
            client.post(
                "player", body, locale, auth, skipLoggedOutCheck = true, profile = tuned,
                query = mapOf("t" to randomToken(12), "id" to videoId),
                visitorOverride = nativeVisitorData(tuned, locale),
            )
        } else {
            client.post("player", body, locale, auth, skipLoggedOutCheck = true, profile = profile)
        }
        val name = profile?.name ?: client.clientConfig.clientName
        val ua = if (native && profile != null) nativeUserAgent(profile, locale) else profile?.userAgent ?: client.clientConfig.userAgent
        return parse(response.root, videoId, name, ua, cpn)
    }

    private fun nativeUserAgent(profile: ClientProfile, locale: ContentLocale): String =
        profile.userAgent.replace("{region}", locale.region)

    fun parse(root: JsonElement, videoId: String, clientName: String, userAgent: String, cpn: String? = null): ResolveResult {
        val status = root.path("playabilityStatus", "status").str
        val reason = root.path("playabilityStatus", "reason").str
            ?: root.firstObject("playerErrorMessageRenderer").at("reason").textOf()
        if (status != "OK" && status != null) {
            return ResolveResult.Blocked(classify(status, reason, root), reason, clientName)
        }
        val streaming = root.at("streamingData")
        if (streaming == null) {
            return ResolveResult.Blocked(PlaybackFailure.Unavailable, reason, clientName)
        }
        if (root.toString().contains("\"drmFamilies\"") || streaming.toString().contains("\"drmTrackType\"")) {
            return ResolveResult.Blocked(PlaybackFailure.DrmUnsupported, reason, clientName)
        }
        var skipped = 0
        fun parseFormats(key: String): List<StreamFormat> = streaming.at(key).list().mapNotNull { f ->
            val url = f.at("url").str?.let { if (cpn != null) "$it&cpn=$cpn" else it }
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
            )
        }
        val muxed = parseFormats("formats")
        val adaptive = parseFormats("adaptiveFormats")
        val details = root.at("videoDetails")
        val isLive = details.at("isLive").bool == true || details.at("isLiveContent").bool == true && streaming.at("hlsManifestUrl") != null
        val hls = streaming.at("hlsManifestUrl").str
        if (muxed.isEmpty() && adaptive.isEmpty() && hls == null) {
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
                muxed = muxed.filter { it.isVideo },
                videoOnly = adaptive.filter { it.isVideo },
                audioOnly = adaptive.filter { it.isAudio },
                captions = captions,
                captionTranslations = captionTranslations,
                expiresAtMillis = expires,
                clientName = clientName,
                userAgent = userAgent,
                skippedCipherFormats = skipped,
            ),
        )
    }

    companion object {
        private const val VISITOR_TTL_MS = 60L * 60 * 1000
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
                status == "LIVE_STREAM_OFFLINE" -> PlaybackFailure.Unavailable
                else -> PlaybackFailure.Unavailable
            }
        }

        val DEFAULT_PROFILES: List<ClientProfile> = listOf(
            // The client NewPipe currently streams with: no PO token, no signature cipher.
            ClientProfile(
                name = "VISIONOS",
                version = "1.04",
                nameId = "101",
                userAgent = "com.google.visionos.youtube/1.04(RealityDevice17,1; U; CPU visionOS 26_6_0 like Mac OS X; {region})",
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
            ClientProfile(
                name = "ANDROID_VR",
                version = "1.65.10",
                nameId = "28",
                userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                extras = mapOf(
                    "deviceMake" to JsonPrimitive("Oculus"),
                    "deviceModel" to JsonPrimitive("Quest 3"),
                    "osName" to JsonPrimitive("Android"),
                    "osVersion" to JsonPrimitive("12L"),
                    "androidSdkVersion" to JsonPrimitive(32),
                ),
            ),
            ClientProfile(
                name = "IOS",
                version = "20.10.4",
                nameId = "5",
                userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
                extras = mapOf(
                    "deviceMake" to JsonPrimitive("Apple"),
                    "deviceModel" to JsonPrimitive("iPhone16,2"),
                    "osName" to JsonPrimitive("iPhone"),
                    "osVersion" to JsonPrimitive("18.3.2.22D82"),
                ),
            ),
            ClientProfile(
                name = "ANDROID",
                version = "20.10.38",
                nameId = "3",
                userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip",
                extras = mapOf(
                    "osName" to JsonPrimitive("Android"),
                    "osVersion" to JsonPrimitive("11"),
                    "androidSdkVersion" to JsonPrimitive(30),
                ),
            ),
        )
    }
}
