package vibe.tube.data.repo

import kotlinx.coroutines.CoroutineScope
import vibe.tube.youtube.YouTubeProvider
import vibe.tube.youtube.model.Page
import vibe.tube.youtube.model.SearchKind
import vibe.tube.youtube.model.SearchSort

class FeedRepository(
    private val provider: YouTubeProvider,
    private val env: FeedEnvironment,
) {
    private fun controller(spec: FeedSpec, scope: CoroutineScope) = FeedController(spec, env, scope)

    fun home(scope: CoroutineScope) = controller(
        FeedSpec("home", requiresAuth = true, cacheable = true) { c, t -> provider.home(c, t) }, scope,
    )

    fun subscriptions(scope: CoroutineScope) = controller(
        FeedSpec("subscriptions", requiresAuth = true, cacheable = true) { c, t -> provider.subscriptionsFeed(c, t) }, scope,
    )

    fun subscribedChannels(scope: CoroutineScope) = controller(
        FeedSpec("channels", requiresAuth = true, cacheable = true) { c, t -> provider.subscribedChannels(c, t) }, scope,
    )

    fun userPlaylists(scope: CoroutineScope) = controller(
        FeedSpec("playlists", requiresAuth = true, cacheable = true) { c, t -> provider.userPlaylists(c, t) }, scope,
    )

    fun history(scope: CoroutineScope) = controller(
        FeedSpec("history", requiresAuth = true, cacheable = true) { c, t -> provider.history(c, t) }, scope,
    )

    fun playlist(playlistId: String, scope: CoroutineScope): FeedController {
        val private = playlistId == "LL" || playlistId == "WL"
        return controller(
            FeedSpec("playlist", requiresAuth = private, cacheable = private, extra = playlistId) { c, t ->
                val detail = provider.playlist(c, playlistId, t)
                Page(
                    items = detail.items,
                    continuation = detail.continuation,
                    title = detail.title.takeIf { it.isNotBlank() },
                    subtitle = detail.ownerName,
                    totalText = detail.countText,
                )
            },
            scope,
        )
    }

    fun search(query: String, kind: SearchKind, sort: SearchSort, scope: CoroutineScope) = controller(
        FeedSpec("search", requiresAuth = false, cacheable = false, extra = "$query|$kind|$sort") { c, t ->
            provider.search(c, query, kind, sort, t)
        },
        scope,
    )

    fun channelVideos(channelId: String, scope: CoroutineScope) = controller(
        FeedSpec("channel_videos", requiresAuth = false, cacheable = false, extra = channelId) { c, t ->
            provider.channelVideos(c, channelId, t)
        },
        scope,
    )
}
