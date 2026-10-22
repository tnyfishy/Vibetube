package vibe.tube.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "feed_cache", indices = [Index("scopeKey")])
data class FeedCacheEntity(
    @PrimaryKey val cacheKey: String,
    val scopeKey: String,
    val feedType: String,
    val payload: String,
    val fetchedAt: Long,
)

@Entity(tableName = "search_history", primaryKeys = ["scopeKey", "query"])
data class SearchHistoryEntity(
    val scopeKey: String,
    val query: String,
    val lastUsedAt: Long,
)

@Entity(tableName = "playback_history", primaryKeys = ["scopeKey", "videoId"], indices = [Index("scopeKey", "lastPlayedAt")])
data class PlaybackHistoryEntity(
    val scopeKey: String,
    val videoId: String,
    val title: String,
    val channelName: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Int?,
    val positionMs: Long,
    val lastPlayedAt: Long,
    val completed: Boolean,
)
