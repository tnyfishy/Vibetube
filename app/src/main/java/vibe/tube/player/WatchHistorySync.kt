package vibe.tube.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import vibe.tube.account.AuthState
import vibe.tube.account.SessionManager
import vibe.tube.core.Clock
import vibe.tube.data.settings.SettingsRepository
import vibe.tube.youtube.ProviderCall
import vibe.tube.youtube.YouTubeProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts what plays into the signed-in account's YouTube watch history, as the official clients do. Signed out it does
 * nothing; a video reported a moment ago (a reload, a replayed short) is not reported again.
 */
@Singleton
class WatchHistorySync @Inject constructor(
    private val provider: YouTubeProvider,
    private val session: SessionManager,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clock: Clock = Clock.System
    private val reported = LinkedHashMap<String, Long>()

    fun markWatched(videoId: String, short: Boolean = false) {
        if (session.authState.value !is AuthState.Verified) return
        val ctx = session.currentContext() ?: return
        val key = "${ctx.accountId}|$videoId"
        val now = clock.nowMillis()
        synchronized(reported) {
            if (reported[key]?.let { now - it < REPORT_AGAIN_AFTER_MS } == true) return
            reported[key] = now
            while (reported.size > REMEMBERED) reported.remove(reported.keys.first())
        }
        scope.launch {
            val auth = session.authFor(ctx.accountId) ?: return@launch
            try {
                provider.markWatched(ProviderCall(settings.latest.value.contentLocale(), auth), videoId, short)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A report that did not go through may be tried again by the next play.
                synchronized(reported) { reported.remove(key) }
                android.util.Log.w("WatchHistory", "$videoId not recorded: ${e.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        const val REPORT_AGAIN_AFTER_MS = 10 * 60_000L
        const val REMEMBERED = 500
    }
}
