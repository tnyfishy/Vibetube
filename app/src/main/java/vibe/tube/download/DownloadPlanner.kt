package vibe.tube.download

import vibe.tube.youtube.player.ResolvedMedia
import vibe.tube.youtube.player.StreamFormat

/** What to fetch for one download: a single stream saved as-is, or video + audio merged locally. */
sealed interface DownloadPlan {
    data class Single(val format: StreamFormat) : DownloadPlan
    data class Mux(val video: StreamFormat, val audio: StreamFormat) : DownloadPlan {
        val webm: Boolean get() = !video.isMp4Family
    }
}

/**
 * Lists the qualities a video offers and picks the streams for the one the user chose (default: tallest resolution, then highest frame rate);
 * only codec/container pairs that [android.media.MediaMuxer] can write are considered (AVC in MP4,
 * VP9 in WebM, and AV1 in MP4 on Android 14+).
 */
object DownloadPlanner {

    private fun isVp9(f: StreamFormat) = f.codecs?.let { it.startsWith("vp9") || it.startsWith("vp09") } == true
    private fun isAv1(f: StreamFormat) = f.codecs?.startsWith("av01") == true

    private fun muxable(f: StreamFormat, sdk: Int): Boolean = when {
        f.isAvc && f.isMp4Family -> true
        isVp9(f) && !f.isMp4Family -> true
        isAv1(f) && f.isMp4Family -> sdk >= 34
        else -> false
    }

    private val videoOrder = compareBy<StreamFormat>({ it.height ?: 0 }, { it.fps ?: 0 }, { if (it.isAvc) 1 else 0 }, { it.bitrate })
    private val audioOrder = compareBy<StreamFormat>({ if (it.isDefaultAudio) 1 else 0 }, { it.bitrate }, { if (it.isAac) 1 else 0 })

    /** One selectable video quality: a resolution/frame-rate pair and the container it will be saved in. */
    data class VideoOption(val height: Int, val fps: Int, val container: String, val sizeBytes: Long?) {
        val label: String get() = if (fps > 30) "${height}p$fps" else "${height}p"
    }

    /** One selectable audio stream (bitrate/codec), saved as M4A (AAC) or WebM (Opus). */
    data class AudioOption(val itag: Int, val bitrateKbps: Int, val codec: String, val container: String, val sizeBytes: Long?, val trackName: String?)

    data class Options(val videos: List<VideoOption>, val audios: List<AudioOption>)

    /** What the user picked: a video quality ([height]/[fps]) or one audio stream ([audioItag]). */
    data class Choice(val kind: DownloadKind, val height: Int? = null, val fps: Int? = null, val audioItag: Int? = null)

    private fun audioFor(video: StreamFormat, media: ResolvedMedia): StreamFormat? = media.audioOnly
        .filter { it.isAudio && if (video.isMp4Family) it.isAac && it.isMp4Family else it.isOpus }
        .maxWithOrNull(audioOrder)

    fun options(media: ResolvedMedia, sdk: Int): Options {
        val adaptive = media.videoOnly.filter { it.isVideo && muxable(it, sdk) && it.height != null }
            .groupBy { (it.height ?: 0) to (it.fps ?: 30) }
            .map { (key, formats) ->
                val best = formats.maxWithOrNull(compareBy<StreamFormat>({ if (it.isAvc) 1 else 0 }, { it.bitrate }))!!
                val audio = audioFor(best, media)
                VideoOption(key.first, key.second, if (best.isMp4Family) "MP4" else "WebM", best.contentLength?.let { it + (audio?.contentLength ?: 0L) })
            }
        val muxedOnly = media.muxed.filter { it.isVideo && it.height != null }
            .filter { m -> adaptive.none { it.height == m.height } }
            .map { VideoOption(it.height ?: 0, it.fps ?: 30, "MP4", it.contentLength) }
        val videos = (adaptive + muxedOnly).sortedWith(compareByDescending<VideoOption> { it.height }.thenByDescending { it.fps })
        val audios = media.audioOnly.filter { it.isAudio && (it.audioTrackId == null || it.isDefaultAudio) }
            .groupBy { Triple(it.bitrate / 1000, it.isAac, it.isMp4Family) }
            .map { (_, formats) -> formats.first() }
            .sortedByDescending { it.bitrate }
            .map {
                AudioOption(
                    itag = it.itag,
                    bitrateKbps = it.bitrate / 1000,
                    codec = if (it.isAac) "AAC" else if (it.isOpus) "Opus" else (it.codecs ?: "audio"),
                    container = if (it.isMp4Family) "M4A" else "WebM",
                    sizeBytes = it.contentLength,
                    trackName = it.audioTrackName,
                )
            }
        return Options(videos, audios)
    }

    /** Video plan for an exact [height] (and preferably [fps]); a missing quality falls back to the closest one below it. */
    fun planVideo(media: ResolvedMedia, sdk: Int, height: Int? = null, fps: Int? = null): DownloadPlan? {
        val candidates = media.videoOnly.filter { it.isVideo && muxable(it, sdk) }
        val pool = if (height == null) candidates else {
            val atOrBelow = candidates.filter { (it.height ?: 0) <= height }.ifEmpty { candidates }
            val target = atOrBelow.maxOfOrNull { it.height ?: 0 }
            atOrBelow.filter { it.height == target }
        }
        val withFps = if (fps == null) pool else pool.filter { (it.fps ?: 30) == fps }.ifEmpty { pool }
        val bestVideo = withFps.maxWithOrNull(videoOrder)
        if (bestVideo != null) {
            val audio = audioFor(bestVideo, media)
            if (audio != null) return DownloadPlan.Mux(bestVideo, audio)
        }
        val muxedPool = media.muxed.filter { it.isVideo }
        val muxed = (if (height == null) muxedPool else muxedPool.filter { (it.height ?: 0) <= height }.ifEmpty { muxedPool })
            .maxWithOrNull(compareBy({ it.height ?: 0 }, { it.bitrate }))
        return muxed?.let(DownloadPlan::Single)
    }

    fun planAudio(media: ResolvedMedia, itag: Int? = null): DownloadPlan? {
        val audios = media.audioOnly.filter { it.isAudio }
        val audio = audios.firstOrNull { it.itag == itag } ?: audios.maxWithOrNull(audioOrder)
        if (audio != null) return DownloadPlan.Single(audio)
        return media.muxed.filter { it.isVideo }.minByOrNull { it.height ?: Int.MAX_VALUE }?.let(DownloadPlan::Single)
    }

    fun extension(format: StreamFormat): String = when {
        format.isAudio && format.isMp4Family -> "m4a"
        format.isAudio -> "webm"
        format.isMp4Family -> "mp4"
        else -> "webm"
    }

    fun mimeType(plan: DownloadPlan): String = when (plan) {
        is DownloadPlan.Mux -> if (plan.webm) "video/webm" else "video/mp4"
        is DownloadPlan.Single -> plan.format.mimeType
    }

    /** `Title [1080p60]` style label for the quality that was picked. */
    fun qualityLabel(plan: DownloadPlan): String? {
        val f = when (plan) {
            is DownloadPlan.Mux -> plan.video
            is DownloadPlan.Single -> plan.format
        }
        val h = f.height ?: return null
        return if ((f.fps ?: 0) > 30) "${h}p${f.fps}" else "${h}p"
    }

    fun safeFileName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(120).ifBlank { "video" }
}
