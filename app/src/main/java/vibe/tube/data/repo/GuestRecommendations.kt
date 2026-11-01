package vibe.tube.data.repo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import vibe.tube.youtube.ProviderCall
import vibe.tube.youtube.YouTubeProvider
import vibe.tube.youtube.model.FeedItem
import vibe.tube.youtube.model.Page
import vibe.tube.youtube.model.SearchKind
import vibe.tube.youtube.model.SearchSort
import vibe.tube.youtube.model.VideoItem

/**
 * The signed-out home feed. With no account for YouTube to personalise, it is built on the device from what the guest
 * watched (YouTube's related videos for each) and searched (the results for each query), the most recent weighing most.
 * Later pages follow the related videos of what the feed already showed, so it keeps drifting along the same interests.
 * A guest with no history yet gets the trending page.
 */
class GuestRecommendations(
    private val provider: YouTubeProvider,
    private val history: LocalHistoryRepository,
) {
    private class State {
        val served = LinkedHashSet<String>()
        val watched = HashSet<String>()
        val seeds = ArrayDeque<String>()
        val queries = ArrayDeque<Pair<String, String?>>()
        var page = 0
    }

    @Volatile
    private var state = State()

    suspend fun page(call: ProviderCall, continuation: String?): Page<FeedItem> {
        if (continuation != null && !continuation.startsWith(TOKEN_PREFIX)) return provider.trending(call, continuation)
        // A later page asked of a fresh process (a cached feed scrolled on) starts over from the history too.
        val s = if (continuation == null || state.page == 0) start() else state
        if (continuation == null && s.seeds.isEmpty() && s.queries.isEmpty()) return provider.trending(call, null)

        val (seeds, queries) = synchronized(s) {
            val ids = List(minOf(SEEDS_PER_PAGE, s.seeds.size)) { s.seeds.removeFirst() }
            val qs = List(minOf(QUERIES_PER_PAGE, s.queries.size)) { s.queries.removeFirst() }
            ids to qs
        }
        val lanes: List<List<FeedItem>> = coroutineScope {
            val related = seeds.map { id -> async { quietly { provider.watch(call, id, null, null).related } } }
            val found = queries.map { (query, token) ->
                async {
                    quietly {
                        val result = provider.search(call, query, SearchKind.Video, SearchSort.Relevance, token)
                        // A query keeps its turn while it has more results, after the ones still waiting.
                        result.continuation?.let { next -> synchronized(s) { s.queries.addLast(query to next) } }
                        result.items
                    }
                }
            }
            val trending = if (continuation == null) async { quietly { provider.trending(call, null).items } } else null
            (related + found).awaitAll() + listOfNotNull(trending?.await())
        }

        val woven = ArrayList<FeedItem>()
        synchronized(s) {
            val longest = lanes.maxOfOrNull { it.size } ?: 0
            for (i in 0 until longest) for (lane in lanes) {
                val item = lane.getOrNull(i) as? VideoItem ?: continue
                if (item.isShort || item.id in s.watched || !s.served.add(item.id)) continue
                woven += item
                if (!item.isLive) s.seeds.addLast(item.id)
            }
            while (s.served.size > SERVED_LIMIT) s.served.remove(s.served.first())
            while (s.seeds.size > SEEDS_LIMIT) s.seeds.removeFirst()
            s.page++
        }
        val more = synchronized(s) { s.seeds.isNotEmpty() || s.queries.isNotEmpty() }
        if (woven.isEmpty() && continuation == null) return provider.trending(call, null)
        return Page(woven, if (more) "$TOKEN_PREFIX${s.page}" else null)
    }

    /** A first page starts over from the history as it is now. */
    private suspend fun start(): State {
        val watched = history.recentPlayback(FeedController.GUEST_SCOPE, HISTORY_LIMIT).first()
        val searches = history.recentSearches(FeedController.GUEST_SCOPE, QUERY_LIMIT).first()
        return State().apply {
            watched.forEach { this.watched += it.videoId }
            // Shorts make poor seeds: their related videos are more shorts.
            watched.asSequence()
                .filter { (it.durationSeconds ?: Int.MAX_VALUE) > SHORT_MAX_SECONDS }
                .map { it.videoId }.distinct().take(SEED_HISTORY).forEach { seeds.addLast(it) }
            searches.forEach { queries.addLast(it to null) }
            // The newest of each kind leads; a few older ones are mixed in so the first page is not all one interest.
            reorder(seeds)
        }.also { state = it }
    }

    private fun <T> reorder(queue: ArrayDeque<T>) {
        if (queue.size <= SEEDS_PER_PAGE) return
        val head = queue.take(2)
        val rest = queue.drop(2).shuffled()
        queue.clear()
        queue.addAll(head + rest)
    }

    private suspend fun quietly(block: suspend () -> List<FeedItem>): List<FeedItem> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("GuestFeed", "lane failed: ${e.javaClass.simpleName}")
        emptyList()
    }

    private companion object {
        const val TOKEN_PREFIX = "guest:"
        const val SEEDS_PER_PAGE = 4
        const val QUERIES_PER_PAGE = 2
        const val HISTORY_LIMIT = 100
        const val SEED_HISTORY = 20
        const val QUERY_LIMIT = 8
        const val SEEDS_LIMIT = 200
        const val SERVED_LIMIT = 2_000
        const val SHORT_MAX_SECONDS = 180
    }
}
