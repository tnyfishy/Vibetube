package vibe.tube.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One on-disk media cache shared by the main player and Shorts, so a looping or swiped-back short
 * and a seek into watched video read from disk instead of the network.
 *
 * Opened on a background thread: until it is ready playback streams uncached, so a cold cache costs
 * nothing at first frame.
 */
@UnstableApi
object PlayerCache {
    private const val MAX_BYTES = 256L * 1024 * 1024
    private val opening = AtomicBoolean(false)

    /** Bytes served from disk against bytes the cache had to leave to the network, logged as the hit rate. */
    val stats = object : androidx.media3.datasource.cache.CacheDataSource.EventListener {
        private val cached = java.util.concurrent.atomic.AtomicLong()
        private var reported = 0L
        override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
            val total = cached.addAndGet(cachedBytesRead)
            if (total - reported >= 8L * 1024 * 1024) {
                reported = total
                android.util.Log.d("PlayerCache", "served ${total / 1024 / 1024} MB from disk this run, cache holds ${cacheSizeBytes / 1024 / 1024} MB")
            }
        }
        override fun onCacheIgnored(reason: Int) {
            android.util.Log.d("PlayerCache", "cache ignored, reason=$reason")
        }
    }

    @Volatile
    var cache: Cache? = null
        private set

    fun openAsync(context: Context) {
        if (cache != null || !opening.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread({
            try {
                cache = SimpleCache(File(app.cacheDir, "media"), LeastRecentlyUsedCacheEvictor(MAX_BYTES), StandaloneDatabaseProvider(app))
            } catch (_: Exception) {
                opening.set(false)
            }
        }, "player-cache").start()
    }

    /**
     * The file a googlevideo URL serves: format, encode time and length are the same whichever client
     * minted the URL, so a re-minted URL, or another client's, reads what is already on disk. Null when the
     * URL does not carry all three, which keeps that stream out of the cache.
     */
    fun keyFor(videoId: String, url: String): String? {
        val query = url.substringAfter('?', "").substringBefore('#')
        fun param(name: String) = query.split('&').firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")?.takeIf { it.isNotEmpty() }
        val itag = param("itag") ?: return null
        val lmt = param("lmt") ?: return null
        val clen = param("clen") ?: return null
        return "$videoId:$itag:$lmt:$clen"
    }
}
