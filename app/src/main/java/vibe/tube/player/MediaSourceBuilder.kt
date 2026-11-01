@file:OptIn(UnstableApi::class)

package vibe.tube.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import okhttp3.OkHttpClient
import vibe.tube.youtube.model.VideoItem
import vibe.tube.youtube.model.bestFor
import vibe.tube.youtube.player.ClientUserAgents
import vibe.tube.youtube.player.MediaPlan
import vibe.tube.youtube.player.PlanKind
import vibe.tube.youtube.player.ResolvedMedia

class MediaSourceBuilder(
    private val http: OkHttpClient,
    private val renewer: StreamRenewer? = null,
    private val poTokens: vibe.tube.youtube.potoken.PoTokenMinter? = null,
    private val sabrReloader: vibe.tube.player.sabr.integration.SabrReloader? = null,
) {
    /** The live SABR session, if the current source is one; at most one exists at a time. */
    @Volatile var sabr: vibe.tube.player.sabr.integration.SabrOrchestrator? = null
        private set

    /** Called when a SABR session ends in a way it cannot recover from. */
    var onSabrFailed: (() -> Unit)? = null

    fun releaseSabr() {
        sabr?.release()
        sabr = null
    }

    fun build(plan: MediaPlan, media: ResolvedMedia, video: VideoItem, startPositionMs: Long = 0L): MediaSource {
        // Each URL is fetched with the identity of the client named in its `c` parameter: the fallback extractor
        // mixes clients, and a URL swapped in after a refusal may come from a different client than the rest.
        val network: DataSource.Factory = YoutubeDataSource.Factory(ClientAwareDataSource.Factory { client ->
            val agent = ClientUserAgents.forClient(client, media.userAgent)
            OkHttpDataSource.Factory(http).setUserAgent(agent).createDataSource()
        }, renewer?.let { r -> { refused: String, idle: Boolean -> r.renew(video.id, refused, idle) } })
        // Files are cached on disk, keyed by what they are rather than by the URL that fetched them; live manifests never are.
        val cache = PlayerCache.cache?.takeIf { plan.kind != PlanKind.Hls && plan.kind != PlanKind.Dash }
        val factory: DataSource.Factory = if (cache == null) network else CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(network)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setEventListener(PlayerCache.stats)
        val metadata = MediaMetadata.Builder()
            .setTitle(media.title ?: video.title)
            .setArtist(media.author ?: video.channelName)
            .setArtworkUri(video.thumbnails.bestFor(480)?.let(Uri::parse))
            .build()
        fun item(url: String, withMetadata: Boolean) = MediaItem.Builder()
            .setUri(url)
            .setMediaId(video.id)
            .apply { if (cache != null) PlayerCache.keyFor(video.id, url)?.let(::setCustomCacheKey) }
            .apply { if (withMetadata) setMediaMetadata(metadata) }
            .build()

        return when (plan.kind) {
            PlanKind.Sabr -> {
                releaseSabr()
                val info = plan.sabr!!
                val result = vibe.tube.player.sabr.integration.SabrMediaSourceFactory.create(
                    info = info,
                    videoId = video.id,
                    durationMs = info.durationMs.takeIf { it > 0 } ?: ((media.durationSeconds ?: 0L) * 1000L),
                    startPositionMs = startPositionMs,
                    mediaId = video.id,
                    mediaMetadata = metadata,
                    poTokens = poTokens,
                    reloader = sabrReloader,
                )
                result.orchestrator.onError = { _, _, recoverable -> if (!recoverable) onSabrFailed?.invoke() }
                result.orchestrator.start()
                sabr = result.orchestrator
                result.mediaSource
            }
            PlanKind.Hls -> HlsMediaSource.Factory(factory).createMediaSource(item(plan.hlsUrl!!, true))
            PlanKind.Dash -> DashMediaSource.Factory(factory).createMediaSource(item(plan.dashUrl!!, true))
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
