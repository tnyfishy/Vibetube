package vibe.tube.youtube.player

object MediaPlanner {

    fun availableHeights(media: ResolvedMedia): List<Int> {
        val adaptive = media.videoOnly.filter { it.isMp4Family || it.mimeType.contains("webm") }.mapNotNull { it.height }
        val muxed = media.muxed.mapNotNull { it.height }
        val sabr = media.sabr?.heights.orEmpty()
        return (adaptive + muxed + sabr).distinct().sortedDescending()
    }

    fun audioTracks(media: ResolvedMedia): List<AudioTrackOption> =
        media.audioOnly.filter { it.audioTrackId != null }
            .distinctBy { it.audioTrackId }
            .map { AudioTrackOption(it.audioTrackId!!, it.audioTrackName ?: it.audioTrackId, it.isDefaultAudio) }

    fun audioTrackFor(media: ResolvedMedia, language: String): String? {
        val tracks = audioTracks(media)
        if (tracks.size < 2) return null
        fun base(tag: String) = tag.substringBefore('-').substringBefore('_').substringBefore('.').lowercase()
        val wanted = base(language)
        val matching = tracks.filter { base(it.id) == wanted }
        return (matching.firstOrNull { it.isDefault } ?: matching.firstOrNull())?.id
    }

    /** The first of [languages] the media has a dubbed track for, or null to keep the original. */
    fun audioTrackFor(media: ResolvedMedia, languages: List<String>): String? =
        languages.firstNotNullOfOrNull { audioTrackFor(media, it) }

    /** The language most spoken in [region], an ISO country code, or null when no locale names it. */
    fun languageOf(region: String): String? =
        java.util.Locale.getAvailableLocales().firstOrNull { it.country.equals(region, ignoreCase = true) && it.language.isNotEmpty() }?.language

    fun plan(
        media: ResolvedMedia,
        maxHeight: Int?,
        preferredAudioTrack: String? = null,
        allowWebm: Boolean = true,
        maxFps: Int? = null,
        audioQuality: vibe.tube.data.settings.AudioQuality = vibe.tube.data.settings.AudioQuality.Highest,
        decodable: (StreamFormat) -> Boolean = { true },
        allowSabr: Boolean = false,
        preferredCodec: String? = null,
    ): MediaPlan? {
        if (media.isLive && media.hlsUrl != null) return MediaPlan(PlanKind.Hls, null, null, null, media.hlsUrl, null)
        if (allowSabr && !media.isLive) sabrPlan(media, maxHeight, preferredCodec, allowWebm, decodable)?.let { return it }

        val allVideos = media.videoOnly.filter { it.isVideo && (it.isMp4Family || (allowWebm && it.mimeType.contains("webm"))) }
        // Streams the device cannot decode are left out, unless that would leave nothing.
        val videos = allVideos.filter(decodable).ifEmpty { allVideos }
        val audios = media.audioOnly.filter { it.isAudio && (allowWebm || it.isMp4Family) }
        if (videos.isNotEmpty() && audios.isNotEmpty()) {
            val audio = pickAudio(audios, preferredAudioTrack, audioQuality)
            val video = pickVideo(videos, maxHeight, maxFps)
            if (video != null && audio != null) return MediaPlan(PlanKind.Adaptive, video, audio, null, null, video.height)
        }
        if (media.muxed.isNotEmpty()) {
            val chosen = pickByHeight(media.muxed.filter(decodable).ifEmpty { media.muxed }, maxHeight, maxFps)
            if (chosen != null) return MediaPlan(PlanKind.Muxed, null, null, chosen, null, chosen.height)
        }
        if (media.hlsUrl != null) return MediaPlan(PlanKind.Hls, null, null, null, media.hlsUrl, null)
        if (media.dashUrl != null) return MediaPlan(PlanKind.Dash, null, null, null, null, null, media.dashUrl)
        return null
    }

