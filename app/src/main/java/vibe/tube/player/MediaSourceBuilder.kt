@file:OptIn(UnstableApi::class)

package vibe.tube.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import okhttp3.OkHttpClient
import vibe.tube.youtube.model.VideoItem
import vibe.tube.youtube.model.bestFor
import vibe.tube.youtube.player.MediaPlan
import vibe.tube.youtube.player.PlanKind
import vibe.tube.youtube.player.ResolvedMedia

class MediaSourceBuilder(private val http: OkHttpClient) {

    fun build(plan: MediaPlan, media: ResolvedMedia, video: VideoItem): MediaSource {
        val factory: DataSource.Factory = YoutubeDataSource.Factory(OkHttpDataSource.Factory(http).setUserAgent(media.userAgent))
        val metadata = MediaMetadata.Builder()
            .setTitle(media.title ?: video.title)
            .setArtist(media.author ?: video.channelName)
            .setArtworkUri(video.thumbnails.bestFor(480)?.let(Uri::parse))
            .build()
        fun item(url: String, withMetadata: Boolean) = MediaItem.Builder()
            .setUri(url)
            .setMediaId(video.id)
            .apply { if (withMetadata) setMediaMetadata(metadata) }
            .build()

        return when (plan.kind) {
            PlanKind.Hls -> HlsMediaSource.Factory(factory).createMediaSource(item(plan.hlsUrl!!, true))
            PlanKind.Muxed -> ProgressiveMediaSource.Factory(factory).createMediaSource(item(plan.muxed!!.url, true))
            PlanKind.Adaptive -> {
                val progressive = ProgressiveMediaSource.Factory(factory)
                val videoSource = progressive.createMediaSource(item(plan.video!!.url, true))
                val audioSource = progressive.createMediaSource(item(plan.audio!!.url, false))
                MergingMediaSource(videoSource, audioSource)
            }
        }
    }
}
