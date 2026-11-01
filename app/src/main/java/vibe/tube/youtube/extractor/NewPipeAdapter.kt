package vibe.tube.youtube.extractor

import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.*
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.*
import vibe.tube.core.AppError
import vibe.tube.youtube.model.*
import vibe.tube.youtube.model.Page as UiPage
import vibe.tube.youtube.player.*

class NewPipeAdapter(http: OkHttpClient) : PublicExtractor, PlaybackExtractor {
    private val owner = ThreadLocal<Job?>()
    private val client = http.newBuilder().callTimeout(12, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    init {
        NewPipe.init(object : Downloader() {
            override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): org.schabi.newpipe.extractor.downloader.Response {
                val builder = okhttp3.Request.Builder().url(request.url()).header("User-Agent", AGENT)
                request.headers().forEach { (name, values) -> builder.removeHeader(name); values.forEach { builder.addHeader(name, it) } }
                val data = request.dataToSend()
                val body = if (data != null) data.toRequestBody() else if (request.httpMethod() in listOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody() else null
                val call = client.newCall(builder.method(request.httpMethod(), body).build())
                val future = CompletableFuture<org.schabi.newpipe.extractor.downloader.Response>()
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: IOException) { future.completeExceptionally(e) }
                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        try { response.use {
                            val source = it.body.source()
                            source.request(16_777_217)
                            if (source.buffer.size > 16_777_216) throw IOException("Extractor response too large")
                            future.complete(org.schabi.newpipe.extractor.downloader.Response(it.code, it.message, it.headers.toMultimap(), source.readUtf8(), it.request.url.toString()))
                        } } catch (e: Exception) { future.completeExceptionally(e) }
                    }
                })
                try {
                    while (true) {
                        owner.get()?.ensureActive()
                        try { return future.get(100, TimeUnit.MILLISECONDS) }
                        catch (_: TimeoutException) {                                                                    }
                        catch (e: java.util.concurrent.ExecutionException) { throw IOException("Extractor transport failed", e.cause) }
                    }
                } finally { if (!future.isDone || owner.get()?.isActive == false) call.cancel() }
            }
        })
    }
    private val service get() = ServiceList.YouTube
    private val locks = Array(32) { Mutex() }
    private data class Cached(val at: Long, val info: StreamInfo)
    private val streams = object : LinkedHashMap<String, Cached>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 24
    }
    private data class Cursor(val kind: String, val seed: String, val filter: String, val page: org.schabi.newpipe.extractor.Page, val locale: ContentLocale, val at: Long = System.currentTimeMillis())
    private val cursors = object : LinkedHashMap<String, Cursor>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cursor>?) = size > 128
    }
    private fun token(kind: String, seed: String, filter: String, page: org.schabi.newpipe.extractor.Page?, locale: ContentLocale): String? {
        if (!org.schabi.newpipe.extractor.Page.isValid(page)) return null
        return "np:" + UUID.randomUUID().toString().also {                                                     }.let { id ->
            synchronized(cursors) { cursors["np:$id"] = Cursor(kind, seed, filter, page!!, locale) }; id
        }
    }
    private fun cursor(value: String, kind: String, seed: String, locale: ContentLocale): Cursor = synchronized(cursors) {
        cursors[value]?.takeIf { it.kind == kind && it.seed == seed && it.locale == locale && System.currentTimeMillis() - it.at < 20 * 60_000 } ?: throw AppError.Stale()
    }
    private fun <T : Extractor> localized(extractor: T, locale: ContentLocale, numericMetadata: Boolean = false): T = extractor.apply {

        forceLocalization(Localization(if (numericMetadata) "en" else locale.language, locale.region))
        forceContentCountry(ContentCountry(locale.region))
    }
    private suspend fun <T> io(block: () -> T): T = withTimeout(15_000) {
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext().job
            owner.set(job)
            try { block().also { job.ensureActive() } } finally { owner.remove() }
        }
    }
    suspend fun stream(id: String, locale: ContentLocale): StreamInfo {
        val key = "$id|${locale.tag}"
        return locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val cached = synchronized(streams) { streams[key] }
            if (cached != null && System.currentTimeMillis() - cached.at < 90_000) return@withLock cached.info
            io { val extractor = localized(service.getStreamExtractor(watchUrl(id)), locale); extractor.fetchPage(); StreamInfo.getInfo(extractor) }
                .also { synchronized(streams) { streams[key] = Cached(System.currentTimeMillis(), it) } }
        }
    }
    override fun invalidate(id: String) { synchronized(streams) { streams.keys.removeAll { it.startsWith("$id|") } } }
    override suspend fun search(query: String, kind: SearchKind, locale: ContentLocale, next: String?, shorts: Boolean): UiPage<FeedItem> = io {
        val filter = when (kind) { SearchKind.Video -> "videos"; SearchKind.Shorts -> "shorts"; SearchKind.Channel -> "channels"; SearchKind.Playlist -> "playlists"; else -> "all" }
        val extractor = localized(service.getSearchExtractor(query, listOf(filter), ""), locale, numericMetadata = true)
        val page = if (next == null) { extractor.fetchPage(); extractor.initialPage } else extractor.getPage(cursor(next, "search", query, locale).page)
        val items = page.items.mapNotNull(::item).filter { !shorts || (it is VideoItem && it.isShort) }.distinctBy { it.id }
        UiPage(items, token("search", query, filter, page.nextPage, locale))
    }
    override suspend fun playlist(id: String, locale: ContentLocale, next: String?): PlaylistDetail = io {
        val extractor = localized(service.getPlaylistExtractor("https://www.youtube.com/playlist?list=$id"), locale, numericMetadata = true)
        if (next == null) {
            extractor.fetchPage(); val info = PlaylistInfo.getInfo(extractor)
            PlaylistDetail(id, info.name, info.uploaderName, channelId(info.uploaderUrl), info.streamCount.takeIf { it >= 0 }?.toString(), info.description?.content,
                info.relatedItems.mapNotNull { item(it) as? VideoItem }, token("playlist", id, "", info.nextPage, locale))
        } else {
            val page = extractor.getPage(cursor(next, "playlist", id, locale).page)
            PlaylistDetail(id, "", null, null, null, null, page.items.mapNotNull { item(it) as? VideoItem }, token("playlist", id, "", page.nextPage, locale))
        }
    }
    override suspend fun channel(id: String, locale: ContentLocale): ChannelDetail = io {
        val extractor = localized(service.getChannelExtractor("https://www.youtube.com/channel/$id"), locale, numericMetadata = true); extractor.fetchPage()
        val info = ChannelInfo.getInfo(extractor)
        ChannelDetail(id, info.name, null, info.avatars.firstOrNull()?.url, info.subscriberCount.takeIf { it >= 0 }?.toString(), info.description, null,
            if (info.isVerified) ChannelBadge.Verified else ChannelBadge.None, banner = info.banners.lastOrNull()?.url, contentTabs = info.tabs.flatMap { it.contentFilters }.filter { it in setOf("videos", "shorts", "livestreams", "playlists") }.distinct())
    }
    override suspend fun channelVideos(id: String, locale: ContentLocale, next: String?): UiPage<FeedItem> = channelTab(id, "videos", locale, next)
    override suspend fun channelTab(id: String, tab: String, locale: ContentLocale, next: String?): UiPage<FeedItem> = io {
        require(tab in setOf("videos", "shorts", "livestreams", "playlists"))
        val extractor = localized(service.getChannelTabExtractorFromId("channel/$id", tab), locale, numericMetadata = true)
        val page = if (next == null) { extractor.fetchPage(); extractor.initialPage } else {
            val c = cursor(next, "channel", id, locale)
            if (c.filter != tab) throw AppError.Stale()
            extractor.getPage(c.page)
        }
        UiPage(page.items.mapNotNull(::item), token("channel", id, tab, page.nextPage, locale))
    }
    override suspend fun watch(id: String, locale: ContentLocale): WatchInfo {
        val info = stream(id, locale)
        val commentStart = "np-comments:$id:${locale.language}:${locale.region}"
        return WatchInfo(id, info.name, info.viewCount.takeIf { it >= 0 }?.toString(), info.textualUploadDate, info.description?.content,
            ChannelRef(channelId(info.uploaderUrl), info.uploaderName, info.uploaderAvatars.firstOrNull()?.url, null, null),
            info.likeCount.takeIf { it >= 0 }?.toString(), null, info.relatedItems.mapNotNull(::item), null, commentStart, emptyList(), null, emptyList(), null, null,
            info.relatedItems.filterIsInstance<StreamInfoItem>().firstOrNull()?.let { videoId(it.url) }, info.streamSegments.map { VideoChapter(it.startTimeSeconds * 1000L, it.title) })
    }
    override suspend fun comments(value: String): CommentsPage = io {
        val initial = value.startsWith("np-comments:")
        val parts = value.split(':')
        val c = if (initial) null else synchronized(cursors) { cursors[value] }?.takeIf { it.kind == "comments" && System.currentTimeMillis() - it.at < 20 * 60_000 } ?: if (!initial) throw AppError.Stale() else null
        val id = c?.seed ?: parts.getOrNull(1) ?: throw AppError.Stale()
        val locale = c?.locale ?: ContentLocale(parts.getOrElse(2) { "vi" }, parts.getOrElse(3) { "VN" })
        val extractor = localized(service.getCommentsExtractor(watchUrl(id)), locale)
        val page = if (initial) { extractor.fetchPage(); extractor.initialPage } else extractor.getPage(c!!.page)
        CommentsPage(page.items.map { comment ->
            Comment(comment.commentId, comment.uploaderName, channelId(comment.uploaderUrl), comment.uploaderAvatars.firstOrNull()?.url, comment.commentText.content,
                comment.textualUploadDate, comment.likeCount.takeIf { it >= 0 }?.toString(), comment.replyCount.takeIf { it > 0 }?.toString(), false, comment.isChannelOwner, false, false,
                token("comments", id, "", comment.replies, locale), creatorHeart = comment.isHeartedByUploader, authorBadge = if (comment.isUploaderVerified) ChannelBadge.Verified else ChannelBadge.None)
        }, token("comments", id, "", page.nextPage, locale), emptyList(), null, null, null)
    }
    override suspend fun media(id: String, locale: ContentLocale): ResolvedMedia {
        val info = stream(id, locale)
        val audio = info.audioStreams.filter { it.isUrl && validUrl(it.content) }.map {
            StreamFormat(it.itag, it.content, it.format?.mimeType ?: "audio/mp4", it.codec, it.bitrate, null, null, null, null,
                it.audioTrackId, it.audioTrackName, it.audioTrackType?.name in listOf(null, "ORIGINAL"), null, it.quality, it.itagItem?.sampleRate, it.itagItem?.audioChannels, isDrc = it.itagItem?.isDrc() == true)
        }
        fun video(values: List<VideoStream>) = values.filter { it.isUrl && validUrl(it.content) }.map {
            StreamFormat(it.itag, it.content, it.format?.mimeType ?: "video/mp4", it.codec, it.bitrate, it.width, it.height, it.fps, it.resolution, null, null, false, null)
        }
        val allUrls = audio.map { it.url } + info.videoStreams.map { it.content } + info.videoOnlyStreams.map { it.content }
        val expiry = allUrls.mapNotNull { url -> Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1000) }.minOrNull() ?: (System.currentTimeMillis() + 90_000)
        return ResolvedMedia(id, info.name, info.uploaderName, info.duration.takeIf { it > 0 }, info.streamType.name.contains("LIVE"), info.hlsUrl?.takeIf(::validUrl),
            video(info.videoStreams), video(info.videoOnlyStreams), audio, info.subtitles.filter { it.isUrl }.map { CaptionTrack(it.languageTag, it.displayLanguageName, it.content, it.isAutoGenerated) },
            expiresAtMillis = expiry, clientName = "NewPipeExtractor", userAgent = AGENT, skippedCipherFormats = 0)
    }
    private fun item(value: InfoItem): FeedItem? = when (value) {
        is StreamInfoItem -> videoId(value.url)?.let { id -> VideoItem(id, value.name, value.uploaderName, channelId(value.uploaderUrl), value.uploaderAvatars.firstOrNull()?.url,
            thumbs(value.thumbnails), value.duration.takeIf { it > 0 }?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" }, value.duration.takeIf { it > 0 }?.toInt(),
            value.viewCount.takeIf { it >= 0 }?.toString(), value.textualUploadDate, isLive = value.streamType.name.contains("LIVE"), isShort = value.isShortFormContent,
            channelBadge = if (value.isUploaderVerified) ChannelBadge.Verified else ChannelBadge.None) }
        is PlaylistInfoItem -> query(value.url, "list")?.let { PlaylistItem(it, value.name, value.uploaderName, null, value.streamCount.takeIf { n -> n >= 0 }?.toString(), thumbs(value.thumbnails)) }
        is ChannelInfoItem -> channelId(value.url)?.let { ChannelItem(it, value.name, null, thumbs(value.thumbnails), null, value.description, null, if (value.isVerified) ChannelBadge.Verified else ChannelBadge.None) }
        else -> null
    }
    companion object {
        const val AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
        private fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"
        fun owns(token: String?) = token?.let { it.startsWith("np:") || it.startsWith("np-comments:") } == true
        fun videoId(url: String): String? = (query(url, "v") ?: url.substringAfter("/shorts/", "").substringBefore('?').takeIf { it.isNotEmpty() }
            ?: url.substringAfter("youtu.be/", "").substringBefore('?')).takeIf(::isVideoId)
        private fun channelId(url: String?) = url?.substringAfter("/channel/", "")?.substringBefore('/')?.substringBefore('?')?.takeIf { it.startsWith("UC") }
        private fun query(url: String, key: String) = runCatching { java.net.URI(url).rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == key }?.substringAfter('=')?.let { java.net.URLDecoder.decode(it, "UTF-8") } }.getOrNull()
        private fun thumbs(values: List<Image>) = values.map { Thumb(it.url, it.width.coerceAtLeast(0), it.height.coerceAtLeast(0)) }
        private fun validUrl(url: String?) = url?.startsWith("https://") == true
    }
}
