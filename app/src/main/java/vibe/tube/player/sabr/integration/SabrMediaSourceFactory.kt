package vibe.tube.player.sabr.integration

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import vibe.tube.player.sabr.core.SabrCpn
import vibe.tube.player.sabr.core.SabrEvent
import vibe.tube.player.sabr.core.SabrSessionState
import vibe.tube.player.sabr.core.SabrStreamController
import vibe.tube.player.sabr.network.SabrDataSource
import vibe.tube.player.stream.VideoCodecUtils
import vibe.tube.youtube.potoken.PoTokenMinter

data class SabrMediaSourceResult(
    val mediaSource: MediaSource,
    val orchestrator: SabrOrchestrator,
)

/** Asks for a fresh answer for a live session: the reload event, the height and codec to keep, the playback nonce and the client. */
fun interface SabrReloader {
    suspend fun reload(videoId: String, event: SabrEvent.ReloadRequired, targetHeight: Int, codec: String, cpn: String, clientNameId: Int): SabrStreamInfo?
}

@UnstableApi
object SabrMediaSourceFactory {
    private const val TAG = "SabrMediaSrcFactory"
    private const val DEFAULT_CLIENT_VERSION = "2.20260710.06.00"
    private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    fun create(
        info: SabrStreamInfo,
        videoId: String,
        durationMs: Long,
        startPositionMs: Long = 0L,
        mediaId: String = videoId,
        mediaMetadata: MediaMetadata = MediaMetadata.EMPTY,
        poTokens: PoTokenMinter? = null,
        reloader: SabrReloader? = null,
    ): SabrMediaSourceResult {
        val sessionState = SabrSessionState().apply {
            this.streamingUrl = info.streamingUrl
            this.videoId = videoId
            this.selectedAudioItag = info.audioItag
            this.selectedAudioLmt = info.audioLmt
            this.selectedVideoItag = info.videoItag
            this.selectedVideoLmt = info.videoLmt
            this.audioTrackId = info.audioTrackId
            this.selectedAudioXtags = info.audioXtags
            this.selectedVideoXtags = info.videoXtags
            // stickyResolution is the explicit pick (0 in auto); selectedVideoHeight floors it so auto still asks for full quality.
            this.stickyResolution = info.targetHeight
            this.selectedVideoHeight = info.videoHeight
            this.playheadPositionMs = startPositionMs
            this.poToken = info.poToken
            this.visitorId = info.visitorId
            this.ustreamerConfig = info.ustreamerConfig
            this.durationMs = durationMs
            this.clientNameId = info.clientNameId
            this.clientVersion = info.clientVersion.ifEmpty { DEFAULT_CLIENT_VERSION }
            // A web streamer_context carries no OS fields; sending them makes the request disagree with the player response.
            this.osName = ""
            this.osVersion = ""
            this.cpn = info.cpn.ifEmpty(SabrCpn::generate)
        }
        if (startPositionMs > 0) sessionState.lastSeekAtMs = System.currentTimeMillis()

        // The streaming request is made as the client that minted the token and the URL.
        val dataSource = SabrDataSource(info.clientUserAgent.ifEmpty { DEFAULT_USER_AGENT })
        val controller = SabrStreamController(dataSource, sessionState)
        val reloadHeight = info.targetHeight.takeIf { it > 0 } ?: info.videoHeight
        val reloadCodec = VideoCodecUtils.codecKeyFromMimeType(info.videoMimeType)
        // A stable content-playback nonce across reloads: a fresh one reads as a new session and re-triggers the reload demand.
        val sessionCpn = sessionState.cpn
        val reloadResolver: (suspend (SabrEvent.ReloadRequired) -> SabrStreamInfo?)? = reloader?.let { r ->
            { event -> r.reload(videoId, event, reloadHeight, reloadCodec, sessionCpn.ifEmpty(SabrCpn::generate), info.clientNameId) }
        }
        val orchestrator = SabrOrchestrator(controller, poTokens, reloadResolver)

        val audioFactory = SabrExoPlayerDataSource.Factory(orchestrator.audioBuffer, orchestrator.videoBuffer).setAudio(true)
        val videoFactory = SabrExoPlayerDataSource.Factory(orchestrator.audioBuffer, orchestrator.videoBuffer).setAudio(false)
        fun item(kind: String, mime: String, isAudio: Boolean): MediaItem {
            val builder = MediaItem.Builder()
                .setUri(Uri.parse("sabr://$videoId/$kind"))
                .setMediaId(mediaId)
                .setMediaMetadata(mediaMetadata)
            containerMimeType(mime, isAudio)?.let { builder.setMimeType(it) }
            return builder.build()
        }
        val audioSource = ProgressiveMediaSource.Factory(audioFactory).createMediaSource(item("audio", info.audioMimeType, true))
        val videoSource = ProgressiveMediaSource.Factory(videoFactory).createMediaSource(item("video", info.videoMimeType, false))
        Log.d(TAG, "SABR source $videoId audio=${info.audioItag} video=${info.videoItag} startPos=${startPositionMs}ms")
        return SabrMediaSourceResult(MergingMediaSource(true, true, videoSource, audioSource), orchestrator)
    }

    private fun containerMimeType(mimeType: String, isAudio: Boolean): String? {
        val mt = mimeType.lowercase()
        return when {
            mt.contains("webm") -> if (isAudio) MimeTypes.AUDIO_WEBM else MimeTypes.VIDEO_WEBM
            mt.contains("mp4") -> if (isAudio) MimeTypes.AUDIO_MP4 else MimeTypes.VIDEO_MP4
            else -> null
        }
    }
}
