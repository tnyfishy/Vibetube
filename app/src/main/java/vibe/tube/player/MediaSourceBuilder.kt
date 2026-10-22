@file:OptIn(UnstableApi::class)

package vibe.tube.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import okhttp3.OkHttpClient
import vibe.tube.youtube.model.VideoItem
import vibe.tube.youtube.model.bestFor
import vibe.tube.youtube.player.CaptionTrack
import vibe.tube.youtube.player.MediaPlan
import vibe.tube.youtube.player.PlanKind
import vibe.tube.youtube.player.ResolvedMedia

class MediaSourceBuilder(private val http: OkHttpClient) {

    fun build(plan: MediaPlan, media: ResolvedMedia, video: VideoItem, captions: List<CaptionTrack>): MediaSource {
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
            PlanKind.Muxed -> withSubtitles(
                ProgressiveMediaSource.Factory(factory).createMediaSource(item(plan.muxed!!.url, true)),
                factory,
                captions,
            )
            PlanKind.Adaptive -> {
                val progressive = ProgressiveMediaSource.Factory(factory)
                val videoSource = progressive.createMediaSource(item(plan.video!!.url, true))
                val audioSource = progressive.createMediaSource(item(plan.audio!!.url, false))
                withSubtitles(MergingMediaSource(videoSource, audioSource), factory, captions)
            }
        }
    }

    private fun withSubtitles(source: MediaSource, factory: DataSource.Factory, captions: List<CaptionTrack>): MediaSource {
        if (captions.isEmpty()) return source
        val subtitleSources = captions.map { track ->
            val configuration = MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url + "&fmt=vtt"))
                .setMimeType(MimeTypes.TEXT_VTT)
                .setLanguage(track.languageCode)
                .setLabel(track.name)
                .setSelectionFlags(0)
                .build()
            SingleSampleMediaSource.Factory(factory).createMediaSource(configuration, C.TIME_UNSET)
        }
        return MergingMediaSource(source, *subtitleSources.toTypedArray())
    }
}
