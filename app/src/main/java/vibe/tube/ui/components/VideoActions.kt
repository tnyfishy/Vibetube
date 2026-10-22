package vibe.tube.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import vibe.tube.youtube.model.VideoItem

interface VideoActions {
    fun play(video: VideoItem, queue: List<VideoItem>)
    fun openChannel(channelId: String)
    fun openPlaylist(playlistId: String, title: String?)
    fun save(video: VideoItem)
    fun watchLater(video: VideoItem)
    fun share(video: VideoItem)
    fun login()
    fun playPlaylist(playlistId: String, shuffle: Boolean)
    fun hideChannel(video: VideoItem)
    fun download(video: VideoItem)
}

object NoOpVideoActions : VideoActions {
    override fun play(video: VideoItem, queue: List<VideoItem>) = Unit
    override fun openChannel(channelId: String) = Unit
    override fun openPlaylist(playlistId: String, title: String?) = Unit
    override fun save(video: VideoItem) = Unit
    override fun watchLater(video: VideoItem) = Unit
    override fun share(video: VideoItem) = Unit
    override fun login() = Unit
    override fun playPlaylist(playlistId: String, shuffle: Boolean) = Unit
    override fun hideChannel(video: VideoItem) = Unit
    override fun download(video: VideoItem) = Unit
}

val LocalVideoActions = staticCompositionLocalOf<VideoActions> { NoOpVideoActions }

data class MenuEntry(val label: String, val icon: Int, val onClick: () -> Unit)

val LocalMiniPlayerPadding = staticCompositionLocalOf { androidx.compose.ui.unit.Dp.Hairline }

val LocalQueueMode = staticCompositionLocalOf { false }

/** Channel ids the user asked not to be recommended; feeds drop their videos. */
val LocalHiddenChannels = staticCompositionLocalOf<Set<String>> { emptySet() }
