package vibe.tube.youtube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import vibe.tube.account.AccountSession
import vibe.tube.account.SessionCookieJar
import vibe.tube.core.AppError
import vibe.tube.core.Clock
import vibe.tube.network.InnerTubeClient
import vibe.tube.network.InnerTubeResponse
import vibe.tube.network.RequestAuth
import vibe.tube.youtube.model.AddToPlaylistOption
import vibe.tube.youtube.model.ChannelDetail
import vibe.tube.youtube.model.CommentsPage
import vibe.tube.youtube.model.ContentLocale
import vibe.tube.youtube.model.FeedItem
import vibe.tube.youtube.model.Page
import vibe.tube.youtube.model.PlaylistDetail
import vibe.tube.youtube.model.PlaylistPrivacy
import vibe.tube.youtube.model.SearchKind
import vibe.tube.youtube.model.SearchSort
import vibe.tube.youtube.model.VideoItem
import vibe.tube.youtube.model.WatchInfo
import vibe.tube.youtube.parser.AccountParser
import vibe.tube.youtube.parser.CommentsParser
import vibe.tube.youtube.parser.FeedParser
import vibe.tube.youtube.parser.ItemParsers
import vibe.tube.youtube.parser.SuggestParser
import vibe.tube.youtube.parser.WatchParser
import vibe.tube.youtube.parser.arr
import vibe.tube.youtube.parser.at
import vibe.tube.youtube.parser.bool
import vibe.tube.youtube.parser.firstObject
import vibe.tube.youtube.parser.firstString
import vibe.tube.youtube.parser.list
import vibe.tube.youtube.parser.path
import vibe.tube.youtube.parser.str
import vibe.tube.youtube.parser.textOf
import vibe.tube.youtube.parser.walk
import vibe.tube.youtube.parser.int
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class InnerTubeProvider(
    private val client: InnerTubeClient,
    private val clock: Clock = Clock.System,
    private val suggestBase: String = "https://suggestqueries-clients6.youtube.com/",
) : YouTubeProvider {

    override val name: String = "InnerTube/WEB ${client.clientConfig.clientVersion}"

    override suspend fun verify(session: AccountSession, locale: ContentLocale): VerifyResult {
        val jar = SessionCookieJar(session.cookies, clock)
        if (!vibe.tube.account.SapisidAuth.hasCredentials(jar)) return VerifyResult.LoggedOut
        val auth = RequestAuth(session.id, jar, session.pageId, session.visitorData)
        return try {
            val (menu, pendingIdentities) = coroutineScope {
                
                val list = async {
                    runCatching {
                        val r = client.post("account/accounts_list", locale = locale, auth = auth, skipLoggedOutCheck = true)
                        AccountParser.parseIdentities(r.root)
                    }.getOrDefault(emptyList())
                }
                client.post("account/account_menu", locale = locale, auth = auth, skipLoggedOutCheck = true) to list
            }
            val profile = AccountParser.parseMenu(menu.root)
            if (!profile.loggedIn) {
                client.forgetAccount(jar)
                return VerifyResult.LoggedOut
            }
            val identities = pendingIdentities.await()
            VerifyResult.Verified(profile, identities, menu.visitorData ?: session.visitorData, jar.snapshot())
        } catch (e: AppError.SessionExpired) {
            VerifyResult.LoggedOut
        } catch (e: AppError) {
            VerifyResult.Failed(e)
        }
    }

    private suspend fun browse(call: ProviderCall, browseId: String?, continuation: String?, params: String? = null): InnerTubeResponse {
        val resolvedId = if (continuation == null && browseId?.startsWith("@") == true) {
            client.post("navigation/resolve_url", buildJsonObject { put("url", "https://www.youtube.com/$browseId") }, call.locale, call.auth)
                .root.firstObject("browseEndpoint").at("browseId").str ?: browseId
        } else browseId
        val body = buildJsonObject {
            if (continuation != null) put("continuation", continuation) else {
                put("browseId", resolvedId.orEmpty())
                if (params != null) put("params", params)
            }
        }
        return client.post("browse", body, call.locale, call.auth)
    }

    private fun requireAuth(call: ProviderCall, feature: String): RequestAuth =
        call.auth ?: throw AppError.LoginRequired(feature)

    private suspend fun page(response: InnerTubeResponse): Page<FeedItem> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { FeedParser.parse(response.root) }

    override suspend fun homeChip(call: ProviderCall, chip: vibe.tube.youtube.model.HomeChip, continuation: String?): Page<FeedItem> =
        page(browse(call, chip.browseId ?: "FEwhat_to_watch", continuation ?: chip.continuation, chip.params))

    override suspend fun home(call: ProviderCall, continuation: String?): Page<FeedItem> {
        requireAuth(call, "home")
        return page(browse(call, "FEwhat_to_watch", continuation))
    }

    private class ExploreState(val served: LinkedHashSet<String> = LinkedHashSet(), val seeds: ArrayDeque<String> = ArrayDeque())

    private val exploreStates = HashMap<String, ExploreState>()

    override suspend fun explore(call: ProviderCall, destinations: List<String>, continuation: String?): Page<FeedItem> {
        val state = synchronized(exploreStates) {
            val key = destinations.joinToString(",")
            if (continuation == null) ExploreState().also { exploreStates[key] = it } else exploreStates.getOrPut(key) { ExploreState() }
        }
        val lists: List<List<FeedItem>> = kotlinx.coroutines.coroutineScope {
            if (continuation == null) {
                destinations.map { id -> async { runCatching { page(browse(call, id, null)).items }.getOrDefault(emptyList()) } }.awaitAll()
            } else {
                // Each later page follows what YouTube shows next to a few videos already on screen.
                val seeds = synchronized(state) { state.seeds.shuffled().take(EXPLORE_SEEDS_PER_PAGE) }
                seeds.map { id -> async { runCatching { watch(call, id, null, null).related }.getOrDefault(emptyList()) } }.awaitAll()
            }
        }
        val woven = ArrayList<FeedItem>()
        synchronized(state) {
            val longest = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until longest) for (list in lists) {
                val item = list.getOrNull(i) as? VideoItem ?: continue
                if (item.isShort || !state.served.add(item.id)) continue
                woven += item
                if (!item.isLive) state.seeds.addLast(item.id)
            }
            while (state.seeds.size > EXPLORE_SEED_POOL) state.seeds.removeFirst()
            while (state.served.size > SHORTS_SEEN_LIMIT) state.served.remove(state.served.first())
        }
        return Page(woven, if (woven.isEmpty() && continuation != null) null else EXPLORE_TOKEN)
    }

    override suspend fun trending(call: ProviderCall, continuation: String?): Page<FeedItem> {
        val vietnamese = call.locale.language == "vi"
        // Vietnamese viewers get topic searches from Vietnam; YouTube's own destination pages (music, news, sports, gaming, live) lead elsewhere.
        val first = continuation == null || continuation == EXPLORE_TOKEN
        if (!vietnamese && first) {
            val explored = explore(call, EXPLORE_DESTINATIONS, continuation)
            if (explored.items.isNotEmpty() || continuation != null) return explored
        }
        val requests: List<JsonObject> = if (continuation == null || continuation == EXPLORE_TOKEN) {
            val pool = TRENDING_QUERIES[call.locale.region]
                ?: if (vietnamese) TRENDING_QUERIES.getValue("VN") else TRENDING_QUERIES.getValue("")
            val topics = pool.shuffled().take(MIX_TOPICS)
            topics.map { topic ->
                buildJsonObject {
                    put("query", if (call.locale.region == "VN") "$topic Việt Nam" else topic)
                    put("params", TRENDING_SEARCH_PARAMS)
                }
            }
        } else if (continuation.startsWith(MIX_PREFIX)) {
            continuation.removePrefix(MIX_PREFIX).split(MIX_SEPARATOR).filter { it.isNotEmpty() }
                .map { token -> buildJsonObject { put("continuation", token) } }
        } else {
            listOf(buildJsonObject { put("continuation", continuation) })
        }
        val pages: List<Page<FeedItem>> = kotlinx.coroutines.coroutineScope {
            requests.map { body ->
                async { runCatching { page(client.post("search", body, call.locale, call.auth)) }.getOrNull() }
            }.awaitAll().filterNotNull()
        }
        if (pages.isEmpty()) throw AppError.Network(null, "trending")
        val seen = HashSet<String>()
        val woven = ArrayList<FeedItem>()
        val longest = pages.maxOf { it.items.size }
        for (i in 0 until longest) {
            for (p in pages) p.items.getOrNull(i)?.let { if (seen.add(it.id)) woven += it }
        }
        val tokens = pages.mapNotNull { it.continuation }
        val next = when {
            tokens.isEmpty() -> null
            tokens.size == 1 && continuation != null && !continuation.startsWith(MIX_PREFIX) -> tokens.first()
            else -> MIX_PREFIX + tokens.joinToString(MIX_SEPARATOR)
        }
        return Page(woven, next)
    }

    private val servedShorts = LinkedHashSet<String>()

    /**
     * YouTube's own reel feed, the endless anonymous `reel_watch_sequence` chain; the topic searches below take over
     * when it answers nothing. Its entries name a reel and its poster only, so titles and channels come later per reel.
     */
    override suspend fun shorts(call: ProviderCall, continuation: String?): Page<FeedItem> {
        if (continuation == null || continuation.startsWith(REEL_PREFIX)) {
            val reels = try {
                reelShorts(call, continuation?.removePrefix(REEL_PREFIX))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Shorts", "reel feed failed: ${e.javaClass.simpleName}")
                null
            }
            if (reels != null && reels.items.isNotEmpty()) return reels
        }
        return searchShorts(call)
    }

    private suspend fun reelShorts(call: ProviderCall, sequence: String?): Page<FeedItem>? {
        // The seedless first page holds a single reel. It plays while the screen asks for the next page, rather than
        // waiting a second round trip for it.
        // The subscription reels are asked alongside the reel page, so a signed-in open waits for the slower of the two only.
        val (page, mine) = kotlinx.coroutines.coroutineScope {
            val subs = async { subscriptionShorts(call) }
            reelPage(call, sequence ?: REEL_INITIAL_SEQUENCE) to subs.await()
        }
        val walked = channelLaneReels(call)
        val fresh = synchronized(servedShorts) {
            val reels = page.items.filter { servedShorts.add(it.id) }.toMutableList()
            // A signed-in feed carries a few reels from subscribed channels, spread through the page rather than heading it:
            // the newest from the subscriptions feed, then those the channel walk has found on the channels' own Shorts tabs.
            val picked = mine.filter { it.id !in servedShorts }.take(SUBSCRIPTION_SHORTS_PER_PAGE) +
                walked.filter { it.id !in servedShorts }.take(CHANNEL_SHORTS_PER_PAGE)
            picked.distinctBy { it.id }.forEachIndexed { i, reel ->
                servedShorts.add(reel.id)
                reels.add(minOf(1 + i * 3, reels.size), reel)
            }
            while (servedShorts.size > SHORTS_SEEN_LIMIT) servedShorts.remove(servedShorts.first())
            reels
        }
        return Page(fresh, page.continuation?.let { REEL_PREFIX + it })
    }

    private val laneScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val channelLane = ArrayDeque<VideoItem>()
    private var channelLaneAccount: String? = null
    private var channelCursor = 0
    @Volatile private var channelLaneJob: kotlinx.coroutines.Job? = null
    @Volatile private var channelOrder: Triple<String, Long, List<String>>? = null

    /**
     * Reels the channel walk has already found, never waited for: the walk opens subscribed channels' Shorts tabs in the
     * background, a few channels at a time, and is topped up whenever what it found runs low.
     */
    private fun channelLaneReels(call: ProviderCall): List<VideoItem> {
        val auth = call.auth ?: return emptyList()
        val (taken, low) = synchronized(channelLane) {
            if (channelLaneAccount != auth.accountId) {
                channelLane.clear()
                channelLaneAccount = auth.accountId
                channelCursor = 0
            }
            List(minOf(CHANNEL_SHORTS_PER_PAGE * 2, channelLane.size)) { channelLane.removeFirst() } to (channelLane.size < CHANNEL_LANE_LOW)
        }
        if (low && channelLaneJob?.isActive != true) {
            channelLaneJob = laneScope.launch { walkChannels(call, auth.accountId) }
        }
        return taken
    }

    private suspend fun walkChannels(call: ProviderCall, accountId: String) {
        val channels = subscribedChannelIds(call, accountId)
        if (channels.isEmpty()) return
        repeat(minOf(CHANNELS_PER_WALK, channels.size)) {
            val channelId = synchronized(channelLane) {
                if (channelLaneAccount != accountId) return
                channels[channelCursor++ % channels.size]
            }
            val reels = try {
                channelTab(call, channelId, "shorts", null).items.filterIsInstance<VideoItem>().take(CHANNEL_REELS_PER_VISIT)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Shorts", "channel shorts failed: ${e.javaClass.simpleName}")
                emptyList()
            }
            synchronized(channelLane) { if (channelLaneAccount == accountId) channelLane.addAll(reels) }        }
    }

    /** The account's subscribed channels in a shuffled walking order, fetched once an hour. */
    private suspend fun subscribedChannelIds(call: ProviderCall, accountId: String): List<String> {
        channelOrder?.takeIf { it.first == accountId && clock.nowMillis() - it.second < CHANNEL_ORDER_TTL_MS }?.let { return it.third }
        val ids = LinkedHashSet<String>()
        var continuation: String? = null
        try {
            var pages = 0
            do {
                val page = subscribedChannels(call, continuation)
                page.items.filterIsInstance<vibe.tube.youtube.model.ChannelItem>().mapTo(ids) { it.id }
                continuation = page.continuation
            } while (continuation != null && ++pages < CHANNEL_LIST_PAGES)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("Shorts", "subscribed channels failed: ${e.javaClass.simpleName}")
            if (ids.isEmpty()) return emptyList()
        }
        val order = ids.shuffled()
        channelOrder = Triple(accountId, clock.nowMillis(), order)
        return order
    }

    /** The account's own reel feed when signed in, so it follows what the account watches; the anonymous chain otherwise. */
    private suspend fun reelPage(call: ProviderCall, sequence: String): Page<FeedItem> {
        val auth = call.auth
        if (auth != null) {
            val signed = try {
                reelRequest(call, sequence, auth, null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Shorts", "signed reel feed failed: ${e.javaClass.simpleName}")
                null
            }
            if (signed != null && signed.items.isNotEmpty()) return signed
        }
        return reelRequest(call, sequence, null, REEL_PROFILE)
    }

    @Volatile private var subscriptionReels: Triple<String, Long, List<VideoItem>>? = null

    /** Reels from the subscriptions feed, fetched once per account and reused for a while. Empty when signed out. */
    private suspend fun subscriptionShorts(call: ProviderCall): List<VideoItem> {
        val auth = call.auth ?: return emptyList()
        val cached = subscriptionReels
        if (cached != null && cached.first == auth.accountId && clock.nowMillis() - cached.second < SUBSCRIPTION_SHORTS_TTL_MS) return cached.third
        val reels = try {
            subscriptionsFeed(call, null).items.filterIsInstance<VideoItem>().filter { it.isShort && !it.isLive }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("Shorts", "subscription reels failed: ${e.javaClass.simpleName}")
            return cached?.takeIf { it.first == auth.accountId }?.third.orEmpty()
        }
        subscriptionReels = Triple(auth.accountId, clock.nowMillis(), reels)
        return reels
    }

    private suspend fun reelRequest(call: ProviderCall, sequence: String, auth: RequestAuth?, profile: vibe.tube.network.ClientProfile?): Page<FeedItem> {
        val root = client.post(
            "reel/reel_watch_sequence", buildJsonObject { put("sequenceParams", sequence) }, call.locale, auth,
            skipLoggedOutCheck = true, profile = profile,
        ).root
        val items = root.at("entries").list().mapNotNull { entry ->
            val endpoint = entry.path("command", "reelWatchEndpoint") ?: return@mapNotNull null
            val id = endpoint.at("videoId").str?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // Ad creatives are ordinary uploads to the player, so they are dropped here or never.
            if (endpoint.at("videoType").str == "REEL_VIDEO_TYPE_AD" || endpoint.path("adClientParams", "isAd").bool == true) return@mapNotNull null
            val thumbs = endpoint.path("thumbnail", "thumbnails").list().mapNotNull { t ->
                vibe.tube.youtube.model.Thumb(t.at("url").str ?: return@mapNotNull null, t.at("width").int ?: 0, t.at("height").int ?: 0)
            }
            VideoItem(
                id = id, title = "", channelName = null, channelId = null, channelAvatar = null, thumbnails = thumbs,
                durationText = null, durationSeconds = null, viewCountText = null, publishedText = null, isShort = true,
            )
        }
        // The app clients answer with a bare token; the web clients wrap it in an endpoint.
        val next = root.at("continuation").str
            ?: root.path("continuationEndpoint", "continuationCommand", "token").str
            ?: root.path("continuationEndpoint", "reelWatchSequenceEndpoint", "sequenceParams").str
        return Page(items, next?.takeIf { it.isNotBlank() })
    }

    private suspend fun searchShorts(call: ProviderCall): Page<FeedItem> {
        fun isShortItem(item: FeedItem) =
            item is vibe.tube.youtube.model.VideoItem && !item.isLive && (item.isShort || (item.durationSeconds ?: Int.MAX_VALUE) in 1..61)
        val pool = SHORTS_QUERIES[call.locale.region] ?: if (call.locale.language == "vi") SHORTS_QUERIES.getValue("VN") else SHORTS_QUERIES.getValue("")
        
        val topics = pool.shuffled().take(SHORTS_TOPICS_PER_PAGE)
        val found = kotlinx.coroutines.coroutineScope {
            topics.map { topic ->
                async {
                    runCatching {
                        val body = buildJsonObject {
                            put("query", topic)
                            
                            put("params", vibe.tube.youtube.model.SearchSort.UploadDate.paramsAll!!)
                        }
                        page(client.post("search", body, call.locale, call.auth)).items.filter(::isShortItem)
                    }.getOrDefault(emptyList())
                }
            }.awaitAll()
        }
        
        val merged = ArrayList<FeedItem>()
        val longest = found.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) for (list in found) list.getOrNull(i)?.let { merged += it }
        val fresh = synchronized(servedShorts) {
            merged.filter { servedShorts.add(it.id) }.also {
                while (servedShorts.size > SHORTS_SEEN_LIMIT) servedShorts.remove(servedShorts.first())
            }
        }
        return Page(fresh, SHORTS_MORE_TOKEN)
    }

    override suspend fun subscriptionsFeed(call: ProviderCall, continuation: String?): Page<FeedItem> {
        requireAuth(call, "subscriptions")
        return page(browse(call, "FEsubscriptions", continuation))
    }

    override suspend fun subscribedChannels(call: ProviderCall, continuation: String?): Page<FeedItem> {
        requireAuth(call, "subscriptions")
        return page(browse(call, "FEchannels", continuation))
    }

    override suspend fun userPlaylists(call: ProviderCall, continuation: String?): Page<FeedItem> {
        requireAuth(call, "library")
        return page(browse(call, "FEplaylist_aggregation", continuation))
    }

    /**
     * The view the official clients report when playback starts: the account's own player answer names a stats URL, and
     * fetching it with the account's cookies is what puts the video in its watch history. The history files a view under
     * Shorts only when the stats URL names the Shorts page (`el=shortspage`), which only the reel's own player answer does:
     * a plain /player answer names the watch page and lands a short among the long videos.
     */
    override suspend fun markWatched(call: ProviderCall, videoId: String, short: Boolean) {
        requireAuth(call, "history")
        val player = buildJsonObject {
            put("videoId", videoId)
            if (short) put("params", SHORTS_PLAYER_PARAMS)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        fun tracking(root: kotlinx.serialization.json.JsonElement?) = root.path("playbackTracking", "videostatsPlaybackUrl", "baseUrl").str
        val reel = if (!short) null else try {
            tracking(
                client.post(
                    "reel/reel_item_watch",
                    buildJsonObject {
                        put("playerRequest", player)
                        put("disablePlayerResponse", false)
                    },
                    call.locale, call.auth,
                ).root.at("playerResponse"),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("WatchHistory", "$videoId reel answer failed: ${e.javaClass.simpleName}")
            null
        }
        val base = reel ?: tracking(client.post("player", player, call.locale, call.auth).root)
            ?: throw AppError.Parse("playback-tracking")
        val cpn = buildString(16) { repeat(16) { append(CPN_ALPHABET[cpnRandom.nextInt(CPN_ALPHABET.length)]) } }
        val url = base.toHttpUrl().newBuilder()
            .setQueryParameter("ver", "2")
            .setQueryParameter("cpn", cpn)
            // The /player fallback names the watch page; a short is reported from the Shorts page all the same.
            .apply { if (short) setQueryParameter("el", "shortspage") }
            .build()
        android.util.Log.i("WatchHistory", "$videoId reported short=$short el=${url.queryParameter("el")}")
        client.getText(url, call.locale, call.auth)
    }

    private val cpnRandom = java.security.SecureRandom()

    override suspend fun history(call: ProviderCall, continuation: String?): Page<FeedItem> {
        requireAuth(call, "history")
        return page(browse(call, "FEhistory", continuation))
    }

    override suspend fun playlist(call: ProviderCall, playlistId: String, continuation: String?): PlaylistDetail {
        if (playlistId.startsWith("RD") && continuation == null) {
            
            val body = buildJsonObject {
                playlistId.removePrefix("RD").takeIf { it.length == 11 }?.let { put("videoId", it) }
                put("playlistId", playlistId)
            }
            val (title, videos) = WatchParser.parseQueuePanel(client.post("next", body, call.locale, call.auth).root)
            return PlaylistDetail(playlistId, title.orEmpty(), null, null, null, null, videos, null)
        }
        val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
        if (browseId == "VLLL" || browseId == "VLWL") requireAuth(call, "library")
        val response = browse(call, browseId, continuation)
        val parsed = FeedParser.parse(response.root)
        val videos = parsed.items.filterIsInstance<VideoItem>()
        val header = response.root.at("header")
        val title = header.firstString("pageTitle")
            ?: header.at("playlistHeaderRenderer").at("title").textOf()
            ?: response.root.path("metadata", "playlistMetadataRenderer", "title").str
        return PlaylistDetail(
            id = browseId.removePrefix("VL"),
            title = title.orEmpty(),
            ownerName = header.firstObject("playlistHeaderRenderer").at("ownerText").textOf()
                ?: header.firstObject("avatarStackViewModel").at("text").textOf()?.removePrefix("by ")?.trim(),
            ownerId = header.firstString("browseId"),
            countText = header.firstObject("playlistHeaderRenderer").at("numVideosText").textOf()
                ?: header.firstObject("contentMetadataViewModel")?.let { m ->
                    m.at("metadataRows").list().flatMap { it.at("metadataParts").list() }
                        .mapNotNull { it.at("text").textOf() }.firstOrNull { t -> t.any(Char::isDigit) }
                },
            description = response.root.path("metadata", "playlistMetadataRenderer", "description").str,
            items = videos,
            continuation = parsed.continuation,
        )
    }

    override suspend fun search(
        call: ProviderCall,
        query: String,
        kind: SearchKind,
        sort: SearchSort,
        continuation: String?,
    ): Page<FeedItem> {
        val body = buildJsonObject {
            if (continuation != null) put("continuation", continuation) else {
                put("query", query)
                val params = when (kind) {
                    SearchKind.All -> sort.paramsAll
                    SearchKind.Video -> sort.paramsVideo ?: kind.params
                    else -> kind.params
                }
                if (params != null) put("params", params)
            }
        }
        var result = page(client.post("search", body, call.locale, call.auth))
        // YouTube's accent-aware matching can return nothing for a mixed query such as
        // "3 phải 6 đừng quân ap" (accents on some words, a bare artist name on others), while the
        // same words without accents find the right videos. Retry the first page unaccented.
        if (continuation == null && result.items.isEmpty() && result.continuation == null) {
            val plain = unaccented(query)
            if (plain != query) {
                val retry = buildJsonObject { body.forEach { (k, v) -> put(k, if (k == "query") kotlinx.serialization.json.JsonPrimitive(plain) else v) } }
                result = page(client.post("search", retry, call.locale, call.auth))
            }
        }
        return if (kind == SearchKind.Shorts) result.copy(items = result.items.filter { it is VideoItem && it.isShort }) else result
    }

    override suspend fun topic(call: ProviderCall, query: String, playlists: Boolean, live: Boolean, continuation: String?): Page<FeedItem> {
        if (!live) return search(call, query, if (playlists) SearchKind.Playlist else SearchKind.Video, SearchSort.Relevance, continuation)
        val body = buildJsonObject {
            if (continuation != null) put("continuation", continuation) else {
                put("query", query)
                put("params", "EgJAAQ%3D%3D") 
            }
        }
        return page(client.post("search", body, call.locale, call.auth))
    }

    override suspend fun suggestions(locale: ContentLocale, query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val url = suggestBase.toHttpUrl().newBuilder()
            .addPathSegments("complete/search")
            .addQueryParameter("client", "youtube")
            .addQueryParameter("ds", "yt")
            .addQueryParameter("hl", locale.language)
            .addQueryParameter("gl", locale.region)
            .addQueryParameter("q", query)
            .build()
        return SuggestParser.parse(client.getText(url, locale))
    }

    private suspend fun channelMembership(call: ProviderCall, channelId: String): Boolean? {
        if (call.auth == null) return null
        return try {
            var next: String? = null
            val seen = HashSet<String>()
            var count = 0
            do {
                val page = subscribedChannels(call, next)
                if (page.items.any { it.id == channelId }) return true
                next = page.continuation
                if (next != null && !seen.add(next)) return null
            } while (next != null && ++count < 20)
            if (next == null) false else null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null }
    }

    override suspend fun channel(call: ProviderCall, channelId: String): ChannelDetail {
        val response = browse(call, channelId, null)
        val root = response.root
        val metadata = root.path("metadata", "channelMetadataRenderer")
        val header = root.at("header")
        val subscribe = root.firstObject("subscribeButtonRenderer")
        return ChannelDetail(
            id = channelId,
            name = metadata.at("title").str ?: header.firstString("pageTitle").orEmpty(),
            handle = ItemParsers.decodeHandle(metadata.at("vanityChannelUrl").str?.substringAfterLast('/')?.takeIf { it.startsWith("@") }),
            avatar = ItemParsers.thumbs(metadata.at("avatar")).lastOrNull()?.url
                ?: ItemParsers.sources(header.firstObject("avatarViewModel").at("image")).lastOrNull()?.url,
            subscriberText = header.firstObject("contentMetadataViewModel")?.let { m ->
                m.at("metadataRows").list().flatMap { it.at("metadataParts").list() }
                    .mapNotNull { it.at("text").textOf() }.firstOrNull { it.contains("subscriber", true) || it.contains("người đăng ký", true) }
            },
            aboutDetails = header.firstObject("contentMetadataViewModel")?.at("metadataRows").list().flatMap { it.at("metadataParts").list() }.mapNotNull { it.at("text").textOf() }.filter { it.contains("video", true) }.map { "videoCountText" to it },
            description = metadata.at("description").str,
            contentTabs = channelTabs(root).mapNotNull(::channelTabKey).distinct(),
            subscribed = channelMembership(call, channelId) ?: vibe.tube.youtube.parser.subscriptionState(root),
            badge = ItemParsers.badgeOf(header),
            notify = vibe.tube.youtube.parser.NotifyParser.parse(root),
            banner = header.firstObject("banner")?.let { ItemParsers.thumbs(it).lastOrNull()?.url }
                ?: header.firstObject("imageBannerViewModel")?.let { ItemParsers.sources(it.at("image")).lastOrNull()?.url },
        )
    }

    override suspend fun channelAbout(call: ProviderCall, channelId: String): ChannelDetail {
        val root = browse(call, channelId, null, "EgVhYm91dA%3D%3D").root
        val initial = vibe.tube.youtube.parser.ChannelAboutParser.parse(root, channelId)

        val panel = root.at("header").firstObject("showEngagementPanelEndpoint").at("engagementPanel")
        val continuation = panel.firstObject("continuationCommand").at("token").str
        if (continuation == null) return initial
        val detail = vibe.tube.youtube.parser.ChannelAboutParser.parse(browse(call, null, continuation).root, channelId)
        return initial.copy(description = detail.description ?: initial.description,
            aboutDetails = detail.aboutDetails, links = detail.links)
    }

    private fun channelTabs(root: kotlinx.serialization.json.JsonElement) =
        root.path("contents", "twoColumnBrowseResultsRenderer", "tabs").list().mapNotNull { it.at("tabRenderer") }

    private fun channelTabKey(tab: kotlinx.serialization.json.JsonElement): String? {
        val path = tab.path("endpoint", "commandMetadata", "webCommandMetadata", "url").str
            ?.substringBefore('?')?.trimEnd('/')?.substringAfterLast('/') ?: return null
        return when (path) { "streams" -> "livestreams"; "videos", "shorts", "playlists" -> path; else -> null }
    }

    override suspend fun channelTab(call: ProviderCall, channelId: String, tab: String, continuation: String?): Page<FeedItem> {
        require(tab in setOf("videos", "shorts", "livestreams", "playlists"))
        val result = if (continuation != null) page(browse(call, channelId, continuation)) else {
            val overview = browse(call, channelId, null)
            val endpoint = channelTabs(overview.root).firstOrNull { channelTabKey(it) == tab }
                ?.path("endpoint", "browseEndpoint") ?: return Page(emptyList(), null)
            
            val params = endpoint.at("params").str ?: throw AppError.Parse("channel-tab-endpoint")
            val response = browse(call, endpoint.at("browseId").str ?: channelId, null, params)
            val selected = channelTabs(response.root).firstOrNull { it.at("selected").toString() == "true" }
                ?: throw AppError.Parse("channel-tab-selection")
            if (channelTabKey(selected) != tab) throw AppError.Parse("channel-tab-mismatch")
            FeedParser.parse(selected.at("content") ?: throw AppError.Parse("channel-tab-content"))
        }
        return when (tab) {
            "shorts" -> result.copy(items = result.items.filter { it is VideoItem && it.isShort })
            "playlists" -> result.copy(items = result.items.filterIsInstance<vibe.tube.youtube.model.PlaylistItem>())
            else -> result
        }
    }

    override suspend fun channelVideos(call: ProviderCall, channelId: String, continuation: String?, sort: vibe.tube.youtube.model.ChannelSort): Page<FeedItem> {
        if (continuation != null || sort == vibe.tube.youtube.model.ChannelSort.Latest) {
            return page(browse(call, channelId, continuation, VIDEOS_TAB_PARAMS))
        }
        val tab = browse(call, channelId, null, VIDEOS_TAB_PARAMS)
        val token = channelSortTokens(tab.root).getOrNull(sort.chipIndex)
        if (token != null) {
            val sorted = runCatching { page(browse(call, channelId, token)) }.getOrNull()
            if (sorted != null && sorted.items.isNotEmpty()) return sorted
        }
        
        var current = page(tab)
        val all = ArrayList<FeedItem>(current.items)
        var hops = 0
        while (current.continuation != null && hops < 3) {
            hops++
            current = page(browse(call, channelId, current.continuation, VIDEOS_TAB_PARAMS))
            all += current.items
        }
        val videos = all.distinctBy { it.id }
        val ordered = when (sort) {
            vibe.tube.youtube.model.ChannelSort.Popular -> videos.sortedByDescending { (it as? vibe.tube.youtube.model.VideoItem)?.viewCountText?.let(::parseViewCount) ?: 0L }
            vibe.tube.youtube.model.ChannelSort.Oldest -> videos.reversed()
            else -> videos
        }
        return Page(ordered, null)
    }

    private fun parseViewCount(text: String): Long {
        val m = Regex("""([\d][\d.,]*)\s*(nghìn|ngàn|triệu|tỷ|[KkMmBbNnTt]\b)?""").find(text) ?: return 0L
        val number = m.groupValues[1]
        val suffix = m.groupValues[2].lowercase()
        val multiplier = when (suffix) {
            "k", "n", "nghìn", "ngàn" -> 1_000.0
            "m", "triệu" -> 1_000_000.0
            "b", "tỷ" -> 1_000_000_000.0
            else -> 1.0
        }
        val value = if (multiplier == 1.0) number.filter(Char::isDigit).toDoubleOrNull() else number.replace(',', '.').toDoubleOrNull()
        return ((value ?: 0.0) * multiplier).toLong()
    }

    private fun channelSortTokens(root: kotlinx.serialization.json.JsonElement): List<String?> {
        val classic = root.firstObject("feedFilterChipBarRenderer").at("contents").list()
        if (classic.isNotEmpty()) {
            return classic.map { it.at("chipCloudChipRenderer").firstObject("continuationCommand").at("token").str }
        }
        val chips = ArrayList<kotlinx.serialization.json.JsonElement>()
        root.walk { key, value ->
            if (key == "chipViewModel") {
                chips += value
                false
            } else true
        }
        return chips.map { it.firstObject("continuationCommand").at("token").str }
    }

    override suspend fun watch(call: ProviderCall, videoId: String, playlistId: String?, playlistIndex: Int?): WatchInfo {
        val body = buildJsonObject {
            put("videoId", videoId)
            if (playlistId != null) put("playlistId", playlistId)
            if (playlistIndex != null) put("playlistIndex", playlistIndex)
            put("racyCheckOk", true)
            put("contentCheckOk", true)
        }
        val response = client.post("next", body, call.locale, call.auth)
        return WatchParser.parse(response.root, videoId)
    }

    override suspend fun related(call: ProviderCall, continuation: String): Page<FeedItem> =
        page(client.post("next", buildJsonObject { put("continuation", continuation) }, call.locale, call.auth))

    override suspend fun comments(call: ProviderCall, continuation: String): CommentsPage {
        val response = client.post("next", buildJsonObject { put("continuation", continuation) }, call.locale, call.auth)
        return CommentsParser.parse(response.root)
    }

    private fun ensureNoError(action: String, response: InnerTubeResponse) {
        val error = response.root.at("error")
        if (error != null) {
            throw AppError.Rejected(action, error.at("message").str ?: error.at("status").str)
        }
    }

    override suspend fun like(call: ProviderCall, videoId: String, liked: Boolean) {
        requireAuth(call, "like")
        val body = buildJsonObject { put("target", buildJsonObject { put("videoId", videoId) }) }
        val response = client.post(if (liked) "like/like" else "like/removelike", body, call.locale, call.auth)
        ensureNoError("like", response)
        if (response.root.at("actions") == null && response.root.at("responseContext") == null) {
            throw AppError.Rejected("like", "empty_response")
        }
    }

    override suspend fun setNotifyLevel(call: ProviderCall, params: String) {
        requireAuth(call, "notify")
        val response = client.post("notification/modify_channel_preference", buildJsonObject { put("params", params) }, call.locale, call.auth)
        ensureNoError("notify", response)
    }

    override suspend fun notifications(call: ProviderCall, continuation: String?): vibe.tube.youtube.model.NotificationsPage {
        requireAuth(call, "notifications")
        val body = buildJsonObject {
            if (continuation != null) put("continuation", continuation)
            else put("notificationsMenuRequestType", "NOTIFICATIONS_MENU_REQUEST_TYPE_INBOX")
        }
        return vibe.tube.youtube.parser.NotificationsParser.parse(client.post("notification/get_notification_menu", body, call.locale, call.auth).root)
    }

    override suspend fun unseenNotificationCount(call: ProviderCall): Int {
        requireAuth(call, "notifications")
        val root = client.post("notification/get_unseen_count", buildJsonObject { }, call.locale, call.auth).root
        var count = 0
        root.walk { key, value ->
            if (key == "unseenCount") {
                count = value.int ?: (value as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: count
                false
            } else true
        }
        return count
    }

    override suspend fun rate(call: ProviderCall, videoId: String, status: vibe.tube.youtube.model.LikeStatus) {
        requireAuth(call, "like")
        val body = buildJsonObject { put("target", buildJsonObject { put("videoId", videoId) }) }
        val endpoint = when (status) {
            vibe.tube.youtube.model.LikeStatus.Like -> "like/like"
            vibe.tube.youtube.model.LikeStatus.Dislike -> "like/dislike"
            vibe.tube.youtube.model.LikeStatus.Indifferent -> "like/removelike"
        }
        val response = client.post(endpoint, body, call.locale, call.auth)
        ensureNoError("like", response)
        if (response.root.at("actions") == null && response.root.at("responseContext") == null) {
            throw AppError.Rejected("like", "empty_response")
        }
    }

    override suspend fun subscribe(call: ProviderCall, channelId: String, subscribed: Boolean) {
        requireAuth(call, "subscribe")
        val body = buildJsonObject {
            put("channelIds", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(channelId)) })
            if (subscribed) put("params", SUBSCRIBE_PARAMS)
        }
        val response = client.post(if (subscribed) "subscription/subscribe" else "subscription/unsubscribe", body, call.locale, call.auth)
        ensureNoError("subscribe", response)
    }

    override suspend fun playlistsForVideo(call: ProviderCall, videoId: String): List<AddToPlaylistOption> {
        requireAuth(call, "playlist")
        val body = buildJsonObject {
            put("videoIds", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(videoId)) })
            put("excludeWatchLater", false)
        }
        val response = client.post("playlist/get_add_to_playlist", body, call.locale, call.auth)
        ensureNoError("playlist", response)
        val options = ArrayList<AddToPlaylistOption>()
        response.root.firstObject("addToPlaylistRenderer").at("playlists").list().forEach { entry ->
            val r = entry.at("playlistAddToOptionRenderer") ?: return@forEach
            val id = r.at("playlistId").str ?: return@forEach
            options += AddToPlaylistOption(
                playlistId = id,
                title = r.at("title").textOf().orEmpty(),
                containsVideo = r.at("containsSelectedVideos").str == "ALL",
                privacy = r.at("privacy").str,
            )
        }
        return options
    }

    private suspend fun editPlaylist(call: ProviderCall, playlistId: String, action: String, actions: JsonArray) {
        requireAuth(call, "playlist")
        val body = buildJsonObject {
            put("playlistId", playlistId)
            put("actions", actions)
        }
        val response = client.post("browse/edit_playlist", body, call.locale, call.auth)
        ensureNoError(action, response)
        val status = response.root.at("status").str
        if (status != null && status != "STATUS_SUCCEEDED") throw AppError.Rejected(action, status)
        if (status == null) throw AppError.Rejected(action, "no_status")
    }

    override suspend fun addToPlaylist(call: ProviderCall, playlistId: String, videoId: String) {
        editPlaylist(call, playlistId, "add_to_playlist", buildJsonArray {
            add(buildJsonObject {
                put("action", "ACTION_ADD_VIDEO")
                put("addedVideoId", videoId)
            })
        })
    }

    override suspend fun removeFromPlaylist(call: ProviderCall, playlistId: String, videoId: String, setVideoId: String?) {
        editPlaylist(call, playlistId, "remove_from_playlist", buildJsonArray {
            add(buildJsonObject {
                if (setVideoId != null) {
                    put("action", "ACTION_REMOVE_VIDEO")
                    put("setVideoId", setVideoId)
                } else {
                    put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID")
                    put("removedVideoId", videoId)
                }
            })
        })
    }

    override suspend fun createPlaylist(call: ProviderCall, title: String, privacy: PlaylistPrivacy, videoId: String?): String {
        requireAuth(call, "playlist")
        val body = buildJsonObject {
            put("title", title)
            put("privacyStatus", privacy.wire)
            if (videoId != null) put("videoIds", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(videoId)) })
        }
        val response = client.post("playlist/create", body, call.locale, call.auth)
        ensureNoError("create_playlist", response)
        return response.root.at("playlistId").str ?: throw AppError.Rejected("create_playlist", "no_playlist_id")
    }

    override suspend fun renamePlaylist(call: ProviderCall, playlistId: String, title: String?, description: String?) {
        val actions = buildJsonArray {
            if (title != null) add(buildJsonObject {
                put("action", "ACTION_SET_PLAYLIST_NAME")
                put("playlistName", title)
            })
            if (description != null) add(buildJsonObject {
                put("action", "ACTION_SET_PLAYLIST_DESCRIPTION")
                put("playlistDescription", description)
            })
        }
        editPlaylist(call, playlistId, "edit_playlist", actions)
    }

    override suspend fun deletePlaylist(call: ProviderCall, playlistId: String) {
        requireAuth(call, "playlist")
        val response = client.post("playlist/delete", buildJsonObject { put("playlistId", playlistId) }, call.locale, call.auth)
        ensureNoError("delete_playlist", response)
    }

    override suspend fun postComment(call: ProviderCall, params: String, text: String) {
        requireAuth(call, "comment")
        val body = buildJsonObject {
            put("createCommentParams", params)
            put("commentText", text)
        }
        val response = client.post("comment/create_comment", body, call.locale, call.auth)
        ensureNoError("comment", response)
        if (response.root.firstObject("createCommentAction") == null) throw AppError.Rejected("comment", "not_accepted")
    }

    override suspend fun performCommentAction(call: ProviderCall, action: vibe.tube.youtube.model.CommentAction) {
        requireAuth(call, "comment")
        val response = client.post("comment/perform_comment_action", buildJsonObject {
            put("actions", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(action.params)) })
        }, call.locale, call.auth)
        ensureNoError("comment", response)
        val results = response.root.at("actionResults").list()
        if (results.isEmpty() || results.any { it.at("status").str != "STATUS_SUCCEEDED" })
            throw AppError.Rejected("comment", "not_confirmed")
    }

    override suspend fun postReply(call: ProviderCall, params: String, text: String) {
        requireAuth(call, "comment")
        val body = buildJsonObject {
            put("createReplyParams", params)
            put("commentText", text)
        }
        val response = client.post("comment/create_comment_reply", body, call.locale, call.auth)
        ensureNoError("reply", response)
        if (response.root.firstObject("createCommentReplyAction") == null) throw AppError.Rejected("reply", "not_accepted")
    }

    companion object {
        const val VIDEOS_TAB_PARAMS = "EgZ2aWRlb3PyBgQKAjoA"

        const val TRENDING_SEARCH_PARAMS = "EgIQAQ%3D%3D"
        const val SUBSCRIBE_PARAMS = "EgIIAhgA"

        private const val SHORTS_TOPICS_PER_PAGE = 4
        private const val SHORTS_SEEN_LIMIT = 600
        private const val SHORTS_MORE_TOKEN = "shorts:more"
        private const val REEL_PREFIX = "reel:"
        private const val EXPLORE_TOKEN = "explore:more"
        private const val EXPLORE_SEEDS_PER_PAGE = 3
        private const val EXPLORE_SEED_POOL = 60

        /** YouTube's destination pages a guest can open: music, sports, gaming, news and live. */
        const val DESTINATION_MUSIC = "UC-9-kyTW8ZkZNDHQJ6FgpwQ"
        const val DESTINATION_SPORTS = "UCEgdi0XIXXZ-qJOFPf4JSKw"
        const val DESTINATION_GAMING = "UCOpNcN46UbXVtpKMrmU4Abg"
        const val DESTINATION_NEWS = "UCYfdidRxbB8Qhf0Nx7ioOYw"
        const val DESTINATION_LIVE = "UC4R8DWoMoI7CAwX8_LjQHig"
        private val EXPLORE_DESTINATIONS = listOf(DESTINATION_MUSIC, DESTINATION_NEWS, DESTINATION_SPORTS, DESTINATION_GAMING, DESTINATION_LIVE)
        private const val CPN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        private const val SUBSCRIPTION_SHORTS_PER_PAGE = 2
        private const val SUBSCRIPTION_SHORTS_TTL_MS = 15 * 60_000L
        private const val CHANNEL_SHORTS_PER_PAGE = 2
        private const val CHANNEL_REELS_PER_VISIT = 3
        private const val CHANNELS_PER_WALK = 4
        private const val CHANNEL_LANE_LOW = 4
        private const val CHANNEL_LIST_PAGES = 3
        private const val CHANNEL_ORDER_TTL_MS = 60 * 60_000L

        /** The seedless reel chain's first page. */
        private const val REEL_INITIAL_SEQUENCE = "CA8%3D"

        /** The smallest reel player params whose answer reports the view from the Shorts page (fields 30 and 114 of the feed's own). */
        private const val SHORTS_PLAYER_PARAMS = "8AEBkAcC"

        /** The reel feed asked as the iOS app: a few kilobytes per entry, where ANDROID prefetches every reel's player answer. */
        private val REEL_PROFILE = vibe.tube.youtube.player.StreamResolver.DEFAULT_PROFILES.first { it.name == "IOS" }
        private val SHORTS_QUERIES: Map<String, List<String>> = mapOf(
            "VN" to listOf(
                "#shorts", "#shorts hài hước", "#shorts nhạc trend", "#shorts ẩm thực", "#shorts bóng đá", "#shorts du lịch",
                "#shorts mèo chó", "#shorts game", "#shorts tin tức", "#shorts khoa học", "#shorts công nghệ", "#shorts thời trang",
                "#shorts làm đẹp", "#shorts xe", "#shorts học tiếng anh", "#shorts phim", "#shorts mẹo vặt", "#shorts thể thao",
                "#shorts nghệ thuật", "#shorts cảm xúc", "#shorts sức khỏe", "#shorts thiên nhiên",
            ),
            "" to listOf(
                "#shorts", "#shorts funny", "#shorts music", "#shorts food", "#shorts sports", "#shorts travel", "#shorts cats",
                "#shorts gaming", "#shorts news", "#shorts science", "#shorts tech", "#shorts fashion", "#shorts beauty",
                "#shorts cars", "#shorts learn", "#shorts movies", "#shorts life hacks", "#shorts art", "#shorts nature",
                "#shorts animals", "#shorts fitness", "#shorts diy",
            ),
        )

        private const val MIX_TOPICS = 8
        private const val MIX_PREFIX = "mix:"
        private const val MIX_SEPARATOR = "\u0001"

        private val TRENDING_QUERIES: Map<String, List<String>> = mapOf(
            "VN" to listOf(
                "thịnh hành", "nhạc thịnh hành", "tin tức hôm nay", "giải trí", "game", "ẩm thực", "du lịch", "bóng đá",
                "phim hay", "công nghệ", "hài hước", "khoa học", "học tiếng anh", "xe", "thời trang", "sức khỏe", "podcast",
                "làm đẹp", "mẹo vặt", "đời sống", "phim truyền hình", "thiên nhiên", "động vật", "nấu ăn", "tâm sự",
                "lịch sử", "kỹ năng sống", "review sản phẩm", "vlog", "thể thao", "ca nhạc", "kinh doanh", "tài chính",
                "phong cảnh", "trẻ em", "học tập", "làm vườn", "thú cưng", "sáng tạo", "xây dựng nhà", "gia đình",
            ),
            "JP" to listOf("急上昇", "音楽 人気", "ニュース", "ゲーム", "料理", "旅行", "アニメ", "テクノロジー", "お笑い", "映画"),
            "KR" to listOf("인기 급상승", "음악 인기", "뉴스", "게임", "먹방", "여행", "예능", "영화", "테크", "스포츠"),
            "" to listOf(
                "trending", "trending music", "news today", "gaming", "entertainment", "cooking", "travel", "sports",
                "movie trailers", "technology", "comedy", "science", "documentary", "fitness", "cars", "podcast",
            ),
        )
    }
}

/** Strips combining accents (and maps đ/Đ) so "đừng quân" becomes "dung quan". */
internal fun unaccented(text: String): String =
    java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace('đ', 'd').replace('Đ', 'D')
