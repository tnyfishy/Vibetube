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
import vibe.tube.youtube.parser.firstObject
import vibe.tube.youtube.parser.firstString
import vibe.tube.youtube.parser.list
import vibe.tube.youtube.parser.path
import vibe.tube.youtube.parser.str
import vibe.tube.youtube.parser.textOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
                // accounts_list is independent of account_menu, so run both at once.
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
        val body = buildJsonObject {
            if (continuation != null) put("continuation", continuation) else {
                put("browseId", browseId.orEmpty())
                if (params != null) put("params", params)
            }
        }
        return client.post("browse", body, call.locale, call.auth)
    }

    private fun requireAuth(call: ProviderCall, feature: String): RequestAuth =
        call.auth ?: throw AppError.LoginRequired(feature)

    private fun page(response: InnerTubeResponse): Page<FeedItem> = FeedParser.parse(response.root)

    override suspend fun home(call: ProviderCall, continuation: String?): Page<FeedItem> {
        requireAuth(call, "home")
        return page(browse(call, "FEwhat_to_watch", continuation))
    }

    /**
     * Guest home feed: a mix. Several different topics are searched at once and their results are woven together
     * (one video of each topic in turn), so the feed is never just football, or just games, or just music. Every fresh
     * load draws another random set of topics, so pull-to-refresh brings a different mix. The continuation is a bundle of
     * the topics' own continuation tokens.
     */
    override suspend fun trending(call: ProviderCall, continuation: String?): Page<FeedItem> {
        val requests: List<JsonObject> = if (continuation == null) {
            val pool = TRENDING_QUERIES[call.locale.region]
                ?: if (call.locale.language == "vi") TRENDING_QUERIES.getValue("VN") else TRENDING_QUERIES.getValue("")
            // The plain "trending" query always takes part (first entry); the rest are drawn at random.
            val topics = listOf(pool.first()) + pool.drop(1).shuffled().take(MIX_TOPICS - 1)
            topics.map { topic ->
                buildJsonObject {
                    put("query", topic)
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

    private val shortsCursor = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Shorts source: YouTube's own search for "#shorts" topics (each fresh load picks another topic), keeping only the
     * vertical short videos. A page without any short is skipped by following its continuation (a few times at most).
     */
    override suspend fun shorts(call: ProviderCall, continuation: String?): Page<FeedItem> {
        fun isShortItem(item: FeedItem) =
            item is vibe.tube.youtube.model.VideoItem && !item.isLive && (item.isShort || (item.durationSeconds ?: Int.MAX_VALUE) in 1..61)
        var token = continuation
        var query: String? = if (continuation == null) {
            val pool = SHORTS_QUERIES[call.locale.region] ?: if (call.locale.language == "vi") SHORTS_QUERIES.getValue("VN") else SHORTS_QUERIES.getValue("")
            pool[(shortsCursor.getAndIncrement() + (System.nanoTime() % 5).toInt().coerceAtLeast(0)) % pool.size]
        } else null
        repeat(4) {
            val body = buildJsonObject { if (token != null) put("continuation", token!!) else put("query", query.orEmpty()) }
            val result = page(client.post("search", body, call.locale, call.auth))
            val shorts = result.items.filter(::isShortItem)
            if (shorts.isNotEmpty() || result.continuation == null) return result.copy(items = shorts)
            token = result.continuation
            query = null
        }
        return Page(emptyList(), token)
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

    override suspend fun history(call: ProviderCall, continuation: String?): Page<FeedItem> {
        requireAuth(call, "history")
        return page(browse(call, "FEhistory", continuation))
    }

    override suspend fun playlist(call: ProviderCall, playlistId: String, continuation: String?): PlaylistDetail {
        if (playlistId.startsWith("RD") && continuation == null) {
            // Mixes are not browsable; they come from the watch-next panel.
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
            description = metadata.at("description").str,
            subscribed = subscribe.at("subscribed").let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() },
            badge = ItemParsers.badgeOf(header),
        )
    }

    override suspend fun channelVideos(call: ProviderCall, channelId: String, continuation: String?, sort: vibe.tube.youtube.model.ChannelSort): Page<FeedItem> {
        // Later pages carry their order inside the continuation token; the default order is what the tab opens with.
        if (continuation != null || sort == vibe.tube.youtube.model.ChannelSort.Latest) {
            return page(browse(call, channelId, continuation, VIDEOS_TAB_PARAMS))
        }
        // Another order is a chip above the grid (Latest / Popular / Oldest), each one a continuation that reloads the grid.
        val tab = browse(call, channelId, null, VIDEOS_TAB_PARAMS)
        val chips = tab.root.firstObject("feedFilterChipBarRenderer").at("contents").list()
        val token = chips.getOrNull(sort.chipIndex)
            ?.at("chipCloudChipRenderer")?.at("navigationEndpoint")?.at("continuationCommand")?.at("token")?.str
            ?: throw AppError.Rejected("channel_sort", "no_sort_chip")
        return page(browse(call, channelId, token))
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

        /** Search filter: type = video only (sorting by views surfaced the same few global, mostly Indian, videos). */
        const val TRENDING_SEARCH_PARAMS = "EgIQAQ%3D%3D"
        const val SUBSCRIBE_PARAMS = "EgIIAhgA"

        /** Topic searches per region (first entry = the default "trending" query). The empty key is the fallback. */
        private val SHORTS_QUERIES: Map<String, List<String>> = mapOf(
            "VN" to listOf("#shorts", "#shorts hài hước", "#shorts nhạc trend", "#shorts ẩm thực", "#shorts bóng đá", "#shorts du lịch", "#shorts mèo"),
            "" to listOf("#shorts", "#shorts funny", "#shorts music", "#shorts food", "#shorts sports", "#shorts travel", "#shorts cats"),
        )

        private const val MIX_TOPICS = 6
        private const val MIX_PREFIX = "mix:"
        private const val MIX_SEPARATOR = "\u0001"

        /** Topic pools for the guest feed (first entry = the plain "trending" query); a handful is mixed per load. */
        private val TRENDING_QUERIES: Map<String, List<String>> = mapOf(
            "VN" to listOf(
                "thịnh hành", "nhạc thịnh hành", "tin tức hôm nay", "giải trí", "game", "ẩm thực", "du lịch", "bóng đá",
                "phim hay", "công nghệ", "hài hước", "khoa học", "học tiếng anh", "xe", "thời trang", "sức khỏe", "podcast",
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