    /**
     * SABR is an upgrade: it is chosen only when no direct stream under the cap reaches its height, or when there is
     * no direct stream at all, so a video that plays directly can never regress onto a session that might be less reliable.
     */
    private fun sabrPlan(media: ResolvedMedia, maxHeight: Int?, codec: String?, allowWebm: Boolean, decodable: (StreamFormat) -> Boolean): MediaPlan? {
        val offer = media.sabr ?: return null
        val info = offer.select(maxHeight, codec) ?: return null
        val directVideos = media.videoOnly.filter { it.isVideo && (it.isMp4Family || (allowWebm && it.mimeType.contains("webm"))) }.filter(decodable)
        val directHeights = (directVideos.mapNotNull { it.height } + media.muxed.mapNotNull { it.height })
            .filter { maxHeight == null || it <= maxHeight }
        val directBest = directHeights.maxOrNull() ?: 0
        val hasDirect = directVideos.isNotEmpty() && media.audioOnly.isNotEmpty() || media.muxed.isNotEmpty()
        if (hasDirect && info.videoHeight <= directBest) return null
        return MediaPlan(PlanKind.Sabr, null, null, null, null, info.videoHeight, null, info)
    }

    internal fun pickAudio(audios: List<StreamFormat>, preferredTrack: String?, quality: vibe.tube.data.settings.AudioQuality = vibe.tube.data.settings.AudioQuality.Highest): StreamFormat? {
        val pool = preferredTrack?.let { id -> audios.filter { it.audioTrackId == id } }?.takeIf { it.isNotEmpty() }
            ?: audios.filter { it.audioTrackId == null || it.isDefaultAudio }.takeIf { it.isNotEmpty() }
            ?: audios
        val original = pool.filter { !it.isDrc }.ifEmpty { pool }
        val ranked = original.sortedWith(
            compareByDescending<StreamFormat> { it.audioTier }
                .thenByDescending { it.bitrate }
                .thenByDescending { it.audioChannels ?: 0 }
                .thenByDescending { it.audioSampleRate ?: 0 }
                .thenByDescending { it.isAac },
        ).distinctBy { listOf(it.audioTier, it.bitrate, it.mimeType, it.audioChannels, it.audioSampleRate) }
        return when (quality) {
            vibe.tube.data.settings.AudioQuality.Highest -> ranked.firstOrNull()
            vibe.tube.data.settings.AudioQuality.Medium -> ranked.getOrNull(ranked.size / 2)
            vibe.tube.data.settings.AudioQuality.Lowest -> ranked.lastOrNull()
        }
    }

    private fun pickVideo(videos: List<StreamFormat>, maxHeight: Int?, maxFps: Int?): StreamFormat? {
        val capped = if (maxHeight == null) videos else videos.filter { (it.height ?: 0) <= maxHeight }.ifEmpty { videos }
        val targetHeight = if (maxHeight == null) capped.maxOf { it.height ?: 0 } else capped.maxOf { it.height ?: 0 }
        val atHeight = capped.filter { (it.height ?: 0) == targetHeight }
            .let { all -> withinFps(all, maxFps) }
        return atHeight.sortedWith(
            compareByDescending<StreamFormat> { it.isAvc }
                .thenByDescending { it.fps ?: 0 }
                .thenByDescending { it.bitrate },
        ).firstOrNull()
    }

    private fun pickByHeight(formats: List<StreamFormat>, maxHeight: Int?, maxFps: Int?): StreamFormat? {
        val capped = if (maxHeight == null) formats else formats.filter { (it.height ?: 0) <= maxHeight }.ifEmpty { listOf(formats.minBy { it.height ?: Int.MAX_VALUE }) }
        return withinFps(capped, maxFps).maxByOrNull { (it.height ?: 0) * 10_000L + it.bitrate }
    }

    private fun withinFps(formats: List<StreamFormat>, maxFps: Int?): List<StreamFormat> =
        if (maxFps == null) formats else formats.filter { (it.fps ?: 0) <= maxFps }.ifEmpty { formats }
}
