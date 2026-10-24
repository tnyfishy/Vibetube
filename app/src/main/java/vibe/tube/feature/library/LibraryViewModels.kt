package vibe.tube.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import vibe.tube.R
import vibe.tube.account.SessionManager
import vibe.tube.data.db.PlaybackHistoryEntity
import vibe.tube.data.repo.AccountActions
import vibe.tube.data.repo.FeedController
import vibe.tube.data.repo.LocalHistoryRepository
import vibe.tube.feature.common.UiEvents
import vibe.tube.youtube.model.PlaylistPrivacy
import vibe.tube.youtube.model.Thumb
import vibe.tube.youtube.model.VideoItem
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val history: LocalHistoryRepository,
    private val session: SessionManager,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val recent: StateFlow<List<PlaybackHistoryEntity>> = session.context
        .flatMapLatest { history.recentPlayback(it?.scopeKey ?: FeedController.GUEST_SCOPE) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun scope(): String = session.currentContext()?.scopeKey ?: FeedController.GUEST_SCOPE

    fun remove(videoId: String) {
        viewModelScope.launch { history.removePlayback(scope(), videoId) }
    }

    fun clearAll() {
        viewModelScope.launch { history.clearPlayback(scope()) }
    }

    fun asVideo(entry: PlaybackHistoryEntity): VideoItem = entry.toVideoItem()
}

internal fun PlaybackHistoryEntity.toVideoItem(): VideoItem {
    val duration = durationSeconds
    val percent = if (duration != null && duration > 0 && !completed) {
        ((positionMs / 1000f) / duration * 100).toInt().coerceIn(0, 100)
    } else null
    return VideoItem(
        id = videoId,
        title = title,
        channelName = channelName,
        channelId = null,
        channelAvatar = null,
        thumbnails = thumbnailUrl?.let { listOf(Thumb(it, 480, 270)) }.orEmpty(),
        durationText = duration?.let { formatHistoryDuration(it) },
        durationSeconds = duration,
        viewCountText = null,
        publishedText = null,
        watchedPercent = percent,
    )
}

private fun formatHistoryDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** One watched video; [onDevice] entries can be removed from this device's history. */
data class HistoryEntry(val video: VideoItem, val onDevice: Boolean)

/** Device history and the YouTube account history merged into one list (device plays first, then the account's). */
@HiltViewModel
class MergedHistoryViewModel @Inject constructor(
    feeds: vibe.tube.data.repo.FeedRepository,
    private val history: LocalHistoryRepository,
    private val session: SessionManager,
) : ViewModel() {
    private val account = feeds.history(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val local = session.context.flatMapLatest { history.recentPlayback(it?.scopeKey ?: FeedController.GUEST_SCOPE) }

    val entries: StateFlow<List<HistoryEntry>> = kotlinx.coroutines.flow.combine(local, account.state) { device, remote ->
        val seen = HashSet<String>()
        buildList {
            device.forEach { if (seen.add(it.videoId)) add(HistoryEntry(it.toVideoItem(), true)) }
            remote.items.forEach { item -> if (item is VideoItem && seen.add(item.id)) add(HistoryEntry(item, false)) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun loadMore() = account.loadMore()
    fun refresh() = account.refresh()

    private fun scope(): String = session.currentContext()?.scopeKey ?: FeedController.GUEST_SCOPE

    fun remove(videoId: String) {
        viewModelScope.launch { history.removePlayback(scope(), videoId) }
    }

    fun clearAll() {
        viewModelScope.launch { history.clearPlayback(scope()) }
    }
}

@HiltViewModel
class PlaylistManageViewModel @Inject constructor(
    private val actions: AccountActions,
    private val events: UiEvents,
) : ViewModel() {

    fun create(title: String, privacy: PlaylistPrivacy, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { actions.createPlaylist(title.trim(), privacy, null) }
                .onSuccess { events.post(R.string.msg_playlist_created); onDone() }
                .onFailure(events::postError)
        }
    }

    fun rename(playlistId: String, title: String, description: String?, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { actions.renamePlaylist(playlistId, title.trim(), description) }
                .onSuccess { events.post(R.string.msg_playlist_updated); onDone() }
                .onFailure(events::postError)
        }
    }

    fun delete(playlistId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { actions.deletePlaylist(playlistId) }
                .onSuccess { events.post(R.string.msg_playlist_deleted); onDone() }
                .onFailure(events::postError)
        }
    }

    fun removeVideo(playlistId: String, video: VideoItem, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                if (playlistId == "LL") actions.like(video.id, false)
                else actions.removeFromPlaylist(playlistId, video.id, video.setVideoId)
            }
                .onSuccess { events.post(R.string.msg_removed_from_playlist); onDone() }
                .onFailure(events::postError)
        }
    }
}
