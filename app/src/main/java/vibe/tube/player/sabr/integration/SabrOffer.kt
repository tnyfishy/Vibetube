package vibe.tube.player.sabr.integration

import vibe.tube.player.stream.VideoCodecUtils

/** What a SABR session needs once a format pair has been chosen. */
data class SabrStreamInfo(
    val streamingUrl: String,
    val audioItag: Int,
    val audioLmt: Long,
    val videoItag: Int,
    val videoLmt: Long,
    val audioXtags: String = "",
    val videoXtags: String = "",
    val durationMs: Long,
    val poToken: String = "",
    val visitorId: String = "",
    val ustreamerConfig: ByteArray = ByteArray(0),
    val audioMimeType: String = "",
    val videoMimeType: String = "",
    val audioTrackId: String = "",
    val targetHeight: Int = 0,
    val videoHeight: Int = 0,
    val cpn: String = "",
    /** The numeric InnerTube id of the client whose player response opened the session (WEB = 1, MWEB = 2). */
    val clientNameId: Int = 1,
    val clientVersion: String = "",
    val clientUserAgent: String = "",
)

data class SabrFormat(
    val itag: Int,
    val lastModified: Long,
    val xtags: String,
    val mimeType: String,
    val bitrate: Int,
    val height: Int,
    val audioTrackId: String?,
    val isAutoDubbed: Boolean,
    val isAudio: Boolean,
)

/**
 * A SABR-capable player answer, kept whole so the format pair can be chosen later against the quality the
 * user has capped, rather than fixed when the answer arrives.
 */
class SabrOffer(
    val streamingUrl: String,
    val ustreamerConfig: ByteArray,
    val poToken: String,
    val visitorId: String,
    val cpn: String,
    val clientNameId: Int,
    val clientVersion: String,
    val clientUserAgent: String,
    val durationMs: Long,
    val formats: List<SabrFormat>,
) {
    private val videos get() = formats.filter { !it.isAudio }
    private val audios get() = formats.filter { it.isAudio }

    val maxHeight: Int get() = videos.maxOfOrNull { it.height } ?: 0
    val heights: List<Int> get() = videos.map { it.height }.filter { it > 0 }

    /** The pair for [targetHeight] (null = the tallest), or null when the answer lacks either track. */
    fun select(targetHeight: Int?, preferredCodec: String? = null): SabrStreamInfo? {
        val video = (if (targetHeight == null) bestVideo(preferredCodec) else videoForHeight(targetHeight, preferredCodec)) ?: return null
        val audio = bestAudio(video) ?: return null
        return SabrStreamInfo(
            streamingUrl = streamingUrl,
            audioItag = audio.itag,
            audioLmt = audio.lastModified,
            videoItag = video.itag,
            videoLmt = video.lastModified,
            audioXtags = audio.xtags,
            videoXtags = video.xtags,
            durationMs = durationMs,
            poToken = poToken,
            visitorId = visitorId,
            ustreamerConfig = ustreamerConfig,
            audioMimeType = audio.mimeType,
            videoMimeType = video.mimeType,
            audioTrackId = audio.audioTrackId.orEmpty(),
            targetHeight = targetHeight ?: 0,
            videoHeight = video.height,
            cpn = cpn,
            clientNameId = clientNameId,
            clientVersion = clientVersion,
            clientUserAgent = clientUserAgent,
        )
    }

    /** Auto-dubbed videos repeat each itag per language: keep the original track (id suffix ".4"). */
    private fun isOriginalTrack(format: SabrFormat): Boolean {
        if (format.isAutoDubbed) return false
        val id = format.audioTrackId ?: return true
        return id.substringAfterLast('.', missingDelimiterValue = "4") == "4"
    }

    private fun bestAudio(video: SabrFormat): SabrFormat? {
        val all = audios
        val candidates = all.filter(::isOriginalTrack).ifEmpty { all }
        val wantsMp4 = video.mimeType.lowercase().contains("mp4") && VideoCodecUtils.codecKeyFromMimeType(video.mimeType) == "h264"
        val compatible = if (wantsMp4) {
            candidates.filter { it.mimeType.contains("mp4", ignoreCase = true) }.ifEmpty { all.filter { it.mimeType.contains("mp4", ignoreCase = true) } }
        } else {
            candidates.filter { it.mimeType.contains("webm", ignoreCase = true) }.ifEmpty { all.filter { it.mimeType.contains("webm", ignoreCase = true) } }
        }
        val pool = compatible.ifEmpty { candidates }
        if (wantsMp4) return pool.maxByOrNull { it.bitrate }
        for (itag in PREFERRED_AUDIO_ITAGS) pool.find { it.itag == itag }?.let { return it }
        return pool.filter { it.mimeType.contains("webm", ignoreCase = true) }.maxByOrNull { it.bitrate } ?: pool.maxByOrNull { it.bitrate }
    }

    private fun bestVideo(preferredCodec: String?): SabrFormat? = videos.sortedWith(
        compareByDescending<SabrFormat> { it.height }
            .thenBy { VideoCodecUtils.codecRankWithPreference(VideoCodecUtils.codecKeyFromMimeType(it.mimeType), preferredCodec) }
            .thenByDescending { it.bitrate },
    ).firstOrNull()

    private fun videoForHeight(target: Int, preferredCodec: String?): SabrFormat? {
        val pool = videos
        for (codecKey in VideoCodecUtils.codecPriorityList(preferredCodec)) {
            pool.filter { it.height == target && VideoCodecUtils.codecKeyFromMimeType(it.mimeType) == codecKey }
                .maxByOrNull { it.bitrate }?.let { return it }
        }
        PREFERRED_VIDEO_ITAGS_BY_HEIGHT[target]?.forEach { itag -> pool.find { it.itag == itag }?.let { return it } }
        pool.filter { it.height == target }.maxByOrNull { it.bitrate }?.let { return it }
        // Nearest height, never above the target unless nothing is below it.
        return pool.sortedWith(
            compareBy<SabrFormat> { it.height > target }.thenBy { kotlin.math.abs(it.height - target) }.thenByDescending { it.bitrate },
        ).firstOrNull()
    }

    private companion object {
        val PREFERRED_AUDIO_ITAGS = listOf(251, 250, 249, 140, 141)
        val PREFERRED_VIDEO_ITAGS_BY_HEIGHT = mapOf(
            2160 to listOf(313, 271),
            1440 to listOf(308, 271),
            1080 to listOf(299, 248, 303),
            720 to listOf(298, 247, 302),
            480 to listOf(135, 244, 218),
            360 to listOf(134, 243),
            240 to listOf(133, 242),
        )
    }
}
