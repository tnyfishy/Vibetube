package vibe.tube.data.repo

import kotlinx.coroutines.CoroutineScope
import vibe.tube.youtube.YouTubeProvider
import vibe.tube.youtube.model.Page
import vibe.tube.youtube.model.SearchKind
import vibe.tube.youtube.model.SearchSort

class FeedRepository(
    private val provider: YouTubeProvider,
    private val env: FeedEnvironment,
    guestHistory: LocalHistoryRepository? = null,
) {
    private val guest = guestHistory?.let { GuestRecommendations(provider, it) }

    private fun controller(spec: FeedSpec, scope: CoroutineScope) = FeedController(spec, env, scope)


    fun home(scope: CoroutineScope) = controller(
        FeedSpec(
            "home",
            requiresAuth = true,
            cacheable = true,
            expectsContent = true,
            guestFetch = { c, t -> guest?.page(c, t) ?: provider.trending(c, t) },
        ) { c, t -> provider.home(c, t) },
        scope,
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

    fun shorts(scope: CoroutineScope) = controller(
        FeedSpec("shorts", requiresAuth = false, cacheable = false, expectsContent = true) { c, t -> provider.shorts(c, t).let { page -> page.copy(items = page.items.filter { item ->
            if (item !is vibe.tube.youtube.model.VideoItem) true else {
                val count = vibe.tube.ui.metadata.compactViews(item.viewCountText, java.util.Locale.US)
                val number = count?.toDoubleOrNull()
                number == null || number >= 1000
            }
        }) } }, scope,
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

    fun homeChip(chip: vibe.tube.youtube.model.HomeChip, scope: CoroutineScope) = controller(
        FeedSpec("home_chip", requiresAuth = false, cacheable = false, extra = chip.key) { c, t -> provider.homeChip(c, chip, t) }, scope)

    /** One of YouTube's destination pages (music, news, gaming, live), widened with what YouTube recommends next. */
    fun explore(destination: String, scope: CoroutineScope) = controller(
        FeedSpec("explore", requiresAuth = false, cacheable = true, extra = destination) { c, t -> provider.explore(c, listOf(destination), t) },
        scope,
    )

    fun topic(query: String, playlists: Boolean, live: Boolean, scope: CoroutineScope) = controller(
        FeedSpec("topic", requiresAuth = false, cacheable = true, extra = "VN-v2|$query|$playlists|$live") { c, t ->
            provider.topic(c.copy(locale = vibe.tube.youtube.model.ContentLocale("vi", "VN")), query, playlists, live, t)
        }, scope,
    )

    fun search(query: String, kind: SearchKind, sort: SearchSort, scope: CoroutineScope) = controller(
        FeedSpec("search", requiresAuth = false, cacheable = false, extra = "$query|$kind|$sort") { c, t ->
            
            provider.search(c.copy(auth = null), query, kind, sort, t)
        },
        scope,
    )

    fun channelVideos(channelId: String, sort: () -> vibe.tube.youtube.model.ChannelSort, scope: CoroutineScope) = channelVideos(channelId, sort, { "videos" }, scope)

    fun channelVideos(channelId: String, sort: () -> vibe.tube.youtube.model.ChannelSort, tab: () -> String, scope: CoroutineScope) = controller(
        FeedSpec("channel_videos", requiresAuth = false, cacheable = false, extra = channelId) { c, t ->
            if (tab() == "videos") provider.channelVideos(c, channelId, t, sort()) else provider.channelTab(c, channelId, tab(), t)
        },
        scope,
    )
}
