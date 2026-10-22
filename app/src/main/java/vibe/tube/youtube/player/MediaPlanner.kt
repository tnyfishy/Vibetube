package vibe.tube.youtube.player

object MediaPlanner {

    fun availableHeights(media: ResolvedMedia): List<Int> {
        val adaptive = media.videoOnly.filter { it.isMp4Family || it.mimeType.contains("webm") }.mapNotNull { it.height }
        val muxed = media.muxed.mapNotNull { it.height }
        return (adaptive + muxed).distinct().sortedDescending()
    }

    fun audioTracks(media: ResolvedMedia): List<AudioTrackOption> =
        media.audioOnly.filter { it.audioTrackId != null }
            .distinctBy { it.audioTrackId }
            .map { AudioTrackOption(it.audioTrackId!!, it.audioTrackName ?: it.audioTrackId, it.isDefaultAudio) }

    fun plan(
        media: ResolvedMedia,
        maxHeight: Int?,
        preferredAudioTrack: String? = null,
        allowWebm: Boolean = true,
        maxFps: Int? = null,
    ): MediaPlan? {
        if (media.isLive && media.hlsUrl != null) return MediaPlan(PlanKind.Hls, null, null, null, media.hlsUrl, null)

        val videos = media.videoOnly.filter { it.isVideo && (it.isMp4Family || (allowWebm && it.mimeType.contains("webm"))) }
        val audios = media.audioOnly.filter { it.isAudio && (allowWebm || it.isMp4Family) }
        if (videos.isNotEmpty() && audios.isNotEmpty()) {
            val audio = pickAudio(audios, preferredAudioTrack)
            val video = pickVideo(videos, maxHeight, maxFps)
            if (video != null && audio != null) return MediaPlan(PlanKind.Adaptive, video, audio, null, null, video.height)
        }
        if (media.muxed.isNotEmpty()) {
            val chosen = pickByHeight(media.muxed, maxHeight, maxFps)
            if (chosen != null) return MediaPlan(PlanKind.Muxed, null, null, chosen, null, chosen.height)
        }
        if (media.hlsUrl != null) return MediaPlan(PlanKind.Hls, null, null, null, media.hlsUrl, null)
        return null
    }

    private fun pickAudio(audios: List<StreamFormat>, preferredTrack: String?): StreamFormat? {
        val pool = preferredTrack?.let { id -> audios.filter { it.audioTrackId == id } }?.takeIf { it.isNotEmpty() }
            ?: audios.filter { it.audioTrackId == null || it.isDefaultAudio }.takeIf { it.isNotEmpty() }
            ?: audios
        return pool.sortedWith(compareByDescending<StreamFormat> { it.isAac }.thenByDescending { it.bitrate }).firstOrNull()
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

    /** Keeps formats at or below [maxFps]; if none qualify (e.g. only a 60 fps stream exists) keeps them all. */
    private fun withinFps(formats: List<StreamFormat>, maxFps: Int?): List<StreamFormat> =
        if (maxFps == null) formats else formats.filter { (it.fps ?: 0) <= maxFps }.ifEmpty { formats }
}
