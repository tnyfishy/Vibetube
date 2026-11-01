package vibe.tube.player

import android.os.SystemClock

internal class PlaybackLog(private val now: () -> Long = SystemClock::elapsedRealtime) {
    private var startedAt = 0L

    fun start(request: Long, videoId: String, reason: String) {
        startedAt = now()
        write(request, videoId, "start", "reason=$reason")
    }

    fun step(request: Long, videoId: String, stage: String, detail: String? = null) = write(request, videoId, stage, detail)

    fun warn(request: Long, videoId: String, stage: String, detail: String? = null) = write(request, videoId, stage, detail, warn = true)

    private fun write(request: Long, videoId: String, stage: String, detail: String?, warn: Boolean = false) {
        val line = "#$request $videoId $stage +${now() - startedAt}ms" + if (detail.isNullOrEmpty()) "" else " $detail"
        runCatching { if (warn) android.util.Log.w(TAG, line) else android.util.Log.i(TAG, line) }
    }

    private companion object {
        const val TAG = "Playback"
    }
}
