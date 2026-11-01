package vibe.tube.youtube.extractor

import vibe.tube.youtube.model.*

interface PublicExtractor {
    suspend fun search(query: String, kind: SearchKind, locale: ContentLocale, next: String?, shorts: Boolean = false): Page<FeedItem>
    suspend fun playlist(id: String, locale: ContentLocale, next: String?): PlaylistDetail
    suspend fun channel(id: String, locale: ContentLocale): ChannelDetail
    suspend fun channelTab(id: String, tab: String, locale: ContentLocale, next: String?): Page<FeedItem> = throw UnsupportedOperationException("Channel tab unavailable")
    suspend fun channelVideos(id: String, locale: ContentLocale, next: String?): Page<FeedItem>
    suspend fun watch(id: String, locale: ContentLocale): WatchInfo
    suspend fun comments(value: String): CommentsPage
}
