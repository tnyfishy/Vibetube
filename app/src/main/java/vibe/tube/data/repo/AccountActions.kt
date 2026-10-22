package vibe.tube.data.repo

import kotlinx.coroutines.CancellationException
import vibe.tube.account.SessionManager
import vibe.tube.core.AppError
import vibe.tube.youtube.ProviderCall
import vibe.tube.youtube.YouTubeProvider
import vibe.tube.youtube.model.AddToPlaylistOption
import vibe.tube.youtube.model.ContentLocale
import vibe.tube.youtube.model.PlaylistPrivacy

class AccountActions(
    private val provider: YouTubeProvider,
    private val session: SessionManager,
    private val locale: () -> ContentLocale,
) {
    private suspend fun authorized(feature: String): ProviderCall {
        val ctx = session.ensureVerified() ?: throw AppError.LoginRequired(feature)
        val auth = session.authFor(ctx.accountId) ?: throw AppError.LoginRequired(feature)
        return ProviderCall(locale(), auth)
    }

    suspend fun publicCall(): ProviderCall {
        val ctx = session.currentContext()
        val auth = ctx?.let { session.authFor(it.accountId) }?.takeIf { session.authState.value is vibe.tube.account.AuthState.Verified }
        return ProviderCall(locale(), auth)
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: AppError.SessionExpired) {
        e.accountId?.let(session::markExpired)
        throw e
    }

    suspend fun like(videoId: String, liked: Boolean) = guarded { provider.like(authorized("like"), videoId, liked) }

    suspend fun subscribe(channelId: String, subscribed: Boolean) =
        guarded { provider.subscribe(authorized("subscribe"), channelId, subscribed) }

    suspend fun playlistsForVideo(videoId: String): List<AddToPlaylistOption> =
        guarded { provider.playlistsForVideo(authorized("playlist"), videoId) }

    suspend fun addToPlaylist(playlistId: String, videoId: String) =
        guarded { provider.addToPlaylist(authorized("playlist"), playlistId, videoId) }

    suspend fun removeFromPlaylist(playlistId: String, videoId: String, setVideoId: String?) =
        guarded { provider.removeFromPlaylist(authorized("playlist"), playlistId, videoId, setVideoId) }

    suspend fun createPlaylist(title: String, privacy: PlaylistPrivacy, videoId: String?): String =
        guarded { provider.createPlaylist(authorized("playlist"), title, privacy, videoId) }

    suspend fun renamePlaylist(playlistId: String, title: String?, description: String?) =
        guarded { provider.renamePlaylist(authorized("playlist"), playlistId, title, description) }

    suspend fun deletePlaylist(playlistId: String) =
        guarded { provider.deletePlaylist(authorized("playlist"), playlistId) }

    suspend fun postComment(params: String, text: String) =
        guarded { provider.postComment(authorized("comment"), params, text) }

    suspend fun postReply(params: String, text: String) =
        guarded { provider.postReply(authorized("comment"), params, text) }
}

sealed interface ToggleResult {
    data class Confirmed(val value: Boolean) : ToggleResult
    data class RolledBack(val value: Boolean, val error: Throwable) : ToggleResult
}

suspend fun optimisticToggle(
    previous: Boolean,
    desired: Boolean,
    apply: (Boolean) -> Unit,
    request: suspend (Boolean) -> Unit,
): ToggleResult {
    apply(desired)
    return try {
        request(desired)
        ToggleResult.Confirmed(desired)
    } catch (e: CancellationException) {
        apply(previous)
        throw e
    } catch (e: Exception) {
        apply(previous)
        ToggleResult.RolledBack(previous, e)
    }
}
