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
 * Picks the best streams for a download. "Best" means tallest resolution, then highest frame rate;
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

    fun planVideo(media: ResolvedMedia, sdk: Int): DownloadPlan? {
        val bestVideo = media.videoOnly.filter { it.isVideo && muxable(it, sdk) }.maxWithOrNull(videoOrder)
        if (bestVideo != null) {
            val audio = media.audioOnly
                .filter { it.isAudio && if (bestVideo.isMp4Family) it.isAac && it.isMp4Family else it.isOpus }
                .maxWithOrNull(audioOrder)
            if (audio != null) return DownloadPlan.Mux(bestVideo, audio)
        }
        val muxed = media.muxed.filter { it.isVideo }.maxWithOrNull(compareBy({ it.height ?: 0 }, { it.bitrate }))
        return muxed?.let(DownloadPlan::Single)
    }

    fun planAudio(media: ResolvedMedia): DownloadPlan? {
        val audio = media.audioOnly.filter { it.isAudio }.maxWithOrNull(audioOrder)
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
