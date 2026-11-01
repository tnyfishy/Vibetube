package vibe.tube.player

import android.media.MediaCodecList
import vibe.tube.youtube.player.StreamFormat

/** Whether this device has a decoder for a video format at its size, so the planner never picks a stream that cannot play. */
object CodecSupport {
    private val codecs by lazy { runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }.getOrDefault(emptyList()) }
    private val verdicts = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun mimeFor(codecs: String?): String? = when {
        codecs == null -> null
        codecs.startsWith("avc1") -> "video/avc"
        codecs.startsWith("vp09") || codecs.startsWith("vp9") -> "video/x-vnd.on2.vp9"
        codecs.startsWith("av01") -> "video/av01"
        codecs.startsWith("hev1") || codecs.startsWith("hvc1") -> "video/hevc"
        else -> null
    }

    /** Unknown codecs and unreadable codec lists count as playable: the player's own fallback decides then. */
    fun canDecode(format: StreamFormat): Boolean {
        if (!format.isVideo) return true
        val mime = mimeFor(format.codecs) ?: return true
        val w = format.width ?: 0
        val h = format.height ?: 0
        return verdicts.getOrPut("$mime:$w:$h") {
            val list = codecs
            if (list.isEmpty()) return@getOrPut true
            list.any { info ->
                info.supportedTypes.any { it.equals(mime, true) } && runCatching {
                    val video = info.getCapabilitiesForType(mime).videoCapabilities
                    w <= 0 || h <= 0 || video == null || video.isSizeSupported(w, h) || video.isSizeSupported(h, w)
                }.getOrDefault(true)
            }
        }
    }
}
