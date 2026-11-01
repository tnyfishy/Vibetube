package vibe.tube.youtube.extractor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import vibe.tube.youtube.*
import vibe.tube.youtube.model.*

class ExtractorYouTubeProvider(private val public: PublicExtractor, private val account: YouTubeProvider) : YouTubeProvider by account {
    override val name = "NewPipeExtractor + InnerTube account adapter"
    private suspend fun <T> read(token: String?, primary: suspend () -> T, fallback: suspend () -> T): T {
        if (token != null && !NewPipeAdapter.owns(token)) return fallback()
        return try { primary() } catch (e: Exception) {
            if (e is CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (NewPipeAdapter.owns(token)) throw e 
            android.util.Log.w("Extractor", "Public extraction failed: ${e.javaClass.simpleName}; using compatible adapter")
            fallback()
        }
    }
    override suspend fun search(call: ProviderCall, query: String, kind: SearchKind, sort: SearchSort, continuation: String?): Page<FeedItem> {

        if ((continuation == null || !NewPipeAdapter.owns(continuation)) &&
            (call.locale.language == "vi" || sort != SearchSort.Relevance || kind == SearchKind.Shorts))
            return account.search(call.copy(auth = null), query, kind, sort, continuation)
        return read(continuation, {
            val result = public.search(query, kind, call.locale, continuation)
            
            if (continuation == null && result.items.isEmpty()) account.search(call.copy(auth = null), query, kind, sort, null) else result
        }, { account.search(call.copy(auth = null), query, kind, sort, continuation) })
    }
    override suspend fun topic(call: ProviderCall, query: String, playlists: Boolean, live: Boolean, continuation: String?): Page<FeedItem> {
        if (live) return account.topic(call, query, playlists, true, continuation)
        return search(call, query, if (playlists) SearchKind.Playlist else SearchKind.Video, SearchSort.Relevance, continuation)
    }
    // The guest home comes from YouTube's own destination pages; a page left over from the older search source still continues there.
    override suspend fun trending(call: ProviderCall, continuation: String?): Page<FeedItem> =
        if (continuation != null && NewPipeAdapter.owns(continuation)) {
            search(call.copy(auth = null), if (call.locale.language == "vi") "thịnh hành Việt Nam" else "trending ${call.locale.region}", SearchKind.Video, SearchSort.Relevance, continuation)
        } else account.trending(call.copy(auth = null), continuation)
    // YouTube's reel feed first; a page left over from the older search source still continues there.
    override suspend fun shorts(call: ProviderCall, continuation: String?): Page<FeedItem> =
        if (continuation != null && NewPipeAdapter.owns(continuation)) {
            public.search(if (call.locale.language == "vi") "shorts Việt Nam" else "shorts", SearchKind.Video, call.locale, continuation, shorts = true)
        } else account.shorts(call, continuation)
    override suspend fun playlist(call: ProviderCall, playlistId: String, continuation: String?): PlaylistDetail {
        if (playlistId in listOf("WL", "LL")) return account.playlist(call, playlistId, continuation)
        return read(continuation, { public.playlist(playlistId, call.locale, continuation) }, { account.playlist(call, playlistId, continuation) })
    }
    override suspend fun channel(call: ProviderCall, channelId: String): ChannelDetail {
        if (call.auth == null) return read(null, { public.channel(channelId, call.locale) }, { account.channel(call, channelId) })
        val signed = account.channel(call, channelId)
        val extra = try { public.channel(channelId, call.locale) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        return signed.copy(banner = signed.banner ?: extra?.banner, contentTabs = extra?.contentTabs?.takeIf { it.isNotEmpty() } ?: signed.contentTabs)
    }
    override suspend fun channelTab(call: ProviderCall, channelId: String, tab: String, continuation: String?): Page<FeedItem> =
        if (NewPipeAdapter.owns(continuation)) public.channelTab(channelId, tab, call.locale, continuation)
        else account.channelTab(call, channelId, tab, continuation)
    override suspend fun channelVideos(call: ProviderCall, channelId: String, continuation: String?, sort: ChannelSort): Page<FeedItem> {
        if (sort != ChannelSort.Latest) return account.channelVideos(call, channelId, continuation, sort)
        return read(continuation, { public.channelVideos(channelId, call.locale, continuation) }, { account.channelVideos(call, channelId, continuation, sort) })
    }
    override suspend fun watch(call: ProviderCall, videoId: String, playlistId: String?, playlistIndex: Int?): WatchInfo {
        
        if (call.auth != null || playlistId != null) return account.watch(call, videoId, playlistId, playlistIndex)

        return read(null, { account.watch(call, videoId, playlistId, playlistIndex) }, { public.watch(videoId, call.locale) })
    }
    override suspend fun comments(call: ProviderCall, continuation: String): CommentsPage =
        if (NewPipeAdapter.owns(continuation)) public.comments(continuation) else account.comments(call, continuation)
}
