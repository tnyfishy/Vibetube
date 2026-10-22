package vibe.tube.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedCacheDao {
    @Query("SELECT * FROM feed_cache WHERE cacheKey = :key")
    suspend fun get(key: String): FeedCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: FeedCacheEntity)

    @Query("DELETE FROM feed_cache WHERE scopeKey = :scopeKey")
    suspend fun deleteScope(scopeKey: String)

    @Query("DELETE FROM feed_cache WHERE scopeKey LIKE :accountPrefix || '/%'")
    suspend fun deleteAccount(accountPrefix: String)

    @Query("DELETE FROM feed_cache WHERE fetchedAt < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)

    @Query("DELETE FROM feed_cache")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM feed_cache")
    suspend fun count(): Int
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT query FROM search_history WHERE scopeKey = :scopeKey ORDER BY lastUsedAt DESC LIMIT :limit")
    fun recent(scopeKey: String, limit: Int): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE scopeKey = :scopeKey AND query = :query")
    suspend fun delete(scopeKey: String, query: String)

    @Query("DELETE FROM search_history WHERE scopeKey = :scopeKey")
    suspend fun clearScope(scopeKey: String)

    @Query("DELETE FROM search_history WHERE scopeKey LIKE :accountPrefix || '/%'")
    suspend fun clearAccount(accountPrefix: String)
}

@Dao
interface PlaybackHistoryDao {
    @Query("SELECT * FROM playback_history WHERE scopeKey = :scopeKey ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun recent(scopeKey: String, limit: Int): Flow<List<PlaybackHistoryEntity>>

    @Query("SELECT * FROM playback_history WHERE scopeKey = :scopeKey AND videoId = :videoId")
    suspend fun get(scopeKey: String, videoId: String): PlaybackHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: PlaybackHistoryEntity)

    @Query("DELETE FROM playback_history WHERE scopeKey = :scopeKey AND videoId = :videoId")
    suspend fun delete(scopeKey: String, videoId: String)

    @Query("DELETE FROM playback_history WHERE scopeKey = :scopeKey")
    suspend fun clearScope(scopeKey: String)

    @Query("DELETE FROM playback_history WHERE scopeKey LIKE :accountPrefix || '/%'")
    suspend fun clearAccount(accountPrefix: String)

    @Query("DELETE FROM playback_history")
    suspend fun clearAll()
}

@Database(
    entities = [FeedCacheEntity::class, SearchHistoryEntity::class, PlaybackHistoryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun feedCache(): FeedCacheDao
    abstract fun searchHistory(): SearchHistoryDao
    abstract fun playbackHistory(): PlaybackHistoryDao
}
