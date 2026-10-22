package vibe.tube.data.repo

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import vibe.tube.data.db.FeedCacheDao
import vibe.tube.data.db.FeedCacheEntity
import vibe.tube.youtube.model.FeedItem

data class CachedFeed(val items: List<FeedItem>, val fetchedAt: Long)

interface FeedCache {
    suspend fun read(key: String): CachedFeed?
    suspend fun write(key: String, scopeKey: String, feedType: String, items: List<FeedItem>, fetchedAt: Long)
    suspend fun clearAccount(accountId: String)
    suspend fun clearAll()
    suspend fun purgeOlderThan(timestamp: Long)
}

class RoomFeedCache(private val dao: FeedCacheDao, private val json: Json) : FeedCache {
    private val serializer = ListSerializer(FeedItem.serializer())

    override suspend fun read(key: String): CachedFeed? {
        val row = dao.get(key) ?: return null
        val items = runCatching { json.decodeFromString(serializer, row.payload) }.getOrNull() ?: return null
        return CachedFeed(items, row.fetchedAt)
    }

    override suspend fun write(key: String, scopeKey: String, feedType: String, items: List<FeedItem>, fetchedAt: Long) {
        dao.put(FeedCacheEntity(key, scopeKey, feedType, json.encodeToString(serializer, items), fetchedAt))
    }

    override suspend fun clearAccount(accountId: String) = dao.deleteAccount(accountId)

    override suspend fun clearAll() = dao.clear()

    override suspend fun purgeOlderThan(timestamp: Long) = dao.deleteOlderThan(timestamp)
}

class InMemoryFeedCache : FeedCache {
    val entries = LinkedHashMap<String, Pair<String, CachedFeed>>()

    override suspend fun read(key: String): CachedFeed? = entries[key]?.second

    override suspend fun write(key: String, scopeKey: String, feedType: String, items: List<FeedItem>, fetchedAt: Long) {
        entries[key] = scopeKey to CachedFeed(items, fetchedAt)
    }

    override suspend fun clearAccount(accountId: String) {
        entries.entries.removeAll { it.value.first.startsWith("$accountId/") }
    }

    override suspend fun clearAll() = entries.clear()

    override suspend fun purgeOlderThan(timestamp: Long) {
        entries.entries.removeAll { it.value.second.fetchedAt < timestamp }
    }
}
