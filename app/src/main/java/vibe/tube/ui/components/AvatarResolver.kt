package vibe.tube.ui.components

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import vibe.tube.data.repo.AccountActions
import vibe.tube.youtube.YouTubeProvider

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface AvatarDependencies { fun avatarResolver(): AvatarResolver }

@Singleton
class AvatarResolver @Inject constructor(private val provider: YouTubeProvider, private val actions: AccountActions) {
    private data class Entry(val url: String?, val expires: Long)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    private val locks = Array(64) { Mutex() }
    private val permits = Semaphore(3)
    suspend fun resolve(channel: String): String? = locks[(channel.hashCode() ushr 1) % locks.size].withLock {
        val now = android.os.SystemClock.elapsedRealtime()
        cache[channel]?.takeIf { it.expires > now }?.let { return@withLock it.url }
        val url = try {
            permits.withPermit { provider.channel(actions.publicCall(), channel).avatar }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
          catch (_: Exception) { null }
        if (cache.size > 300) cache.clear()
        cache[channel] = Entry(url, now + if (url == null) 30_000L else 30 * 60_000L)
        url
    }
}
